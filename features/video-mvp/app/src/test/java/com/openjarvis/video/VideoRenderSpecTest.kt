package com.openjarvis.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRenderSpecTest {
    @Test
    fun sampleIsBoundedPortraitSilentDraft() {
        val spec = VideoRenderSpec.sample("  İstanbul'un Fethi  ")
        spec.validate()
        assertEquals("İstanbul'un Fethi", spec.title)
        assertEquals(6, spec.durationSeconds)
        assertEquals(144, spec.frameCount)
        assertEquals(360, spec.width)
        assertEquals(640, spec.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankTitle() {
        VideoRenderSpec.sample("   ").validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLandscapeOutput() {
        VideoRenderSpec(
            title = "Başlık",
            scenes = listOf(VideoScene("Sahne", "Açıklama")),
            width = 640,
            height = 360,
            secondsPerScene = 3
        ).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedFrameRate() {
        VideoRenderSpec.sample("Konu").copy(framesPerSecond = 60).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooManyScenes() {
        VideoRenderSpec.sample("Konu").copy(
            scenes = (1..7).map { VideoScene("Sahne $it", "Açıklama") }
        ).validate()
    }

    @Test
    fun estimatedOutputRemainsBelowPrototypeCap() {
        val spec = VideoRenderSpec.sample("Tarih")
        spec.validate()
        assertTrue(spec.estimatedOutputBytes > 0L)
        assertTrue(spec.estimatedOutputBytes <= VideoRenderSpec.MAX_OUTPUT_BYTES)
        assertTrue(VideoRenderSpec.MIN_FREE_SPACE_MARGIN_BYTES >= 2L * 1024L * 1024L)
    }
}
