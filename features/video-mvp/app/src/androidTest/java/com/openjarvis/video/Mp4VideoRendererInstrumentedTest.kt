package com.openjarvis.video

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Mp4VideoRendererInstrumentedTest {
    @Test
    fun rendersReadableSilentPortraitMp4IntoAppPrivateStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spec = VideoRenderSpec.sample("Android 13 video testi").copy(
            width = 360,
            height = 640,
            framesPerSecond = 15,
            secondsPerScene = 1
        )
        val result = Mp4VideoRenderer(context).render(spec)
        try {
            assertTrue(result.file.isFile)
            assertTrue(result.file.length() > 4_096L)
            assertTrue(result.file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            assertEquals(360, result.width)
            assertEquals(640, result.height)
            assertTrue(result.durationMs >= 2_000L)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(result.file.absolutePath)
                val videoTracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                assertEquals(1, videoTracks.size)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, videoTracks.single().getString(MediaFormat.KEY_MIME))
                assertEquals(360, videoTracks.single().getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(640, videoTracks.single().getInteger(MediaFormat.KEY_HEIGHT))
            } finally {
                extractor.release()
            }
        } finally {
            result.file.delete()
        }
    }

    @Test
    fun cancellationAfterFirstFrameDeletesPartialOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = File(context.filesDir, "jarvis-videos")
        val existingNames = outputDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val jobReference = AtomicReference<Job?>()
        val spec = VideoRenderSpec.sample("İptal testi").copy(
            framesPerSecond = 15,
            secondsPerScene = 5
        )
        val renderJob = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            Mp4VideoRenderer(context).render(spec) { completedFrames, _ ->
                if (completedFrames >= 1) jobReference.get()?.cancel()
            }
        }
        jobReference.set(renderJob)
        renderJob.start()

        withTimeout(15_000L) { renderJob.join() }

        assertTrue("Render işi ilk kareden sonra iptal edilmeliydi.", renderJob.isCancelled)
        val newFiles = outputDir.listFiles()?.filterNot { it.name in existingNames }.orEmpty()
        assertTrue("İptal edilen render yarım MP4 bırakmamalı.", newFiles.isEmpty())
    }
}
