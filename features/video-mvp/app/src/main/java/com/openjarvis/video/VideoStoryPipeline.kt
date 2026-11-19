package com.openjarvis.video

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

data class VideoStoryRenderResult(
    val video: VideoRenderResult,
    val captionsFile: File,
    val exportedUri: Uri,
    val captionsUri: Uri,
    val audioDurationMs: Long,
    val narrationText: String,
    val sceneCount: Int,
    val sourceLabel: String
)

/**
 * End-to-end local workflow: topic -> Turkish storyboard/procedural scenes -> tr-TR TTS WAV
 * per scene -> AAC + H.264 MP4 -> actual-speech SRT -> MediaStore export.
 *
 * If a Turkish voice is unavailable, rendering fails with a Turkish explanation; English
 * fallback is deliberately forbidden. Procedural visuals are local illustrations, not AI art.
 */
class VideoStoryPipeline(context: Context) {
    private val appContext = context.applicationContext

    suspend fun render(
        prompt: String,
        onProgress: (completedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {}
    ): VideoStoryRenderResult {
        val plan = VideoStoryPlanBuilder.build(prompt)
        onStage("Yerel sahneler çiziliyor…")
        val silentVideo = Mp4VideoRenderer(appContext).render(plan.renderSpec, onProgress)
        var narratedVideo: NarratedVideoResult? = null
        var captionsFile: File? = null
        var exportedUri: Uri? = null
        var captionsUri: Uri? = null
        try {
            onStage("Türkçe seslendirme hazırlanıyor…")
            narratedVideo = VideoNarrationComposer(appContext).compose(plan, silentVideo)
            captionsFile = File(
                narratedVideo.video.file.parentFile,
                narratedVideo.video.file.nameWithoutExtension + ".srt"
            )
            onStage("Altyazılar ve MP4 doğrulanıyor…")
            withContext(Dispatchers.IO) {
                captionsFile.writeText(
                    VideoStoryPlan(
                        title = plan.title,
                        scenes = plan.scenes,
                        narrationText = plan.narrationText,
                        captions = narratedVideo.captions,
                        sourceLabel = plan.sourceLabel
                    ).toSrt(),
                    StandardCharsets.UTF_8
                )
                exportedUri = VideoMediaStoreExporter.export(appContext, narratedVideo.video.file)
                captionsUri = VideoMediaStoreExporter.exportCaptions(appContext, captionsFile)
            }
            onStage("Sesli MP4 galeriye kaydedildi.")
            silentVideo.file.delete()
            return VideoStoryRenderResult(
                video = narratedVideo.video,
                captionsFile = captionsFile,
                exportedUri = exportedUri ?: throw VideoRenderException("MP4 dışa aktarma URI'si alınamadı."),
                captionsUri = captionsUri ?: throw VideoRenderException("SRT dışa aktarma URI'si alınamadı."),
                audioDurationMs = narratedVideo.audioDurationMs,
                narrationText = plan.narrationText,
                sceneCount = plan.scenes.size,
                sourceLabel = plan.sourceLabel
            )
        } catch (cancelled: CancellationException) {
            silentVideo.file.delete()
            narratedVideo?.video?.file?.delete()
            captionsFile?.delete()
            exportedUri?.let { appContext.contentResolver.delete(it, null, null) }
            captionsUri?.let { appContext.contentResolver.delete(it, null, null) }
            throw cancelled
        } catch (error: Exception) {
            silentVideo.file.delete()
            narratedVideo?.video?.file?.delete()
            captionsFile?.delete()
            exportedUri?.let { appContext.contentResolver.delete(it, null, null) }
            captionsUri?.let { appContext.contentResolver.delete(it, null, null) }
            if (error is VideoRenderException) throw error
            throw VideoRenderException("Sesli video dosyası hazırlanamadı.", error)
        }
    }
}
