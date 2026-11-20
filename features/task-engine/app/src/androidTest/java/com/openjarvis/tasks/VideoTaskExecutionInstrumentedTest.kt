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
        assertTrue(preview.outputSummary!!.contains("Video henüz oluşturulmadı"))
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
    fun videoCapabilityRejectsUnsupportedTopicsAndRequiresNetworkForUncached1453Pack() = runBlocking {
        val capability = VideoStoryTaskCapability()
        val unsupportedFailure = runCatching {
            capability.buildPlan("Kedilerin bakımı hakkında video oluştur")
        }.exceptionOrNull()
        assertTrue(
            "Unsupported topics must fail before the user is shown an acceptable plan.",
            unsupportedFailure is IllegalArgumentException
        )

        val plan = capability.buildPlan("İstanbul'un Fethi hakkında video oluştur")
        assertTrue("The renderer has no side-effect-free cache readiness query.", plan.requiresNetwork)
        assertFalse(plan.requiresApproval)
        assertTrue(plan.summary.contains("İstanbul'un Fethi: 1453"))
        assertTrue(plan.summary.contains("Video henüz oluşturulmadı"))
        assertTrue(plan.steps.any { it.contains("Wikimedia Commons") && it.contains("SHA-256") })
        assertTrue(plan.steps.any { it.contains("tr-TR") })
        assertTrue(plan.steps.any { it.contains("MediaStore") })
        assertFalse(plan.steps.any { it.contains("prosedürel", ignoreCase = true) })
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
    fun productionRepositoryRunsRegisteredVideoExecutorAndPersistsVerifiedMediaStoreBundle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val goal = "İstanbul'un Fethi hakkında video oluştur"
        val productionRegistry = JarvisTaskRepository.createDefaultRegistry(context)
        assertTrue(
            "The production repository must register VideoStoryTaskCapability.",
            productionRegistry.resolve(goal) is VideoStoryTaskCapability
        )
        assertTrue(
            "The production repository must register the local VideoStoryTaskExecutor.",
            productionRegistry.executorFor(VIDEO_TASK_EXECUTION_MODE) is VideoStoryTaskExecutor
        )

        val productionRepository = JarvisTaskRepository.getInstance(context)
        val productionDao = JarvisTaskDatabase.getInstance(context).taskDao()
        val requestKey = "video-e2e-" + UUID.randomUUID().toString()
        var taskId: String? = null
        var mp4Uri: Uri? = null
        var srtUri: Uri? = null
        var thumbnailUri: Uri? = null

        try {
            taskId = productionRepository.createTask(goal, requestKey)
            val preview = productionDao.findById(taskId!!)!!
            assertEquals(VIDEO_TASK_EXECUTION_MODE, preview.ownerAgent)
            assertEquals("local_plan_preview", preview.executionMode)
            assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, preview.status)
            assertTrue(preview.requiresNetwork)
            assertTrue(preview.outputSummary!!.contains("Video henüz oluşturulmadı"))

            productionRepository.acceptPlan(taskId!!)
            val completed = productionDao.findById(taskId!!)!!
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)

            val artifacts = JSONArray(completed.artifactRefs)
            assertEquals("Verified MP4, SRT and thumbnail must all be persisted", 3, artifacts.length())
            assertEquals(artifacts.getString(0), completed.videoUri)
            assertEquals(artifacts.getString(1), completed.captionsUri)
            mp4Uri = Uri.parse(artifacts.getString(0))
            srtUri = Uri.parse(artifacts.getString(1))
            thumbnailUri = Uri.parse(artifacts.getString(2))

            val metadata = VideoArtifactMetadataCodec.decode(completed.videoArtifactMetadataJson)
                ?: throw AssertionError("Complete renderer metadata must be persisted.")
            assertTrue(metadata.isValid())
            assertTrue(metadata.attributionManifestComplete)
            assertEquals(5, metadata.sceneCount)
            assertEquals(5, metadata.attributionManifest.size)
            assertTrue(metadata.attributionManifest.all { it.isValid() })
            assertEquals((0..4).toList(), metadata.attributionManifest.map { it.sceneIndex }.sorted())
            assertEquals(5, metadata.claimSources.size)
            assertTrue(metadata.claimSources.all { it.isValid() })
            assertEquals("tr-TR", metadata.narrationLocale)
            assertEquals("tr-TR", metadata.captionLocale)
            assertTrue((metadata.hookDurationMs ?: 0L) in 1L..2_000L)
            assertTrue(!metadata.thumbnailAltText.isNullOrBlank())
            assertTrue(!metadata.attributionCredits.isNullOrBlank())
            assertTrue(!metadata.researchCredits.isNullOrBlank())
            assertTrue(!metadata.audioDescription.isNullOrBlank())
            assertFalse(metadata.backgroundMusicIncluded)
            assertEquals(thumbnailUri.toString(), metadata.thumbnailUri)

            assertTrue(completed.outputSummary!!.contains(mp4Uri.toString()))
            assertTrue(completed.outputSummary!!.contains(srtUri.toString()))
            assertTrue(completed.outputSummary!!.contains(thumbnailUri.toString()))

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

            val thumbnailHeader = context.contentResolver.openInputStream(thumbnailUri!!)!!.use { input ->
                ByteArray(3).also { header ->
                    var offset = 0
                    while (offset < header.size) {
                        val count = input.read(header, offset, header.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    assertEquals("Thumbnail must be a complete JPEG header.", 3, offset)
                }
            }
            assertEquals(0xff, thumbnailHeader[0].toInt() and 0xff)
            assertEquals(0xd8, thumbnailHeader[1].toInt() and 0xff)
            assertEquals(0xff, thumbnailHeader[2].toInt() and 0xff)

            val captions = context.contentResolver.openInputStream(srtUri!!)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val cueBlocks = captions.trim().split(Regex("\\r?\\n\\s*\\r?\\n"))
            assertEquals("One measured hook cue plus five scene cues are required.", 6, cueBlocks.size)
            val cueTimes = cueBlocks.mapIndexed { index, block ->
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                assertEquals((index + 1).toString(), lines[0])
                val range = lines[1].split(" --> ")
                assertEquals(2, range.size)
                val startMs = parseSrtTimeMs(range[0])
                val endMs = parseSrtTimeMs(range[1])
                assertTrue("Every speech cue must have positive duration.", endMs > startMs)
                startMs to endMs
            }
            assertEquals(0L, cueTimes.first().first)
            assertEquals(metadata.hookDurationMs, cueTimes.first().second)
            assertTrue(cueTimes.first().second in 1L..2_000L)
            assertEquals(
                "Gemiler karadan Haliç'e!",
                cueBlocks.first().lines().drop(2).joinToString(" ").trim()
            )
            assertEquals("Five scene speech cues must follow the hook.", 5, cueBlocks.drop(1).size)
            for (index in 1 until cueTimes.size) {
                assertEquals("Speech cues must follow the measured timeline without gaps.", cueTimes[index - 1].second, cueTimes[index].first)
            }
            assertTrue(cueBlocks.drop(1).any { it.contains("29 Mayıs") })
        } finally {
            mp4Uri?.let { context.contentResolver.delete(it, null, null) }
            srtUri?.let { context.contentResolver.delete(it, null, null) }
            thumbnailUri?.let { context.contentResolver.delete(it, null, null) }
            taskId?.let { productionDao.deleteById(it) }
        }
    }

    private fun parseSrtTimeMs(value: String): Long {
        val parts = value.trim().split(':', ',')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { char -> !char.isDigit() } }) {
            throw AssertionError("Invalid SRT timestamp: $value")
        }
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        val seconds = parts[2].toLong()
        val millis = parts[3].toLong()
        return hours * 3_600_000L +
            minutes * 60_000L +
            seconds * 1_000L +
            millis
    }

}
