package com.openjarvis.tasks

import org.json.JSONArray
import org.json.JSONObject

/** Typed, durable attribution data returned by the Shorts renderer when its manifest is available. */
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
    val cacheMetadata: String? = null
) {
    fun isValid(): Boolean = sceneIndex >= 0 &&
        assetId.isNotBlank() &&
        sha256.matches(Regex("[a-fA-F0-9]{64}")) &&
        !sourceUrl.isNullOrBlank() &&
        !licenseId.isNullOrBlank() &&
        !licenseVersion.isNullOrBlank() &&
        !licenseUrl.isNullOrBlank() &&
        !attributionText.isNullOrBlank() &&
        retrievedAtEpochMs != null && retrievedAtEpochMs > 0L
}

/** Only metadata that is actually known is stored; missing provenance is never fabricated. */
data class VideoArtifactMetadata(
    val containerMimeType: String = "video/mp4",
    val videoTrackMimeType: String = "video/avc",
    val captionsMimeType: String = "application/x-subrip",
    val audioTrackMimeType: String? = "audio/mp4a-latm",
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val hasAudio: Boolean,
    val narrationLocale: String,
    val sceneCount: Int? = null,
    val sourceLabel: String? = null,
    val attributionManifest: List<VideoSceneAssetAttribution> = emptyList(),
    val attributionManifestComplete: Boolean = false
) {
    fun isValid(): Boolean =
        containerMimeType == "video/mp4" &&
            videoTrackMimeType == "video/avc" &&
            captionsMimeType == "application/x-subrip" &&
            durationMs > 0L &&
            width > 0 && height > width &&
            frameRate in 1..120 &&
            hasAudio &&
            audioTrackMimeType == "audio/mp4a-latm" &&
            narrationLocale == "tr-TR" &&
            (sceneCount == null || sceneCount > 0) &&
            (!attributionManifestComplete ||
                (attributionManifest.isNotEmpty() && attributionManifest.all { it.isValid() }))
}

/** Stored on the durable task row only after both MediaStore outputs have been verified. */
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
        val parsed = java.net.URI(value)
        parsed.scheme.equals("content", ignoreCase = true) && !parsed.authority.isNullOrBlank()
    }.getOrDefault(false)
}

/** Stable JSON codec avoids a new serialization dependency in the host APK. */
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
            })
        }
        return JSONObject().apply {
            put("containerMimeType", metadata.containerMimeType)
            put("videoTrackMimeType", metadata.videoTrackMimeType)
            put("captionsMimeType", metadata.captionsMimeType)
            putNullable("audioTrackMimeType", metadata.audioTrackMimeType)
            put("durationMs", metadata.durationMs)
            put("width", metadata.width)
            put("height", metadata.height)
            put("frameRate", metadata.frameRate)
            put("hasAudio", metadata.hasAudio)
            put("narrationLocale", metadata.narrationLocale)
            putNullable("sceneCount", metadata.sceneCount)
            putNullable("sourceLabel", metadata.sourceLabel)
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
                            cacheMetadata = item.stringOrNull("cacheMetadata")
                        )
                    )
                }
            }
            VideoArtifactMetadata(
                containerMimeType = json.getString("containerMimeType"),
                videoTrackMimeType = json.getString("videoTrackMimeType"),
                captionsMimeType = json.getString("captionsMimeType"),
                audioTrackMimeType = json.stringOrNull("audioTrackMimeType"),
                durationMs = json.getLong("durationMs"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                frameRate = json.getInt("frameRate"),
                hasAudio = json.getBoolean("hasAudio"),
                narrationLocale = json.getString("narrationLocale"),
                sceneCount = json.intOrNull("sceneCount"),
                sourceLabel = json.stringOrNull("sourceLabel"),
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
