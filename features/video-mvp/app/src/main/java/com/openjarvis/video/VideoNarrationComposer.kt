package com.openjarvis.video

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.openjarvis.voice.TurkishTtsPolicy
import com.openjarvis.voice.TurkishTtsVoiceCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PcmWav(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcm: ByteArray
) {
    val durationMs: Long
        get() = (pcm.size.toLong() / (channels * (bitsPerSample / 8)) * 1_000L) / sampleRate
}

/** Reads the PCM WAV format produced by Android TextToSpeech.synthesizeToFile. */
internal object PcmWavReader {
    fun read(file: File): PcmWav {
        if (!file.isFile || file.length() !in 44L..(8L * 1024L * 1024L)) {
            throw VideoRenderException("Türkçe ses dosyası boş veya desteklenen boyutun dışında.")
        }
        java.io.RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE") {
                throw VideoRenderException("Türkçe ses motoru PCM WAV biçiminde çıktı üretmedi.")
            }

            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var pcm: ByteArray? = null
            while (input.filePointer + 8L <= input.length()) {
                val chunkIdBytes = ByteArray(4)
                input.readFully(chunkIdBytes)
                val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
                val chunkSize = readIntLe(input)
                if (chunkSize < 0 || input.filePointer + chunkSize.toLong() > input.length()) {
                    throw VideoRenderException("Türkçe ses dosyasının WAV bölümleri bozuk.")
                }
                val chunkStart = input.filePointer
                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16) throw VideoRenderException("WAV ses biçimi başlığı eksik.")
                        audioFormat = readShortLe(input)
                        channels = readShortLe(input)
                        sampleRate = readIntLe(input)
                        readIntLe(input) // byte rate
                        readShortLe(input) // block alignment
                        bitsPerSample = readShortLe(input)
                    }
                    "data" -> {
                        if (chunkSize > MAX_PCM_BYTES) throw VideoRenderException("Türkçe ses dosyası çok büyük.")
                        pcm = ByteArray(chunkSize).also(input::readFully)
                    }
                }
                input.seek(chunkStart + chunkSize.toLong() + (chunkSize and 1).toLong())
                if (pcm != null && audioFormat != 0) break
            }

            val data = pcm ?: throw VideoRenderException("Türkçe ses dosyasında PCM verisi bulunamadı.")
            if (audioFormat != PCM_FORMAT || channels !in 1..2 ||
                sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE || bitsPerSample != 16 ||
                data.isEmpty() || data.size % (channels * 2) != 0) {
                throw VideoRenderException("Türkçe ses biçimi desteklenmiyor; 16-bit PCM mono/stereo gerekir.")
            }
            return PcmWav(sampleRate, channels, bitsPerSample, data)
        }
    }

    private fun readShortLe(input: java.io.RandomAccessFile): Int {
        val low = input.read()
        val high = input.read()
        if (low < 0 || high < 0) throw VideoRenderException("WAV başlığı beklenmedik biçimde sona erdi.")
        return (low and 0xff) or ((high and 0xff) shl 8)
    }

    private fun readIntLe(input: java.io.RandomAccessFile): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw VideoRenderException("WAV başlığı beklenmedik biçimde sona erdi.")
        }
        return (b0 and 0xff) or ((b1 and 0xff) shl 8) or
            ((b2 and 0xff) shl 16) or ((b3 and 0xff) shl 24)
    }

    private const val PCM_FORMAT = 1
    private const val MIN_SAMPLE_RATE = 16_000
    private const val MAX_SAMPLE_RATE = 48_000
    private const val MAX_PCM_BYTES = 8 * 1024 * 1024
}

internal object VideoNarrationTimeline {
    fun captions(
        scenes: List<VideoScene>,
        segmentDurationsMs: List<Long>,
        videoDurationMs: Long
    ): List<TimedVideoCaption> {
        require(scenes.isNotEmpty() && scenes.size == segmentDurationsMs.size)
        require(videoDurationMs > 0 && segmentDurationsMs.all { it > 0 })
        val audioDuration = segmentDurationsMs.sum()
        require(audioDuration <= videoDurationMs) {
            "Türkçe anlatım video süresini aşıyor; daha kısa bir konu metni kullan."
        }
        var cursor = 0L
        return scenes.mapIndexed { index, scene ->
            val start = cursor
            cursor += segmentDurationsMs[index]
            val end = if (index == scenes.lastIndex) videoDurationMs else cursor
            require(end > start)
            TimedVideoCaption(start, end, scene.caption)
        }
    }
}

internal data class NarratedVideoResult(
    val video: VideoRenderResult,
    val captions: List<TimedVideoCaption>,
    val audioDurationMs: Long
)

/**
 * Creates one Turkish TTS WAV per scene, encodes the PCM as AAC, muxes it with the silent
 * H.264 render, and verifies both tracks. No network/provider or publishing is used here.
 */
class VideoNarrationComposer(private val context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun compose(plan: VideoStoryPlan, silentVideo: VideoRenderResult): NarratedVideoResult {
        val tempDir = File(appContext.cacheDir, "jarvis-narration-${UUID.randomUUID()}")
        if (!tempDir.mkdirs()) throw VideoRenderException("Türkçe ses için geçici klasör oluşturulamadı.")
        var finalFile: File? = null
        var engine: TextToSpeech? = null
        try {
            engine = createTurkishEngine()
            configureTurkishVoice(engine)
            val segments = mutableListOf<PcmWav>()
            plan.scenes.forEachIndexed { index, scene ->
                val wavFile = File(tempDir, "scene-${index + 1}.wav")
                val spokenText = "${scene.heading}. ${scene.caption}"
                synthesizeScene(engine, spokenText, wavFile)
                segments += withContext(Dispatchers.IO) { PcmWavReader.read(wavFile) }
            }

            val first = segments.firstOrNull() ?: throw VideoRenderException("Video için anlatım sahnesi bulunamadı.")
            if (segments.any {
                    it.sampleRate != first.sampleRate || it.channels != first.channels ||
                        it.bitsPerSample != first.bitsPerSample
                }) {
                throw VideoRenderException("Türkçe ses motoru sahneler arasında farklı ses biçimleri üretti.")
            }
            val pcmOutput = ByteArrayOutputStream()
            segments.forEach { segment ->
                if (pcmOutput.size().toLong() + segment.pcm.size > MAX_COMBINED_PCM_BYTES) {
                    throw VideoRenderException("Türkçe anlatım dosyası güvenli boyut sınırını aştı.")
                }
                pcmOutput.write(segment.pcm)
            }
            val pcm = pcmOutput.toByteArray()
            val audioDurationMs = segments.sumOf { it.durationMs }
            if (audioDurationMs > silentVideo.durationMs) {
                throw VideoRenderException("Türkçe anlatım ${audioDurationMs / 1_000} saniye; video ${silentVideo.durationMs / 1_000} saniye. Daha kısa bir konu metni kullan.")
            }
            val captions = VideoNarrationTimeline.captions(
                plan.scenes,
                segments.map { it.durationMs },
                silentVideo.durationMs
            )

            val audioFile = File(tempDir, "narration.m4a")
            finalFile = File(
                silentVideo.file.parentFile,
                silentVideo.file.nameWithoutExtension + "-turkish.mp4"
            )
            val jobContext = currentCoroutineContext()
            withContext(Dispatchers.IO) {
                encodeAac(pcm, first.sampleRate, first.channels, audioFile) { jobContext.ensureActive() }
                muxVideoAndAudio(silentVideo.file, audioFile, finalFile!!) { jobContext.ensureActive() }
                verifyMuxedFile(finalFile!!, silentVideo.width, silentVideo.height, silentVideo.durationMs)
            }
            val final = finalFile!!
            return NarratedVideoResult(
                video = silentVideo.copy(file = final, sizeBytes = final.length()),
                captions = captions,
                audioDurationMs = audioDurationMs
            )
        } catch (cancelled: CancellationException) {
            finalFile?.delete()
            throw cancelled
        } catch (error: VideoRenderException) {
            finalFile?.delete()
            throw error
        } catch (error: Exception) {
            finalFile?.delete()
            throw VideoRenderException("Türkçe sesli MP4 oluşturulamadı: ${error.message ?: "bilinmeyen hata"}", error)
        } finally {
            engine?.let { active ->
                withContext(Dispatchers.Main.immediate) {
                    runCatching { active.stop() }
                    runCatching { active.shutdown() }
                }
            }
            tempDir.deleteRecursively()
        }
    }

    private suspend fun createTurkishEngine(): TextToSpeech = withTimeout(TTS_INIT_TIMEOUT_MS) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                lateinit var engine: TextToSpeech
                try {
                    engine = TextToSpeech(appContext) { status ->
                        mainHandler.post {
                            if (!continuation.isActive) {
                                runCatching { engine.shutdown() }
                            } else if (status == TextToSpeech.SUCCESS) {
                                continuation.resume(engine)
                            } else {
                                runCatching { engine.shutdown() }
                                continuation.resumeWithException(
                                    VideoRenderException("Android Türkçe ses motoru başlatılamadı.")
                                )
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        mainHandler.post {
                            runCatching { engine.stop() }
                            runCatching { engine.shutdown() }
                        }
                    }
                } catch (error: Exception) {
                    continuation.resumeWithException(
                        VideoRenderException("Android metin okuma motoru açılamadı.", error)
                    )
                }
            }
        }
    }

    private fun configureTurkishVoice(engine: TextToSpeech) {
        val locale = Locale.forLanguageTag("tr-TR")
        val available = runCatching { engine.isLanguageAvailable(locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val selectedLanguage = runCatching { engine.setLanguage(locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (!TurkishTtsPolicy.isLanguageAvailable(available) ||
            !TurkishTtsPolicy.isLanguageAvailable(selectedLanguage)) {
            throw VideoRenderException(
                "Bu cihazda Türkçe ses verisi bulunamadı. Android metin okuma ayarlarından Türkçe ses yükle."
            )
        }

        val installed = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
        val candidates = installed.map { voice ->
            TurkishTtsVoiceCandidate(
                name = voice.name,
                localeTag = voice.locale.toLanguageTag(),
                quality = voice.quality,
                latency = voice.latency,
                requiresNetwork = TurkishTtsPolicy.requiresNetwork(voice.features.orEmpty())
            )
        }
        val selected = TurkishTtsPolicy.selectVoice(candidates)
            ?: throw VideoRenderException("Kurulu Android ses motorunda kullanılabilir Türkçe ses bulunamadı.")
        val voice = installed.firstOrNull { it.name == selected.name }
            ?: throw VideoRenderException("Seçilen Türkçe ses motoru artık kullanılamıyor.")
        if (runCatching { engine.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.SUCCESS) {
            throw VideoRenderException("Türkçe ses seçilemedi.")
        }
        val actualLocale = runCatching { engine.voice?.locale ?: voice.locale }.getOrDefault(voice.locale)
        if (!TurkishTtsPolicy.isTurkishLocaleTag(actualLocale.toLanguageTag())) {
            throw VideoRenderException("Ses motoru Türkçe yerine başka bir dil seçti; video oluşturulmadı.")
        }
        runCatching { engine.setSpeechRate(1.08f) }
        runCatching { engine.setPitch(1.0f) }
    }

    private suspend fun synthesizeScene(engine: TextToSpeech, text: String, output: File) {
        withTimeout(TTS_SCENE_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val utteranceId = "jarvis_video_${UUID.randomUUID()}"
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == this@VideoNarrationComposer.currentUtteranceId) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            if (utteranceId == this@VideoNarrationComposer.currentUtteranceId &&
                                continuation.isActive) {
                                continuation.resumeWithException(
                                    VideoRenderException("Türkçe seslendirme sırasında Android TTS hatası oluştu.")
                                )
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            onError(utteranceId)
                        }
                    })
                    currentUtteranceId = utteranceId
                    val result = runCatching {
                        engine.synthesizeToFile(text, Bundle(), output, utteranceId)
                    }.getOrDefault(TextToSpeech.ERROR)
                    if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                        continuation.resumeWithException(
                            VideoRenderException("Türkçe ses dosyası üretimi başlatılamadı.")
                        )
                    }
                    continuation.invokeOnCancellation {
                        mainHandler.post { runCatching { engine.stop() } }
                    }
                }
            }
        }
        if (!output.isFile || output.length() < 44L) {
            throw VideoRenderException("Türkçe ses motoru boş bir ses dosyası üretti.")
        }
    }

    @Volatile private var currentUtteranceId: String = ""

    private fun encodeAac(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        output: File,
        ensureActive: () -> Unit
    ) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var muxerTrack = -1
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_INPUT_BUFFER_BYTES)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
            val activeCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = activeCodec
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            activeCodec.start()
            codecStarted = true
            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer

            val info = MediaCodec.BufferInfo()
            var inputOffset = 0
            var inputEnded = false
            var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + AAC_ENCODE_TIMEOUT_MS
            val bytesPerFrame = channels * 2
            while (!outputEnded) {
                ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw VideoRenderException("AAC ses kodlama zaman aşımına uğradı.")
                }
                if (!inputEnded) {
                    val inputIndex = activeCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                            ?: throw VideoRenderException("AAC kodlayıcı giriş tamponu kullanılamıyor.")
                        inputBuffer.clear()
                        val remaining = pcm.size - inputOffset
                        var size = minOf(inputBuffer.remaining(), remaining)
                        size -= size % bytesPerFrame
                        if (size <= 0 && remaining > 0) {
                            throw VideoRenderException("AAC kodlayıcı giriş tamponu çok küçük.")
                        }
                        if (size > 0) inputBuffer.put(pcm, inputOffset, size)
                        val presentationTimeUs =
                            (inputOffset.toLong() / bytesPerFrame) * 1_000_000L / sampleRate
                        inputOffset += size
                        val flags = if (inputOffset >= pcm.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        activeCodec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, flags)
                        if (flags != 0) inputEnded = true
                    }
                }

                when (val outputIndex = activeCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw VideoRenderException("AAC ses biçimi birden fazla kez değişti.")
                        muxerTrack = activeMuxer.addTrack(activeCodec.outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val encoded = activeCodec.getOutputBuffer(outputIndex)
                        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !codecConfig) {
                            if (!muxerStarted || muxerTrack < 0 || encoded == null) {
                                throw VideoRenderException("AAC MP4 yazıcısı hazır değil.")
                            }
                            val sample = encoded.duplicate().apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }
                            val copy = ByteBuffer.allocateDirect(info.size)
                            copy.put(sample)
                            copy.flip()
                            val sampleInfo = MediaCodec.BufferInfo().apply {
                                set(0, info.size, info.presentationTimeUs, info.flags)
                            }
                            activeMuxer.writeSampleData(muxerTrack, copy, sampleInfo)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        activeCodec.releaseOutputBuffer(outputIndex, false)
                        if (eos) outputEnded = true
                    }
                }
            }
            if (!muxerStarted) throw VideoRenderException("AAC kodlayıcı ses izi üretmedi.")
            activeMuxer.stop()
            muxerStarted = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: VideoRenderException) {
            throw error
        } catch (error: Exception) {
            throw VideoRenderException("AAC ses izi oluşturulamadı.", error)
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!output.isFile || output.length() < 256L) output.delete()
        }
    }

    private fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        output: File,
        ensureActive: () -> Unit
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val outputVideoTrack = activeMuxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outputAudioTrack = activeMuxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            activeMuxer.start()
            muxerStarted = true
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val videoBuffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
            val audioBuffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
            var videoTime = videoExtractor.sampleTime
            var audioTime = audioExtractor.sampleTime
            while (videoTime >= 0L || audioTime >= 0L) {
                ensureActive()
                val takeVideo = audioTime < 0L || (videoTime >= 0L && videoTime <= audioTime)
                val extractor = if (takeVideo) videoExtractor else audioExtractor
                val targetTrack = if (takeVideo) outputVideoTrack else outputAudioTrack
                val buffer = if (takeVideo) videoBuffer else audioBuffer
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (takeVideo) videoTime = -1L else audioTime = -1L
                } else {
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    }
                    activeMuxer.writeSampleData(targetTrack, buffer, info)
                    extractor.advance()
                    if (takeVideo) videoTime = videoExtractor.sampleTime else audioTime = audioExtractor.sampleTime
                }
            }
            activeMuxer.stop()
            muxerStarted = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: VideoRenderException) {
            throw error
        } catch (error: Exception) {
            throw VideoRenderException("H.264 video ile AAC ses birleştirilemedi.", error)
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (!output.isFile || output.length() < 4_096L) output.delete()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: throw VideoRenderException("Medya dosyasında gerekli $prefix izi bulunamadı.")

    private fun verifyMuxedFile(file: File, width: Int, height: Int, expectedVideoDurationMs: Long) {
        if (!file.isFile || file.length() < 4_096L || file.length() > VideoRenderSpec.MAX_OUTPUT_BYTES) {
            throw VideoRenderException("Sesli MP4 dosyası boş, eksik veya 24 MB sınırını aşıyor.")
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) video = format
                if (mime.startsWith("audio/")) audio = format
            }
            val videoFormat = video ?: throw VideoRenderException("Son MP4 içinde video izi bulunamadı.")
            val audioFormat = audio ?: throw VideoRenderException("Son MP4 içinde AAC ses izi bulunamadı.")
            if (videoFormat.getInteger(MediaFormat.KEY_WIDTH) != width ||
                videoFormat.getInteger(MediaFormat.KEY_HEIGHT) != height) {
                throw VideoRenderException("Son MP4 dikey video çözünürlüğünü korumadı.")
            }
            val videoDuration = videoFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L
            val audioDuration = audioFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L
            if (videoDuration < expectedVideoDurationMs * 700L / 1_000L ||
                audioDuration < 1_000L || audioDuration > videoDuration + 300L) {
                throw VideoRenderException("Son MP4 ses/video süre doğrulamasından geçemedi.")
            }
        } finally {
            extractor.release()
        }
    }

    companion object {
        private const val TTS_INIT_TIMEOUT_MS = 15_000L
        private const val TTS_SCENE_TIMEOUT_MS = 45_000L
        private const val AAC_ENCODE_TIMEOUT_MS = 30_000L
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val AAC_BIT_RATE = 128_000
        private const val AAC_INPUT_BUFFER_BYTES = 16_384
        private const val MAX_SAMPLE_BYTES = 2 * 1024 * 1024
        private const val MAX_COMBINED_PCM_BYTES = 12 * 1024 * 1024
    }
}

/** Saves a verified MP4 to the public Movies collection without requesting broad storage access. */
object VideoMediaStoreExporter {
    fun export(context: Context, file: File): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw VideoRenderException("Galeriye güvenli dışa aktarma Android 10 ve üzeri gerektirir.")
        }
        if (!file.isFile || file.length() < 4_096L) {
            throw VideoRenderException("Dışa aktarılacak MP4 dosyası bulunamadı.")
        }
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Open JARVIS")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw VideoRenderException("MP4 galeriye kaydedilemedi.")
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw VideoRenderException("Galeri MP4 dosyasına yazılamadı.")
            output.use { stream ->
                file.inputStream().use { input -> input.copyTo(stream) }
            }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) {
                throw VideoRenderException("MP4 galeri kaydı tamamlanamadı.")
            }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            if (error is VideoRenderException) throw error
            throw VideoRenderException("MP4 galeriye aktarılamadı.", error)
        }
    }
}
