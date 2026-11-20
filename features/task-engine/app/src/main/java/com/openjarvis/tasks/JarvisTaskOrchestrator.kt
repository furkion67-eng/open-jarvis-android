package com.openjarvis.tasks

import kotlinx.coroutines.CancellationException

data class JarvisTaskPlan(
    val summary: String,
    val steps: List<String>,
    val requiresNetwork: Boolean,
    val requiresApproval: Boolean
)

interface JarvisTaskCapability {
    val id: String
    fun supports(goal: String): Boolean
    suspend fun buildPlan(goal: String): JarvisTaskPlan
}

/** This capability only drafts a plan; it performs no device or cloud side effects. */
class LocalPlanPreviewCapability : JarvisTaskCapability {
    override val id: String = "local.plan-preview"
    override fun supports(goal: String): Boolean = goal.isNotBlank()

    override suspend fun buildPlan(goal: String): JarvisTaskPlan = JarvisTaskPlan(
        summary = "Yerel plan taslağı hazırlandı. Bu sürümde uzman ajan veya bulut yürütücüsü bağlı değil; gerçek cihaz, mesaj, satın alma ya da yayın işlemi yapılmadı.",
        steps = listOf(
            "İsteği ve gerekli bilgileri netleştir",
            "Uygun yeteneği, izinleri ve ağ gereksinimini doğrula",
            "Dış etki oluşturacak her adım için ayrı kullanıcı onayı iste",
            "Sonucu yalnızca doğrulanabilir kanıt geldikten sonra tamamlandı say"
        ),
        requiresNetwork = true,
        requiresApproval = false
    )
}

data class JarvisTaskExecutionResult(
    val outputSummary: String,
    val evidenceReference: String,
    val verified: Boolean,
    /** All user-visible artifacts; empty keeps compatibility with single-evidence executors. */
    val artifactReferences: List<String> = emptyList(),
    /** Non-null only for a fully verified local video + captions pair. */
    val videoArtifact: PersistedVideoArtifact? = null
)

internal fun encodeArtifactReferences(references: List<String>): String {
    require(references.isNotEmpty() && references.all { it.isNotBlank() })
    fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
    return references.joinToString(prefix = "[", postfix = "]") { "\"" + escape(it) + "\"" }
}

interface JarvisTaskExecutor {
    val capabilityId: String
    val executionMode: String get() = capabilityId
    val executionLabel: String get() = capabilityId

    /**
     * The same persisted idempotencyKey must be forwarded to the real tool/API boundary.
     * Implementations must make duplicate requests with this key safe; never generate a new
     * key inside the executor for a retry of the same task.
     */
    suspend fun execute(task: JarvisTaskEntity, idempotencyKey: String): JarvisTaskExecutionResult
}

class JarvisTaskCapabilityRegistry(
    private val capabilities: List<JarvisTaskCapability>,
    private val executors: List<JarvisTaskExecutor> = emptyList()
) {
    fun resolve(goal: String): JarvisTaskCapability? = capabilities.firstOrNull { it.supports(goal) }

    fun executorFor(capabilityId: String?): JarvisTaskExecutor? =
        executors.firstOrNull { it.capabilityId == capabilityId }

    companion object {
        fun localPreviewOnly(): JarvisTaskCapabilityRegistry =
            JarvisTaskCapabilityRegistry(listOf(LocalPlanPreviewCapability()))
    }
}

class JarvisTaskOrchestrator(
    private val dao: JarvisTaskDao,
    private val registry: JarvisTaskCapabilityRegistry
) {
    suspend fun prepare(taskId: String) {
        val task = dao.findById(taskId) ?: return
        val status = JarvisTaskStatus.fromStorage(task.status)
        if (!JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.PLANNING)) return

        val enteredPlanning = dao.update(
            task.copy(
                status = JarvisTaskStatus.PLANNING.storageValue,
                currentStep = "Yerel plan hazırlanıyor",
                progressPercent = JarvisTaskStatus.PLANNING.defaultProgress,
                errorCode = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (enteredPlanning == 0) return

        try {
            val capability = registry.resolve(task.goal)
                ?: throw IllegalStateException("Bu istek için kayıtlı bir yetenek bulunamadı.")
            val plan = capability.buildPlan(task.goal)
            val latest = dao.findById(taskId) ?: return
            if (JarvisTaskStatus.fromStorage(latest.status) != JarvisTaskStatus.PLANNING) return

            val planText = buildString {
                append(plan.summary)
                append("\n\nPlan:\n")
                plan.steps.forEachIndexed { index, step ->
                    append(index + 1).append(". ").append(step).append('\n')
                }
            }.trim()

            dao.update(
                latest.copy(
                    status = JarvisTaskStatus.WAITING_FOR_USER.storageValue,
                    ownerAgent = capability.id,
                    currentStep = "Planı gözden geçir",
                    progressPercent = JarvisTaskStatus.WAITING_FOR_USER.defaultProgress,
                    outputSummary = planText,
                    requiresNetwork = plan.requiresNetwork,
                    requiresApproval = plan.requiresApproval,
                    executionMode = "local_plan_preview",
                    checkpoint = JarvisTaskCheckpoint.PLAN_CREATED,
                    planAcceptedAt = null,
                    errorCode = null,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val latest = dao.findById(taskId) ?: return
            if (JarvisTaskStateMachine.canTransition(
                    JarvisTaskStatus.fromStorage(latest.status),
                    JarvisTaskStatus.FAILED
                )
            ) {
                dao.update(
                    latest.copy(
                        status = JarvisTaskStatus.FAILED.storageValue,
                        currentStep = "Plan hazırlanamadı",
                        errorCode = "PLAN_PREPARATION_FAILED",
                        errorMessage = error.message ?: "Görev planı hazırlanamadı.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * No executor is entered unless the exact current plan has a durable user-acceptance
     * checkpoint. The DAO claim is a compare-and-set so concurrent callers cannot both run it.
     */
    suspend fun execute(taskId: String) {
        val task = dao.findById(taskId) ?: return
        val status = JarvisTaskStatus.fromStorage(task.status)
        if (status != JarvisTaskStatus.WAITING_FOR_USER &&
            status != JarvisTaskStatus.WAITING_FOR_NETWORK &&
            status != JarvisTaskStatus.NOT_EXECUTED &&
            status != JarvisTaskStatus.INTERRUPTED
        ) return

        if (task.planAcceptedAt == null || task.checkpoint != JarvisTaskCheckpoint.PLAN_REVIEWED) {
            return
        }

        if (task.requiresApproval && !task.approvalGranted) {
            dao.waitForActionApprovalIfCurrent(
                id = taskId,
                expectedStatus = status.storageValue,
                waitingUserStatus = JarvisTaskStatus.WAITING_FOR_USER.storageValue,
                currentStep = "Ayrı işlem onayı bekleniyor",
                errorCode = "APPROVAL_REQUIRED",
                errorMessage = "Plan onayı, dış işlem onayı değildir. Devam etmek için ilgili işlem ayrıca onaylanmalı.",
                updatedAt = System.currentTimeMillis()
            )
            return
        }

        val executor = registry.executorFor(task.ownerAgent)
        if (executor == null) {
            dao.markNotExecutedIfCurrent(
                id = taskId,
                expectedStatus = status.storageValue,
                notExecutedStatus = JarvisTaskStatus.NOT_EXECUTED.storageValue,
                currentStep = "Yürütülmedi · yürütücü yok",
                errorCode = "EXECUTOR_NOT_CONFIGURED",
                errorMessage = "Bu yetenek için çalıştırılabilir ajan veya bulut bağlantısı yapılandırılmadı; hiçbir gerçek işlem başlatılmadı.",
                updatedAt = System.currentTimeMillis()
            )
            return
        }

        if (!JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.RUNNING)) return
        val claimed = dao.claimForExecution(
            id = taskId,
            expectedStatus = status.storageValue,
            runningStatus = JarvisTaskStatus.RUNNING.storageValue,
            acceptedCheckpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            currentStep = executor.executionLabel + " çalıştırılıyor",
            executionMode = executor.executionMode,
            progressPercent = 0,
            updatedAt = System.currentTimeMillis()
        )
        if (claimed != 1) return

        val claimedTask = dao.findById(taskId) ?: return
        try {
            val result = executor.execute(claimedTask, claimedTask.idempotencyKey)
            val references = result.artifactReferences.ifEmpty { listOf(result.evidenceReference) }
            val videoArtifact = result.videoArtifact
            val videoTaskRequiresArtifact =
                claimedTask.ownerAgent == VIDEO_TASK_EXECUTION_MODE ||
                    claimedTask.executionMode == VIDEO_TASK_EXECUTION_MODE
            val videoArtifactValid = if (videoTaskRequiresArtifact) {
                videoArtifact != null && videoArtifact.artifactId == taskId && videoArtifact.isValid()
            } else {
                videoArtifact == null || (videoArtifact.artifactId == taskId && videoArtifact.isValid())
            }
            val verified = result.verified &&
                result.evidenceReference.isNotBlank() &&
                references.isNotEmpty() &&
                references.all { it.isNotBlank() } &&
                videoArtifactValid
            val next = if (verified) JarvisTaskStatus.SUCCEEDED else JarvisTaskStatus.FAILED
            dao.finishExecutionIfRunning(
                id = taskId,
                expectedStatus = JarvisTaskStatus.RUNNING.storageValue,
                status = next.storageValue,
                currentStep = if (verified) "Sonuç doğrulandı" else "Sonuç doğrulanamadı",
                progressPercent = next.defaultProgress,
                outputSummary = result.outputSummary,
                artifactRefs = if (verified) encodeArtifactReferences(references) else claimedTask.artifactRefs,
                videoUri = if (verified) videoArtifact?.exportedUri else null,
                captionsUri = if (verified) videoArtifact?.captionsUri else null,
                videoArtifactMetadataJson = if (verified) videoArtifact?.let { VideoArtifactMetadataCodec.encode(it.metadata) } else null,
                errorCode = if (verified) null else "UNVERIFIED_RESULT",
                errorMessage = if (verified) null else "Yürütücü tamamlandı dedi ancak doğrulanabilir kanıt sunmadı.",
                updatedAt = System.currentTimeMillis()
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            dao.finishExecutionIfRunning(
                id = taskId,
                expectedStatus = JarvisTaskStatus.RUNNING.storageValue,
                status = JarvisTaskStatus.FAILED.storageValue,
                currentStep = "Yürütme başarısız",
                progressPercent = JarvisTaskStatus.FAILED.defaultProgress,
                outputSummary = claimedTask.outputSummary,
                artifactRefs = claimedTask.artifactRefs,
                videoUri = null,
                captionsUri = null,
                videoArtifactMetadataJson = null,
                errorCode = "EXECUTION_FAILED",
                errorMessage = error.message ?: "Yetenek çalıştırılamadı.",
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}
