package com.openjarvis.ui.player

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.tasks.JarvisTaskDatabase
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VideoArtifactMetadata
import com.openjarvis.tasks.VideoArtifactMetadataCodec
import com.openjarvis.tasks.VideoRendererMetadataAdapter
import com.openjarvis.ui.MainActivity
import com.openjarvis.video.VideoStoryPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoArtifactPlayerInstrumentedTest {
    @Test
    fun revokedOrUnreadableContentUriReturnsUnavailableForRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metadata = VideoArtifactMetadata(
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
    fun productionDashboardRestoresAndPlaysRendererArtifactAndCreditsAfterRoomReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = JarvisTaskDatabase.getInstance(context)
        val artifactKey = UUID.randomUUID().toString().replace("-", "")
        val rendered = VideoStoryPipeline(context).render(
            prompt = "İstanbul'un fethi 1453 uygulama içi video oynatıcı testi",
            artifactKey = artifactKey
        )
        val metadata = VideoRendererMetadataAdapter.fromRenderResult(
            renderResult = rendered,
            durationMs = rendered.video.durationMs,
            audioDurationMs = rendered.audioDurationMs,
            width = rendered.video.width,
            height = rendered.video.height,
            frameRate = 24,
            hasAudio = true,
            sceneCount = rendered.sceneCount,
            sourceLabel = rendered.sourceLabel
        )
        assertTrue(metadata.attributionManifestComplete)
        assertEquals(rendered.sceneCount, metadata.attributionManifest.size)
        assertTrue(metadata.claimSources.isNotEmpty())
        assertTrue(!metadata.thumbnailUri.isNullOrBlank())

        val videoUri = rendered.exportedUri.toString()
        val captionsUri = rendered.captionsUri.toString()
        val thumbnailUri = metadata.thumbnailUri
            ?: throw AssertionError("Renderer thumbnail URI must be present.")
        val now = System.currentTimeMillis()
        val task = JarvisTaskEntity(
            id = "player-" + artifactKey,
            goal = "JARVIS player instrumentation",
            status = JarvisTaskStatus.SUCCEEDED.storageValue,
            outputSummary = "Renderer MP4, SRT, cover and attribution verified.",
            artifactRefs = encodeRefs(videoUri, captionsUri, thumbnailUri),
            idempotencyKey = "player-key-" + artifactKey,
            createdAt = now,
            updatedAt = now,
            videoUri = videoUri,
            captionsUri = captionsUri,
            videoArtifactMetadataJson = VideoArtifactMetadataCodec.encode(metadata)
        )

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            database.taskDao().insert(task)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitDashboardPlayer(scenario, videoUri)
            scenario.recreate()
            val activityRestoredPlayer = awaitDashboardPlayer(scenario, videoUri)
            assertEquals(videoUri, activityRestoredPlayer.tag)

            // Simulate process loss: close the production Room singleton, then launch a fresh
            // Activity against a newly opened database instance backed by the same on-disk file.
            scenario.close()
            scenario = null
            JarvisTaskDatabase.closeInstanceForTesting()
            val reopenedDatabase = JarvisTaskDatabase.getInstance(context)
            val restoredTask = reopenedDatabase.taskDao().findById(task.id)
                ?: throw AssertionError("The production Room task must survive database reopen.")
            assertEquals(videoUri, restoredTask.videoUri)
            assertEquals(captionsUri, restoredTask.captionsUri)
            assertTrue(restoredTask.artifactRefs.contains(videoUri))
            assertTrue(restoredTask.artifactRefs.contains(captionsUri))
            assertTrue(restoredTask.artifactRefs.contains(thumbnailUri))
            val restoredMetadata = VideoArtifactMetadataCodec.decode(restoredTask.videoArtifactMetadataJson)
                ?: throw AssertionError("Renderer metadata must survive database reopen.")
            assertEquals(metadata, restoredMetadata)
            assertEquals(metadata.attributionCredits, restoredMetadata.attributionCredits)
            assertEquals(metadata.researchCredits, restoredMetadata.researchCredits)
            assertEquals(metadata.thumbnailUri, restoredMetadata.thumbnailUri)
            assertTrue(restoredMetadata.attributionManifestComplete)
            assertTrue(restoredMetadata.attributionManifest.all { it.isValid() })
            assertTrue(restoredMetadata.claimSources.all { it.isValid() })
            assertEquals(metadata.attributionManifest.first().sha256, restoredMetadata.attributionManifest.first().sha256)
            assertEquals(metadata.attributionManifest.first().licenseUrl, restoredMetadata.attributionManifest.first().licenseUrl)
            assertEquals(metadata.attributionManifest.first().cacheMetadata, restoredMetadata.attributionManifest.first().cacheMetadata)

            scenario = ActivityScenario.launch(MainActivity::class.java)
            val restoredPlayer = awaitDashboardPlayer(scenario, videoUri)
            assertEquals(videoUri, restoredPlayer.tag)

            // Start the exact VideoView created by VideoArtifactPlayerPanel. Do not construct
            // another player or reset its URI: the Dashboard must restore the persisted task URI.
            scenario.onActivity {
                val player = findVideoView(it.window.decorView)
                    ?: throw AssertionError("The embedded dashboard VideoView must exist.")
                assertEquals(videoUri, player.tag)
                player.start()
            }
            val playbackProgressMs = awaitPlaybackProgress(scenario)
            assertTrue(
                "The production dashboard player must advance the restored MediaStore URI",
                playbackProgressMs > 0
            )
        } finally {
            scenario?.close()
            JarvisTaskDatabase.closeInstanceForTesting()
            runCatching { JarvisTaskDatabase.getInstance(context).taskDao().deleteById(task.id) }
            JarvisTaskDatabase.closeInstanceForTesting()
            runCatching { context.contentResolver.delete(rendered.exportedUri, null, null) }
            runCatching { context.contentResolver.delete(rendered.captionsUri, null, null) }
            runCatching { context.contentResolver.delete(android.net.Uri.parse(thumbnailUri), null, null) }
            runCatching { rendered.video.file.delete() }
            runCatching { rendered.captionsFile.delete() }
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

    private fun encodeRefs(vararg values: String): String =
        org.json.JSONArray(values.toList()).toString()
}
