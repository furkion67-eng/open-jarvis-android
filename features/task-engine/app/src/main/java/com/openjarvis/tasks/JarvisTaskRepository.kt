package com.openjarvis.tasks

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class JarvisTaskRepository(
    private val dao: JarvisTaskDao,
    private val orchestrator: JarvisTaskOrchestrator
) {
    fun observeRecentTasks(limit: Int = 8): Flow<List<JarvisTaskEntity>> = dao.observeRecent(limit)

    /** A bounded page of successful tasks with both persisted video and captions URIs. */
    fun observeSuccessfulVideoArtifacts(
        limit: Int = 9,
        offset: Int = 0
    ): Flow<List<JarvisTaskEntity>> = dao.observeSuccessfulVideoArtifacts(
        succeededStatus = JarvisTaskStatus.SUCCEEDED.storageValue,
        limit = limit.coerceIn(1, 51),
        offset = offset.coerceAtLeast(0)
    )

    /** Keeps a selected archive item observable while the user changes archive pages. */
    fun observeTaskById(id: String): Flow<JarvisTaskEntity?> = dao.observeById(id)

    /**
     * Persist a typed device-action result with a fixed summary. Arbitrary caller text is not
     * accepted, so contact names, phone numbers and message bodies cannot enter this journal.
     */
    suspend fun recordDeviceActionOutcome(
        actionType: DeviceActionType,
        outcomeCode: DeviceActionOutcomeCode
    ): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertDeviceActionEvent(
            DeviceActionEventEntity(
                id = id,
                actionType = actionType.storageValue,
                outcomeCode = outcomeCode.storageValue,
                safeSummary = outcomeCode.safeSummary,
                createdAt = now
            )
        )
        dao.pruneDeviceActionEvents(MAX_DEVICE_ACTION_EVENTS)
        return id
    }

    fun observeRecentDeviceActionEvents(limit: Int = 20): Flow<List<DeviceActionEventEntity>> =
        dao.observeRecentDeviceActionEvents(limit.coerceIn(1, MAX_DEVICE_ACTION_EVENTS))

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
        when (val status = JarvisTaskStatus.fromStorage(task.status)) {
            JarvisTaskStatus.WAITING_FOR_NETWORK,
            JarvisTaskStatus.NOT_EXECUTED,
            JarvisTaskStatus.INTERRUPTED -> {
                if (task.planAcceptedAt == null) {
                    val requeued = dao.requeueUnacceptedWaitingTask(
                        id = id,
                        expectedStatus = status.storageValue,
                        queuedStatus = JarvisTaskStatus.QUEUED.storageValue,
                        currentStep = "Güvenli biçimde yeniden planlanıyor",
                        progressPercent = JarvisTaskStatus.QUEUED.defaultProgress,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (requeued == 1) orchestrator.prepare(id)
                } else {
                    val counted = dao.incrementAcceptedTaskRetryIfCurrent(
                        id = id,
                        expectedStatus = status.storageValue,
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
            if (JarvisTaskStateMachine.canTransition(status, JarvisTaskStatus.INTERRUPTED)) {
                dao.markInterruptedIfCurrent(
                    id = task.id,
                    expectedStatus = status.storageValue,
                    interruptedStatus = JarvisTaskStatus.INTERRUPTED.storageValue,
                    currentStep = "Güvenli kurtarma beklemesi",
                    errorCode = "APP_RESTARTED",
                    errorMessage = "Uygulama yeniden başlatıldı. Yinelenen işlem riski olmaması için görev otomatik yürütülmedi.",
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    companion object {
        private const val MAX_DEVICE_ACTION_EVENTS = 100
        private const val STARTUP_RECOVERY_TRACE_COOKIE = 731

        private val startupRecoveryGate = JarvisStartupRecoveryGate()

        fun getInstance(context: Context): JarvisTaskRepository {
            val dao = JarvisTaskDatabase.getInstance(context).taskDao()
            return createWithRegistry(dao, createDefaultRegistry(context.applicationContext))
        }

        /**
         * Dashboard bootstrap must not construct/open Room on the main thread.
         * The first process-level load also marks abandoned active tasks INTERRUPTED;
         * this is a state repair only and never replays an executor.
         */
        suspend fun getInstanceOnIo(context: Context): JarvisTaskRepository = withContext(Dispatchers.IO) {
            val repository = traceSection("JARVIS.task.repository.open") {
                getInstance(context.applicationContext)
            }
            startupRecoveryGate.runOnce {
                val recoveryStartedAt = SystemClock.elapsedRealtime()
                if (Build.VERSION.SDK_INT >= 29) {
                    Trace.beginAsyncSection("JARVIS.task.restore", STARTUP_RECOVERY_TRACE_COOKIE)
                }
                try {
                    repository.recoverInterruptedTasks()
                } finally {
                    if (Build.VERSION.SDK_INT >= 29) {
                        Trace.endAsyncSection("JARVIS.task.restore", STARTUP_RECOVERY_TRACE_COOKIE)
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - recoveryStartedAt
                    if (elapsedMs > 250L) {
                        Log.w("JARVISStartup", "task.restore_ms=" + elapsedMs + " budget_ms=250")
                    }
                }
            }
            repository
        }

        private inline fun <T> traceSection(name: String, block: () -> T): T {
            Trace.beginSection(name)
            val startedAt = SystemClock.elapsedRealtime()
            return try {
                block()
            } finally {
                Trace.endSection()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                if (elapsedMs > 250L) {
                    Log.w("JARVISStartup", name + "_ms=" + elapsedMs + " budget_ms=250")
                }
            }
        }

        /** The production repository and end-to-end tests share this exact capability/executor registration. */
        internal fun createDefaultRegistry(context: Context): JarvisTaskCapabilityRegistry =
            JarvisTaskCapabilityRegistry(
                capabilities = listOf(VideoStoryTaskCapability(), LocalPlanPreviewCapability()),
                executors = listOf(VideoStoryTaskExecutor(context.applicationContext))
            )

        internal fun createWithRegistry(
            dao: JarvisTaskDao,
            registry: JarvisTaskCapabilityRegistry
        ): JarvisTaskRepository = JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry))
    }
}
