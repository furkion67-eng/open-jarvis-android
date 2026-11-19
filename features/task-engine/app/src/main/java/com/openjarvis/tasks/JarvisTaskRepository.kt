package com.openjarvis.tasks

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class JarvisTaskRepository(
    private val dao: JarvisTaskDao,
    private val orchestrator: JarvisTaskOrchestrator
) {
    fun observeRecentTasks(limit: Int = 8): Flow<List<JarvisTaskEntity>> = dao.observeRecent(limit)

    suspend fun createTask(
        goal: String,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): String {
        val normalizedGoal = goal.trim()
        require(normalizedGoal.isNotEmpty()) { "Görev metni boş olamaz." }
        require(normalizedGoal.length <= 1000) { "Görev metni 1000 karakterden kısa olmalı." }
        val stableKey = idempotencyKey.trim()
        require(stableKey.isNotEmpty() && stableKey.length <= 200) {
            "Görev isteği kimliği geçersiz."
        }

        // Fast path for a retry after process recreation.
        dao.findByIdempotencyKey(stableKey)?.let { return it.id }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = JarvisTaskEntity(
            id = id,
            goal = normalizedGoal,
            status = JarvisTaskStatus.QUEUED.storageValue,
            currentStep = "Görev sıraya alındı",
            createdAt = now,
            updatedAt = now,
            idempotencyKey = stableKey
        )
        // The unique Room index is the race-safe source of truth. Only the insert winner
        // may prepare the plan; duplicate callers return the same persisted task identity.
        val insertedRowId = dao.insertIfAbsent(task)
        if (insertedRowId == -1L) {
            return dao.findByIdempotencyKey(stableKey)?.id
                ?: throw IllegalStateException("Tekrarlanan görev kimliği çözümlenemedi.")
        }

        orchestrator.prepare(id)
        return id
    }

    /** A plan-review tap is accepted once and never grants an external side-effect approval. */
    suspend fun acceptPlan(id: String) {
        val accepted = dao.acceptPlanCheckpoint(
            id = id,
            expectedStatus = JarvisTaskStatus.WAITING_FOR_USER.storageValue,
            expectedCheckpoint = JarvisTaskCheckpoint.PLAN_CREATED,
            acceptedCheckpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            acceptedAt = System.currentTimeMillis()
        )
        if (accepted != 1) return
        orchestrator.execute(id)
    }

    suspend fun retry(id: String) {
        val task = dao.findById(id) ?: return
        when (JarvisTaskStatus.fromStorage(task.status)) {
            JarvisTaskStatus.WAITING_FOR_NETWORK -> {
                if (task.planAcceptedAt == null) {
                    val requeued = dao.requeueUnacceptedWaitingTask(
                        id = id,
                        expectedStatus = JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue,
                        queuedStatus = JarvisTaskStatus.QUEUED.storageValue,
                        currentStep = "Güvenli biçimde yeniden planlanıyor",
                        progressPercent = JarvisTaskStatus.QUEUED.defaultProgress,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (requeued == 1) orchestrator.prepare(id)
                } else {
                    val counted = dao.incrementAcceptedTaskRetryIfCurrent(
                        id = id,
                        expectedStatus = JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (counted == 1) orchestrator.execute(id)
                }
            }
            JarvisTaskStatus.FAILED -> {
                val requeued = dao.requeueFailedIfCurrent(
                    id = id,
                    failedStatus = JarvisTaskStatus.FAILED.storageValue,
                    queuedStatus = JarvisTaskStatus.QUEUED.storageValue,
                    currentStep = "Yeniden sıraya alındı",
                    progressPercent = JarvisTaskStatus.QUEUED.defaultProgress,
                    updatedAt = System.currentTimeMillis()
                )
                if (requeued == 1) orchestrator.prepare(id)
            }
            else -> Unit
        }
    }

    suspend fun cancel(id: String) {
        val task = dao.findById(id) ?: return
        val status = JarvisTaskStatus.fromStorage(task.status)
        if (!JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.CANCELLED)) return
        dao.cancelIfCurrent(
            id = id,
            expectedStatus = status.storageValue,
            cancelledStatus = JarvisTaskStatus.CANCELLED.storageValue,
            currentStep = "Kullanıcı tarafından iptal edildi",
            updatedAt = System.currentTimeMillis()
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
            return JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry))
        }
    }
}
