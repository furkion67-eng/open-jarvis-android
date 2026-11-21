package com.openjarvis.video.assets

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoVisualAssetsTest {
    private val now = 1_800_000_000_000L
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 4, 5)

    @Test
    fun missingUnknownNcAndNdLicensesFailBeforeDownload() {
        val downloader = FakeDownloader(jpeg)
        val resolver = VideoVisualAssetResolver(MemoryCache(), downloader, clock = { now })
        val missing = descriptor().copy(licenseId = "", licenseVersion = "", licenseUrl = "")
        val nc = descriptor().copy(licenseId = "CC BY-NC", licenseVersion = "4.0")
        val nd = descriptor().copy(licenseId = "CC BY-ND", licenseVersion = "4.0")

        listOf(missing, nc, nd).forEach { candidate ->
            assertTrue(runCatching { resolver.resolveAll(listOf(candidate)) }.isFailure)
        }
        assertEquals(0, downloader.calls)
    }

    @Test
    fun attributionRequiresCreatorExactSourceAndShareAlikeObligation() {
        val missingCreator = descriptor().copy(creator = "")
        val missingSource = descriptor().copy(sourceUrl = "https://commons.wikimedia.org/wiki/File:Example.jpg")
        val missingShareAlike = descriptor().copy(requiredOutputLicense = null)
        val missingLicenseVersion = descriptor().copy(
            attributionText = descriptor().attributionText.replace("CC BY-SA 4.0", "CC BY-SA")
        )

        val wrongSourceHost = descriptor().copy(
            sourceUrl = "https://commons.wikimedia.org.evil.example/w/index.php?title=File:Example.jpg&oldid=123456"
        )
        val wrongDownloadHost = descriptor().copy(downloadUrl = "https://evil.example/example.jpg")
        listOf(wrongSourceHost, wrongDownloadHost).forEach { candidate ->
            assertTrue(runCatching { VideoAssetLicensePolicy.validate(candidate) }.isFailure)
        }
        listOf(missingCreator, missingSource, missingShareAlike, missingLicenseVersion).forEach { candidate ->
            assertTrue(runCatching { VideoAssetLicensePolicy.validate(candidate) }.isFailure)
        }
    }

    @Test
    fun forgedAttributionCannotMarkNcLicenseAsCommercial() {
        val source = "https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456"
        val forged = VideoAssetAttribution(
            sceneIndex = 0,
            sceneId = "scene-01",
            assetId = "asset-one",
            sha256 = "a".repeat(64),
            sourceUrl = source,
            creator = "Example Creator",
            licenseId = "CC BY-NC",
            licenseVersion = "4.0",
            licenseUrl = "https://creativecommons.org/licenses/by-nc/4.0/",
            attributionText = "Example Creator; CC BY-NC; https://creativecommons.org/licenses/by-nc/4.0/; " + source,
            retrievedAtEpochMs = now,
            cacheKey = "b".repeat(64),
            cacheMetadata = "provider=WikimediaCommons;mime=image/jpeg;bytes=8",
            commercialUseAllowed = true,
            derivativesAllowed = true,
            shareAlikeRequired = false,
            requiredOutputLicense = null
        )
        assertFalse(forged.isValid())
    }

    @Test
    fun verifiedAssetIsCachedWithShaAndCompleteAttribution() {
        val cache = MemoryCache()
        val downloader = FakeDownloader(jpeg)
        val resolver = VideoVisualAssetResolver(cache, downloader, clock = { now })
        val resolved = resolver.resolveAll(listOf(descriptor())).single()

        assertTrue(resolved.file.isFile)
        assertEquals(0, resolved.attribution.sceneIndex)
        assertEquals("scene-01", resolved.attribution.sceneId)
        assertTrue(resolved.attribution.sha256.matches(Regex("[a-f0-9]{64}")))
        assertTrue(resolved.attribution.attributionText.contains(resolved.attribution.sourceUrl))
        assertTrue(resolved.attribution.attributionText.contains(resolved.attribution.licenseUrl))
        assertEquals("CC BY-SA", resolved.attribution.licenseId)
        assertTrue(resolved.attribution.shareAlikeRequired)
        assertEquals("CC BY-SA 4.0", resolved.attribution.requiredOutputLicense)
        assertTrue(resolved.attribution.cacheMetadata.contains("expiresAt"))
        assertEquals(1, downloader.calls)
    }

    @Test
    fun cacheHitAvoidsSecondNetworkRequest() {
        val cache = MemoryCache()
        val downloader = FakeDownloader(jpeg)
        val resolver = VideoVisualAssetResolver(cache, downloader, clock = { now })
        val first = resolver.resolveAll(listOf(descriptor())).single()
        val second = resolver.resolveAll(listOf(descriptor())).single()
        assertEquals(first.attribution.sha256, second.attribution.sha256)
        assertEquals(1, downloader.calls)
    }

    @Test
    fun networkFailureWithoutValidCacheFailsClosed() {
        val downloader = FakeDownloader(jpeg).apply { fail = true }
        val resolver = VideoVisualAssetResolver(MemoryCache(), downloader, clock = { now })
        assertTrue(runCatching { resolver.resolveAll(listOf(descriptor())) }.isFailure)
        assertEquals(1, downloader.calls)
    }


    @Test
    fun validCacheCanBeReusedWhenNetworkGoesOffline() {
        val cache = MemoryCache()
        val downloader = FakeDownloader(jpeg)
        val resolver = VideoVisualAssetResolver(cache, downloader, clock = { now })
        val first = resolver.resolveAll(listOf(descriptor())).single()
        downloader.fail = true
        val second = resolver.resolveAll(listOf(descriptor())).single()
        assertEquals(first.attribution.sha256, second.attribution.sha256)
        assertEquals(1, downloader.calls)
    }

    @Test
    fun cacheWriteFailureStopsRenderingInsteadOfUsingUntrackedImage() {
        val failingCache = object : VideoVisualAssetCache {
            override fun read(cacheKey: String, nowEpochMs: Long): CachedVisualAsset? = null
            override fun write(
                cacheKey: String,
                bytes: ByteArray,
                mimeType: String,
                contentSha256: String,
                retrievedAtEpochMs: Long,
                expiresAtEpochMs: Long
            ) {
                throw java.io.IOException("disk full")
            }
            override fun cleanup(nowEpochMs: Long) = Unit
        }
        val resolver = VideoVisualAssetResolver(failingCache, FakeDownloader(jpeg), clock = { now })
        assertTrue(runCatching { resolver.resolveAll(listOf(descriptor())) }.isFailure)
    }

    @Test
    fun duplicateAssetIdsOrIdenticalImageHashesAreRejected() {
        val resolver = VideoVisualAssetResolver(MemoryCache(), FakeDownloader(jpeg), clock = { now })
        val sameId = listOf(descriptor(), descriptor().copy(sceneId = "scene-02"))
        assertTrue(runCatching { resolver.resolveAll(sameId) }.isFailure)

        val distinctIds = listOf(
            descriptor(),
            descriptor().copy(assetId = "asset-two", sceneId = "scene-02")
        )
        assertTrue(runCatching { resolver.resolveAll(distinctIds) }.isFailure)
    }

    @Test
    fun fileCacheRejectsFutureRetrievalTimesAndOverlongTtl() {
        val root = Files.createTempDirectory("jarvis-visual-cache-clock").toFile()
        try {
            val cache = FileVideoVisualAssetCache(root)
            val futureKey = "d".repeat(64)
            val hash = sha256(jpeg)
            cache.write(
                futureKey,
                jpeg,
                "image/jpeg",
                hash,
                now + 1_000L,
                now + 2_000L
            )
            assertEquals(null, cache.read(futureKey, now))

            assertTrue(runCatching {
                cache.write(
                    "e".repeat(64),
                    jpeg,
                    "image/jpeg",
                    hash,
                    now,
                    now + VideoAssetLicensePolicy.MAX_CACHE_TTL_MS + 1L
                )
            }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolverRejectsFutureDatedCacheFromAnyCacheProvider() {
        val file = File.createTempFile("jarvis-future-cache-", ".jpg")
        file.writeBytes(jpeg)
        val cache = object : VideoVisualAssetCache {
            override fun read(cacheKey: String, nowEpochMs: Long): CachedVisualAsset =
                CachedVisualAsset(
                    file = file,
                    sha256 = sha256(jpeg),
                    mimeType = "image/jpeg",
                    retrievedAtEpochMs = now + 1L,
                    expiresAtEpochMs = now + 10_000L,
                    byteSize = jpeg.size.toLong()
                )

            override fun write(
                cacheKey: String,
                bytes: ByteArray,
                mimeType: String,
                contentSha256: String,
                retrievedAtEpochMs: Long,
                expiresAtEpochMs: Long
            ) = Unit

            override fun cleanup(nowEpochMs: Long) = Unit
        }
        val downloader = FakeDownloader(jpeg)
        try {
            assertTrue(runCatching {
                VideoVisualAssetResolver(cache, downloader, clock = { now })
                    .resolveAll(listOf(descriptor()))
            }.isFailure)
            assertEquals(0, downloader.calls)
        } finally {
            file.delete()
        }
    }

    @Test
    fun fileCacheExpiresAndEvictsOldestEntriesWithinBounds() {
        val root = Files.createTempDirectory("jarvis-visual-cache").toFile()
        try {
            val cache = FileVideoVisualAssetCache(root, maxEntries = 2, maxBytes = 64L)
            val firstKey = "a".repeat(64)
            val secondKey = "b".repeat(64)
            val thirdKey = "c".repeat(64)
            val hash = sha256(jpeg)
            cache.write(firstKey, jpeg, "image/jpeg", hash, now, now + 10_000L)
            cache.write(secondKey, jpeg, "image/jpeg", hash, now + 1L, now + 10_000L)
            cache.write(thirdKey, jpeg, "image/jpeg", hash, now + 2L, now + 10_000L)
            cache.cleanup(now + 3L)
            val survivors = listOf(firstKey, secondKey, thirdKey).count { cache.read(it, now + 3L) != null }
            assertTrue(survivors <= 2)
            assertEquals(null, cache.read(thirdKey, now + 20_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pinnedCommonsRevisionMustBeNumericUniqueAndOnTheExpectedFilePage() {
        val source = descriptor().sourceUrl
        val invalidSources = listOf(
            source.replace("oldid=123456", "oldid="),
            source.replace("oldid=123456", "oldid=not-a-revision"),
            source + "&oldid=654321",
            source + "#fragment",
            source.replace("https://", "http://"),
            source.replace("/w/index.php?", "/wiki/File:Example.jpg?")
        )
        invalidSources.forEach { invalid ->
            assertTrue(runCatching {
                VideoAssetLicensePolicy.validate(descriptor().copy(sourceUrl = invalid))
            }.isFailure)
        }

        val invalidDownloads = listOf(
            "http://upload.wikimedia.org/wikipedia/commons/a/ab/example.jpg",
            "https://upload.wikimedia.org:444/wikipedia/commons/a/ab/example.jpg",
            "https://user@upload.wikimedia.org/wikipedia/commons/a/ab/example.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/example.jpg#fragment"
        )
        invalidDownloads.forEach { invalid ->
            assertTrue(runCatching {
                VideoAssetLicensePolicy.validate(descriptor().copy(downloadUrl = invalid))
            }.isFailure)
        }
    }

    @Test
    fun resolvedAttributionRejectsMissingCreatorLicenseSourceAndCacheEvidence() {
        val attribution = VideoVisualAssetResolver(
            MemoryCache(),
            FakeDownloader(jpeg),
            clock = { now }
        ).resolveAll(listOf(descriptor())).single().attribution
        assertTrue(attribution.isValid())

        assertFalse(attribution.copy(
            attributionText = attribution.attributionText.replace("Example Creator", "Unknown")
        ).isValid())
        assertFalse(attribution.copy(
            attributionText = attribution.attributionText.replace("CC BY-SA 4.0", "CC BY-SA")
        ).isValid())
        assertFalse(attribution.copy(
            attributionText = attribution.attributionText.replace(attribution.sourceUrl, "")
        ).isValid())
        assertFalse(attribution.copy(
            sourceUrl = attribution.sourceUrl.replace("https://", "http://")
        ).isValid())
        assertFalse(attribution.copy(
            cacheMetadata = "provider=Unverified;mime=image/jpeg;bytes=8;expiresAt=${now + 10_000L}"
        ).isValid())
        assertFalse(attribution.copy(
            cacheMetadata = "provider=WikimediaCommons;mime=image/jpeg;bytes=8;expiresAt=$now"
        ).isValid())
        assertFalse(attribution.copy(
            cacheMetadata = "provider=WikimediaCommons;mime=image/jpeg;bytes=8;expiresAt=${now + VideoVisualAssetResolver.DEFAULT_CACHE_TTL_MS + 1L}"
        ).isValid())
        assertTrue(runCatching {
            VideoVisualAssetResolver(
                MemoryCache(),
                FakeDownloader(jpeg),
                clock = { now },
                cacheTtlMs = VideoVisualAssetResolver.DEFAULT_CACHE_TTL_MS + 1L
            )
        }.isFailure)
    }

    private fun descriptor(): VideoVisualAssetDescriptor {
        val source = "https://commons.wikimedia.org/w/index.php?title=File:Example.jpg&oldid=123456"
        return VideoVisualAssetDescriptor(
            assetId = "asset-one",
            sceneId = "scene-01",
            title = "Example visual",
            downloadUrl = "https://upload.wikimedia.org/wikipedia/commons/a/ab/example.jpg",
            sourceUrl = source,
            creator = "Example Creator",
            licenseId = "CC BY-SA",
            licenseVersion = "4.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
            attributionText = "Example Creator, Example visual; CC BY-SA 4.0. License: https://creativecommons.org/licenses/by-sa/4.0/. Source: " + source,
            expectedMimeType = "image/jpeg",
            shareAlikeRequired = true,
            requiredOutputLicense = "CC BY-SA 4.0"
        )
    }

    private class FakeDownloader(private val bytes: ByteArray) : VideoVisualAssetDownloader {
        var calls = 0
        var fail = false

        override fun download(descriptor: VideoVisualAssetDescriptor): DownloadedVisualAsset {
            calls++
            if (fail) throw java.io.IOException("offline")
            return DownloadedVisualAsset(bytes, descriptor.expectedMimeType, descriptor.downloadUrl)
        }
    }

    private class MemoryCache : VideoVisualAssetCache {
        private val records = mutableMapOf<String, CachedVisualAsset>()

        override fun read(cacheKey: String, nowEpochMs: Long): CachedVisualAsset? {
            val cached = records[cacheKey] ?: return null
            if (cached.expiresAtEpochMs <= nowEpochMs) {
                records.remove(cacheKey)
                cached.file.delete()
                return null
            }
            return cached
        }

        override fun write(
            cacheKey: String,
            bytes: ByteArray,
            mimeType: String,
            contentSha256: String,
            retrievedAtEpochMs: Long,
            expiresAtEpochMs: Long
        ) {
            val file = File.createTempFile("jarvis-asset-", ".img")
            file.writeBytes(bytes)
            records[cacheKey] = CachedVisualAsset(
                file,
                contentSha256,
                mimeType,
                retrievedAtEpochMs,
                expiresAtEpochMs,
                bytes.size.toLong()
            )
        }

        override fun cleanup(nowEpochMs: Long) {
            records.entries.removeAll { entry ->
                if (entry.value.expiresAtEpochMs <= nowEpochMs) {
                    entry.value.file.delete()
                    true
                } else false
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
