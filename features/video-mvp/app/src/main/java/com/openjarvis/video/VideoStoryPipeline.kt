package com.openjarvis.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import com.openjarvis.video.assets.FileVideoVisualAssetCache
import com.openjarvis.video.assets.VideoAssetAttribution
import com.openjarvis.video.assets.VideoVisualAssetResolver
import com.openjarvis.video.assets.Wikimedia1453AssetCatalog
import com.openjarvis.video.assets.WikimediaSinanAssetCatalog
import com.openjarvis.video.assets.WikimediaCommonsVisualAssetDownloader
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
    val sourceLabel: String,
    val thumbnailUri: Uri? = null,
    val thumbnailAltText: String = "",
    val backgroundMusicIncluded: Boolean = false,
    val audioDescription: String = "tr-TR TTS narration only; no background music",
    val hookDurationMs: Long = 0L,
    val narrationLocale: String = "tr-TR",
    val captionLocale: String = "tr-TR",
    val attributionManifest: List<VideoAssetAttribution> = emptyList(),
    val attributionManifestComplete: Boolean = false,
    val attributionCredits: String = "",
    val researchCredits: String = "",
    val claimSources: List<VideoClaimSource> = emptyList()
)

/**
 * Research-backed local workflow: verified topic pack -> licensed source images -> tr-TR TTS
 * hook and scene segments -> speech-timed vertical H.264 -> AAC mux -> SRT -> MediaStore.
 * Unsupported topics, missing licences, duplicate images and unavailable uncached assets fail closed.
 */
class VideoStoryPipeline(context: Context) {
    private val appContext = context.applicationContext

    suspend fun render(
        prompt: String,
        onProgress: (completedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {},
        artifactKey: String? = null
    ): VideoStoryRenderResult {
        require(artifactKey == null || artifactKey.matches(Regex("[a-f0-9]{32}"))) {
            "Video dışa aktarma kimliği geçersiz."
        }
        val storyPlan = VideoStoryPlanBuilder.build(prompt)
        VideoStoryQualityPolicy.validate(storyPlan)
        onStage("Kaynaklı görseller ve lisanslar doğrulanıyor…")

        val descriptors = storyPlan.scenes.map { scene ->
            val assetId = scene.visualAssetId
                ?: throw VideoRenderException("Sahne için doğrulanmış görsel kimliği eksik.")
            val descriptor = Wikimedia1453AssetCatalog.find(assetId)
                ?: WikimediaSinanAssetCatalog.find(assetId)
                ?: throw VideoRenderException("Bu sahne için lisanslı görsel kataloğu bulunamadı.")
            if (descriptor.sceneId != scene.sceneId) {
                throw VideoRenderException("Görsel ile storyboard sahnesinin kimliği eşleşmiyor.")
            }
            descriptor
        }
        val resolver = VideoVisualAssetResolver(
            cache = FileVideoVisualAssetCache(File(appContext.cacheDir, "jarvis-licensed-visuals")),
            downloader = WikimediaCommonsVisualAssetDownloader()
        )
        val resolvedAssets = withContext(Dispatchers.IO) {
            resolver.resolveAll(descriptors)
        }
        val resolvedPlan = storyPlan.copy(
            scenes = storyPlan.scenes.mapIndexed { index, scene ->
                scene.copy(visualAsset = resolvedAssets[index])
            }
        )
        resolvedPlan.renderSpec.validate()
        val attributionManifest = resolvedAssets.map { it.attribution }
        val attributionComplete = attributionManifest.size == resolvedPlan.scenes.size &&
            attributionManifest.map { it.assetId }.distinct().size == attributionManifest.size &&
            attributionManifest.all { it.isValid() }
        if (!attributionComplete) {
            throw VideoRenderException("Görsel atıf manifesti eksik; video dışa aktarılmadı.")
        }
        val attributionCredits = attributionManifest.joinToString("\n") { it.attributionText }
        val researchCredits = storyPlan.claimSources.joinToString("\n") { source ->
            source.publisher + " — " + source.supportedClaim + " — " + source.sourceUrl +
                (source.uncertaintyNote?.let { " Not: " + it } ?: "")
        }

        onStage("Türkçe anlatım hazırlanıyor…")
        var narratedVideo: NarratedVideoResult? = null
        var captionsFile: File? = null
        var exportedUri: Uri? = null
        var captionsUri: Uri? = null
        var thumbnailUri: Uri? = null
        try {
            val composed = VideoNarrationComposer(appContext).compose(resolvedPlan) { speechTimedSpec ->
                onStage("Kaynak görsellerle dikey sahneler çiziliyor…")
                Mp4VideoRenderer(appContext).render(speechTimedSpec, onProgress)
            }
            narratedVideo = composed
            val srtFile = File(
                composed.video.file.parentFile,
                composed.video.file.nameWithoutExtension + ".srt"
            )
            captionsFile = srtFile
            onStage("Zamanlı altyazılar ve MP4 dışa aktarma doğrulanıyor…")
            val mediaBaseName = artifactKey?.let { "jarvis-" + it } ?: composed.video.file.nameWithoutExtension
            val exportedUris = withContext(Dispatchers.IO) {
                srtFile.writeText(
                    resolvedPlan.copy(captions = composed.captions).toSrt(),
                    StandardCharsets.UTF_8
                )
                val videoUri = VideoMediaStoreExporter.export(
                    appContext,
                    composed.video.file,
                    mediaBaseName + ".mp4"
                )
                exportedUri = videoUri
                val subtitleUri = VideoMediaStoreExporter.exportCaptions(
                    appContext,
                    srtFile,
                    mediaBaseName + ".srt"
                )
                captionsUri = subtitleUri
                videoUri to subtitleUri
            }
            exportedUri = exportedUris.first
            captionsUri = exportedUris.second
            verifyExportedVideo(exportedUris.first)
            verifyExportedCaptions(exportedUris.second, resolvedPlan.hookText)
            onStage("Dikey kapak görseli hazırlanıyor…")
            val exportedThumbnail = withContext(Dispatchers.IO) {
                exportThumbnail(composed.video.file, mediaBaseName + "-thumb.jpg")
            }
            thumbnailUri = exportedThumbnail
            verifyExportedThumbnail(exportedThumbnail)
            val firstScene = resolvedPlan.scenes.first()
            val firstAttribution = attributionManifest.first()
            val thumbnailAltText = "Kapak görseli: " + firstScene.heading + " " +
                firstScene.caption + " Görsel: " + firstAttribution.creator + ", " +
                firstAttribution.licenseId + " " + firstAttribution.licenseVersion + "."
            onStage("Kaynaklı Türkçe MP4, altyazı ve kapak görseli galeriye kaydedildi.")
            return VideoStoryRenderResult(
                video = composed.video,
                captionsFile = srtFile,
                exportedUri = exportedUris.first,
                captionsUri = exportedUris.second,
                audioDurationMs = composed.audioDurationMs,
                narrationText = resolvedPlan.narrationText,
                sceneCount = resolvedPlan.scenes.size,
                sourceLabel = resolvedPlan.sourceLabel,
                thumbnailUri = exportedThumbnail,
                thumbnailAltText = thumbnailAltText,
                backgroundMusicIncluded = false,
                audioDescription = "tr-TR sistem TTS anlatımı; arka plan müziği eklenmedi.",
                hookDurationMs = composed.hookDurationMs,
                narrationLocale = "tr-TR",
                captionLocale = "tr-TR",
                attributionManifest = attributionManifest,
                attributionManifestComplete = attributionComplete,
                attributionCredits = attributionCredits,
                researchCredits = researchCredits,
                claimSources = resolvedPlan.claimSources
            )
        } catch (cancelled: CancellationException) {
            narratedVideo?.video?.file?.delete()
            captionsFile?.delete()
            exportedUri?.let { appContext.contentResolver.delete(it, null, null) }
            captionsUri?.let { appContext.contentResolver.delete(it, null, null) }
            thumbnailUri?.let { appContext.contentResolver.delete(it, null, null) }
            throw cancelled
        } catch (error: Exception) {
            narratedVideo?.video?.file?.delete()
            captionsFile?.delete()
            exportedUri?.let { appContext.contentResolver.delete(it, null, null) }
            captionsUri?.let { appContext.contentResolver.delete(it, null, null) }
            thumbnailUri?.let { appContext.contentResolver.delete(it, null, null) }
            if (error is VideoRenderException) throw error
            throw VideoRenderException("Kaynaklı sesli video dosyası hazırlanamadı.", error)
        }
    }


    private fun exportThumbnail(videoFile: File, displayName: String): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw VideoRenderException("Kapak görseli için Android 10 veya üzeri gerekli.")
        }
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        var uri: Uri? = null
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val frameBitmap = retriever.getFrameAtTime(
                500_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: throw VideoRenderException("MP4 içinden kapak karesi alınamadı.")
            bitmap = frameBitmap
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Open JARVIS"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val insertedUri = appContext.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw VideoRenderException("Kapak görseli MediaStore'a eklenemedi.")
            uri = insertedUri
            appContext.contentResolver.openOutputStream(insertedUri, "w")?.use { output ->
                if (!frameBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw VideoRenderException("Kapak görseli JPEG olarak kodlanamadı.")
                }
            } ?: throw VideoRenderException("Kapak görseli yazma akışı açılamadı.")
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (appContext.contentResolver.update(insertedUri, publishValues, null, null) != 1) {
                throw VideoRenderException("Kapak görseli MediaStore'da yayımlanamadı.")
            }
            return insertedUri
        } catch (error: Exception) {
            uri?.let { appContext.contentResolver.delete(it, null, null) }
            throw error
        } finally {
            bitmap?.recycle()
            runCatching { retriever.release() }
        }
    }

    private fun verifyExportedThumbnail(uri: Uri) {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            throw VideoRenderException("Kapak görseli URI'si geçerli bir MediaStore content URI değil.")
        }
        val header = appContext.contentResolver.openInputStream(uri)?.use { input ->
            ByteArray(3).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset < 3) throw VideoRenderException("Kapak görseli okunamadı.")
            }
        } ?: throw VideoRenderException("Kapak görseli açılamıyor.")
        if (header[0] != 0xff.toByte() || header[1] != 0xd8.toByte() || header[2] != 0xff.toByte()) {
            throw VideoRenderException("Kapak görseli geçerli JPEG değil.")
        }
    }

    private fun verifyExportedVideo(uri: Uri) {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            throw VideoRenderException("MP4 dışa aktarma URI'si geçerli bir MediaStore content URI değil.")
        }
        val header = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(16)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                offset += count
            }
            if (offset < 12) throw VideoRenderException("Dışa aktarılan MP4 başlığı okunamadı.")
            bytes
        } ?: throw VideoRenderException("Dışa aktarılan MP4 açılamıyor.")
        if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") {
            throw VideoRenderException("Dışa aktarılan dosya geçerli MP4 kapsayıcısı değil.")
        }
    }

    private fun verifyExportedCaptions(uri: Uri, expectedHook: String) {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            throw VideoRenderException("Altyazı dışa aktarma URI'si geçerli bir MediaStore content URI değil.")
        }
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_CAPTION_EXPORT_BYTES) {
                    throw VideoRenderException("Altyazı dosyası beklenenden büyük.")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw VideoRenderException("Dışa aktarılan altyazı dosyası açılamıyor.")
        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.contains("-->") || !text.contains(expectedHook)) {
            throw VideoRenderException("Altyazı zaman kodu veya ilk iki saniyelik kanca dışa aktarımda bulunamadı.")
        }
    }

    companion object {
        private const val MAX_CAPTION_EXPORT_BYTES = 512 * 1024
    }
}
