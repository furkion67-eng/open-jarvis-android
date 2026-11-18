package com.openjarvis.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        assertEquals(JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue, accepted.status)
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
}
