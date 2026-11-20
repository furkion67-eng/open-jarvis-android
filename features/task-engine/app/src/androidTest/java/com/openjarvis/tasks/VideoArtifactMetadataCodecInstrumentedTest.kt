package com.openjarvis.tasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoArtifactMetadataCodecInstrumentedTest {
    @Test
    fun typedRendererMetadataAndCompleteAttributionManifestRoundTrip() {
        val attribution = VideoSceneAssetAttribution(
            sceneIndex = 0,
            sceneId = "scene-1",
            assetId = "asset-1",
            sha256 = "a".repeat(64),
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456",
            creator = "Example Creator",
            licenseId = "CC BY-SA",
            licenseVersion = "4.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
            attributionText = "Example Creator, CC BY-SA 4.0. License: https://creativecommons.org/licenses/by-sa/4.0/. Source: https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456",
            retrievedAtEpochMs = 1_800_000_000_000L,
            cacheKey = "b".repeat(64),
            cacheMetadata = "provider=WikimediaCommons;mime=image/jpeg;bytes=1024;expiresAt=1800000100000",
            commercialUseAllowed = true,
            derivativesAllowed = true,
            shareAlikeRequired = true,
            requiredOutputLicense = "CC BY-SA 4.0"
        )
        val claim = VideoClaimSourceEvidence(
            claimId = "claim-1",
            sceneId = "scene-1",
            sourceUrl = "https://islamansiklopedisi.org.tr/halic",
            publisher = "TDV İslâm Ansiklopedisi",
            checkedOn = "2026-11-20",
            supportedClaim = "1453 kuşatmasında Haliç zinciri ve gemilerin karadan taşınması."
        )
        val metadata = completeMetadata(attribution, claim)

        val restored = VideoArtifactMetadataCodec.decode(VideoArtifactMetadataCodec.encode(metadata))

        assertEquals(metadata, restored)
        assertTrue(restored!!.attributionManifestComplete)
        assertTrue(restored.attributionManifest.single().isValid())
        assertTrue(restored.claimSources.single().isValid())
        assertEquals("content://media/external/images/media/789", restored.thumbnailUri)
        assertEquals("Kapak görseli açıklaması", restored.thumbnailAltText)
    }

    @Test
    fun rendererContractMapsTypedManifestClaimsCreditsAndThumbnail() {
        val attribution = FakeRendererAttribution(
            sceneIndex = 0,
            sceneId = "scene-1",
            assetId = "asset-1",
            sha256 = "a".repeat(64),
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456",
            creator = "Example Creator",
            licenseId = "CC BY-SA",
            licenseVersion = "4.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
            attributionText = "Example Creator, CC BY-SA 4.0. License: https://creativecommons.org/licenses/by-sa/4.0/. Source: https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456",
            retrievedAtEpochMs = 1_800_000_000_000L,
            cacheKey = "b".repeat(64),
            cacheMetadata = "provider=WikimediaCommons;mime=image/jpeg;bytes=1024;expiresAt=1800000100000",
            commercialUseAllowed = true,
            derivativesAllowed = true,
            shareAlikeRequired = true,
            requiredOutputLicense = "CC BY-SA 4.0"
        )
        val claim = FakeRendererClaim(
            claimId = "claim-1",
            sceneId = "scene-1",
            sourceUrl = "https://islamansiklopedisi.org.tr/halic",
            publisher = "TDV İslâm Ansiklopedisi",
            checkedOn = "2026-11-20",
            supportedClaim = "Haliç zinciri ve gemilerin karadan taşınması."
        )
        val result = FakeRendererResult(
            attributionManifest = listOf(attribution),
            attributionManifestComplete = true,
            attributionCredits = attribution.attributionText,
            researchCredits = claim.publisher + " — " + claim.supportedClaim + " — " + claim.sourceUrl,
            claimSources = listOf(claim),
            narrationLocale = "tr-TR",
            captionLocale = "tr-TR",
            hookDurationMs = 1_850L,
            thumbnailUri = "content://media/external/images/media/789",
            thumbnailAltText = "Kapak görseli açıklaması",
            backgroundMusicIncluded = false,
            audioDescription = "tr-TR sistem TTS anlatımı; arka plan müziği eklenmedi."
        )

        val mapped = VideoRendererMetadataAdapter.fromRenderResult(
            renderResult = result,
            durationMs = 54_583L,
            audioDurationMs = 53_900L,
            width = 720,
            height = 1_280,
            frameRate = 24,
            hasAudio = true,
            sceneCount = 1,
            sourceLabel = "Wikimedia Commons"
        )

        assertTrue(mapped.isValid())
        assertEquals("tr-TR", mapped.captionLocale)
        assertEquals(1_850L, mapped.hookDurationMs)
        assertEquals(result.attributionCredits, mapped.attributionCredits)
        assertEquals(result.researchCredits, mapped.researchCredits)
        assertEquals(result.thumbnailUri, mapped.thumbnailUri)
        assertEquals(result.thumbnailAltText, mapped.thumbnailAltText)
        assertEquals(claim.sourceUrl, mapped.claimSources.single().sourceUrl)
        assertEquals(attribution.cacheMetadata, mapped.attributionManifest.single().cacheMetadata)
    }

    @Test
    fun incompleteRendererManifestFailsClosedBeforeTaskSuccess() {
        val result = FakeRendererResult(
            attributionManifest = emptyList(),
            attributionManifestComplete = false,
            attributionCredits = "",
            researchCredits = "",
            claimSources = emptyList(),
            narrationLocale = "tr-TR",
            captionLocale = "tr-TR",
            hookDurationMs = 1_800L,
            thumbnailUri = "content://media/external/images/media/789",
            thumbnailAltText = "Kapak",
            backgroundMusicIncluded = false,
            audioDescription = "tr-TR TTS"
        )
        assertTrue(
            runCatching {
                VideoRendererMetadataAdapter.fromRenderResult(
                    result, 54_583L, 53_900L, 720, 1_280, 24, true, 1, "test"
                )
            }.isFailure
        )
    }

    @Test
    fun completeManifestRejectsUnpinnedVisualsUnmappedClaimsAndNonContentThumbnail() {
        val attribution = VideoSceneAssetAttribution(
            sceneIndex = 0,
            sceneId = "scene-1",
            assetId = "asset-1",
            sha256 = "a".repeat(64),
            sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456",
            creator = "Example Creator",
            licenseId = "CC BY-SA",
            licenseVersion = "4.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
            attributionText = "Example Creator, CC BY-SA 4.0. License: https://creativecommons.org/licenses/by-sa/4.0/. Source: https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456",
            retrievedAtEpochMs = 1_800_000_000_000L,
            cacheKey = "b".repeat(64),
            cacheMetadata = "provider=WikimediaCommons;mime=image/jpeg;bytes=1024;expiresAt=1800000100000",
            commercialUseAllowed = true,
            derivativesAllowed = true,
            shareAlikeRequired = true,
            requiredOutputLicense = "CC BY-SA 4.0"
        )
        val claim = VideoClaimSourceEvidence(
            claimId = "claim-1",
            sceneId = "scene-1",
            sourceUrl = "https://islamansiklopedisi.org.tr/halic",
            publisher = "TDV İslâm Ansiklopedisi",
            checkedOn = "2026-11-20",
            supportedClaim = "Haliç zinciri ve gemilerin karadan taşınması."
        )
        val valid = completeMetadata(attribution, claim)

        assertTrue(valid.isValid())
        assertFalse(
            valid.copy(
                attributionManifest = listOf(attribution.copy(
                    sourceUrl = "https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=0"
                ))
            ).isValid()
        )
        assertFalse(valid.copy(claimSources = listOf(claim.copy(sceneId = "unknown-scene"))).isValid())
        assertFalse(valid.copy(thumbnailUri = "file:///sdcard/cover.jpg").isValid())
    }

    @Test
    fun olderMetadataJsonWithoutRendererFieldsRemainsReadable() {
        val legacy = """{"containerMimeType":"video/mp4","videoTrackMimeType":"video/avc","captionsMimeType":"application/x-subrip","audioTrackMimeType":"audio/mp4a-latm","durationMs":6000,"width":360,"height":640,"frameRate":24,"hasAudio":true,"narrationLocale":"tr-TR","attributionManifest":[],"attributionManifestComplete":false}"""
        val restored = VideoArtifactMetadataCodec.decode(legacy)
        assertNotNull(restored)
        assertFalse(restored!!.attributionManifestComplete)
        assertNull(restored.thumbnailUri)
        assertTrue(restored.claimSources.isEmpty())
    }

    @Test
    fun missingRendererAttributionIsExplicitlyIncompleteNotInvented() {
        val metadata = VideoArtifactMetadata(
            durationMs = 6_000L,
            width = 360,
            height = 640,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR",
            sceneCount = 3,
            sourceLabel = "legacy procedural scenes",
            attributionManifest = emptyList(),
            attributionManifestComplete = false
        )

        val restored = VideoArtifactMetadataCodec.decode(VideoArtifactMetadataCodec.encode(metadata))

        assertNotNull(restored)
        assertTrue(restored!!.attributionManifest.isEmpty())
        assertFalse(restored.attributionManifestComplete)
    }

    @Test
    fun completeManifestCannotBeEncodedWithMissingAttributionEvidence() {
        val metadata = VideoArtifactMetadata(
            durationMs = 6_000L,
            width = 360,
            height = 640,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR",
            attributionManifestComplete = true
        )

        assertFalse(metadata.isValid())
        assertNull(VideoArtifactMetadataCodec.decode("{}"))
    }

    @Test
    fun durableArtifactRequiresBothContentUrisAndMatchingArtifactId() {
        val metadata = VideoArtifactMetadata(
            durationMs = 6_000L,
            width = 360,
            height = 640,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR"
        )
        val valid = PersistedVideoArtifact(
            artifactId = "task-1",
            exportedUri = "content://media/external/video/media/123",
            captionsUri = "content://media/external/downloads/456",
            metadata = metadata
        )
        val invalid = valid.copy(exportedUri = "file:///sdcard/video.mp4")

        assertTrue(valid.isValid())
        assertFalse(invalid.isValid())
        assertFalse(valid.copy(captionsUri = "").isValid())
    }

    private fun completeMetadata(
        attribution: VideoSceneAssetAttribution,
        claim: VideoClaimSourceEvidence
    ) = VideoArtifactMetadata(
        durationMs = 54_583L,
        audioDurationMs = 53_900L,
        width = 720,
        height = 1_280,
        frameRate = 24,
        hasAudio = true,
        narrationLocale = "tr-TR",
        sceneCount = 1,
        sourceLabel = "verified test fixture",
        captionLocale = "tr-TR",
        hookDurationMs = 1_850L,
        thumbnailUri = "content://media/external/images/media/789",
        thumbnailAltText = "Kapak görseli açıklaması",
        backgroundMusicIncluded = false,
        audioDescription = "tr-TR sistem TTS anlatımı; arka plan müziği eklenmedi.",
        attributionCredits = attribution.attributionText,
        researchCredits = claim.publisher + " — " + claim.supportedClaim + " — " + claim.sourceUrl,
        claimSources = listOf(claim),
        attributionManifest = listOf(attribution),
        attributionManifestComplete = true
    )

    private data class FakeRendererAttribution(
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
        fun isValid(): Boolean = true
    }

    private data class FakeRendererClaim(
        val claimId: String,
        val sceneId: String,
        val sourceUrl: String,
        val publisher: String,
        val checkedOn: String,
        val supportedClaim: String,
        val uncertaintyNote: String? = null
    )

    private data class FakeRendererResult(
        val attributionManifest: List<FakeRendererAttribution>,
        val attributionManifestComplete: Boolean,
        val attributionCredits: String,
        val researchCredits: String,
        val claimSources: List<FakeRendererClaim>,
        val narrationLocale: String,
        val captionLocale: String,
        val hookDurationMs: Long,
        val thumbnailUri: String,
        val thumbnailAltText: String,
        val backgroundMusicIncluded: Boolean,
        val audioDescription: String
    )
}
