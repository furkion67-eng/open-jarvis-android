package com.openjarvis.video

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

data class VideoStoryRenderResult(
    val video: VideoRenderResult,
    val captionsFile: File,
    val narrationText: String,
    val sceneCount: Int,
    val sourceLabel: String
)

/**
 * Offline first-stage pipeline: prompt -> storyboard -> silent vertical MP4 + timed SRT.
 * narrationText is ready for a future tr-TR TTS stage; it is not represented as an audio track.
 */
class VideoStoryPipeline(context: Context) {
    private val appContext = context.applicationContext

    suspend fun render(
        prompt: String,
        onProgress: (completedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> }
    ): VideoStoryRenderResult {
        val plan = VideoStoryPlanBuilder.build(prompt)
        val rendered = Mp4VideoRenderer(appContext).render(plan.renderSpec, onProgress)
        val captions = File(
            rendered.file.parentFile,
            rendered.file.nameWithoutExtension + ".srt"
        )

        try {
            captions.writeText(plan.toSrt(), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            rendered.file.delete()
            captions.delete()
            throw VideoRenderException("Zamanlı altyazı dosyası kaydedilemedi.", error)
        }

        return VideoStoryRenderResult(
            video = rendered,
            captionsFile = captions,
            narrationText = plan.narrationText,
            sceneCount = plan.scenes.size,
            sourceLabel = plan.sourceLabel
        )
    }
}
