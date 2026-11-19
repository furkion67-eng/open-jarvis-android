package com.openjarvis.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.os.StatFs
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.TimeoutException

class VideoRenderException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class VideoRenderResult(
    val file: File,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long
)

/** Silent local renderer; no network, account, publishing or external-storage dependency. */
class Mp4VideoRenderer(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Renders on Dispatchers.IO. Progress is called synchronously on that dispatcher;
     * callbacks must be lightweight and safe to invoke off the main thread.
     */
    suspend fun render(
        spec: VideoRenderSpec,
        onProgress: (completedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> }
    ): VideoRenderResult {
        spec.validate()
        return withContext(Dispatchers.IO) {
            val jobContext = currentCoroutineContext()
            renderBlocking(spec, onProgress) { jobContext.ensureActive() }
        }
    }

    private fun renderBlocking(
        spec: VideoRenderSpec,
        onProgress: (Int, Int) -> Unit,
        ensureActive: () -> Unit
    ): VideoRenderResult {
        val outputDir = File(appContext.filesDir, "jarvis-videos")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw VideoRenderException("JARVIS video klasörü oluşturulamadı.")
        }
        val requiredBytes = spec.estimatedOutputBytes + VideoRenderSpec.MIN_FREE_SPACE_MARGIN_BYTES
        val availableBytes = runCatching { StatFs(outputDir.absolutePath).availableBytes }.getOrElse {
            throw VideoRenderException("Cihaz depolama alanı kontrol edilemedi.", it)
        }
        if (availableBytes < requiredBytes) {
            val requiredMb = (requiredBytes + 1024L * 1024L - 1L) / (1024L * 1024L)
            throw VideoRenderException("Yeterli boş alan yok. Yaklaşık ${requiredMb} MB boş alan bırak.")
        }
        val output = File(outputDir, "jarvis-${System.currentTimeMillis()}-${UUID.randomUUID()}.mp4")
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var eglRenderer: EglFrameRenderer? = null
        var muxerStarted = false
        var muxerTrack = -1
        var completed = false
        val bufferInfo = MediaCodec.BufferInfo()

        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.framesPerSecond)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val activeCodec = try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (error: Exception) {
                throw VideoRenderException("Bu cihazda H.264 video kodlayıcısı başlatılamadı.", error)
            }
            codec = activeCodec
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val activeInputSurface = activeCodec.createInputSurface()
            inputSurface = activeInputSurface
            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            activeCodec.start()
            val activeEglRenderer = EglFrameRenderer(activeInputSurface, spec.width, spec.height)
            eglRenderer = activeEglRenderer

            val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            try {
                for (frame in 0 until spec.frameCount) {
                    ensureActive()
                    drawFrame(canvas, bitmap, spec, frame)
                    val presentationTimeNs = frame * 1_000_000_000L / spec.framesPerSecond
                    activeEglRenderer.draw(bitmap, presentationTimeNs)
                    drainEncoder(activeCodec, bufferInfo, false, onFormatChanged = { outputFormat ->
                        if (muxerStarted) throw VideoRenderException("Video biçimi birden fazla kez değişti.")
                        muxerTrack = activeMuxer.addTrack(outputFormat)
                        activeMuxer.start()
                        muxerStarted = true
                    }, writeSample = { buffer, info ->
                        if (!muxerStarted || muxerTrack < 0) throw VideoRenderException("MP4 yazıcısı hazır değil.")
                        activeMuxer.writeSampleData(muxerTrack, buffer, info)
                    })
                    if (frame % 4 == 0 || frame == spec.frameCount - 1) {
                        val done = frame + 1
                        // Invoke synchronously on the render dispatcher: callers can cancel deterministically,
                        // and no queued progress callback can overwrite the terminal UI state after render returns.
                        onProgress(done, spec.frameCount)
                    }
                }
            } finally {
                bitmap.recycle()
            }

            ensureActive()
            activeCodec.signalEndOfInputStream()
            drainEncoder(activeCodec, bufferInfo, true, onFormatChanged = { outputFormat ->
                if (muxerStarted) throw VideoRenderException("Video biçimi birden fazla kez değişti.")
                muxerTrack = activeMuxer.addTrack(outputFormat)
                activeMuxer.start()
                muxerStarted = true
            }, writeSample = { buffer, info ->
                if (!muxerStarted || muxerTrack < 0) throw VideoRenderException("MP4 yazıcısı hazır değil.")
                activeMuxer.writeSampleData(muxerTrack, buffer, info)
            })
            if (!muxerStarted) throw VideoRenderException("Video kodlayıcısı MP4 çıktısı üretmedi.")
            activeMuxer.stop()
            muxerStarted = false
            val result = validateOutput(output, spec)
            completed = true
            result
        } catch (error: VideoRenderException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TimeoutException) {
            throw VideoRenderException("Video kodlama zaman aşımına uğradı.", error)
        } catch (error: Exception) {
            throw VideoRenderException("MP4 oluşturulamadı. Cihazın video kodlayıcısını ve boş alanı kontrol et.", error)
        } finally {
            runCatching { eglRenderer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!completed) output.delete()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onFormatChanged: (MediaFormat) -> Unit,
        writeSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (SystemClock.elapsedRealtime() >= deadline) throw TimeoutException("Encoder EOS timeout")
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(codec.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    val encoded = codec.getOutputBuffer(index)
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !codecConfig) {
                        if (encoded == null) throw VideoRenderException("Kodlanmış video verisi okunamadı.")
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        writeSample(encoded, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun validateOutput(file: File, spec: VideoRenderSpec): VideoRenderResult {
        if (!file.isFile || file.length() < 4_096L) {
            throw VideoRenderException("Oluşturulan MP4 dosyası boş veya eksik.")
        }
        if (file.length() > VideoRenderSpec.MAX_OUTPUT_BYTES) {
            throw VideoRenderException("MP4 dosyası 24 MB güvenlik sınırını aştı.")
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw VideoRenderException("MP4 içinde video izi bulunamadı.")
            val trackFormat = extractor.getTrackFormat(track)
            val width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationMs = trackFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L
            if (width != spec.width || height != spec.height || durationMs < spec.durationSeconds * 700L) {
                throw VideoRenderException("MP4 doğrulaması beklenen çözünürlük veya süreyi bulamadı.")
            }
            return VideoRenderResult(file, width, height, durationMs, file.length())
        } finally {
            extractor.release()
        }
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap, spec: VideoRenderSpec, frame: Int) {
        canvas.drawColor(Color.rgb(8, 12, 28))
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        paint.color = Color.rgb(124, 92, 255)
        canvas.drawRoundRect(RectF(width * 0.08f, height * 0.09f, width * 0.92f, height * 0.105f), 8f, 8f, paint)

        paint.color = Color.rgb(178, 168, 208)
        paint.textSize = width * 0.035f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        canvas.drawText("JARVIS  •  YEREL MP4 TASLAĞI", width / 2f, height * 0.17f, paint)

        val sceneIndex = (frame / (spec.framesPerSecond * spec.secondsPerScene)).coerceAtMost(spec.scenes.lastIndex)
        val scene = spec.scenes[sceneIndex]
        paint.color = Color.WHITE
        paint.textSize = width * 0.085f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        drawCenteredWrapped(canvas, spec.title, paint, width / 2f, height * 0.34f, width * 0.82f, width * 0.105f)
        drawSceneVisual(canvas, width, height, scene.visualStyle)

        paint.color = Color.rgb(91, 224, 225)
        paint.textSize = width * 0.062f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        drawCenteredWrapped(canvas, scene.heading, paint, width / 2f, height * 0.56f, width * 0.82f, width * 0.08f)

        paint.color = Color.rgb(214, 218, 232)
        paint.textSize = width * 0.047f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        drawCenteredWrapped(canvas, scene.caption, paint, width / 2f, height * 0.66f, width * 0.82f, width * 0.065f)

        paint.color = Color.rgb(45, 48, 68)
        canvas.drawRoundRect(RectF(width * 0.08f, height * 0.91f, width * 0.92f, height * 0.925f), 8f, 8f, paint)
        paint.color = Color.rgb(91, 224, 225)
        val progress = (frame + 1).toFloat() / spec.frameCount.toFloat()
        canvas.drawRoundRect(RectF(width * 0.08f, height * 0.91f, width * (0.08f + 0.84f * progress), height * 0.925f), 8f, 8f, paint)
    }


    /** Local vector illustrations; these are procedural graphics, not AI-generated images. */
    private fun drawSceneVisual(canvas: Canvas, width: Float, height: Float, style: VideoVisualStyle) {
        val cx = width / 2f
        val cy = height * 0.455f
        val half = width * 0.32f
        val base = cy + height * 0.045f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width * 0.012f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val stone = Color.rgb(58, 72, 108)
        val aqua = Color.rgb(91, 224, 225)
        val gold = Color.rgb(238, 177, 83)

        when (style) {
            VideoVisualStyle.CITY_WALLS -> {
                fill.color = stone
                canvas.drawRect(cx - half, base - height * 0.055f, cx + half, base, fill)
                canvas.drawRect(cx - half * 0.82f, base - height * 0.15f, cx - half * 0.52f, base, fill)
                canvas.drawRect(cx + half * 0.52f, base - height * 0.15f, cx + half * 0.82f, base, fill)
                for (i in 0..5) {
                    val x = cx - half + i * (half * 0.4f)
                    canvas.drawRect(x, base - height * 0.075f, x + width * 0.045f, base - height * 0.055f, fill)
                }
                line.color = aqua
                canvas.drawLine(cx - half, base - height * 0.055f, cx + half, base - height * 0.055f, line)
                fill.color = Color.rgb(12, 18, 36)
                canvas.drawRoundRect(RectF(cx - width * 0.035f, base - height * 0.075f, cx + width * 0.035f, base), width * 0.025f, width * 0.025f, fill)
            }
            VideoVisualStyle.CANNON_SIEGE -> {
                fill.color = stone
                canvas.drawRect(cx - half, base - height * 0.06f, cx + half, base, fill)
                canvas.drawRect(cx - half * 0.65f, base - height * 0.13f, cx - half * 0.38f, base - height * 0.06f, fill)
                fill.color = Color.rgb(166, 112, 67)
                canvas.drawRoundRect(RectF(cx - width * 0.16f, base - height * 0.085f, cx + width * 0.18f, base - height * 0.045f), width * 0.02f, width * 0.02f, fill)
                fill.color = aqua
                canvas.drawCircle(cx - width * 0.09f, base - height * 0.025f, width * 0.035f, fill)
                canvas.drawCircle(cx + width * 0.10f, base - height * 0.025f, width * 0.035f, fill)
                line.color = gold
                canvas.drawLine(cx + width * 0.08f, base - height * 0.075f, cx + half * 0.78f, base - height * 0.18f, line)
                fill.color = gold
                canvas.drawCircle(cx + half * 0.78f, base - height * 0.18f, width * 0.012f, fill)
            }
            VideoVisualStyle.SHIPS -> {
                line.color = aqua
                for (i in 0..2) {
                    val y = base - height * (0.015f + i * 0.025f)
                    canvas.drawLine(cx - half, y, cx + half, y, line)
                }
                val hull = android.graphics.Path().apply {
                    moveTo(cx - half * 0.85f, base - height * 0.06f)
                    lineTo(cx + half * 0.85f, base - height * 0.06f)
                    lineTo(cx + half * 0.55f, base - height * 0.015f)
                    lineTo(cx - half * 0.55f, base - height * 0.015f)
                    close()
                }
                fill.color = Color.rgb(156, 92, 57)
                canvas.drawPath(hull, fill)
                val sail = android.graphics.Path().apply {
                    moveTo(cx, base - height * 0.07f)
                    lineTo(cx, base - height * 0.24f)
                    lineTo(cx + half * 0.55f, base - height * 0.07f)
                    close()
                }
                fill.color = Color.rgb(224, 219, 198)
                canvas.drawPath(sail, fill)
                line.color = gold
                canvas.drawLine(cx, base - height * 0.25f, cx, base - height * 0.005f, line)
            }
            VideoVisualStyle.SUNRISE -> {
                fill.color = gold
                canvas.drawCircle(cx, cy - height * 0.055f, width * 0.105f, fill)
                line.color = gold
                canvas.drawLine(cx, cy - height * 0.20f, cx, cy - height * 0.16f, line)
                canvas.drawLine(cx - width * 0.18f, cy - height * 0.15f, cx - width * 0.14f, cy - height * 0.12f, line)
                canvas.drawLine(cx + width * 0.18f, cy - height * 0.15f, cx + width * 0.14f, cy - height * 0.12f, line)
                fill.color = stone
                canvas.drawRect(cx - half, base - height * 0.055f, cx + half, base, fill)
                canvas.drawRect(cx - half * 0.7f, base - height * 0.14f, cx - half * 0.42f, base, fill)
                canvas.drawRect(cx + half * 0.42f, base - height * 0.14f, cx + half * 0.7f, base, fill)
            }
            VideoVisualStyle.CITY_MAP -> {
                line.color = aqua
                canvas.drawCircle(cx, cy - height * 0.015f, width * 0.22f, line)
                canvas.drawCircle(cx, cy - height * 0.015f, width * 0.13f, line)
                canvas.drawLine(cx - width * 0.22f, cy - height * 0.015f, cx + width * 0.22f, cy - height * 0.015f, line)
                canvas.drawLine(cx, cy - height * 0.12f, cx, cy + height * 0.09f, line)
                fill.color = gold
                canvas.drawCircle(cx, cy - height * 0.015f, width * 0.045f, fill)
            }
            VideoVisualStyle.GENERIC -> {
                line.color = aqua
                canvas.drawCircle(cx, cy - height * 0.02f, width * 0.14f, line)
                canvas.drawLine(cx - width * 0.22f, cy - height * 0.02f, cx + width * 0.22f, cy - height * 0.02f, line)
                canvas.drawLine(cx, cy - height * 0.13f, cx, cy + height * 0.09f, line)
                fill.color = Color.rgb(124, 92, 255)
                canvas.drawCircle(cx, cy - height * 0.02f, width * 0.035f, fill)
            }
        }
    }

    private fun drawCenteredWrapped(
        canvas: Canvas,
        text: String,
        paint: Paint,
        centerX: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float
    ) {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines += current
                current = word
            } else current = candidate
        }
        if (current.isNotEmpty()) lines += current
        lines.take(5).forEachIndexed { index, line ->
            canvas.drawText(line, centerX, startY + index * lineHeight, paint)
        }
    }

    private class EglFrameRenderer(surface: Surface, private val width: Int, private val height: Int) {
        private var display: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
        private var window: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
        private var initialized = false
        private var program: Int = 0
        private val texture = IntArray(1)
        private var textureInitialized = false
        private val positionBuffer: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val textureBuffer: FloatBuffer = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

        init {
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) throw VideoRenderException("EGL ekranı başlatılamadı.")
                val version = IntArray(2)
                initialized = EGL14.eglInitialize(display, version, 0, version, 1)
                if (!initialized) throw VideoRenderException("EGL başlatılamadı.")
                val configAttributes = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                    throw VideoRenderException("Video kodlayıcı için EGL yapılandırması bulunamadı.")
                }
                val config = configs[0] ?: throw VideoRenderException("EGL yapılandırması boş.")
                val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
                if (context == EGL14.EGL_NO_CONTEXT) throw VideoRenderException("EGL bağlamı oluşturulamadı.")
                window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (window == EGL14.EGL_NO_SURFACE) throw VideoRenderException("Video yüzeyi oluşturulamadı.")
                if (!EGL14.eglMakeCurrent(display, window, window, context)) {
                    throw VideoRenderException("EGL video yüzeyi etkinleştirilemedi.")
                }
                program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                GLES20.glGenTextures(1, texture, 0)
                if (texture[0] == 0) throw VideoRenderException("Video dokusu oluşturulamadı.")
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            } catch (error: Throwable) {
                release()
                throw error
            }
        }
        fun draw(bitmap: Bitmap, presentationTimeNs: Long) {
            EGL14.eglMakeCurrent(display, window, window, context)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            if (textureInitialized) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            else {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                textureInitialized = true
            }
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            val sampler = GLES20.glGetUniformLocation(program, "uTexture")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
            GLES20.glUniform1i(sampler, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(texCoord)
            if (!EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)) {
                throw VideoRenderException("Video kare zaman damgası yazılamadı.")
            }
            if (!EGL14.eglSwapBuffers(display, window)) throw VideoRenderException("Video karesi kodlayıcıya aktarılamadı.")
        }

        fun release() {
            if (initialized && context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglMakeCurrent(display, window, window, context) }
            }
            if (initialized && context != EGL14.EGL_NO_CONTEXT) {
                if (texture[0] != 0) runCatching { GLES20.glDeleteTextures(1, texture, 0) }
                if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                }
            }
            if (initialized && window != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(display, window) }
            }
            if (initialized && context != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(display, context) }
            }
            if (initialized) runCatching { EGL14.eglTerminate(display) }
            initialized = false
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            window = EGL14.EGL_NO_SURFACE
            program = 0
            texture[0] = 0
        }

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            var fragment = 0
            var result = 0
            try {
                fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
                result = GLES20.glCreateProgram()
                if (result == 0) throw VideoRenderException("Video çizim programı oluşturulamadı.")
                GLES20.glAttachShader(result, vertex)
                GLES20.glAttachShader(result, fragment)
                GLES20.glLinkProgram(result)
                val status = IntArray(1)
                GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    throw VideoRenderException("Video çizim programı bağlanamadı: ${GLES20.glGetProgramInfoLog(result)}")
                }
                return result
            } catch (error: Throwable) {
                if (result != 0) runCatching { GLES20.glDeleteProgram(result) }
                throw error
            } finally {
                runCatching { GLES20.glDeleteShader(vertex) }
                if (fragment != 0) runCatching { GLES20.glDeleteShader(fragment) }
            }
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw VideoRenderException("Video çizim gölgelendiricisi derlenemedi: $error")
            }
            return shader
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
            """
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTexture;
                varying vec2 vTexCoord;
                void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
            """
        }
    }
}
