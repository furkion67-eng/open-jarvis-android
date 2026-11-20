package com.openjarvis.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Feature-owned end-to-end acceptance test for the narrated local video pipeline.
 * It is explicitly skipped (not passed) when the emulator has no exact tr-TR voice.
 */
@RunWith(AndroidJUnit4::class)
class VideoNarratedExportInstrumentedTest {

    @Test
    fun exactTrTrVoiceExportsAacH264AndSpeechTimedCaptions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "SKIPPED: emulator has no installed exact tr-TR TTS voice",
            hasExactTurkishVoice(context)
        )

        val result = withTimeout(120_000L) {
            VideoStoryPipeline(context).render("İstanbul'un Fethi")
        }
        try {
            assertTrue("MP4 export must use a MediaStore content URI", result.exportedUri.scheme == "content")
            assertTrue("SRT export must use a MediaStore content URI", result.captionsUri.scheme == "content")
            assertTrue("Narrated MP4 must exist", result.video.file.isFile && result.video.file.length() > 4_096L)
            assertTrue("Actual narration duration must be positive", result.audioDurationMs > 0L)
            assertTrue("Narration must fit inside the video", result.audioDurationMs <= result.video.durationMs)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, result.exportedUri, null)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                val videoTracks = formats.filter {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val audioTracks = formats.filter {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                assertEquals("Exactly one H.264 video track is expected", 1, videoTracks.size)
                assertEquals("Exactly one AAC narration track is expected", 1, audioTracks.size)
                val video = videoTracks.single()
                val audio = audioTracks.single()
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, video.getString(MediaFormat.KEY_MIME))
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, audio.getString(MediaFormat.KEY_MIME))
                assertEquals("Shorts export must use the agreed 720px portrait width", 720, video.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals("Shorts export must use the agreed 1280px portrait height", 1_280, video.getInteger(MediaFormat.KEY_HEIGHT))

                val audioTrackIndex = formats.indexOf(audio)
                assertTrue("AAC track must contain encoded samples", audioTrackIndex >= 0)
                extractor.selectTrack(audioTrackIndex)
                val firstAacSample = ByteBuffer.allocate(256 * 1024)
                assertTrue(
                    "AAC track must be readable, not just present in metadata",
                    extractor.readSampleData(firstAacSample, 0) > 0
                )

                val videoDurationMs = video.getLong(MediaFormat.KEY_DURATION) / 1_000L
                val audioTrackDurationMs = audio.getLong(MediaFormat.KEY_DURATION) / 1_000L
                assertTrue("Expected a vertical short-form video, got ${videoDurationMs}ms", videoDurationMs in 15_000L..60_000L)
                assertTrue(
                    "AAC and H.264 track durations differ by more than 750ms",
                    abs(audioTrackDurationMs - videoDurationMs) <= 750L
                )
            } finally {
                extractor.release()
            }

            val srt = context.contentResolver.openInputStream(result.captionsUri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("Exported SRT could not be opened")
            val cueTimes = Regex(
                "(?m)^(\\d{2}:\\d{2}:\\d{2},\\d{3}) --> (\\d{2}:\\d{2}:\\d{2},\\d{3})$"
            ).findAll(srt).toList()
            assertEquals(
                "A dedicated measured hook cue plus one cue per storyboard scene is expected",
                result.sceneCount + 1,
                cueTimes.size
            )
            assertEquals(5, result.sceneCount)
            assertTrue("The measured hook must be positive and no longer than two seconds", result.hookDurationMs in 1L..2_000L)
            assertEquals("The hook caption must start at video time zero", formatSrtTime(0L), cueTimes.first().groupValues[1])
            assertEquals(
                "The hook caption must end at the measured hook TTS duration",
                formatSrtTime(result.hookDurationMs),
                cueTimes.first().groupValues[2]
            )
            assertEquals(
                "The first scene caption must begin immediately after the hook",
                cueTimes.first().groupValues[2],
                cueTimes[1].groupValues[1]
            )
            for (index in 1 until cueTimes.lastIndex) {
                assertEquals(
                    "Speech caption cues must be contiguous",
                    cueTimes[index].groupValues[2],
                    cueTimes[index + 1].groupValues[1]
                )
            }
            assertEquals(
                "Final SRT cue must end at actual measured narration duration",
                formatSrtTime(result.audioDurationMs),
                cueTimes.last().groupValues[2]
            )
            assertTrue("Spoken scene heading must appear in captions", srt.contains("Kuşatma başladı."))
        } finally {
            context.contentResolver.delete(result.exportedUri, null, null)
            context.contentResolver.delete(result.captionsUri, null, null)
            result.video.file.delete()
            result.captionsFile.delete()
        }
        Unit
    }

    private suspend fun hasExactTurkishVoice(context: Context): Boolean = withTimeout(15_000L) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var engine: TextToSpeech? = null
                try {
                    engine = TextToSpeech(context) { status ->
                        val available = status == TextToSpeech.SUCCESS && runCatching {
                            engine?.voices.orEmpty().any {
                                it.locale.toLanguageTag().equals("tr-TR", ignoreCase = true)
                            }
                        }.getOrDefault(false)
                        runCatching { engine?.shutdown() }
                        if (continuation.isActive) continuation.resume(available)
                    }
                    continuation.invokeOnCancellation {
                        engine?.let { active -> runCatching { active.shutdown() } }
                    }
                } catch (_: Exception) {
                    runCatching { engine?.shutdown() }
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    }

    private fun formatSrtTime(valueMs: Long): String {
        val hours = valueMs / 3_600_000L
        val minutes = (valueMs % 3_600_000L) / 60_000L
        val seconds = (valueMs % 60_000L) / 1_000L
        val millis = valueMs % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
