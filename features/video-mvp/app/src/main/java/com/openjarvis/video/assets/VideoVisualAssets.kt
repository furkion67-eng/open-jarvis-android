package com.openjarvis.video.assets

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

data class VideoVisualAssetDescriptor(
    val assetId: String,
    val sceneId: String,
    val title: String,
    val downloadUrl: String,
    val sourceUrl: String,
    val creator: String,
    val licenseId: String,
    val licenseVersion: String,
    val licenseUrl: String,
    val attributionText: String,
    val expectedMimeType: String,
    val maxBytes: Long = 4_500_000L,
    val commercialUseAllowed: Boolean = true,
    val derivativesAllowed: Boolean = true,
    val shareAlikeRequired: Boolean = false,
    val requiredOutputLicense: String? = null
)

data class VideoAssetAttribution(
    val sceneIndex: Int,
    val sceneId: String,
    val assetId: String,
    val sha256: String,
    val sourceUrl: String,
    val creator: String,
    val licenseId: String,
    val licenseVersion: String,
    val licenseUrl: String,
    val attributionText: String,
    val retrievedAtEpochMs: Long,
    val cacheKey: String,
    val cacheMetadata: String,
    val commercialUseAllowed: Boolean,
    val derivativesAllowed: Boolean,
    val shareAlikeRequired: Boolean,
    val requiredOutputLicense: String?
) {
    fun isValid(): Boolean {
        if (sceneIndex < 0 || sceneId.isBlank() || assetId.isBlank() ||
            !sha256.matches(Regex("[a-f0-9]{64}")) ||
            !VideoAssetLicensePolicy.isPinnedCommonsSource(sourceUrl) ||
            creator.isBlank() || licenseId.isBlank() || licenseVersion.isBlank() ||
            attributionText.isBlank() ||
            !attributionText.contains(creator) ||
            !attributionText.contains(licenseId) ||
            !attributionText.contains("$licenseId $licenseVersion") ||
            !attributionText.contains(licenseUrl) ||
            !attributionText.contains(sourceUrl) ||
            retrievedAtEpochMs <= 0L ||
            !cacheKey.matches(Regex("[a-f0-9]{64}")) ||
            !commercialUseAllowed || !derivativesAllowed ||
            !VideoAssetLicensePolicy.isAllowedAttribution(
                licenseId,
                licenseVersion,
                licenseUrl,
                shareAlikeRequired,
                requiredOutputLicense
            )
        ) return false
        return VideoAssetLicensePolicy.isValidCacheMetadata(cacheMetadata, retrievedAtEpochMs)
    }
}

data class ResolvedSceneVisual(
    val file: File,
    val attribution: VideoAssetAttribution
)

data class DownloadedVisualAsset(
    val bytes: ByteArray,
    val mimeType: String,
    val finalUrl: String
)

data class CachedVisualAsset(
    val file: File,
    val sha256: String,
    val mimeType: String,
    val retrievedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val byteSize: Long
)

interface VideoVisualAssetDownloader {
    fun download(descriptor: VideoVisualAssetDescriptor): DownloadedVisualAsset
}

interface VideoVisualAssetCache {
    fun read(cacheKey: String, nowEpochMs: Long): CachedVisualAsset?
    fun write(
        cacheKey: String,
        bytes: ByteArray,
        mimeType: String,
        contentSha256: String,
        retrievedAtEpochMs: Long,
        expiresAtEpochMs: Long
    )
    fun cleanup(nowEpochMs: Long)
}

object VideoAssetLicensePolicy {
    private data class Rule(
        val version: String,
        val url: String,
        val shareAlike: Boolean,
        val outputLicense: String?
    )

    private val rules = mapOf(
        "Public Domain Mark" to Rule(
            "1.0",
            "https://creativecommons.org/publicdomain/mark/1.0/",
            false,
            null
        ),
        "CC0" to Rule(
            "1.0",
            "https://creativecommons.org/publicdomain/zero/1.0/",
            false,
            null
        ),
        "CC BY" to Rule(
            "4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            false,
            null
        ),
        "CC BY-SA" to Rule(
            "4.0",
            "https://creativecommons.org/licenses/by-sa/4.0/",
            true,
            "CC BY-SA 4.0"
        )
    )

    fun isAllowedAttribution(
        licenseId: String,
        licenseVersion: String,
        licenseUrl: String,
        shareAlikeRequired: Boolean,
        requiredOutputLicense: String?
    ): Boolean {
        val rule = rules[licenseId] ?: return false
        return rule.version == licenseVersion &&
            rule.url == licenseUrl &&
            rule.shareAlike == shareAlikeRequired &&
            rule.outputLicense == requiredOutputLicense
    }

    fun validate(descriptor: VideoVisualAssetDescriptor) {
        require(descriptor.assetId.matches(Regex("[a-z0-9][a-z0-9-]{4,79}"))) {
            "Görsel varlık kimliği geçersiz."
        }
        require(descriptor.sceneId.matches(Regex("[a-z0-9][a-z0-9-]{2,79}"))) {
            "Sahne kimliği geçersiz."
        }
        require(descriptor.title.isNotBlank() && descriptor.creator.isNotBlank()) {
            "Görsel başlığı veya üreticisi doğrulanamadı."
        }
        require(isPinnedCommonsSource(descriptor.sourceUrl)) {
            "Görselin HTTPS Wikimedia dosya sayfası ve sayısal sabit revizyon kimliği doğrulanamadı."
        }
        require(isAllowedDownloadUrl(descriptor.downloadUrl)) {
            "Görsel indirme adresi güvenilir HTTPS Wikimedia dosya yolunda değil."
        }
        require(descriptor.expectedMimeType in ALLOWED_IMAGE_MIME_TYPES) {
            "Görsel biçimi desteklenmiyor."
        }
        require(descriptor.maxBytes in 1_024L..5_000_000L) {
            "Görsel boyut sınırı geçersiz."
        }

        val rule = rules[descriptor.licenseId]
            ?: throw IllegalArgumentException("Lisans eksik, belirsiz veya ticari kullanım için uygun değil.")
        require(descriptor.licenseVersion == rule.version && descriptor.licenseUrl == rule.url) {
            "Lisans sürümü veya lisans URL'si doğrulanamadı."
        }
        require(descriptor.commercialUseAllowed && descriptor.derivativesAllowed) {
            "Ticari kullanım veya uyarlama izni doğrulanamadı."
        }
        require(descriptor.shareAlikeRequired == rule.shareAlike &&
            descriptor.requiredOutputLicense == rule.outputLicense) {
            "ShareAlike yükümlülüğü eksik veya tutarsız."
        }
        require(descriptor.attributionText.contains(descriptor.creator) &&
            descriptor.attributionText.contains("${descriptor.licenseId} ${descriptor.licenseVersion}") &&
            descriptor.attributionText.contains(descriptor.licenseUrl) &&
            descriptor.attributionText.contains(descriptor.sourceUrl)) {
            "Atıf metni üretici, lisans ve kaynak bağlantısını birlikte içermeli."
        }
    }

    internal fun isPinnedCommonsSource(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != "commons.wikimedia.org" ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) ||
            uri.path != "/w/index.php" || uri.rawFragment != null
        ) return false
        val parameters = uri.rawQuery?.split("&") ?: return false
        val titles = parameters.filter { it.substringBefore('=') == "title" }
        val revisions = parameters.filter { it.substringBefore('=') == "oldid" }
        return titles.size == 1 &&
            titles.single().substringAfter('=', "").startsWith("File:") &&
            revisions.size == 1 &&
            revisions.single().substringAfter('=', "").matches(Regex("[1-9][0-9]{0,11}"))
    }

    internal fun isAllowedDownloadUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host in ALLOWED_DOWNLOAD_HOSTS &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.path.startsWith("/wikipedia/commons/") &&
            uri.rawFragment == null
    }

    internal fun isValidCacheMetadata(value: String, retrievedAtEpochMs: Long): Boolean {
        val entries = value.split(';')
        if (entries.size != 4) return false
        val fields = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) return false
            val key = entry.substring(0, separator)
            if (fields.put(key, entry.substring(separator + 1)) != null) return false
        }
        val byteSize = fields["bytes"]?.toLongOrNull() ?: return false
        val expiresAt = fields["expiresAt"]?.toLongOrNull() ?: return false
        return fields.keys == setOf("provider", "mime", "bytes", "expiresAt") &&
            fields["provider"] == "WikimediaCommons" &&
            fields["mime"]?.let { it in ALLOWED_IMAGE_MIME_TYPES } == true &&
            byteSize in 1L..5_000_000L &&
            retrievedAtEpochMs > 0L &&
            expiresAt > retrievedAtEpochMs &&
            expiresAt - retrievedAtEpochMs <= MAX_CACHE_TTL_MS
    }

    internal const val MAX_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
    internal val ALLOWED_DOWNLOAD_HOSTS = setOf("upload.wikimedia.org", "thumb.wikimedia.org")
    internal val ALLOWED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png")
}

class VideoVisualAssetResolver(
    private val cache: VideoVisualAssetCache,
    private val downloader: VideoVisualAssetDownloader,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
) {
    init {
        require(cacheTtlMs in 1L..VideoAssetLicensePolicy.MAX_CACHE_TTL_MS) {
            "Görsel önbellek süresi 30 günü aşamaz."
        }
    }

    fun resolveAll(descriptors: List<VideoVisualAssetDescriptor>): List<ResolvedSceneVisual> {
        require(descriptors.isNotEmpty()) { "Storyboard için lisanslı görsel bulunamadı." }
        require(descriptors.map { it.sceneId }.distinct().size == descriptors.size) {
            "Her sahne için farklı bir görsel kimliği gerekli."
        }
        require(descriptors.map { it.assetId }.distinct().size == descriptors.size) {
            "Aynı görsel birden fazla sahnede kullanılamaz."
        }
        descriptors.forEach(VideoAssetLicensePolicy::validate)
        val now = clock()
        cache.cleanup(now)
        val resolved = descriptors.mapIndexed { index, descriptor -> resolve(descriptor, index, now) }
        require(resolved.map { it.attribution.sha256 }.distinct().size == resolved.size) {
            "Aynı görsel içeriği birden fazla sahnede tekrarlandı; video üretilmedi."
        }
        cache.cleanup(clock())
        return resolved
    }

    private fun resolve(
        descriptor: VideoVisualAssetDescriptor,
        sceneIndex: Int,
        now: Long
    ): ResolvedSceneVisual {
        val cacheKey = sha256(
            listOf(
                descriptor.assetId,
                descriptor.downloadUrl,
                descriptor.sourceUrl,
                descriptor.creator,
                descriptor.licenseId,
                descriptor.licenseVersion
            ).joinToString("|")
        )
        val cached = cache.read(cacheKey, now) ?: run {
            val downloaded = downloader.download(descriptor)
            validateDownload(descriptor, downloaded)
            val hash = sha256(downloaded.bytes)
            cache.write(
                cacheKey = cacheKey,
                bytes = downloaded.bytes,
                mimeType = downloaded.mimeType,
                contentSha256 = hash,
                retrievedAtEpochMs = now,
                expiresAtEpochMs = now + cacheTtlMs
            )
            cache.read(cacheKey, now)
                ?: throw IOException("Lisanslı görsel önbelleğe güvenli biçimde yazılamadı.")
        }

        if (cached.mimeType != descriptor.expectedMimeType ||
            cached.retrievedAtEpochMs <= 0L ||
            cached.retrievedAtEpochMs > now ||
            cached.expiresAtEpochMs <= cached.retrievedAtEpochMs ||
            cached.expiresAtEpochMs - cached.retrievedAtEpochMs > VideoAssetLicensePolicy.MAX_CACHE_TTL_MS ||
            !cached.file.isFile ||
            cached.file.length() != cached.byteSize ||
            sha256(cached.file) != cached.sha256 ||
            cached.expiresAtEpochMs <= now
        ) {
            throw IOException("Önbellekteki görsel doğrulanamadı.")
        }

        val attribution = VideoAssetAttribution(
            sceneIndex = sceneIndex,
            sceneId = descriptor.sceneId,
            assetId = descriptor.assetId,
            sha256 = cached.sha256,
            sourceUrl = descriptor.sourceUrl,
            creator = descriptor.creator,
            licenseId = descriptor.licenseId,
            licenseVersion = descriptor.licenseVersion,
            licenseUrl = descriptor.licenseUrl,
            attributionText = descriptor.attributionText,
            retrievedAtEpochMs = cached.retrievedAtEpochMs,
            cacheKey = cacheKey,
            cacheMetadata = "provider=WikimediaCommons;mime=" + cached.mimeType +
                ";bytes=" + cached.byteSize + ";expiresAt=" + cached.expiresAtEpochMs,
            commercialUseAllowed = descriptor.commercialUseAllowed,
            derivativesAllowed = descriptor.derivativesAllowed,
            shareAlikeRequired = descriptor.shareAlikeRequired,
            requiredOutputLicense = descriptor.requiredOutputLicense
        )
        require(attribution.isValid()) { "Görsel atıf kaydı eksik; video üretilmedi." }
        return ResolvedSceneVisual(cached.file, attribution)
    }

    private fun validateDownload(
        descriptor: VideoVisualAssetDescriptor,
        downloaded: DownloadedVisualAsset
    ) {
        require(VideoAssetLicensePolicy.isAllowedDownloadUrl(downloaded.finalUrl)) {
            "Görsel indirme yönlendirmesi güvenilir HTTPS Wikimedia dosya yolunun dışına çıktı."
        }
        require(downloaded.mimeType == descriptor.expectedMimeType) {
            "Görselin MIME türü kaynak kaydıyla eşleşmiyor."
        }
        require(downloaded.bytes.isNotEmpty() && downloaded.bytes.size.toLong() <= descriptor.maxBytes) {
            "Görsel boş veya izin verilen boyut sınırını aşıyor."
        }
        val signatureValid = when (downloaded.mimeType) {
            "image/jpeg" -> downloaded.bytes.size >= 3 &&
                downloaded.bytes[0] == 0xff.toByte() &&
                downloaded.bytes[1] == 0xd8.toByte() &&
                downloaded.bytes[2] == 0xff.toByte()
            "image/png" -> downloaded.bytes.size >= 8 &&
                downloaded.bytes.take(8).map { it.toInt() and 0xff } ==
                listOf(137, 80, 78, 71, 13, 10, 26, 10)
            else -> false
        }
        require(signatureValid) { "İndirilen dosya doğrulanmış bir JPEG/PNG görseli değil." }
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = VideoAssetLicensePolicy.MAX_CACHE_TTL_MS
    }
}

class FileVideoVisualAssetCache(
    private val root: File,
    private val maxEntries: Int = 10,
    private val maxBytes: Long = 24L * 1024L * 1024L
) : VideoVisualAssetCache {
    init {
        require(maxEntries > 0 && maxBytes > 0L)
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Lisanslı görsel önbellek klasörü oluşturulamadı.")
        }
    }

    override fun read(cacheKey: String, nowEpochMs: Long): CachedVisualAsset? {
        require(cacheKey.matches(Regex("[a-f0-9]{64}")))
        val dataFile = File(root, "$cacheKey.bin")
        val metadataFile = File(root, "$cacheKey.properties")
        if (!dataFile.isFile || !metadataFile.isFile) {
            dataFile.delete()
            metadataFile.delete()
            return null
        }
        val properties = Properties()
        try {
            FileInputStream(metadataFile).use { properties.load(it) }
        } catch (_: Exception) {
            dataFile.delete()
            metadataFile.delete()
            return null
        }
        val expectedHash = properties.getProperty("sha256")
        val mimeType = properties.getProperty("mimeType")
        val retrievedAt = properties.getProperty("retrievedAtEpochMs")?.toLongOrNull()
        val expiresAt = properties.getProperty("expiresAtEpochMs")?.toLongOrNull()
        val byteSize = properties.getProperty("byteSize")?.toLongOrNull()
        if (expectedHash.isNullOrBlank() || mimeType == null || retrievedAt == null ||
            expiresAt == null || byteSize == null ||
            retrievedAt <= 0L || retrievedAt > nowEpochMs ||
            expiresAt <= retrievedAt || expiresAt <= nowEpochMs ||
            expiresAt - retrievedAt > VideoAssetLicensePolicy.MAX_CACHE_TTL_MS ||
            byteSize <= 0L || dataFile.length() != byteSize ||
            mimeType !in VideoAssetLicensePolicy.ALLOWED_IMAGE_MIME_TYPES ||
            sha256(dataFile) != expectedHash
        ) {
            dataFile.delete()
            metadataFile.delete()
            return null
        }
        dataFile.setLastModified(nowEpochMs)
        return CachedVisualAsset(dataFile, expectedHash, mimeType, retrievedAt, expiresAt, byteSize)
    }
    override fun write(
        cacheKey: String,
        bytes: ByteArray,
        mimeType: String,
        contentSha256: String,
        retrievedAtEpochMs: Long,
        expiresAtEpochMs: Long
    ) {
        require(cacheKey.matches(Regex("[a-f0-9]{64}")))
        require(bytes.isNotEmpty() && mimeType in VideoAssetLicensePolicy.ALLOWED_IMAGE_MIME_TYPES)
        require(contentSha256.matches(Regex("[a-f0-9]{64}")) && sha256(bytes) == contentSha256)
        require(
            retrievedAtEpochMs > 0L &&
                expiresAtEpochMs > retrievedAtEpochMs &&
                expiresAtEpochMs - retrievedAtEpochMs <= VideoAssetLicensePolicy.MAX_CACHE_TTL_MS
        ) { "Görsel önbellek süresi geçersiz veya 30 günü aşıyor." }
        val dataFile = File(root, "$cacheKey.bin")
        val metadataFile = File(root, "$cacheKey.properties")
        val dataTemp = File(root, "$cacheKey.bin.tmp")
        val metadataTemp = File(root, "$cacheKey.properties.tmp")
        try {
            FileOutputStream(dataTemp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            val properties = Properties().apply {
                setProperty("sha256", contentSha256)
                setProperty("mimeType", mimeType)
                setProperty("retrievedAtEpochMs", retrievedAtEpochMs.toString())
                setProperty("expiresAtEpochMs", expiresAtEpochMs.toString())
                setProperty("byteSize", bytes.size.toString())
            }
            FileOutputStream(metadataTemp).use { output ->
                properties.store(output, "Open JARVIS licensed visual asset cache")
                output.fd.sync()
            }
            dataFile.delete()
            metadataFile.delete()
            if (!dataTemp.renameTo(dataFile) || !metadataTemp.renameTo(metadataFile)) {
                throw IOException("Görsel önbellek kaydı atomik olarak tamamlanamadı.")
            }
            dataFile.setLastModified(retrievedAtEpochMs)
            cleanup(retrievedAtEpochMs)
        } catch (error: Exception) {
            dataTemp.delete()
            metadataTemp.delete()
            dataFile.delete()
            metadataFile.delete()
            throw error
        }
    }

    override fun cleanup(nowEpochMs: Long) {
        val files = root.listFiles()?.filter { it.extension == "bin" } ?: return
        val valid = mutableListOf<File>()
        files.forEach { file ->
            val key = file.nameWithoutExtension
            val metadata = File(root, "$key.properties")
            val metadataValid = runCatching {
                val properties = Properties()
                FileInputStream(metadata).use { properties.load(it) }
                val retrievedAt = properties.getProperty("retrievedAtEpochMs")?.toLongOrNull()
                val expiresAt = properties.getProperty("expiresAtEpochMs")?.toLongOrNull()
                retrievedAt != null && expiresAt != null &&
                    retrievedAt > 0L && retrievedAt <= nowEpochMs &&
                    expiresAt > nowEpochMs && expiresAt > retrievedAt &&
                    expiresAt - retrievedAt <= VideoAssetLicensePolicy.MAX_CACHE_TTL_MS
            }.getOrDefault(false)
            if (!metadata.isFile || !metadataValid) {
                file.delete()
                metadata.delete()
            } else {
                valid += file
            }
        }
        var totalBytes = valid.sumOf { it.length() }
        val oldestFirst = valid.sortedBy { it.lastModified() }
        var remaining = valid.size
        for (file in oldestFirst) {
            if (remaining <= maxEntries && totalBytes <= maxBytes) break
            val bytes = file.length()
            val metadata = File(root, file.nameWithoutExtension + ".properties")
            file.delete()
            metadata.delete()
            totalBytes -= bytes
            remaining--
        }
        root.listFiles()?.filter {
            it.extension == "properties" &&
                !File(root, it.nameWithoutExtension + ".bin").isFile
        }?.forEach { it.delete() }
        root.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }


}

class WikimediaCommonsVisualAssetDownloader : VideoVisualAssetDownloader {
    override fun download(descriptor: VideoVisualAssetDescriptor): DownloadedVisualAsset {
        VideoAssetLicensePolicy.validate(descriptor)
        var current = URL(descriptor.downloadUrl)
        repeat(MAX_REDIRECTS + 1) {
            validateUrl(current)
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", descriptor.expectedMimeType)
                when (val status = connection.responseCode) {
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Wikimedia görsel yönlendirmesi eksik.")
                        current = URL(current, location)
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val mimeType = connection.contentType
                            ?.substringBefore(';')
                            ?.trim()
                            ?.lowercase(Locale.ROOT)
                            ?: throw IOException("Wikimedia görsel MIME türünü bildirmedi.")
                        if (mimeType != descriptor.expectedMimeType) {
                            throw IOException("Wikimedia beklenmeyen görsel biçimi döndürdü.")
                        }
                        val length = connection.getHeaderFieldLong("Content-Length", -1L)
                        if (length > descriptor.maxBytes) {
                            throw IOException("Wikimedia görseli boyut sınırını aşıyor.")
                        }
                        val bytes = connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > descriptor.maxBytes) {
                                    throw IOException("Wikimedia görseli boyut sınırını aşıyor.")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        return DownloadedVisualAsset(bytes, mimeType, current.toExternalForm())
                    }
                    else -> throw IOException("Wikimedia görseli indirilemedi (HTTP $status).")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Wikimedia görseli için çok fazla yönlendirme yapıldı.")
    }

    private fun validateUrl(url: URL) {
        require(VideoAssetLicensePolicy.isAllowedDownloadUrl(url.toExternalForm())) {
            "Wikimedia yönlendirmesi izin verilmeyen HTTPS dosya yoluna çıktı."
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 3
        private const val USER_AGENT =
            "OpenJarvisAndroid/1.0 (licensed visual assets; https://github.com/furkion67-eng/open-jarvis-android)"
    }
}

object Wikimedia1453AssetCatalog {
    private const val PDM_URL = "https://creativecommons.org/publicdomain/mark/1.0/"
    private const val BY_SA_URL = "https://creativecommons.org/licenses/by-sa/4.0/"

    val assets: List<VideoVisualAssetDescriptor> = listOf(
        VideoVisualAssetDescriptor(
            assetId = "commons-constantinople-1453-miniature",
            sceneId = "1453-hook-siege",
            title = "Fall of Constantinople, c. 1455–1500",
            downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/f/fa/Constantinople_1453.jpg?utm_campaign=index&utm_content=original&utm_source=commons.wikimedia.org",
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Constantinople_1453.jpg&oldid=1222147207",
            creator = "Jean Le Tavernier",
            licenseId = "Public Domain Mark",
            licenseVersion = "1.0",
            licenseUrl = PDM_URL,
            attributionText = "Jean Le Tavernier, “Fall of Constantinople” (c. 1455–1500), Gallica/BnF via Wikimedia Commons; Public Domain Mark 1.0. 9:16 kadraj için kırpıldı. Kaynak: https://commons.wikimedia.org/w/index.php?title=File:Constantinople_1453.jpg&oldid=1222147207 Lisans: https://creativecommons.org/publicdomain/mark/1.0/",
            expectedMimeType = "image/jpeg"
        ),
        VideoVisualAssetDescriptor(
            assetId = "commons-mehmed-ii-portrait",
            sceneId = "1453-mehmed",
            title = "The Sultan Mehmet II, Gentile Bellini",
            downloadUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6e/Bellini%2C_Gentile_-_Sultan_Mehmet_II.jpg/960px-Bellini%2C_Gentile_-_Sultan_Mehmet_II.jpg?utm_campaign=index&utm_content=thumbnail&utm_source=commons.wikimedia.org",
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Bellini,_Gentile_-_Sultan_Mehmet_II.jpg&oldid=1284749530",
            creator = "Gentile Bellini",
            licenseId = "Public Domain Mark",
            licenseVersion = "1.0",
            licenseUrl = PDM_URL,
            attributionText = "Gentile Bellini, “The Sultan Mehmet II” (c. 1480), Wikimedia Commons; Public Domain Mark 1.0. 9:16 kadraj için kırpıldı. Kaynak: https://commons.wikimedia.org/w/index.php?title=File:Bellini,_Gentile_-_Sultan_Mehmet_II.jpg&oldid=1284749530 Lisans: https://creativecommons.org/publicdomain/mark/1.0/",
            expectedMimeType = "image/jpeg"
        ),
        VideoVisualAssetDescriptor(
            assetId = "commons-rumeli-hisari-2025",
            sceneId = "1453-rumeli",
            title = "Rumeli Hisarı Fortress in 2025",
            downloadUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/49/Rumeli_Hisar%C4%B1_Fortress_in_2025.jpg/1280px-Rumeli_Hisar%C4%B1_Fortress_in_2025.jpg?utm_campaign=index&utm_content=thumbnail&utm_source=commons.wikimedia.org",
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Rumeli_Hisar%C4%B1_Fortress_in_2025.jpg&oldid=1118525609",
            creator = "mike",
            licenseId = "CC BY-SA",
            licenseVersion = "4.0",
            licenseUrl = BY_SA_URL,
            attributionText = "mike, “Rumeli Hisarı Fortress in 2025” (2025), Wikimedia Commons; CC BY-SA 4.0. 9:16 kadraj için kırpıldı; bu görselin uyarlaması CC BY-SA 4.0 ile paylaşılmalıdır. Kaynak: https://commons.wikimedia.org/w/index.php?title=File:Rumeli_Hisar%C4%B1_Fortress_in_2025.jpg&oldid=1118525609 Lisans: https://creativecommons.org/licenses/by-sa/4.0/",
            expectedMimeType = "image/jpeg",
            shareAlikeRequired = true,
            requiredOutputLicense = "CC BY-SA 4.0"
        ),
        VideoVisualAssetDescriptor(
            assetId = "commons-1453-siege-map-en",
            sceneId = "1453-halic-map",
            title = "Siege of Constantinople 1453 map-en",
            downloadUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a6/Siege_of_Constantinople_1453_map-en.svg/1280px-Siege_of_Constantinople_1453_map-en.svg.png?utm_campaign=index&utm_content=thumbnail&utm_source=commons.wikimedia.org",
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Siege_of_Constantinople_1453_map-en.svg&oldid=1287462680",
            creator = "Sémhur",
            licenseId = "CC BY-SA",
            licenseVersion = "4.0",
            licenseUrl = BY_SA_URL,
            attributionText = "Sémhur, “Siege of Constantinople 1453 map-en” (2020), Wikimedia Commons; CC BY-SA 4.0. PNG küçük resmi kullanıldı ve 9:16 kadraj için kırpıldı; bu görselin uyarlaması CC BY-SA 4.0 ile paylaşılmalıdır. Kaynak: https://commons.wikimedia.org/w/index.php?title=File:Siege_of_Constantinople_1453_map-en.svg&oldid=1287462680 Lisans: https://creativecommons.org/licenses/by-sa/4.0/",
            expectedMimeType = "image/png",
            shareAlikeRequired = true,
            requiredOutputLicense = "CC BY-SA 4.0"
        ),
        VideoVisualAssetDescriptor(
            assetId = "commons-golden-horn-wall",
            sceneId = "1453-walls",
            title = "Golden Horn Wall",
            downloadUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/54/Golden_Horn_Wall.jpg/1280px-Golden_Horn_Wall.jpg?utm_campaign=index&utm_content=thumbnail&utm_source=commons.wikimedia.org",
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Golden_Horn_Wall.jpg&oldid=1237207514",
            creator = "Isabeau",
            licenseId = "CC BY-SA",
            licenseVersion = "4.0",
            licenseUrl = BY_SA_URL,
            attributionText = "Isabeau, “Golden Horn Wall” (2018), Wikimedia Commons; CC BY-SA 4.0. 9:16 kadraj için kırpıldı; bu görselin uyarlaması CC BY-SA 4.0 ile paylaşılmalıdır. Kaynak: https://commons.wikimedia.org/w/index.php?title=File:Golden_Horn_Wall.jpg&oldid=1237207514 Lisans: https://creativecommons.org/licenses/by-sa/4.0/",
            expectedMimeType = "image/jpeg",
            shareAlikeRequired = true,
            requiredOutputLicense = "CC BY-SA 4.0"
        )
    )

    private val byId = assets.associateBy { it.assetId }

    fun find(assetId: String): VideoVisualAssetDescriptor? = byId[assetId]
}

private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
