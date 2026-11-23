package com.openjarvis.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** CI-only evidence overlay; retains MP4/SRT/thumbnail/attribution for independent human review. */
@RunWith(AndroidJUnit4::class)
class VideoArtifactEvidenceCiOverlayTest {
    @Test
    fun writesNarratedMediaStoreBundleAndPerSceneAttributionManifest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val result = withTimeout(180_000L) {
            VideoStoryPipeline(context).render(
                prompt = "İstanbul'un Fethi",
                artifactKey = "0123456789abcdef0123456789abcdef"
            )
        }
        try {
            assertEquals("tr-TR", result.narrationLocale)
            assertEquals("tr-TR", result.captionLocale)
            assertEquals(5, result.sceneCount)
            assertTrue(result.attributionManifestComplete)
            assertEquals(5, result.attributionManifest.size)
            assertTrue(result.attributionManifest.all { it.isValid() })
            assertEquals(5, result.claimSources.size)
            assertTrue(result.claimSources.all { it.sourceUrl.startsWith("https://") && it.publisher.isNotBlank() })
            assertTrue(result.exportedUri.scheme == "content")
            assertTrue(result.captionsUri.scheme == "content")
            val thumbnailUri = result.thumbnailUri
            assertNotNull(thumbnailUri)
            assertTrue(thumbnailUri!!.scheme == "content")

            copyContent(context, result.exportedUri, File(context.filesDir, "ci-video-artifact.mp4"))
            copyContent(context, result.captionsUri, File(context.filesDir, "ci-video-artifact.srt"))
            copyContent(context, thumbnailUri, File(context.filesDir, "ci-video-thumbnail.jpg"))

            val manifest = JSONObject()
                .put("schemaVersion", 1)
                .put("createdAtEpochMs", System.currentTimeMillis())
                .put("sourceLabel", result.sourceLabel)
                .put("sceneCount", result.sceneCount)
                .put("narrationLocale", result.narrationLocale)
                .put("captionLocale", result.captionLocale)
                .put("audioDurationMs", result.audioDurationMs)
                .put("hookDurationMs", result.hookDurationMs)
                .put("narrationText", result.narrationText)
                .put("audioDescription", result.audioDescription)
                .put("backgroundMusicIncluded", result.backgroundMusicIncluded)
                .put("thumbnailAltText", result.thumbnailAltText)
                .put("attributionManifestComplete", result.attributionManifestComplete)
                .put("attributionCredits", result.attributionCredits)
                .put("researchCredits", result.researchCredits)
            val assets = JSONArray()
            result.attributionManifest.forEach { asset ->
                assets.put(
                    JSONObject()
                        .put("sceneIndex", asset.sceneIndex)
                        .put("sceneId", asset.sceneId)
                        .put("assetId", asset.assetId)
                        .put("sha256", asset.sha256)
                        .put("sourceUrl", asset.sourceUrl)
                        .put("creator", asset.creator)
                        .put("licenseId", asset.licenseId)
                        .put("licenseVersion", asset.licenseVersion)
                        .put("licenseUrl", asset.licenseUrl)
                        .put("attributionText", asset.attributionText)
                        .put("retrievedAtEpochMs", asset.retrievedAtEpochMs)
                        .put("cacheKey", asset.cacheKey)
                        .put("cacheMetadata", asset.cacheMetadata)
                        .put("commercialUseAllowed", asset.commercialUseAllowed)
                        .put("derivativesAllowed", asset.derivativesAllowed)
                        .put("shareAlikeRequired", asset.shareAlikeRequired)
                        .put("requiredOutputLicense", asset.requiredOutputLicense ?: JSONObject.NULL)
                )
            }
            manifest.put("attributionManifest", assets)
            val claims = JSONArray()
            result.claimSources.forEach { claim ->
                claims.put(
                    JSONObject()
                        .put("claimId", claim.claimId)
                        .put("sceneId", claim.sceneId)
                        .put("sourceUrl", claim.sourceUrl)
                        .put("publisher", claim.publisher)
                        .put("checkedOn", claim.checkedOn)
                        .put("supportedClaim", claim.supportedClaim)
                        .put("uncertaintyNote", claim.uncertaintyNote ?: JSONObject.NULL)
                )
            }
            manifest.put("claimSources", claims)
            File(context.filesDir, "ci-video-attribution.json").writeText(manifest.toString(2), Charsets.UTF_8)
            File(context.filesDir, "ci-video-audio-status.txt").writeText("VIDEO-NARRATED-PIPELINE-OK\n", Charsets.UTF_8)

            assertTrue(File(context.filesDir, "ci-video-artifact.mp4").length() > 4_096L)
            assertTrue(File(context.filesDir, "ci-video-artifact.srt").length() > 0L)
            assertTrue(File(context.filesDir, "ci-video-thumbnail.jpg").length() > 1_024L)
            assertTrue(File(context.filesDir, "ci-video-attribution.json").length() > 1_024L)
        } finally {
            runCatching { context.contentResolver.delete(result.exportedUri, null, null) }
            runCatching { context.contentResolver.delete(result.captionsUri, null, null) }
            result.thumbnailUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            runCatching { result.video.file.delete() }
            runCatching { result.captionsFile.delete() }
        }
    }

    private fun copyContent(context: android.content.Context, uri: android.net.Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw AssertionError("MediaStore content URI could not be opened: $uri")
        input.use { source ->
            destination.outputStream().use { output -> source.copyTo(output) }
        }
    }
}
