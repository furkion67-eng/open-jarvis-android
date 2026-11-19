package com.openjarvis.tasks

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import com.openjarvis.video.VideoCommandOrigin
import com.openjarvis.video.VideoCommandRouter
import com.openjarvis.video.VideoStoryPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

const val VIDEO_TASK_EXECUTION_MODE = "local.video-render"

/** A non-reversible, stable MediaStore filename token derived from the durable task request key. */
internal object VideoTaskArtifactKey {
    fun from(idempotencyKey: String): String {
        require(idempotencyKey.isNotBlank()) { "Görev isteği kimliği geçersiz." }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(idempotencyKey.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
        }
    }
}

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
            summary = "“" + topic + "” için plan taslağı hazır. Henüz video oluşturulmadı. Planı kabul ettiğinde cihazda Türkçe sesli MP4 ve SRT üretilecek; otomatik paylaşım yapılmayacak.",
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
 * Local-only executor. The task's stable idempotency key determines both MediaStore names.
 * If the process died after export but before Room persisted success, a user retry reuses the
 * already verified pair rather than creating a second copy.
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
        val topic = VideoCommandRouter.parse(task.goal, VideoCommandOrigin.TASK)?.topic
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Video konusu bulunamadı.")
        val artifactKey = VideoTaskArtifactKey.from(idempotencyKey)
        val videoName = "jarvis-" + artifactKey + ".mp4"
        val captionsName = "jarvis-" + artifactKey + ".srt"

        val existing = withContext(Dispatchers.IO) {
            val videos = findByDisplayName(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoName)
            val captions = findByDisplayName(MediaStore.Downloads.EXTERNAL_CONTENT_URI, captionsName)
            if (videos.size == 1 && captions.size == 1 &&
                isReadableVideoUri(videos.single()) &&
                isReadableCaptionsUri(captions.single())
            ) {
                videos.single() to captions.single()
            } else {
                (videos + captions).forEach { appContext.contentResolver.delete(it, null, null) }
                null
            }
        }
        if (existing != null) {
            return verifiedResult(
                videoUri = existing.first,
                captionsUri = existing.second,
                summary = "Aynı görev kimliğiyle daha önce dışa aktarılmış Türkçe sesli video yeniden kullanıldı."
            )
        }

        val rendered = VideoStoryPipeline(appContext).render(topic, artifactKey = artifactKey)
        var keepExports = false
        try {
            val bothReadable = withContext(Dispatchers.IO) {
                isReadableVideoUri(rendered.exportedUri) &&
                    isReadableCaptionsUri(rendered.captionsUri)
            }
            if (!bothReadable) {
                throw IllegalStateException("MP4 veya SRT dışa aktarma dosyası doğrulanamadı; görev tamamlanmadı.")
            }

            keepExports = true
            return verifiedResult(
                videoUri = rendered.exportedUri,
                captionsUri = rendered.captionsUri,
                summary = "Türkçe sesli MP4 hazır: " + (rendered.video.durationMs / 1_000L) +
                    " sn, " + rendered.sceneCount + " sahne. Görseller: " + rendered.sourceLabel
            )
        } finally {
            rendered.video.file.delete()
            rendered.captionsFile.delete()
            if (!keepExports) {
                runCatching { appContext.contentResolver.delete(rendered.exportedUri, null, null) }
                runCatching { appContext.contentResolver.delete(rendered.captionsUri, null, null) }
            }
        }
    }

    private fun verifiedResult(videoUri: Uri, captionsUri: Uri, summary: String): JarvisTaskExecutionResult =
        JarvisTaskExecutionResult(
            outputSummary = summary + "\nMP4: " + videoUri + "\nSRT: " + captionsUri,
            evidenceReference = videoUri.toString(),
            verified = true
        )

    private fun findByDisplayName(collection: Uri, displayName: String): List<Uri> {
        val matches = mutableListOf<Uri>()
        val cursor = appContext.contentResolver.query(
            collection,
            arrayOf(BaseColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        ) ?: throw IllegalStateException("Önceki video çıktısı MediaStore üzerinden kontrol edilemedi.")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(BaseColumns._ID)
            while (it.moveToNext()) {
                matches += ContentUris.withAppendedId(collection, it.getLong(idColumn))
            }
        }
        return matches
    }

    private fun isReadableVideoUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, uri, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val videoIndex = formats.indexOfFirst {
                it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC
            }
            val audioIndex = formats.indexOfFirst {
                it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            }
            if (videoIndex < 0 || audioIndex < 0) {
                false
            } else {
                extractor.selectTrack(audioIndex)
                extractor.readSampleData(ByteBuffer.allocate(256 * 1024), 0) > 0
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun isReadableCaptionsUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        return appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            val contents = it.readText()
            contents.isNotBlank() && contents.contains("-->")
        } == true
    }
}
