package com.openjarvis.tasks

/** Planning output is not an execution receipt and must never be displayed as task completion. */
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

/** The only capability shipped in this slice prepares a local plan; it performs no side effects. */
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

class JarvisTaskCapabilityRegistry(
    private val capabilities: List<JarvisTaskCapability>,
    private val executableCapabilityIds: Set<String> = emptySet()
) {
    fun resolve(goal: String): JarvisTaskCapability? = capabilities.firstOrNull { it.supports(goal) }

    fun hasExecutor(capabilityId: String?): Boolean =
        capabilityId != null && executableCapabilityIds.contains(capabilityId)

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
}
