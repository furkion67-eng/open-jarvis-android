package com.openjarvis.tasks

import android.content.Context
import android.net.Uri
import com.openjarvis.video.VideoCommandOrigin
import com.openjarvis.video.VideoCommandRouter
import com.openjarvis.video.VideoStoryPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val VIDEO_TASK_EXECUTION_MODE = "local.video-render"

/** Builds a preview only; the render executor is entered only after explicit plan acceptance. */
class VideoStoryTaskCapability : JarvisTaskCapability {
    override val id: String = VIDEO_TASK_EXECUTION_MODE

    override fun supports(goal: String): Boolean =
        VideoCommandRouter.parse(goal, VideoCommandOrigin.TASK)?.topic?.isNotBlank() == true

    override suspend fun buildPlan(goal: String): JarvisTaskPlan {
        val topic = VideoCommandRouter.parse(goal, VideoCommandOrigin.TASK)?.topic
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Video konusu belirtilmedi.")

        return JarvisTaskPlan(
            summary = "“$topic” için plan taslağı hazır. Henüz video oluşturulmadı. Planı kabul ettiğinde cihazda Türkçe sesli MP4 ve SRT üretilecek; otomatik paylaşım yapılmayacak.",
            steps = listOf(
                "Kurulu tam tr-TR sesini doğrula; uygun ses yoksa Türkçe hata ile dur.",
                "Beş sahneli yerel prosedürel storyboard hazırla.",
                "Her sahneyi Türkçe seslendir ve gerçek konuşma sürelerini ölç.",
                "Dikey H.264 video, AAC ses ve konuşma zamanlı SRT üret.",
                "MP4 ve SRT'yi MediaStore'a aktar; iki URI de okunabiliyorsa sonucu kaydet."
            ),
            requiresNetwork = false,
            requiresApproval = false
        )
    }
}

/**
 * Local-only executor. It returns verified success only after both exported MediaStore URIs
 * are readable. It does not perform any social upload or other external side effect.
 */
class VideoStoryTaskExecutor(context: Context) : JarvisTaskExecutor {
    private val appContext = context.applicationContext

    override val capabilityId: String = VIDEO_TASK_EXECUTION_MODE
    override val executionMode: String = VIDEO_TASK_EXECUTION_MODE
    override val executionLabel: String = "Türkçe video üretimi"

    override suspend fun execute(
        task: JarvisTaskEntity,
        idempotencyKey: String
    ): JarvisTaskExecutionResult {
        require(idempotencyKey.isNotBlank()) { "Görev isteği kimliği geçersiz." }
        val topic = VideoCommandRouter.parse(task.goal, VideoCommandOrigin.TASK)?.topic
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Video konusu bulunamadı.")

        val rendered = VideoStoryPipeline(appContext).render(topic)
        var keepExports = false
        try {
            val bothReadable = withContext(Dispatchers.IO) {
                isReadableContentUri(rendered.exportedUri) &&
                    isReadableContentUri(rendered.captionsUri)
            }
            if (!bothReadable) {
                throw IllegalStateException("MP4 veya SRT dışa aktarma dosyası okunamadı; görev tamamlanmadı.")
            }

            keepExports = true
            return JarvisTaskExecutionResult(
                outputSummary = buildString {
                    append("Türkçe sesli MP4 hazır: ")
                    append(rendered.video.durationMs / 1_000L)
                    append(" sn, ")
                    append(rendered.sceneCount)
                    append(" sahne.\nMP4: ")
                    append(rendered.exportedUri)
                    append("\nSRT: ")
                    append(rendered.captionsUri)
                    append("\nGörseller: ")
                    append(rendered.sourceLabel)
                },
                evidenceReference = rendered.exportedUri.toString(),
                verified = true
            )
        } finally {
            // The public MediaStore copies are the durable artifacts; remove app-cache intermediates.
            rendered.video.file.delete()
            rendered.captionsFile.delete()
            if (!keepExports) {
                appContext.contentResolver.delete(rendered.exportedUri, null, null)
                appContext.contentResolver.delete(rendered.captionsUri, null, null)
            }
        }
    }

    private fun isReadableContentUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        return appContext.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
    }
}
