package com.openjarvis.tasks

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Typed, durable attribution data returned by the Shorts renderer. */
data class VideoSceneAssetAttribution(
    val sceneIndex: Int,
    val sceneId: String? = null,
    val assetId: String,
    val sha256: String,
    val sourceUrl: String? = null,
    val creator: String? = null,
    val licenseId: String? = null,
    val licenseVersion: String? = null,
    val licenseUrl: String? = null,
    val attributionText: String? = null,
    val retrievedAtEpochMs: Long? = null,
    val cacheKey: String? = null,
    val cacheMetadata: String? = null,
    val commercialUseAllowed: Boolean = false,
    val derivativesAllowed: Boolean = false,
    val shareAlikeRequired: Boolean = false,
    val requiredOutputLicense: String? = null
) {
    fun isValid(): Boolean {
        val source = sourceUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val sourceParameters = source.rawQuery?.split("&") ?: return false
        val titleParameters = sourceParameters.filter { it.substringBefore('=') == "title" }
        val revisionParameters = sourceParameters.filter { it.substringBefore('=') == "oldid" }
        val retrieved = retrievedAtEpochMs ?: return false
        val cache = cacheMetadata ?: return false
        val cacheFields = linkedMapOf<String, String>()
        for (entry in cache.split(";")) {
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) return false
            val key = entry.substring(0, separator)
            if (cacheFields.put(key, entry.substring(separator + 1)) != null) return false
        }
        val byteSize = cacheFields["bytes"]?.toLongOrNull() ?: return false
        val expiresAt = cacheFields["expiresAt"]?.toLongOrNull() ?: return false
        val cacheMime = cacheFields["mime"]
        return sceneIndex >= 0 &&
            !sceneId.isNullOrBlank() &&
            assetId.isNotBlank() &&
            sha256.matches(Regex("[a-fA-F0-9]{64}")) &&
            source.scheme == "https" &&
            source.host == "commons.wikimedia.org" &&
            source.rawUserInfo == null &&
            (source.port == -1 || source.port == 443) &&
            source.path == "/w/index.php" &&
            titleParameters.size == 1 &&
            titleParameters.single().substringAfter('=', "").startsWith("File:") &&
            revisionParameters.size == 1 &&
            revisionParameters.single().substringAfter('=', "").matches(Regex("[1-9][0-9]{0,11}")) &&
            !creator.isNullOrBlank() &&
            !licenseId.isNullOrBlank() &&
            !licenseVersion.isNullOrBlank() &&
            !licenseUrl.isNullOrBlank() &&
            isHttpsUrl(licenseUrl) &&
            licenseUrl.startsWith("https://creativecommons.org/") &&
            !attributionText.isNullOrBlank() &&
            attributionText.contains(creator) &&
            attributionText.contains(licenseId + " " + licenseVersion) &&
            attributionText.contains(licenseUrl) &&
            attributionText.contains(sourceUrl) &&
            retrieved > 0L &&
            cacheKey?.matches(Regex("[a-fA-F0-9]{64}")) == true &&
            cacheFields.keys == setOf("provider", "mime", "bytes", "expiresAt") &&
            cacheFields["provider"] == "WikimediaCommons" &&
            (cacheMime == "image/jpeg" || cacheMime == "image/png") &&
            byteSize in 1L..5_000_000L &&
            expiresAt > retrieved &&
            expiresAt - retrieved <= 30L * 24L * 60L * 60L * 1_000L &&
            commercialUseAllowed &&
            derivativesAllowed &&
            (!shareAlikeRequired || !requiredOutputLicense.isNullOrBlank())
    }
}

data class VideoClaimSourceEvidence(
    val claimId: String,
    val sceneId: String,
    val sourceUrl: String,
    val publisher: String,
    val checkedOn: String,
    val supportedClaim: String,
    val uncertaintyNote: String? = null
) {
    fun isValid(): Boolean =
        claimId.isNotBlank() &&
            sceneId.isNotBlank() &&
            isHttpsUrl(sourceUrl) &&
            publisher.isNotBlank() &&
            checkedOn.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
            supportedClaim.isNotBlank()
}

/** Only renderer-observed metadata is stored; incomplete provenance is never fabricated. */
data class VideoArtifactMetadata(
    val containerMimeType: String = "video/mp4",
    val videoTrackMimeType: String = "video/avc",
    val captionsMimeType: String = "application/x-subrip",
    val audioTrackMimeType: String? = "audio/mp4a-latm",
    val durationMs: Long,
    val audioDurationMs: Long? = null,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val hasAudio: Boolean,
    val narrationLocale: String,
    val sceneCount: Int? = null,
    val sourceLabel: String? = null,
    val captionLocale: String? = null,
    val hookDurationMs: Long? = null,
    val thumbnailUri: String? = null,
    val thumbnailAltText: String? = null,
    val backgroundMusicIncluded: Boolean = false,
    val audioDescription: String? = null,
    val attributionCredits: String? = null,
    val researchCredits: String? = null,
    val claimSources: List<VideoClaimSourceEvidence> = emptyList(),
    val attributionManifest: List<VideoSceneAssetAttribution> = emptyList(),
    val attributionManifestComplete: Boolean = false
) {
    fun isValid(): Boolean {
        val baseValid =
            containerMimeType == "video/mp4" &&
                videoTrackMimeType == "video/avc" &&
                captionsMimeType == "application/x-subrip" &&
                durationMs > 0L &&
                (audioDurationMs == null || audioDurationMs > 0L) &&
                width > 0 && height > width &&
                frameRate in 1..120 &&
                hasAudio &&
                audioTrackMimeType == "audio/mp4a-latm" &&
                narrationLocale == "tr-TR" &&
                (sceneCount == null || sceneCount > 0)
        if (!baseValid) return false
        if (!attributionManifestComplete) return true

        val count = sceneCount ?: return false
        val credits = attributionCredits ?: return false
        val research = researchCredits ?: return false
        return captionLocale == "tr-TR" &&
            (hookDurationMs ?: 0L) in 1L..2_000L &&
            isContentUri(thumbnailUri) &&
            !thumbnailAltText.isNullOrBlank() &&
            !audioDescription.isNullOrBlank() &&
            credits.isNotBlank() &&
            research.isNotBlank() &&
            attributionManifest.size == count &&
            attributionManifest.all { it.isValid() } &&
            attributionManifest.mapNotNull { it.sceneId }.distinct().size == count &&
            attributionManifest.map { it.sceneIndex }.sorted() == (0 until count).toList() &&
            attributionManifest.map { it.assetId }.distinct().size == count &&
            attributionManifest.all { credits.contains(it.attributionText.orEmpty()) } &&
            claimSources.isNotEmpty() &&
            claimSources.all { it.isValid() } &&
            claimSources.map { it.claimId }.distinct().size == claimSources.size &&
            claimSources.all { source -> attributionManifest.any { it.sceneId == source.sceneId } } &&
            attributionManifest.all { item ->
                claimSources.any { it.sceneId == item.sceneId }
            } &&
            claimSources.all { research.contains(it.publisher) && research.contains(it.sourceUrl) }
    }
}

/**
 * Compatibility bridge for the renderer PR stacked immediately after this feature PR.
 * The renderer result is consumed read-only by its stable Kotlin property/getter contract;
 * this adapter converts it into our own typed durable model and fails closed if fields are absent.
 */
object VideoRendererMetadataAdapter {
    fun fromRenderResult(
        renderResult: Any,
        durationMs: Long,
        audioDurationMs: Long?,
        width: Int,
        height: Int,
        frameRate: Int,
        hasAudio: Boolean,
        sceneCount: Int,
        sourceLabel: String?
    ): VideoArtifactMetadata {
        val complete = renderResult.booleanProperty("attributionManifestComplete") ?: false
        require(complete) { "Kaynaklı video için renderer atıf manifesti eksik." }

        val manifestValues = renderResult.listProperty("attributionManifest")
        require(manifestValues.size == sceneCount) {
            "Her video sahnesi için tam bir doğrulanmış görsel atfı gerekli."
        }
        val manifest = manifestValues.mapIndexed { index, item ->
            requireNotNull(item) { "Renderer atıf girdisi boş." }
            require(item.validMethod() == true) { "Renderer görsel lisans/önbellek doğrulaması başarısız." }
            VideoSceneAssetAttribution(
                sceneIndex = item.intProperty("sceneIndex") ?: index,
                sceneId = item.stringProperty("sceneId"),
                assetId = item.stringProperty("assetId") ?: "",
                sha256 = item.stringProperty("sha256") ?: "",
                sourceUrl = item.stringProperty("sourceUrl"),
                creator = item.stringProperty("creator"),
                licenseId = item.stringProperty("licenseId"),
                licenseVersion = item.stringProperty("licenseVersion"),
                licenseUrl = item.stringProperty("licenseUrl"),
                attributionText = item.stringProperty("attributionText"),
                retrievedAtEpochMs = item.longProperty("retrievedAtEpochMs"),
                cacheKey = item.stringProperty("cacheKey"),
                cacheMetadata = item.stringProperty("cacheMetadata"),
                commercialUseAllowed = item.booleanProperty("commercialUseAllowed") ?: false,
                derivativesAllowed = item.booleanProperty("derivativesAllowed") ?: false,
                shareAlikeRequired = item.booleanProperty("shareAlikeRequired") ?: false,
                requiredOutputLicense = item.stringProperty("requiredOutputLicense")
            )
        }

        val claimSources = renderResult.listProperty("claimSources").map { item ->
            requireNotNull(item) { "Renderer araştırma kaynağı boş." }
            VideoClaimSourceEvidence(
                claimId = item.stringProperty("claimId") ?: "",
                sceneId = item.stringProperty("sceneId") ?: "",
                sourceUrl = item.stringProperty("sourceUrl") ?: "",
                publisher = item.stringProperty("publisher") ?: "",
                checkedOn = item.stringProperty("checkedOn") ?: "",
                supportedClaim = item.stringProperty("supportedClaim") ?: "",
                uncertaintyNote = item.stringProperty("uncertaintyNote")
            )
        }

        val metadata = VideoArtifactMetadata(
            durationMs = durationMs,
            audioDurationMs = audioDurationMs,
            width = width,
            height = height,
            frameRate = frameRate,
            hasAudio = hasAudio,
            narrationLocale = renderResult.stringProperty("narrationLocale") ?: "",
            sceneCount = sceneCount,
            sourceLabel = sourceLabel,
            captionLocale = renderResult.stringProperty("captionLocale"),
            hookDurationMs = renderResult.longProperty("hookDurationMs"),
            thumbnailUri = thumbnailUri(renderResult)?.toString(),
            thumbnailAltText = renderResult.stringProperty("thumbnailAltText"),
            backgroundMusicIncluded = renderResult.booleanProperty("backgroundMusicIncluded") ?: false,
            audioDescription = renderResult.stringProperty("audioDescription"),
            attributionCredits = renderResult.stringProperty("attributionCredits"),
            researchCredits = renderResult.stringProperty("researchCredits"),
            claimSources = claimSources,
            attributionManifest = manifest,
            attributionManifestComplete = complete
        )
        require(metadata.isValid()) { "Renderer metadata/atıf sözleşmesi eksik veya geçersiz." }
        return metadata
    }

    fun thumbnailUri(renderResult: Any): Uri? {
        val value = renderResult.property("thumbnailUri") ?: return null
        return when (value) {
            is Uri -> value
            else -> runCatching { Uri.parse(value.toString()) }.getOrNull()
        }
    }

    private fun Any.property(name: String): Any? {
        val getter = "get" + name.substring(0, 1).uppercase(java.util.Locale.ROOT) + name.substring(1)
        val method = javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
            ?: return null
        method.isAccessible = true
        return method.invoke(this)
    }

    private fun Any.stringProperty(name: String): String? =
        property(name)?.toString()?.takeIf { it.isNotBlank() }

    private fun Any.booleanProperty(name: String): Boolean? = property(name) as? Boolean

    private fun Any.longProperty(name: String): Long? =
        (property(name) as? Number)?.toLong() ?: property(name)?.toString()?.toLongOrNull()

    private fun Any.intProperty(name: String): Int? =
        (property(name) as? Number)?.toInt() ?: property(name)?.toString()?.toIntOrNull()

    private fun Any.listProperty(name: String): List<*> =
        property(name) as? List<*> ?: throw IllegalArgumentException("Renderer " + name + " listesi eksik.")

    private fun Any.validMethod(): Boolean? {
        val method = javaClass.methods.firstOrNull { it.name == "isValid" && it.parameterCount == 0 }
            ?: return null
        method.isAccessible = true
        return method.invoke(this) as? Boolean
    }
}

/** Stored on the durable task row only after all MediaStore outputs have been verified. */
data class PersistedVideoArtifact(
    val artifactId: String,
    val exportedUri: String,
    val captionsUri: String,
    val metadata: VideoArtifactMetadata
) {
    fun isValid(): Boolean =
        artifactId.isNotBlank() &&
            isContentUri(exportedUri) &&
            isContentUri(captionsUri) &&
            metadata.isValid()

    private fun isContentUri(value: String): Boolean = runCatching {
        val parsed = URI(value)
        parsed.scheme.equals("content", ignoreCase = true) && !parsed.authority.isNullOrBlank()
    }.getOrDefault(false)
}

/** Stable JSON codec; new fields are optional when decoding older task rows. */
object VideoArtifactMetadataCodec {
    fun encode(metadata: VideoArtifactMetadata): String {
        require(metadata.isValid()) { "Video artifact metadata is incomplete or invalid." }
        val attributions = JSONArray()
        metadata.attributionManifest.forEach { item ->
            attributions.put(JSONObject().apply {
                put("sceneIndex", item.sceneIndex)
                putNullable("sceneId", item.sceneId)
                put("assetId", item.assetId)
                put("sha256", item.sha256)
                putNullable("sourceUrl", item.sourceUrl)
                putNullable("creator", item.creator)
                putNullable("licenseId", item.licenseId)
                putNullable("licenseVersion", item.licenseVersion)
                putNullable("licenseUrl", item.licenseUrl)
                putNullable("attributionText", item.attributionText)
                putNullable("retrievedAtEpochMs", item.retrievedAtEpochMs)
                putNullable("cacheKey", item.cacheKey)
                putNullable("cacheMetadata", item.cacheMetadata)
                put("commercialUseAllowed", item.commercialUseAllowed)
                put("derivativesAllowed", item.derivativesAllowed)
                put("shareAlikeRequired", item.shareAlikeRequired)
                putNullable("requiredOutputLicense", item.requiredOutputLicense)
            })
        }
        val claims = JSONArray()
        metadata.claimSources.forEach { item ->
            claims.put(JSONObject().apply {
                put("claimId", item.claimId)
                put("sceneId", item.sceneId)
                put("sourceUrl", item.sourceUrl)
                put("publisher", item.publisher)
                put("checkedOn", item.checkedOn)
                put("supportedClaim", item.supportedClaim)
                putNullable("uncertaintyNote", item.uncertaintyNote)
            })
        }
        return JSONObject().apply {
            put("containerMimeType", metadata.containerMimeType)
            put("videoTrackMimeType", metadata.videoTrackMimeType)
            put("captionsMimeType", metadata.captionsMimeType)
            putNullable("audioTrackMimeType", metadata.audioTrackMimeType)
            put("durationMs", metadata.durationMs)
            putNullable("audioDurationMs", metadata.audioDurationMs)
            put("width", metadata.width)
            put("height", metadata.height)
            put("frameRate", metadata.frameRate)
            put("hasAudio", metadata.hasAudio)
            put("narrationLocale", metadata.narrationLocale)
            putNullable("sceneCount", metadata.sceneCount)
            putNullable("sourceLabel", metadata.sourceLabel)
            putNullable("captionLocale", metadata.captionLocale)
            putNullable("hookDurationMs", metadata.hookDurationMs)
            putNullable("thumbnailUri", metadata.thumbnailUri)
            putNullable("thumbnailAltText", metadata.thumbnailAltText)
            put("backgroundMusicIncluded", metadata.backgroundMusicIncluded)
            putNullable("audioDescription", metadata.audioDescription)
            putNullable("attributionCredits", metadata.attributionCredits)
            putNullable("researchCredits", metadata.researchCredits)
            put("claimSources", claims)
            put("attributionManifest", attributions)
            put("attributionManifestComplete", metadata.attributionManifestComplete)
        }.toString()
    }

    fun decode(encoded: String?): VideoArtifactMetadata? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(encoded)
            val attributionsJson = json.optJSONArray("attributionManifest") ?: JSONArray()
            val attributions = buildList {
                for (index in 0 until attributionsJson.length()) {
                    val item = attributionsJson.optJSONObject(index) ?: continue
                    add(
                        VideoSceneAssetAttribution(
                            sceneIndex = item.getInt("sceneIndex"),
                            sceneId = item.stringOrNull("sceneId"),
                            assetId = item.getString("assetId"),
                            sha256 = item.getString("sha256"),
                            sourceUrl = item.stringOrNull("sourceUrl"),
                            creator = item.stringOrNull("creator"),
                            licenseId = item.stringOrNull("licenseId"),
                            licenseVersion = item.stringOrNull("licenseVersion"),
                            licenseUrl = item.stringOrNull("licenseUrl"),
                            attributionText = item.stringOrNull("attributionText"),
                            retrievedAtEpochMs = item.longOrNull("retrievedAtEpochMs"),
                            cacheKey = item.stringOrNull("cacheKey"),
                            cacheMetadata = item.stringOrNull("cacheMetadata"),
                            commercialUseAllowed = item.optBoolean("commercialUseAllowed", false),
                            derivativesAllowed = item.optBoolean("derivativesAllowed", false),
                            shareAlikeRequired = item.optBoolean("shareAlikeRequired", false),
                            requiredOutputLicense = item.stringOrNull("requiredOutputLicense")
                        )
                    )
                }
            }
            require(attributions.size == attributionsJson.length()) {
                "Malformed renderer attribution entry."
            }
            val claimsJson = json.optJSONArray("claimSources") ?: JSONArray()
            val claims = buildList {
                for (index in 0 until claimsJson.length()) {
                    val item = claimsJson.optJSONObject(index) ?: continue
                    add(
                        VideoClaimSourceEvidence(
                            claimId = item.optString("claimId", ""),
                            sceneId = item.optString("sceneId", ""),
                            sourceUrl = item.optString("sourceUrl", ""),
                            publisher = item.optString("publisher", ""),
                            checkedOn = item.optString("checkedOn", ""),
                            supportedClaim = item.optString("supportedClaim", ""),
                            uncertaintyNote = item.stringOrNull("uncertaintyNote")
                        )
                    )
                }
            }
            require(claims.size == claimsJson.length()) {
                "Malformed renderer claim source entry."
            }
            VideoArtifactMetadata(
                containerMimeType = json.getString("containerMimeType"),
                videoTrackMimeType = json.getString("videoTrackMimeType"),
                captionsMimeType = json.getString("captionsMimeType"),
                audioTrackMimeType = json.stringOrNull("audioTrackMimeType"),
                durationMs = json.getLong("durationMs"),
                audioDurationMs = json.longOrNull("audioDurationMs"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                frameRate = json.getInt("frameRate"),
                hasAudio = json.getBoolean("hasAudio"),
                narrationLocale = json.getString("narrationLocale"),
                sceneCount = json.intOrNull("sceneCount"),
                sourceLabel = json.stringOrNull("sourceLabel"),
                captionLocale = json.stringOrNull("captionLocale"),
                hookDurationMs = json.longOrNull("hookDurationMs"),
                thumbnailUri = json.stringOrNull("thumbnailUri"),
                thumbnailAltText = json.stringOrNull("thumbnailAltText"),
                backgroundMusicIncluded = json.optBoolean("backgroundMusicIncluded", false),
                audioDescription = json.stringOrNull("audioDescription"),
                attributionCredits = json.stringOrNull("attributionCredits"),
                researchCredits = json.stringOrNull("researchCredits"),
                claimSources = claims,
                attributionManifest = attributions,
                attributionManifestComplete = json.optBoolean("attributionManifestComplete", false)
            ).takeIf { it.isValid() }
        }.getOrNull()
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it != "null" }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.intOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)
}

private fun isHttpsUrl(value: String?): Boolean {
    val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443)
}

private fun isContentUri(value: String?): Boolean {
    val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return false
    return uri.scheme.equals("content", ignoreCase = true) && !uri.authority.isNullOrBlank()
}
