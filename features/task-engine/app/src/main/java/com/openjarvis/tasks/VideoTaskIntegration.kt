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
import com.openjarvis.video.VideoStoryPlanBuilder
import kotlinx.coroutines.CancellationException
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

        // Validate the same researched story pack the renderer will execute before the user
        // accepts the plan. Unsupported topics fail here, not after an apparently valid preview.
        val researchedPlan = VideoStoryPlanBuilder.build(topic)

        return JarvisTaskPlan(
            summary = "“" + researchedPlan.title + "” için " + researchedPlan.scenes.size +
                " sahneli kaynaklı plan hazır. Video henüz oluşturulmadı. Görsel dosyaları ve lisans kanıtları render sırasında doğrulanacak; otomatik paylaşım yapılmayacak.",
            steps = listOf(
                "Desteklenen araştırma paketini, sahne iddialarını ve kaynak eşleşmelerini doğrula.",
                "Her sahne için Wikimedia Commons görselini, lisansını ve SHA-256 bütünlüğünü doğrula; geçerli önbellek yoksa internetten al.",
                "Kurulu tam tr-TR sesini doğrula; gerçek Türkçe anlatımı ve konuşma sürelerini ölç.",
                "Kaynaklı görsellerle dikey H.264 video, AAC ses ve konuşma zamanlı SRT üret.",
                "MediaStore MP4, SRT ve kapak URI'lerini okuyarak A/V, altyazı ve atıf manifestini doğrula; tüm kanıtlar geçerse başarı kaydet."
            ),
            // The renderer currently has no side-effect-free cache availability query. Be
            // conservative: an uncached first render downloads Commons assets over HTTPS.
            requiresNetwork = true,
            requiresApproval = false
        )
    }
}

/**
 * Local-only executor. The task's stable idempotency key determines both MediaStore names.
 * If the process died after export but before Room persisted success, the uncommitted MediaStore
 * rows are deleted and the render runs again; an unpersisted file is never reused as proof.
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

        val thumbnailName = "jarvis-" + artifactKey + "-thumb.jpg"
        withContext(Dispatchers.IO) {
            val staleArtifacts =
                findByDisplayName(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoName) +
                    findByDisplayName(MediaStore.Downloads.EXTERNAL_CONTENT_URI, captionsName) +
                    findByDisplayName(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, thumbnailName)
            if (staleArtifacts.isNotEmpty()) {
                // If Room did not commit renderer metadata before process death, do not reuse
                // the files as a successful task. Re-render to recover the complete proof.
                deleteExisting(staleArtifacts.map { it.uri })
            }
        }

        val rendered = VideoStoryPipeline(appContext).render(topic, artifactKey = artifactKey)
        var keepExports = false
        var thumbnailUri: Uri? = null
        try {
            val videoAndCaptionsReadable = withContext(Dispatchers.IO) {
                isReadableVideoUri(rendered.exportedUri) &&
                    isReadableCaptionsUri(rendered.captionsUri)
            }
            if (!videoAndCaptionsReadable) {
                throw IllegalStateException("MP4 veya SRT dışa aktarma dosyası doğrulanamadı; görev tamamlanmadı.")
            }

            thumbnailUri = VideoRendererMetadataAdapter.thumbnailUri(rendered)
                ?: throw IllegalStateException("Renderer doğrulanmış kapak URI'si sağlamadı.")
            val verifiedMetadata = VideoRendererMetadataAdapter.fromRenderResult(
                renderResult = rendered,
                durationMs = rendered.video.durationMs,
                audioDurationMs = rendered.audioDurationMs,
                width = rendered.video.width,
                height = rendered.video.height,
                frameRate = 24,
                hasAudio = true,
                sceneCount = rendered.sceneCount,
                sourceLabel = rendered.sourceLabel
            )
            if (verifiedMetadata.thumbnailUri != thumbnailUri.toString()) {
                throw IllegalStateException("Kapak URI'si renderer metadata kaydıyla eşleşmiyor.")
            }
            val thumbnailReadable = withContext(Dispatchers.IO) {
                isReadableThumbnailUri(thumbnailUri!!)
            }
            if (!thumbnailReadable) {
                throw IllegalStateException("Kapak görseli MediaStore üzerinden okunamadı.")
            }

            val artifact = PersistedVideoArtifact(
                artifactId = task.id,
                exportedUri = rendered.exportedUri.toString(),
                captionsUri = rendered.captionsUri.toString(),
                metadata = verifiedMetadata
            )
            if (!artifact.isValid() || !verifiedMetadata.attributionManifestComplete) {
                throw IllegalStateException("Video atıf/metadata kanıtı tamamlanmadı; görev başarılı sayılamaz.")
            }

            val result = verifiedResult(
                artifactId = task.id,
                videoUri = rendered.exportedUri,
                captionsUri = rendered.captionsUri,
                summary = "Kaynakları ve lisansları doğrulanmış Türkçe MP4 hazır: " +
                    (rendered.video.durationMs / 1_000L) + " sn, " + rendered.sceneCount +
                    " sahne. " + verifiedMetadata.attributionManifest.size +
                    " görsel atfı ve " + verifiedMetadata.claimSources.size + " araştırma kaynağı kaydedildi.",
                metadata = verifiedMetadata
            )
            keepExports = true
            return result
        } finally {
            rendered.video.file.delete()
            rendered.captionsFile.delete()
            if (!keepExports) {
                runCatching { appContext.contentResolver.delete(rendered.exportedUri, null, null) }
                runCatching { appContext.contentResolver.delete(rendered.captionsUri, null, null) }
                thumbnailUri?.let { uri ->
                    runCatching { appContext.contentResolver.delete(uri, null, null) }
                }
            }
        }
    }

    private fun verifiedResult(
        artifactId: String,
        videoUri: Uri,
        captionsUri: Uri,
        summary: String,
        metadata: VideoArtifactMetadata
    ): JarvisTaskExecutionResult {
        require(metadata.attributionManifestComplete && metadata.isValid()) {
            "Video atıf manifesti doğrulanmadan görev başarılı olamaz."
        }
        val artifact = PersistedVideoArtifact(
            artifactId = artifactId,
            exportedUri = videoUri.toString(),
            captionsUri = captionsUri.toString(),
            metadata = metadata
        )
        require(artifact.isValid()) { "Kalıcı video artifact kanıtı geçersiz." }
        return JarvisTaskExecutionResult(
            outputSummary = summary + "\nMP4: " + videoUri + "\nSRT: " + captionsUri +
                "\nKapak: " + metadata.thumbnailUri +
                "\nGörsel atıfları: " + metadata.attributionManifest.size +
                "\nAraştırma kaynakları: " + metadata.claimSources.size,
            evidenceReference = videoUri.toString(),
            verified = true,
            artifactReferences = listOfNotNull(videoUri.toString(), captionsUri.toString(), metadata.thumbnailUri),
            videoArtifact = artifact
        )
    }

    private fun isReadableThumbnailUri(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return false
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(3)
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) break
                    offset += count
                }
                offset == 3 &&
                    (header[0].toInt() and 0xff) == 0xff &&
                    (header[1].toInt() and 0xff) == 0xd8 &&
                    (header[2].toInt() and 0xff) == 0xff
            } == true
        } catch (_: Exception) {
            false
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
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return false
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                VideoArtifactCaptionsPolicy.readValidated(input) != null
            } == true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}
