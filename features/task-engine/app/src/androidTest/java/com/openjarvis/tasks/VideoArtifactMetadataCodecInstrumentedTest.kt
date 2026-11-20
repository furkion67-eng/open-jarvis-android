package com.openjarvis.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoArtifactMetadataCodecInstrumentedTest {
    @Test
    fun typedMetadataAndCompleteAttributionManifestRoundTrip() {
        val attribution = VideoSceneAssetAttribution(
            sceneIndex = 0,
            sceneId = "scene-1",
            assetId = "asset-1",
            sha256 = "a".repeat(64),
            sourceUrl = "https://assets.example.test/scene-1.png",
            creator = "Example Creator",
            licenseId = "CC-BY-4.0",
            licenseVersion = "4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            attributionText = "Example Creator, CC BY 4.0",
            retrievedAtEpochMs = 1_800_000_000_000L,
            cacheKey = "asset-1-a",
            cacheMetadata = "etag=example"
        )
        val metadata = VideoArtifactMetadata(
            durationMs = 54_583L,
            width = 360,
            height = 640,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR",
            sceneCount = 1,
            sourceLabel = "verified test fixture",
            attributionManifest = listOf(attribution),
            attributionManifestComplete = true
        )

        val restored = VideoArtifactMetadataCodec.decode(VideoArtifactMetadataCodec.encode(metadata))

        assertEquals(metadata, restored)
        assertTrue(restored!!.attributionManifestComplete)
        assertTrue(restored.attributionManifest.single().isValid())
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
            sourceLabel = "procedural local scenes",
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
}
