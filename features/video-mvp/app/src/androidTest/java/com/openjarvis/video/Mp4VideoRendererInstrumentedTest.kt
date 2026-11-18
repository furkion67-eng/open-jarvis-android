package com.openjarvis.video

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
