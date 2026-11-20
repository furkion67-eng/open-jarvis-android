package com.openjarvis.tasks

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
import org.json.JSONArray
import java.nio.ByteBuffer
import java.util.UUID

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
                verified = true,
                artifactReferences = listOf(
                    "content://media/external/video/media/123",
                    "content://media/external/downloads/456"
                )
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
        assertEquals("content://media/external/video/media/123", completed.videoUri)
        assertEquals("content://media/external/downloads/456", completed.captionsUri)
        assertTrue(VideoArtifactMetadataCodec.decode(completed.videoArtifactMetadataJson)!!.isValid())
        val artifacts = JSONArray(completed.artifactRefs)
        assertEquals(2, artifacts.length())
        assertEquals("content://media/external/video/media/123", artifacts.getString(0))
        assertEquals("content://media/external/downloads/456", artifacts.getString(1))
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
            if (!result.verified || result.artifactReferences.size < 2) return result
            val metadata = VideoArtifactMetadata(
                durationMs = 54_000L,
                width = 360,
                height = 640,
                frameRate = 24,
                hasAudio = true,
                narrationLocale = "tr-TR",
                sceneCount = 5,
                sourceLabel = "fake verified fixture",
                attributionManifest = emptyList(),
                attributionManifestComplete = false
            )
            return result.copy(
                videoArtifact = PersistedVideoArtifact(
                    artifactId = task.id,
                    exportedUri = result.artifactReferences[0],
                    captionsUri = result.artifactReferences[1],
                    metadata = metadata
                )
            )
        }
    }

    @Test
    fun productionRepositoryRunsRegisteredVideoExecutorAndPersistsReadableMediaStorePair() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val productionRepository = JarvisTaskRepository.getInstance(context)
        val productionDao = JarvisTaskDatabase.getInstance(context).taskDao()
        val requestKey = "video-e2e-" + UUID.randomUUID().toString()
        val goal = "İstanbul'un Fethi hakkında video oluştur"
        var taskId: String? = null
        var mp4Uri: Uri? = null
        var srtUri: Uri? = null

        try {
            taskId = productionRepository.createTask(goal, requestKey)
            val preview = productionDao.findById(taskId!!)!!
            assertEquals(VIDEO_TASK_EXECUTION_MODE, preview.ownerAgent)
            assertEquals("local_plan_preview", preview.executionMode)
            assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, preview.status)
            assertTrue(preview.outputSummary!!.contains("Henüz video oluşturulmadı"))

            productionRepository.acceptPlan(taskId!!)
            val completed = productionDao.findById(taskId!!)!!
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)

            val artifacts = JSONArray(completed.artifactRefs)
            assertEquals("Both verified MediaStore artifacts must be persisted", 2, artifacts.length())
            assertEquals(artifacts.getString(0), completed.videoUri)
            assertEquals(artifacts.getString(1), completed.captionsUri)
            assertTrue(VideoArtifactMetadataCodec.decode(completed.videoArtifactMetadataJson)!!.isValid())
            mp4Uri = Uri.parse(artifacts.getString(0))
            srtUri = Uri.parse(artifacts.getString(1))
            assertTrue(completed.outputSummary!!.contains(mp4Uri.toString()))
            assertTrue(completed.outputSummary!!.contains(srtUri.toString()))

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, mp4Uri, null)
                val mimeTypes = (0 until extractor.trackCount).mapNotNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                }
                assertTrue("MP4 must contain H.264", mimeTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC))
                assertTrue("MP4 must contain AAC", mimeTypes.contains(MediaFormat.MIMETYPE_AUDIO_AAC))
                val audioTrack = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                }
                extractor.selectTrack(audioTrack)
                assertTrue("AAC sample must be readable", extractor.readSampleData(ByteBuffer.allocate(2 * 1024 * 1024), 0) > 0)
            } finally {
                extractor.release()
            }

            val captions = context.contentResolver.openInputStream(srtUri!!)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals(5, captions.lines().count { it.contains("-->") })
            assertTrue(captions.contains("29 Mayıs"))
        } finally {
            mp4Uri?.let { context.contentResolver.delete(it, null, null) }
            srtUri?.let { context.contentResolver.delete(it, null, null) }
            taskId?.let { productionDao.deleteById(it) }
        }
    }

}
