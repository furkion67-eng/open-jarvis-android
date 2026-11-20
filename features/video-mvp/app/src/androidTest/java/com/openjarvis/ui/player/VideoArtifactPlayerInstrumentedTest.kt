package com.openjarvis.ui.player

import android.net.Uri
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
import com.openjarvis.ui.MainActivity
import com.openjarvis.video.VideoStoryPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    fun productionDashboardRestoresAndPlaysThePersistedMediaStoreArtifactAfterActivityRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = JarvisTaskDatabase.getInstance(context)
        val artifactKey = UUID.randomUUID().toString().replace("-", "")
        val rendered = VideoStoryPipeline(context).render(
            prompt = "JARVIS uygulama içi video oynatıcı testi",
            artifactKey = artifactKey
        )
        val metadata = VideoArtifactMetadata(
            durationMs = rendered.video.durationMs,
            audioDurationMs = rendered.audioDurationMs,
            width = rendered.video.width,
            height = rendered.video.height,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR",
            sceneCount = rendered.sceneCount,
            sourceLabel = rendered.sourceLabel,
            attributionManifest = emptyList(),
            attributionManifestComplete = false
        )
        val now = System.currentTimeMillis()
        val task = JarvisTaskEntity(
            id = "player-" + artifactKey,
            goal = "JARVIS player instrumentation",
            status = JarvisTaskStatus.SUCCEEDED.storageValue,
            outputSummary = "Renderer MP4 ve SRT doğrulandı.",
            artifactRefs = encodeRefs(rendered.exportedUri.toString(), rendered.captionsUri.toString()),
            idempotencyKey = "player-key-" + artifactKey,
            createdAt = now,
            updatedAt = now,
            videoUri = rendered.exportedUri.toString(),
            captionsUri = rendered.captionsUri.toString(),
            videoArtifactMetadataJson = VideoArtifactMetadataCodec.encode(metadata)
        )

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            database.taskDao().insert(task)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitDashboardPlayer(scenario, rendered.exportedUri.toString())
            scenario.recreate()

            val restoredPlayer = awaitDashboardPlayer(scenario, rendered.exportedUri.toString())
            assertEquals(rendered.exportedUri.toString(), restoredPlayer.tag)

            val prepared = CountDownLatch(1)
            val playbackError = AtomicReference<String?>(null)
            scenario.onActivity {
                val player = findVideoView(it.window.decorView)
                    ?: throw AssertionError("The embedded dashboard VideoView must exist.")
                player.setOnPreparedListener {
                    player.start()
                    prepared.countDown()
                }
                player.setOnErrorListener { _, what, extra ->
                    playbackError.set("what=" + what + ", extra=" + extra)
                    prepared.countDown()
                    true
                }
                player.setVideoURI(Uri.parse(rendered.exportedUri.toString()))
            }

            assertTrue("The production embedded player must prepare the persisted content URI", prepared.await(30, TimeUnit.SECONDS))
            assertTrue("Embedded playback error: " + playbackError.get(), playbackError.get() == null)
            SystemClock.sleep(700)
            scenario.onActivity {
                val activePlayer = findVideoView(it.window.decorView)
                    ?: throw AssertionError("The embedded dashboard VideoView disappeared.")
                assertTrue(
                    "The dashboard's real embedded player must advance playback",
                    activePlayer.isPlaying || activePlayer.currentPosition > 0
                )
            }
        } finally {
            scenario?.close()
            runCatching { database.taskDao().deleteById(task.id) }
            runCatching { context.contentResolver.delete(rendered.exportedUri, null, null) }
            runCatching { context.contentResolver.delete(rendered.captionsUri, null, null) }
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

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVideoView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun encodeRefs(video: String, captions: String): String =
        "[\"" + video.replace("\\", "\\\\").replace("\"", "\\\"") +
            "\",\"" + captions.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]"
}
