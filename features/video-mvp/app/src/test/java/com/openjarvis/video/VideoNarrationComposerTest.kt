package com.openjarvis.video

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNarrationComposerTest {

    @Test
    fun pcmWavReaderCalculatesOneSecondMonoDuration() {
        val pcm = ByteArray(16_000 * 2)
        val file = File.createTempFile("jarvis-tts-", ".wav")
        try {
            file.writeBytes(wavFile(pcm, sampleRate = 16_000, channels = 1))
            val parsed = PcmWavReader.read(file)
            assertEquals(16_000, parsed.sampleRate)
            assertEquals(1, parsed.channels)
            assertEquals(16, parsed.bitsPerSample)
            assertEquals(1_000L, parsed.durationMs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pcmWavReaderRejectsNonPcmEncoding() {
        val file = File.createTempFile("jarvis-tts-invalid-", ".wav")
        try {
            file.writeBytes(wavFile(ByteArray(3_200), sampleRate = 16_000, channels = 1, format = 3))
            val error = runCatching { PcmWavReader.read(file) }.exceptionOrNull()
            assertTrue(error is VideoRenderException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun captionsFollowActualPerSceneSpeechDurations() {
        val scenes = listOf(VideoScene("Bir", "İlk sahne"), VideoScene("İki", "İkinci sahne"))
        val captions = VideoNarrationTimeline.captions(scenes, listOf(1_250L, 2_500L), 5_000L)
        assertEquals(0L, captions[0].startMs)
        assertEquals(1_250L, captions[0].endMs)
        assertEquals("Bir. İlk sahne", captions[0].text)
        assertEquals(1_250L, captions[1].startMs)
        assertEquals(3_750L, captions[1].endMs)
        assertEquals("Bir. İlk sahne", captions[0].text)
        assertEquals("İki. İkinci sahne", captions[1].text)
    }


    @Test
    fun sceneFrameDurationsFollowMeasuredSpeechWithOnlyShortVisualHold() {
        val speechDurations = listOf(6_600L, 5_400L, 7_300L)
        val frames = VideoNarrationTimeline.sceneFrameDurations(speechDurations, 24)

        assertEquals(listOf(159, 130, 176), frames)
        val renderedDurationMs = frames.sum().toLong() * 1_000L / 24L
        assertTrue(renderedDurationMs >= speechDurations.sum())
        assertTrue(renderedDurationMs - speechDurations.sum() <= 250L)
    }

    @Test
    fun sceneFrameDurationsRejectEmptyOrInvalidSpeechTimeline() {
        assertTrue(runCatching {
            VideoNarrationTimeline.sceneFrameDurations(emptyList(), 24)
        }.isFailure)
        assertTrue(runCatching {
            VideoNarrationTimeline.sceneFrameDurations(listOf(1_000L), 0)
        }.isFailure)
    }

    @Test
    fun finalCaptionDoesNotCoverPaddedSilentVideoTail() {
        val scenes = listOf(
            VideoScene("Giriş", "İlk anlatım"),
            VideoScene("Sonuç", "Son anlatım")
        )
        val captions = VideoNarrationTimeline.captions(scenes, listOf(1_000L, 1_500L), 20_000L)

        assertEquals(2_500L, captions.last().endMs)
        assertTrue(captions.last().endMs < 20_000L)
    }

    @Test
    fun captionTextIncludesTheHeadingThatTtsActuallySpeaks() {
        val scenes = listOf(VideoScene("Kuşatma başladı", "6 Nisan 1453'te surlar kuşatıldı."))
        val caption = VideoNarrationTimeline.captions(scenes, listOf(3_200L), 20_000L).single()

        assertEquals("Kuşatma başladı. 6 Nisan 1453'te surlar kuşatıldı.", caption.text)
    }

    @Test
    fun narrationLongerThanRenderedVideoIsRejected() {
        val scenes = listOf(VideoScene("Bir", "İlk sahne"), VideoScene("İki", "İkinci sahne"))
        val error = runCatching {
            VideoNarrationTimeline.captions(scenes, listOf(12_000L, 10_000L), 20_000L)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun firstHookCaptionStartsAtZeroAndUsesMeasuredTtsDuration() {
        val captions = VideoNarrationTimeline.captionsForSpeechSegments(
            listOf("Gemiler karadan Haliç'e!", "Haliç zinciri. Savunma hattı."),
            listOf(1_650L, 3_400L),
            6_000L
        )
        assertEquals(0L, captions.first().startMs)
        assertEquals(1_650L, captions.first().endMs)
        assertEquals("Gemiler karadan Haliç'e!", captions.first().text)
        assertEquals(1_650L, captions[1].startMs)
        assertEquals(5_050L, captions[1].endMs)
    }

    @Test
    fun hookCaptionRejectsSpeechThatWouldRunPastTwoSecondsOrVideoEnd() {
        assertTrue(runCatching {
            VideoStoryQualityPolicy.validateHookDuration(2_001L)
        }.isFailure)
        assertTrue(runCatching {
            VideoNarrationTimeline.captionsForSpeechSegments(
                listOf("Gemiler karadan Haliç'e!"),
                listOf(1_500L),
                1_000L
            )
        }.isFailure)
    }

    @Test
    fun longNarrationIsSplitIntoReadableWordBoundedCuesUsingMeasuredSpeechTime() {
        val spoken = "Osmanlı gemileri gece boyunca Galata sırtlarından dikkatle Haliç'e indirildi ve savunma hattını şaşırttı."
        val captions = VideoNarrationTimeline.captionsForSpeechSegments(
            listOf(spoken),
            listOf(4_800L),
            6_000L
        )

        assertTrue(captions.size > 1)
        assertTrue(captions.all { it.text.length <= 42 })
        assertEquals(0L, captions.first().startMs)
        assertEquals(4_800L, captions.last().endMs)
        captions.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endMs, right.startMs)
        }
        assertEquals(spoken, captions.joinToString(" ") { it.text })
    }

    @Test
    fun narrationFailsClosedWhenReadableCaptionChunksWouldFlashTooQuickly() {
        val spoken = "Osmanlı gemileri gece boyunca Galata sırtlarından dikkatle Haliç'e indirildi ve savunma hattını şaşırttı."
        val error = runCatching {
            VideoNarrationTimeline.captionsForSpeechSegments(
                listOf(spoken),
                listOf(1_200L),
                3_000L
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun shortHookRemainsOneCueAndKeepsItsMeasuredDuration() {
        val captions = VideoNarrationTimeline.captionsForSpeechSegments(
            listOf("Gemiler karadan Haliç'e!"),
            listOf(1_650L),
            3_000L
        )

        assertEquals(1, captions.size)
        assertEquals(0L, captions.single().startMs)
        assertEquals(1_650L, captions.single().endMs)
        assertEquals("Gemiler karadan Haliç'e!", captions.single().text)
    }

    @Test
    fun actualTtsVoiceMustBePresentAndExactlyTurkishAfterSelection() {
        assertTrue(!isActualVoiceExactTurkish(null))
        assertTrue(!isActualVoiceExactTurkish(Locale.forLanguageTag("en-US")))
        assertTrue(isActualVoiceExactTurkish(Locale.forLanguageTag("tr-TR")))
    }

    private fun wavFile(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        format: Int = 1
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.writeIntLe(36 + pcm.size)
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        output.writeIntLe(16)
        output.writeShortLe(format)
        output.writeShortLe(channels)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * channels * 2)
        output.writeShortLe(channels * 2)
        output.writeShortLe(16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.writeIntLe(pcm.size)
        output.write(pcm)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
