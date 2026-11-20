package com.openjarvis.ui.player

import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.JarvisTaskDatabase
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
    fun persistedMediaStoreVideoReopensAndPlaysInsideJarvisAfterActivityRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artifactKey = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "jarvis_player_reopen_" + artifactKey + ".db"
        context.deleteDatabase(databaseName)
        val rendered = VideoStoryPipeline(context).render(
            prompt = "JARVIS uygulama içi video oynatıcı testi",
            artifactKey = artifactKey
        )
        val metadata = VideoArtifactMetadata(
            durationMs = rendered.video.durationMs,
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
        val task = JarvisTaskEntity(
            id = "player-" + artifactKey,
            goal = "JARVIS player instrumentation",
            status = JarvisTaskStatus.SUCCEEDED.storageValue,
            outputSummary = "Renderer MP4 ve SRT doğrulandı.",
            artifactRefs = encodeRefs(rendered.exportedUri.toString(), rendered.captionsUri.toString()),
            idempotencyKey = "player-key-" + artifactKey,
            videoUri = rendered.exportedUri.toString(),
            captionsUri = rendered.captionsUri.toString(),
            videoArtifactMetadataJson = VideoArtifactMetadataCodec.encode(metadata)
        )

        val first = Room.databaseBuilder(context, JarvisTaskDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        first.taskDao().insert(task)
        first.close()

        val reopened = Room.databaseBuilder(context, JarvisTaskDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val restored = reopened.taskDao().findById(task.id)!!
            assertEquals(rendered.exportedUri.toString(), restored.videoUri)
            assertEquals(rendered.captionsUri.toString(), restored.captionsUri)
            val availability = VideoArtifactPlaybackValidator.inspect(
                context,
                restored.videoUri,
                restored.captionsUri,
                restored.videoArtifactMetadataJson
            )
            assertTrue("Persisted MediaStore pair must remain readable", availability is PlaybackAvailability.Ready)

            val prepared = CountDownLatch(1)
            val playbackError = AtomicReference<String?>(null)
            val playerReference = AtomicReference<VideoView?>(null)
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            activeScenario.recreate()
            activeScenario.onActivity { activity ->
                val player = VideoView(activity)
                val controller = MediaController(activity)
                controller.setAnchorView(player)
                player.setMediaController(controller)
                player.setOnPreparedListener {
                    player.start()
                    prepared.countDown()
                }
                player.setOnErrorListener { _, what, extra ->
                    playbackError.set("what=" + what + ", extra=" + extra)
                    prepared.countDown()
                    true
                }
                activity.addContentView(
                    player,
                    FrameLayout.LayoutParams(360, 640)
                )
                playerReference.set(player)
                player.setVideoURI(android.net.Uri.parse(restored.videoUri))
            }

            assertTrue("VideoView must prepare the persisted content URI", prepared.await(30, TimeUnit.SECONDS))
            assertTrue("VideoView error: " + playbackError.get(), playbackError.get() == null)
            Thread.sleep(600)
            activeScenario.onActivity { assertTrue(playerReference.get()?.isPlaying == true || (playerReference.get()?.currentPosition ?: 0) > 0) }
        } finally {
            scenario?.close()
            reopened.close()
            context.deleteDatabase(databaseName)
            runCatching { context.contentResolver.delete(rendered.exportedUri, null, null) }
            runCatching { context.contentResolver.delete(rendered.captionsUri, null, null) }
            rendered.video.file.delete()
            rendered.captionsFile.delete()
        }
    }

    private fun encodeRefs(video: String, captions: String): String =
        "[\"" + video.replace("\\", "\\\\").replace("\"", "\\\"") +
            "\",\"" + captions.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]"
}
