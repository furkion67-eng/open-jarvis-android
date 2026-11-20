package com.openjarvis.ui.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.tasks.JarvisTaskDatabase
import com.openjarvis.tasks.JarvisTaskRepository
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VideoArtifactMetadataCodec
import com.openjarvis.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoArtifactPlayerInstrumentedTest {
    @Test
    fun revokedOrUnreadableContentUriReturnsUnavailableForRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metadata = com.openjarvis.tasks.VideoArtifactMetadata(
            durationMs = 6_000L,
            width = 360,
            height = 640,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR"
        )
        val state = VideoArtifactPlaybackValidator.inspect(
            context,
            "content://com.openjarvis.revoked/video/1",
            "content://com.openjarvis.revoked/captions/1",
            VideoArtifactMetadataCodec.encode(metadata)
        )
        assertTrue(state is PlaybackAvailability.Unavailable)
    }

    @Test
    fun productionCreateAcceptExecutorPersistsAndDashboardPlaysSameArtifactAfterRoomReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val repository = JarvisTaskRepository.getInstance(context)
        val requestKey = "player-e2e-" + UUID.randomUUID().toString()
        var taskId: String? = null
        var videoUri: Uri? = null
        var captionsUri: Uri? = null
        var thumbnailUri: Uri? = null
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            // Use the production repository and its registered VideoStoryTaskExecutor. The plan
            // must be durably accepted before rendering; no direct renderer call or manual task
            // insertion is allowed in this end-to-end acceptance path.
            val createdTaskId = repository.createTask(
                goal = "İstanbul'un Fethi hakkında video oluştur",
                idempotencyKey = requestKey
            )
            taskId = createdTaskId

            val database = JarvisTaskDatabase.getInstance(context)
            val preview = database.taskDao().findById(createdTaskId)
                ?: throw AssertionError("Production repository must persist the task preview.")
            assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, preview.status)
            assertEquals(null, preview.planAcceptedAt)
            assertTrue(preview.videoUri.isNullOrBlank())
            assertTrue(preview.captionsUri.isNullOrBlank())
            assertTrue(preview.outputSummary.orEmpty().contains("Henüz video oluşturulmadı"))

            repository.acceptPlan(createdTaskId)

            val completed = database.taskDao().findById(createdTaskId)
                ?: throw AssertionError("Accepted production task must remain in Room.")
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)
            assertTrue(completed.planAcceptedAt != null)
            assertTrue(completed.outputSummary.orEmpty().contains("MP4 hazır"))

            val artifacts = JSONArray(completed.artifactRefs)
            assertEquals("The executor must persist MP4, SRT and thumbnail together.", 3, artifacts.length())
            val persistedVideoUri = Uri.parse(artifacts.getString(0))
            val persistedCaptionsUri = Uri.parse(artifacts.getString(1))
            val persistedThumbnailUri = Uri.parse(artifacts.getString(2))
            videoUri = persistedVideoUri
            captionsUri = persistedCaptionsUri
            thumbnailUri = persistedThumbnailUri
            assertEquals(persistedVideoUri.toString(), completed.videoUri)
            assertEquals(persistedCaptionsUri.toString(), completed.captionsUri)
            assertTrue(persistedVideoUri.toString().startsWith("content://"))
            assertTrue(persistedCaptionsUri.toString().startsWith("content://"))
            assertTrue(persistedThumbnailUri.toString().startsWith("content://"))

            val metadata = VideoArtifactMetadataCodec.decode(completed.videoArtifactMetadataJson)
                ?: throw AssertionError("Production renderer metadata must be persisted.")
            assertTrue(metadata.isValid())
            assertTrue(metadata.attributionManifestComplete)
            assertEquals(5, metadata.sceneCount)
            assertEquals(5, metadata.attributionManifest.size)
            assertEquals((0..4).toList(), metadata.attributionManifest.map { it.sceneIndex }.sorted())
            assertTrue(metadata.attributionManifest.all { it.isValid() })
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
            assertEquals(persistedThumbnailUri.toString(), metadata.thumbnailUri)
            assertTrue(completed.outputSummary.orEmpty().contains(persistedVideoUri.toString()))
            assertTrue(completed.outputSummary.orEmpty().contains(persistedCaptionsUri.toString()))
            assertTrue(completed.outputSummary.orEmpty().contains(persistedThumbnailUri.toString()))

            // Verify the exact persisted MP4 is readable and contains both expected tracks.
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, persistedVideoUri, null)
                val mimeTypes = (0 until extractor.trackCount).mapNotNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                }
                assertTrue("MP4 must contain H.264 video.", mimeTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC))
                assertTrue("MP4 must contain AAC audio.", mimeTypes.contains(MediaFormat.MIMETYPE_AUDIO_AAC))
                val audioTrack = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                }
                extractor.selectTrack(audioTrack)
                assertTrue(
                    "AAC sample from the persisted MediaStore URI must be readable.",
                    extractor.readSampleData(ByteBuffer.allocate(2 * 1024 * 1024), 0) > 0
                )
            } finally {
                extractor.release()
            }

            val captionsText = context.contentResolver.openInputStream(persistedCaptionsUri)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val cueBlocks = captionsText.trim().split(Regex("\\r?\\n\\s*\\r?\\n"))
            assertEquals("The renderer must persist one hook cue and five scene cues.", 6, cueBlocks.size)
            assertTrue(cueBlocks.all { it.contains("-->") })

            val thumbnailHeader = context.contentResolver.openInputStream(persistedThumbnailUri)!!.use { input ->
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

            val initialAvailability = VideoArtifactPlaybackValidator.inspect(
                context,
                persistedVideoUri.toString(),
                persistedCaptionsUri.toString(),
                completed.videoArtifactMetadataJson
            )
            assertTrue("The persisted MP4/SRT pair must pass the same validator used by the Dashboard.", initialAvailability is PlaybackAvailability.Ready)

            // Open the real host Activity and wait for the Dashboard-owned player to restore the
            // exact URI produced by the accepted production task.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitDashboardPlayer(scenario!!, persistedVideoUri.toString())
            scenario!!.recreate()
            val recreatedPlayer = awaitDashboardPlayer(scenario!!, persistedVideoUri.toString())
            assertEquals(persistedVideoUri.toString(), recreatedPlayer.tag)

            // Simulate process loss: close the Activity and production Room singleton, then
            // reopen the same on-disk database before launching a fresh host Activity.
            scenario!!.close()
            scenario = null
            JarvisTaskDatabase.closeInstanceForTesting()
            val reopenedDatabase = JarvisTaskDatabase.getInstance(context)
            val restoredTask = reopenedDatabase.taskDao().findById(createdTaskId)
                ?: throw AssertionError("The production-created task must survive Room reopen.")
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, restoredTask.status)
            assertEquals(persistedVideoUri.toString(), restoredTask.videoUri)
            assertEquals(persistedCaptionsUri.toString(), restoredTask.captionsUri)
            assertTrue(restoredTask.artifactRefs.contains(persistedVideoUri.toString()))
            assertTrue(restoredTask.artifactRefs.contains(persistedCaptionsUri.toString()))
            assertTrue(restoredTask.artifactRefs.contains(persistedThumbnailUri.toString()))

            val restoredMetadata = VideoArtifactMetadataCodec.decode(restoredTask.videoArtifactMetadataJson)
                ?: throw AssertionError("Renderer metadata must survive Room reopen.")
            assertEquals(metadata, restoredMetadata)
            assertTrue(restoredMetadata.attributionManifestComplete)
            assertTrue(restoredMetadata.attributionManifest.all { it.isValid() })
            assertTrue(restoredMetadata.claimSources.all { it.isValid() })
            assertEquals(metadata.attributionCredits, restoredMetadata.attributionCredits)
            assertEquals(metadata.researchCredits, restoredMetadata.researchCredits)
            assertEquals(metadata.thumbnailUri, restoredMetadata.thumbnailUri)
            assertEquals(metadata.attributionManifest.first().sha256, restoredMetadata.attributionManifest.first().sha256)
            assertEquals(metadata.attributionManifest.first().licenseUrl, restoredMetadata.attributionManifest.first().licenseUrl)
            assertEquals(metadata.attributionManifest.first().cacheMetadata, restoredMetadata.attributionManifest.first().cacheMetadata)

            val reopenedAvailability = VideoArtifactPlaybackValidator.inspect(
                context,
                restoredTask.videoUri,
                restoredTask.captionsUri,
                restoredTask.videoArtifactMetadataJson
            )
            assertTrue("The restored URI pair must remain playable after Room reopen.", reopenedAvailability is PlaybackAvailability.Ready)

            scenario = ActivityScenario.launch(MainActivity::class.java)
            val restoredPlayer = awaitDashboardPlayer(scenario!!, videoUri.toString())
            assertEquals(videoUri.toString(), restoredPlayer.tag)

            // Start the exact VideoView created by VideoArtifactPlayerPanel. Do not construct
            // another player or reset its URI: the Dashboard must play the persisted task URI.
            scenario!!.onActivity {
                val player = findVideoView(it.window.decorView)
                    ?: throw AssertionError("The embedded dashboard VideoView must exist.")
                assertEquals(persistedVideoUri.toString(), player.tag)
                player.start()
            }
            val playbackProgressMs = awaitPlaybackProgress(scenario!!)
            assertTrue(
                "The production Dashboard player must advance the same executor-produced MediaStore URI after Room reopen.",
                playbackProgressMs > 0
            )
        } finally {
            scenario?.close()
            JarvisTaskDatabase.closeInstanceForTesting()
            runCatching {
                val cleanupDatabase = JarvisTaskDatabase.getInstance(context)
                taskId?.let { cleanupDatabase.taskDao().deleteById(it) }
            }
            JarvisTaskDatabase.closeInstanceForTesting()
            listOf(videoUri, captionsUri, thumbnailUri).forEach { uri ->
                if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    private fun awaitDashboardPlayer(
        scenario: ActivityScenario<MainActivity>,
        expectedUri: String
    ): VideoView {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var found: VideoView? = null
            scenario.onActivity { activity ->
                found = findVideoView(activity.window.decorView)
                    ?.takeIf { it.tag == expectedUri }
            }
            if (found != null) return found!!
            SystemClock.sleep(100)
        }
        throw AssertionError("Production DashboardScreen did not restore the embedded VideoArtifactPlayerPanel for the persisted URI.")
    }

    private fun awaitPlaybackProgress(scenario: ActivityScenario<MainActivity>): Int {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var progressMs = 0
            scenario.onActivity { activity ->
                val player = findVideoView(activity.window.decorView)
                    ?: throw AssertionError("The production embedded VideoView disappeared.")
                if (player.isPlaying || player.currentPosition > 0) {
                    progressMs = player.currentPosition.coerceAtLeast(1)
                }
            }
            if (progressMs > 0) return progressMs
            SystemClock.sleep(100)
        }
        throw AssertionError("The production embedded player did not begin playback after the user play action.")
    }

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVideoView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
