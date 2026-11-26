package com.openjarvis.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class JarvisTaskExecutionSafetyInstrumentedTest {
    private lateinit var database: JarvisTaskDatabase
    private lateinit var dao: JarvisTaskDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, JarvisTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.taskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun executorCannotRunBeforeCurrentPlanHasAcceptanceCheckpoint() = runBlocking {
        val executor = RecordingExecutor()
        val task = task(
            id = "not-accepted",
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_CREATED,
            planAcceptedAt = null
        )
        dao.insert(task)

        orchestrator(executor).execute(task.id)

        assertEquals(0, executor.calls)
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, dao.findById(task.id)!!.status)
        assertNull(dao.findById(task.id)!!.planAcceptedAt)
    }

    @Test
    fun concurrentExecuteCallsClaimAndRunOnlyOnce() = runBlocking {
        val executor = RecordingExecutor(delayMillis = 120)
        val task = task(
            id = "concurrent-claim",
            status = JarvisTaskStatus.WAITING_FOR_NETWORK,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = System.currentTimeMillis()
        )
        dao.insert(task)

        coroutineScope {
            awaitAll(
                async(Dispatchers.IO) { orchestrator(executor).execute(task.id) },
                async(Dispatchers.IO) { orchestrator(executor).execute(task.id) }
            )
        }

        assertEquals(1, executor.calls)
        assertEquals(listOf(task.idempotencyKey), executor.idempotencyKeys.toList())
        assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, dao.findById(task.id)!!.status)
    }

    @Test
    fun concurrentPlanReviewTapsPersistOnlyOneAcceptance() = runBlocking {
        val executor = RecordingExecutor()
        val registry = JarvisTaskCapabilityRegistry.localPreviewOnly()
        val repository = JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry))
        val id = repository.createTask("Bir not hazırla")

        coroutineScope {
            awaitAll(
                async(Dispatchers.IO) { repository.acceptPlan(id) },
                async(Dispatchers.IO) { repository.acceptPlan(id) }
            )
        }

        val accepted = dao.findById(id)!!
        assertEquals(JarvisTaskCheckpoint.PLAN_REVIEWED, accepted.checkpoint)
        assertEquals(JarvisTaskStatus.NOT_EXECUTED.storageValue, accepted.status)
        assertEquals(0, executor.calls)
    }

    @Test
    fun planAcceptanceDoesNotAuthorizeAnExternalSideEffect() = runBlocking {
        val executor = RecordingExecutor()
        val task = task(
            id = "external-approval",
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = System.currentTimeMillis(),
            requiresApproval = true,
            approvalGranted = false
        )
        dao.insert(task)

        orchestrator(executor).execute(task.id)

        val paused = dao.findById(task.id)!!
        assertEquals(0, executor.calls)
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, paused.status)
        assertEquals("APPROVAL_REQUIRED", paused.errorCode)
        assertFalse(paused.approvalGranted)
    }

    @Test
    fun failedRetryReusesTheSameIdempotencyKeyAtExecutorBoundary() = runBlocking {
        val executor = RecordingExecutor(failFirst = true)
        val registry = JarvisTaskCapabilityRegistry(
            capabilities = listOf(LocalPlanPreviewCapability()),
            executors = listOf(executor)
        )
        val orchestrator = JarvisTaskOrchestrator(dao, registry)
        val repository = JarvisTaskRepository(dao, orchestrator)

        val id = repository.createTask("Bir araştırma özeti hazırla")
        val stableKey = dao.findById(id)!!.idempotencyKey
        repository.acceptPlan(id)
        assertEquals(JarvisTaskStatus.FAILED.storageValue, dao.findById(id)!!.status)

        repository.retry(id)
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, dao.findById(id)!!.status)
        assertNull(dao.findById(id)!!.planAcceptedAt)
        assertEquals(stableKey, dao.findById(id)!!.idempotencyKey)

        repository.acceptPlan(id)
        assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, dao.findById(id)!!.status)
        assertEquals(listOf(stableKey, stableKey), executor.idempotencyKeys.toList())
    }

    @Test
    fun recoveryDoesNotReplayInterruptedWorkUntilExplicitRetry() = runBlocking {
        val executor = RecordingExecutor()
        val registry = JarvisTaskCapabilityRegistry(
            capabilities = listOf(LocalPlanPreviewCapability()),
            executors = listOf(executor)
        )
        val repository = JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry))
        val task = task(
            id = "interrupted-recovery",
            status = JarvisTaskStatus.RUNNING,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = System.currentTimeMillis()
        )
        dao.insert(task)

        repository.recoverInterruptedTasks()

        val paused = dao.findById(task.id)!!
        assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, paused.status)
        assertEquals(0, paused.progressPercent)
        assertEquals(0, executor.calls)

        // The only subsequent execution is the explicit user retry path.
        repository.retry(task.id)

        val completed = dao.findById(task.id)!!
        assertEquals(1, executor.calls)
        assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)
    }

    @Test
    fun cancellationDuringPlanPreparationPersistsInterruptedWithoutAutomaticReplay() = runBlocking {
        val enteredCapability = CompletableDeferred<Unit>()
        val blockingCapability = object : JarvisTaskCapability {
            override val id: String = "blocking.plan"
            override fun supports(goal: String): Boolean = true
            override suspend fun buildPlan(goal: String): JarvisTaskPlan {
                enteredCapability.complete(Unit)
                awaitCancellation()
            }
        }
        val orchestrator = JarvisTaskOrchestrator(
            dao,
            JarvisTaskCapabilityRegistry(capabilities = listOf(blockingCapability))
        )
        val queued = task(
            id = "cancelled-plan",
            status = JarvisTaskStatus.QUEUED,
            checkpoint = null,
            planAcceptedAt = null
        )
        dao.insert(queued)

        val job = launch { orchestrator.prepare(queued.id) }
        enteredCapability.await()
        job.cancelAndJoin()

        val interrupted = dao.findById(queued.id)!!
        assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, interrupted.status)
        assertEquals("PLAN_PREPARATION_CANCELLED", interrupted.errorCode)
        assertEquals(0, interrupted.progressPercent)

        // Recovery observes only active states and must not restart this cancelled plan.
        JarvisTaskRepository(dao, orchestrator).recoverInterruptedTasks()
        assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, dao.findById(queued.id)!!.status)
    }

    @Test
    fun cancellationDuringExecutionPersistsInterruptedUntilExplicitRetry() = runBlocking {
        val firstCallStarted = CompletableDeferred<Unit>()
        val executor = CancellingOnceExecutor(firstCallStarted)
        val registry = JarvisTaskCapabilityRegistry(
            capabilities = listOf(LocalPlanPreviewCapability()),
            executors = listOf(executor)
        )
        val orchestrator = JarvisTaskOrchestrator(dao, registry)
        val repository = JarvisTaskRepository(dao, orchestrator)
        val accepted = task(
            id = "cancelled-execution",
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = System.currentTimeMillis()
        )
        dao.insert(accepted)

        val job = launch { orchestrator.execute(accepted.id) }
        firstCallStarted.await()
        job.cancelAndJoin()

        val interrupted = dao.findById(accepted.id)!!
        assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, interrupted.status)
        assertEquals("EXECUTION_CANCELLED", interrupted.errorCode)
        assertEquals(0, interrupted.progressPercent)
        assertEquals(1, executor.calls)

        // Startup recovery must not replay a cancellation while the process remains alive.
        repository.recoverInterruptedTasks()
        assertEquals(1, executor.calls)
        assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, dao.findById(accepted.id)!!.status)

        // A distinct, explicit user retry is the only path that can claim the interrupted task.
        repository.retry(accepted.id)
        assertEquals(2, executor.calls)
        assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, dao.findById(accepted.id)!!.status)
    }

    private fun orchestrator(executor: RecordingExecutor): JarvisTaskOrchestrator {
        val registry = JarvisTaskCapabilityRegistry(
            capabilities = listOf(LocalPlanPreviewCapability()),
            executors = listOf(executor)
        )
        return JarvisTaskOrchestrator(dao, registry)
    }

    private fun task(
        id: String,
        status: JarvisTaskStatus,
        checkpoint: String?,
        planAcceptedAt: Long?,
        requiresApproval: Boolean = false,
        approvalGranted: Boolean = false
    ) = JarvisTaskEntity(
        id = id,
        goal = "Test görevi",
        status = status.storageValue,
        ownerAgent = "local.plan-preview",
        requiresApproval = requiresApproval,
        approvalGranted = approvalGranted,
        checkpoint = checkpoint,
        planAcceptedAt = planAcceptedAt,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        idempotencyKey = "stable-key-" + id
    )

    private class CancellingOnceExecutor(
        private val firstCallStarted: CompletableDeferred<Unit>
    ) : JarvisTaskExecutor {
        override val capabilityId: String = "local.plan-preview"
        private val callCounter = AtomicInteger()
        val calls: Int
            get() = callCounter.get()

        override suspend fun execute(
            task: JarvisTaskEntity,
            idempotencyKey: String
        ): JarvisTaskExecutionResult {
            val attempt = callCounter.incrementAndGet()
            if (attempt == 1) {
                firstCallStarted.complete(Unit)
                awaitCancellation()
            }
            return JarvisTaskExecutionResult(
                outputSummary = "Test sonucu doğrulandı",
                evidenceReference = "test://verified/" + task.id,
                verified = true
            )
        }
    }

    private class RecordingExecutor(
        private val failFirst: Boolean = false,
        private val delayMillis: Long = 40
    ) : JarvisTaskExecutor {
        override val capabilityId: String = "local.plan-preview"
        private val callCounter = AtomicInteger()
        val idempotencyKeys = CopyOnWriteArrayList<String>()
        val calls: Int
            get() = callCounter.get()

        override suspend fun execute(
            task: JarvisTaskEntity,
            idempotencyKey: String
        ): JarvisTaskExecutionResult {
            val attempt = callCounter.incrementAndGet()
            idempotencyKeys.add(idempotencyKey)
            delay(delayMillis)
            if (failFirst && attempt == 1) {
                throw IllegalStateException("temporary test failure")
            }
            return JarvisTaskExecutionResult(
                outputSummary = "Test sonucu doğrulandı",
                evidenceReference = "test://verified/" + task.id,
                verified = true
            )
        }
    }

    @Test
    fun missingExecutorRevokesPreviouslyGrantedExternalApproval() = runBlocking {
        val now = System.currentTimeMillis()
        val task = task(
            id = "approved-without-executor",
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = now,
            requiresApproval = true,
            approvalGranted = true
        ).copy(approvalReference = "stale-action-approval")
        dao.insert(task)

        JarvisTaskOrchestrator(dao, JarvisTaskCapabilityRegistry.localPreviewOnly()).execute(task.id)

        val persisted = dao.findById(task.id)!!
        assertEquals(JarvisTaskStatus.NOT_EXECUTED.storageValue, persisted.status)
        assertEquals(0, persisted.progressPercent)
        assertFalse(persisted.approvalGranted)
        assertNull(persisted.approvalReference)
    }

}
