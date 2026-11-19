package com.openjarvis.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** CI-only integration check for the current storyboard + silent MP4 + timed SRT stage. */
@RunWith(AndroidJUnit4::class)
class VideoStoryPipelineInstrumentedTest {
    @Test
    fun rendersTwentySecondPortraitStoryboardAndExportsTimedCaptionEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = VideoStoryPipeline(context).render("İstanbul'un Fethi")
        val exportedVideo = File(context.filesDir, "ci-video-artifact.mp4")
        val exportedCaptions = File(context.filesDir, "ci-video-artifact.srt")

        try {
            result.video.file.copyTo(exportedVideo, overwrite = true)
            result.captionsFile.copyTo(exportedCaptions, overwrite = true)

            assertTrue("Rendered MP4 is missing", exportedVideo.isFile && exportedVideo.length() > 4_096L)
            assertTrue("Timed SRT sidecar is missing", exportedCaptions.isFile && exportedCaptions.length() > 0L)
            assertTrue("MP4 must stay inside app-private storage", exportedVideo.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            assertEquals(5, result.sceneCount)
            assertEquals("Yerel tarih şablonu", result.sourceLabel)
            assertTrue("Narration script should be available as text", result.narrationText.contains("29 Mayıs 1453"))

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(exportedVideo.absolutePath)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val videoTracks = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val audioTracks = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                assertEquals(1, videoTracks.size)
                assertEquals("Current MVP stage is explicitly silent; narrationText is not an audio track", 0, audioTracks.size)
                val video = videoTracks.single()
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, video.getString(MediaFormat.KEY_MIME))
                assertEquals(360, video.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(640, video.getInteger(MediaFormat.KEY_HEIGHT))
                val durationMs = video.getLong(MediaFormat.KEY_DURATION) / 1_000L
                assertTrue("Expected 15–60 seconds, received " + durationMs + "ms", durationMs in 15_000L..60_000L)
            } finally {
                extractor.release()
            }

            val srt = exportedCaptions.readText(Charsets.UTF_8)
            val cueCount = Regex("(?m)^\\d+$").findAll(srt).count()
            assertEquals(5, cueCount)
            assertTrue(srt.contains("00:00:00,000 --> 00:00:04,000"))
            assertTrue(srt.contains("00:00:16,000 --> 00:00:20,000"))
            assertTrue(srt.contains("29 Mayıs 1453"))
            Log.i("OpenJarvisVideoCI", "VIDEO-STORY-PIPELINE-OK: 360x640, 20s, 5 timed SRT cues, silent stage")
        } finally {
            result.video.file.delete()
            result.captionsFile.delete()
        }
    }
}
