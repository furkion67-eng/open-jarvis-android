package com.openjarvis.tasks

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class JarvisTaskRepository(
    private val dao: JarvisTaskDao,
    private val orchestrator: JarvisTaskOrchestrator,
    private val registry: JarvisTaskCapabilityRegistry
) {
    fun observeRecentTasks(limit: Int = 8): Flow<List<JarvisTaskEntity>> = dao.observeRecent(limit)

    suspend fun createTask(goal: String): String {
        val normalizedGoal = goal.trim()
        require(normalizedGoal.isNotEmpty()) { "Görev metni boş olamaz." }
        require(normalizedGoal.length <= 1000) { "Görev metni 1000 karakterden kısa olmalı." }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insert(
            JarvisTaskEntity(
                id = id,
                goal = normalizedGoal,
                status = JarvisTaskStatus.QUEUED.storageValue,
                currentStep = "Görev sıraya alındı",
                createdAt = now,
                updatedAt = now,
                idempotencyKey = UUID.randomUUID().toString()
            )
        )
        orchestrator.prepare(id)
        return id
    }

    /** Accepting a plan never starts the underlying action. It only records the user's review. */
    suspend fun acceptPlan(id: String) {
        val task = dao.findById(id) ?: return
        if (!JarvisTaskStateMachine.canTransition(
                JarvisTaskStatus.fromStorage(task.status),
                JarvisTaskStatus.WAITING_FOR_NETWORK
            )
        ) return

        dao.update(
            task.copy(
                status = JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue,
                currentStep = "Yürütücü bağlantısı bekleniyor",
                progressPercent = JarvisTaskStatus.WAITING_FOR_NETWORK.defaultProgress,
                checkpoint = "plan_reviewed",
                errorCode = "EXECUTOR_NOT_CONFIGURED",
                errorMessage = "Plan kabul edildi. Bulut/uzman ajan yürütücüsü yapılandırılmadığı için hiçbir gerçek işlem başlatılmadı.",
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun retry(id: String) {
        val task = dao.findById(id) ?: return
        when (JarvisTaskStatus.fromStorage(task.status)) {
            JarvisTaskStatus.WAITING_FOR_NETWORK -> {
                if (registry.hasExecutor(task.ownerAgent)) return
                dao.update(
                    task.copy(
                        retryCount = task.retryCount + 1,
                        currentStep = "Yürütücü bağlantısı yeniden kontrol edildi",
                        errorCode = "EXECUTOR_NOT_CONFIGURED",
                        errorMessage = "Yeniden denendi; bu dalda çalıştırılabilir bulut/uzman ajan kayıtlı değil. Görev beklemede kaldı.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            JarvisTaskStatus.FAILED -> {
                if (!JarvisTaskStateMachine.canTransition(
                        JarvisTaskStatus.FAILED,
                        JarvisTaskStatus.QUEUED
                    )
                ) return
                dao.update(
                    task.copy(
                        status = JarvisTaskStatus.QUEUED.storageValue,
                        currentStep = "Yeniden sıraya alındı",
                        progressPercent = JarvisTaskStatus.QUEUED.defaultProgress,
                        retryCount = task.retryCount + 1,
                        errorCode = null,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                orchestrator.prepare(id)
            }
            else -> Unit
        }
    }

    suspend fun cancel(id: String) {
        val task = dao.findById(id) ?: return
        val status = JarvisTaskStatus.fromStorage(task.status)
        if (!JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.CANCELLED)) return
        dao.update(
            task.copy(
                status = JarvisTaskStatus.CANCELLED.storageValue,
                currentStep = "Kullanıcı tarafından iptal edildi",
                errorCode = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Interrupted work is paused, never replayed automatically after process death. */
    suspend fun recoverInterruptedTasks() {
        val active = dao.findByStatuses(
            listOf(
                JarvisTaskStatus.QUEUED.storageValue,
                JarvisTaskStatus.PLANNING.storageValue,
                JarvisTaskStatus.RUNNING.storageValue
            )
        )
        active.forEach { task ->
            val status = JarvisTaskStatus.fromStorage(task.status)
            if (JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.WAITING_FOR_NETWORK)) {
                dao.update(
                    task.copy(
                        status = JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue,
                        currentStep = "Güvenli kurtarma beklemesi",
                        errorCode = "APP_RESTARTED",
                        errorMessage = "Uygulama yeniden başlatıldı. Yinelenen işlem riski olmaması için görev otomatik yürütülmedi.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    companion object {
        fun getInstance(context: Context): JarvisTaskRepository {
            val dao = JarvisTaskDatabase.getInstance(context).taskDao()
            val registry = JarvisTaskCapabilityRegistry.localPreviewOnly()
            return JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry), registry)
        }
    }
}
