package com.openjarvis.tasks

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
    val verified: Boolean
)

interface JarvisTaskExecutor {
    val capabilityId: String
    suspend fun execute(task: JarvisTaskEntity): JarvisTaskExecutionResult
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

        dao.update(
            task.copy(
                status = JarvisTaskStatus.PLANNING.storageValue,
                currentStep = "Yerel plan hazırlanıyor",
                progressPercent = JarvisTaskStatus.PLANNING.defaultProgress,
                errorCode = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )

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
                    checkpoint = "plan_created",
                    errorCode = null,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (error: Exception) {
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

    /** Executes only a registered capability and requires verified evidence before success. */
    suspend fun execute(taskId: String) {
        val task = dao.findById(taskId) ?: return
        val status = JarvisTaskStatus.fromStorage(task.status)
        if (status != JarvisTaskStatus.WAITING_FOR_USER && status != JarvisTaskStatus.WAITING_FOR_NETWORK) return

        if (task.requiresApproval && !task.approvalGranted) {
            if (status == JarvisTaskStatus.WAITING_FOR_USER ||
                JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.WAITING_FOR_USER)
            ) {
                dao.update(
                    task.copy(
                        status = JarvisTaskStatus.WAITING_FOR_USER.storageValue,
                        currentStep = "Ayrı işlem onayı bekleniyor",
                        errorCode = "APPROVAL_REQUIRED",
                        errorMessage = "Plan onayı, dış işlem onayı değildir. Devam etmek için ilgili işlem ayrıca onaylanmalı.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            return
        }

        val executor = registry.executorFor(task.ownerAgent)
        if (executor == null) {
            dao.update(
                task.copy(
                    status = JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue,
                    currentStep = "Bulut/uzman ajan yürütücüsü bekleniyor",
                    progressPercent = JarvisTaskStatus.WAITING_FOR_NETWORK.defaultProgress,
                    requiresNetwork = true,
                    errorCode = "EXECUTOR_NOT_CONFIGURED",
                    errorMessage = "Bu yetenek için çalıştırılabilir ajan veya bulut bağlantısı yapılandırılmadı; hiçbir gerçek işlem başlatılmadı.",
                    updatedAt = System.currentTimeMillis()
                )
            )
            return
        }

        if (!JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.RUNNING)) return
        dao.update(
            task.copy(
                status = JarvisTaskStatus.RUNNING.storageValue,
                currentStep = "${executor.capabilityId} çalıştırılıyor",
                progressPercent = JarvisTaskStatus.RUNNING.defaultProgress,
                errorCode = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        try {
            val result = executor.execute(task)
            val latest = dao.findById(taskId) ?: return
            val verified = result.verified && result.evidenceReference.isNotBlank()
            val next = if (verified) JarvisTaskStatus.SUCCEEDED else JarvisTaskStatus.FAILED
            if (JarvisTaskStateMachine.canTransition(JarvisTaskStatus.fromStorage(latest.status), next)) {
                dao.update(
                    latest.copy(
                        status = next.storageValue,
                        currentStep = if (verified) "Sonuç doğrulandı" else "Sonuç doğrulanamadı",
                        progressPercent = next.defaultProgress,
                        outputSummary = result.outputSummary,
                        artifactRefs = if (verified) result.evidenceReference else latest.artifactRefs,
                        errorCode = if (verified) null else "UNVERIFIED_RESULT",
                        errorMessage = if (verified) null else "Yürütücü tamamlandı dedi ancak doğrulanabilir kanıt sunmadı.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (error: Exception) {
            val latest = dao.findById(taskId) ?: return
            if (JarvisTaskStateMachine.canTransition(
                    JarvisTaskStatus.fromStorage(latest.status),
                    JarvisTaskStatus.FAILED
                )
            ) {
                dao.update(
                    latest.copy(
                        status = JarvisTaskStatus.FAILED.storageValue,
                        currentStep = "Yürütme başarısız",
                        errorCode = "EXECUTION_FAILED",
                        errorMessage = error.message ?: "Yetenek çalıştırılamadı.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
