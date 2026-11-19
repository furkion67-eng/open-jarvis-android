package com.openjarvis.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import com.openjarvis.voice.ActualTtsVoiceLocaleProvider
import com.openjarvis.voice.AndroidTTSEngine
import com.openjarvis.voice.TurkishTtsStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** CI-only end-to-end check for tr-TR TTS -> AAC + H.264 -> timed SRT -> MediaStore export. */
@RunWith(AndroidJUnit4::class)
class VideoStoryPipelineInstrumentedTest {
    @Test
    fun rendersTurkishNarratedPortraitMp4OrFailsClosedWithoutTurkishVoice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val statusFile = File(context.filesDir, "ci-video-audio-status.txt")
        statusFile.delete()

        val result = try {
            VideoStoryPipeline(context).render("İstanbul'un Fethi")
        } catch (error: VideoRenderException) {
            val message = error.message.orEmpty()
            val noTurkishVoice =
                message.contains("Bu cihazda Türkçe ses verisi bulunamadı") ||
                    message.contains("kullanılabilir Türkçe ses bulunamadı")
            if (!noTurkishVoice) throw error
            statusFile.writeText("TURKISH_TTS_UNAVAILABLE_ON_EMULATOR\n$message")
            Log.w("OpenJarvisVideoCI", "TURKISH_TTS_UNAVAILABLE_ON_EMULATOR: fail-closed path verified")
            return@runBlocking
        }

        val evidenceVideo = File(context.filesDir, "ci-video-artifact.mp4")
        val evidenceCaptions = File(context.filesDir, "ci-video-artifact.srt")
        try {
            result.video.file.copyTo(evidenceVideo, overwrite = true)
            result.captionsFile.copyTo(evidenceCaptions, overwrite = true)
            assertTrue("Narrated MP4 is missing", evidenceVideo.isFile && evidenceVideo.length() > 4_096L)
            assertTrue("Timed SRT is missing", evidenceCaptions.isFile && evidenceCaptions.length() > 0L)
            assertTrue("Expected five storyboard scenes", result.sceneCount == 5)
            assertTrue("Expected Turkish narration duration", result.audioDurationMs > 0L)
            assertTrue("Expected procedural local visuals", result.sourceLabel.contains("prosedürel çizimler"))
            assertTrue("Expected Istanbul narration text", result.narrationText.contains("29 Mayıs 1453"))

            val exportedVideoBytes = context.contentResolver.openInputStream(result.exportedUri)?.use { it.readBytes() }
                ?: throw AssertionError("MediaStore MP4 URI could not be opened")
            val exportedCaptionText = context.contentResolver.openInputStream(result.captionsUri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw AssertionError("MediaStore SRT URI could not be opened")
            assertTrue("Exported MP4 is empty", exportedVideoBytes.size > 4_096)
            assertTrue("Exported SRT is empty", exportedCaptionText.contains("29 Mayıs 1453"))

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(evidenceVideo.absolutePath)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val videos = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val audios = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                assertEquals("Exactly one H.264 video track required", 1, videos.size)
                assertEquals("Exactly one AAC audio track required", 1, audios.size)
                val video = videos.single()
                val audio = audios.single()
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, video.getString(MediaFormat.KEY_MIME))
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, audio.getString(MediaFormat.KEY_MIME))
                assertEquals(360, video.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(640, video.getInteger(MediaFormat.KEY_HEIGHT))
                val videoDuration = video.getLong(MediaFormat.KEY_DURATION) / 1_000L
                val audioDuration = audio.getLong(MediaFormat.KEY_DURATION) / 1_000L
                assertTrue("Expected 15–60s video, got ${videoDuration}ms", videoDuration in 15_000L..60_000L)
                assertTrue("AAC/H.264 duration mismatch: ${audioDuration}ms vs ${videoDuration}ms", abs(audioDuration - videoDuration) <= 750L)
            } finally {
                extractor.release()
            }

            val srt = evidenceCaptions.readText(Charsets.UTF_8)
            val cueCount = Regex("(?m)^\\d+$").findAll(srt).count()
            assertEquals(5, cueCount)
            assertTrue("SRT must start at zero", srt.contains("00:00:00,000 -->"))
            val expectedSpeechEnd = formatSrtTime(result.audioDurationMs)
            assertTrue("Last SRT cue must end at actual speech duration", srt.contains("--> $expectedSpeechEnd"))
            assertTrue(srt.contains("29 Mayıs 1453"))
            statusFile.writeText("VIDEO-NARRATED-PIPELINE-OK\nAAC+H264\nMediaStore MP4+SRT verified\n")
            Log.i("OpenJarvisVideoCI", "VIDEO-NARRATED-PIPELINE-OK: 360x640, AAC+H.264, 5 speech-timed SRT cues")
        } finally {
            context.contentResolver.delete(result.exportedUri, null, null)
            context.contentResolver.delete(result.captionsUri, null, null)
            result.video.file.delete()
            result.captionsFile.delete()
            // Keep CI evidence copies until scripts/smoke_test.sh extracts them.
            // Temporary renderer files and MediaStore rows above are still cleaned.
        }
        Unit
    }

    @Test
    fun actualEngineVariantLocaleNeverReportsReady() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val nonCanonicalTags = listOf("tr-TR-u-nu-latn", "tr-TR-x-private", "tr-TR-variant")

        for (tag in nonCanonicalTags) {
            val providerReads = AtomicInteger(0)
            lateinit var engine: AndroidTTSEngine
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                engine = AndroidTTSEngine(
                    context,
                    ActualTtsVoiceLocaleProvider {
                        providerReads.incrementAndGet()
                        tag
                    }
                )
            }

            try {
                val status = withTimeout(15_000) {
                    engine.status.first { it !is TurkishTtsStatus.Initializing }
                }
                assertEquals("The actual engine voice must be inspected for $tag", 1, providerReads.get())
                assertEquals("A non-canonical actual engine locale must fail closed: $tag", TurkishTtsStatus.TurkishVoiceMissing, status)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.release() }
            }
        }
    }

    private fun formatSrtTime(valueMs: Long): String {
        val hours = valueMs / 3_600_000L
        val minutes = (valueMs % 3_600_000L) / 60_000L
        val seconds = (valueMs % 60_000L) / 1_000L
        val millis = valueMs % 1_000L
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
