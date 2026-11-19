package com.openjarvis.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoTaskExecutionInstrumentedTest {
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
    fun videoTaskWaitsForPlanAcceptanceThenPersistsExecutorVerifiedEvidence() = runBlocking {
        val executor = FakeVideoExecutor(
            JarvisTaskExecutionResult(
                outputSummary = "Türkçe sesli MP4 hazır: 54 sn. MP4: content://media/external/video/media/123",
                evidenceReference = "content://media/external/video/media/123",
                verified = true
            )
        )
        val repository = repository(executor)
        val id = repository.createTask(
            "İstanbul'un Fethi hakkında video oluştur",
            "video-command-request-001"
        )

        val preview = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, preview.status)
        assertEquals(JarvisTaskCheckpoint.PLAN_CREATED, preview.checkpoint)
        assertEquals("local_plan_preview", preview.executionMode)
        assertEquals(null, preview.planAcceptedAt)
        assertTrue(preview.outputSummary!!.contains("Henüz video oluşturulmadı"))
        assertEquals(0, executor.calls)

        repository.acceptPlan(id)

        val completed = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)
        assertEquals(VIDEO_TASK_EXECUTION_MODE, completed.executionMode)
        assertEquals("content://media/external/video/media/123", completed.artifactRefs)
        assertTrue(completed.outputSummary!!.contains("MP4 hazır"))
        assertEquals(1, executor.calls)
    }

    @Test
    fun unverifiedVideoExportCannotPersistSuccessOrArtifactReference() = runBlocking {
        val executor = FakeVideoExecutor(
            JarvisTaskExecutionResult(
                outputSummary = "Dışa aktarma doğrulanamadı.",
                evidenceReference = "",
                verified = false
            )
        )
        val repository = repository(executor)
        val id = repository.createTask(
            "İstanbul'un Fethi hakkında video oluştur",
            "video-command-request-002"
        )

        repository.acceptPlan(id)

        val failed = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.FAILED.storageValue, failed.status)
        assertEquals("UNVERIFIED_RESULT", failed.errorCode)
        assertEquals("[]", failed.artifactRefs)
        assertFalse(failed.outputSummary!!.contains("MP4 hazır"))
        assertEquals(1, executor.calls)
    }

    @Test
    fun videoCapabilityCreatesLocalPlanWithoutNetworkOrExternalApproval() = runBlocking {
        val plan = VideoStoryTaskCapability().buildPlan("Kedilerin bakımı hakkında video oluştur")
        assertFalse(plan.requiresNetwork)
        assertFalse(plan.requiresApproval)
        assertTrue(plan.summary.contains("Henüz video oluşturulmadı"))
        assertTrue(plan.steps.any { it.contains("tr-TR") })
    }

    private fun repository(executor: FakeVideoExecutor): JarvisTaskRepository {
        val registry = JarvisTaskCapabilityRegistry(
            capabilities = listOf(VideoStoryTaskCapability(), LocalPlanPreviewCapability()),
            executors = listOf(executor)
        )
        return JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry))
    }

    private class FakeVideoExecutor(
        private val result: JarvisTaskExecutionResult
    ) : JarvisTaskExecutor {
        override val capabilityId: String = VIDEO_TASK_EXECUTION_MODE
        override val executionMode: String = VIDEO_TASK_EXECUTION_MODE
        override val executionLabel: String = "Türkçe video üretimi"
        var calls: Int = 0
            private set

        override suspend fun execute(
            task: JarvisTaskEntity,
            idempotencyKey: String
        ): JarvisTaskExecutionResult {
            calls += 1
            assertEquals(task.idempotencyKey, idempotencyKey)
            return result
        }
    }
}
