package com.openjarvis.tasks

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
                !videos.single().isPending &&
                !captions.single().isPending &&
                isReadableVideoUri(videos.single().uri) &&
                isReadableCaptionsUri(captions.single().uri)
            ) {
                videos.single().uri to captions.single().uri
            } else {
                deleteExisting((videos + captions).map { it.uri })
                null
            }
        }
        if (existing != null) {
            return verifiedResult(
                artifactId = task.id,
                videoUri = existing.first,
                captionsUri = existing.second,
                summary = "Aynı görev kimliğiyle daha önce dışa aktarılmış Türkçe sesli video yeniden kullanıldı.",
                metadata = readMetadata(existing.first)
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
                artifactId = task.id,
                videoUri = rendered.exportedUri,
                captionsUri = rendered.captionsUri,
                summary = "Türkçe sesli MP4 hazır: " + (rendered.video.durationMs / 1_000L) +
                    " sn, " + rendered.sceneCount + " sahne. Görseller: " + rendered.sourceLabel,
                metadata = VideoArtifactMetadata(
                    durationMs = rendered.video.durationMs,
                    width = rendered.video.width,
                    height = rendered.video.height,
                    frameRate = 24,
                    hasAudio = true,
                    narrationLocale = "tr-TR",
                    sceneCount = rendered.sceneCount,
                    sourceLabel = rendered.sourceLabel,
                    attributionManifest = emptyList(),
                    attributionManifestComplete = false
                )
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

    private fun verifiedResult(
        artifactId: String,
        videoUri: Uri,
        captionsUri: Uri,
        summary: String,
        metadata: VideoArtifactMetadata
    ): JarvisTaskExecutionResult = JarvisTaskExecutionResult(
        outputSummary = summary + "\nMP4: " + videoUri + "\nSRT: " + captionsUri,
        evidenceReference = videoUri.toString(),
        verified = true,
        artifactReferences = listOf(videoUri.toString(), captionsUri.toString()),
        videoArtifact = PersistedVideoArtifact(
            artifactId = artifactId,
            exportedUri = videoUri.toString(),
            captionsUri = captionsUri.toString(),
            metadata = metadata
        )
    )

    private fun readMetadata(uri: Uri): VideoArtifactMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Önceki MP4 süresi okunamadı.")
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: throw IllegalStateException("Önceki MP4 genişliği okunamadı.")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: throw IllegalStateException("Önceki MP4 yüksekliği okunamadı.")
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!hasAudio) throw IllegalStateException("Önceki MP4 AAC ses izi doğrulanamadı.")
            return VideoArtifactMetadata(
                durationMs = duration,
                width = width,
                height = height,
                frameRate = 24,
                hasAudio = true,
                narrationLocale = "tr-TR",
                sourceLabel = "Aynı görev kimliğiyle yeniden kullanılan doğrulanmış MediaStore çıktısı",
                attributionManifest = emptyList(),
                attributionManifestComplete = false
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class MediaStoreArtifactRow(val uri: Uri, val isPending: Boolean)

    private fun findByDisplayName(collection: Uri, displayName: String): List<MediaStoreArtifactRow> {
        val matches = mutableListOf<MediaStoreArtifactRow>()
        val cursor = appContext.contentResolver.query(
            collection,
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.IS_PENDING),
            MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        ) ?: throw IllegalStateException("Önceki video çıktısı MediaStore üzerinden kontrol edilemedi.")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(BaseColumns._ID)
            val pendingColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            while (it.moveToNext()) {
                matches += MediaStoreArtifactRow(
                    uri = ContentUris.withAppendedId(collection, it.getLong(idColumn)),
                    isPending = it.getInt(pendingColumn) != 0
                )
            }
        }
        return matches
    }

    private fun deleteExisting(uris: List<Uri>) {
        uris.forEach { uri ->
            val deleted = appContext.contentResolver.delete(uri, null, null)
            if (deleted <= 0) {
                throw IllegalStateException("Önceki video çıktısı temizlenemedi; yinelenen dosya oluşturulmadı.")
            }
        }
    }

    private fun isReadableVideoUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        return hasTrackSample(uri, MediaFormat.MIMETYPE_VIDEO_AVC) &&
            hasTrackSample(uri, MediaFormat.MIMETYPE_AUDIO_AAC)
    }

    private fun hasTrackSample(uri: Uri, mimeType: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == mimeType
            } ?: return false
            extractor.selectTrack(trackIndex)
            extractor.readSampleData(ByteBuffer.allocate(2 * 1024 * 1024), 0) > 0
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
