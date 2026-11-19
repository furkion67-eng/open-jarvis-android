package com.openjarvis.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadServiceLifecycleTest {

    @Test
    fun missingTierIsRejectedBeforeAnyDownloadCanStart() {
        val result = DownloadServiceCommandParser.parse(
            DownloadService.ACTION_DOWNLOAD,
            tierName = null
        )
        assertEquals(
            DownloadServiceCommand.Reject(RejectReason.MISSING_TIER),
            result
        )
    }

    @Test
    fun invalidTierIsRejectedBeforeAnyDownloadCanStart() {
        val result = DownloadServiceCommandParser.parse(
            DownloadService.ACTION_DOWNLOAD,
            tierName = "Ultra Secret Tier"
        )
        assertEquals(
            DownloadServiceCommand.Reject(RejectReason.INVALID_TIER),
            result
        )
    }

    @Test
    fun unknownAndNullActionsAreRejectedDeterministically() {
        assertEquals(
            DownloadServiceCommand.Reject(RejectReason.UNKNOWN_ACTION),
            DownloadServiceCommandParser.parse("com.openjarvis.UNKNOWN", null)
        )
        assertEquals(
            DownloadServiceCommand.Reject(RejectReason.UNKNOWN_ACTION),
            DownloadServiceCommandParser.parse(null, null)
        )
    }

    @Test
    fun validDownloadActionResolvesOnlyADeclaredModelTier() {
        val result = DownloadServiceCommandParser.parse(
            DownloadService.ACTION_DOWNLOAD,
            ModelManager.ModelTier.MINIMUM.displayName
        )
        assertEquals(
            DownloadServiceCommand.Download(ModelManager.ModelTier.MINIMUM),
            result
        )
    }

    @Test
    fun cancelBeforeAnyDownloadInvalidatesTheRunWithoutAnOwner() {
        val guard = DownloadServiceRunGuard()
        val cancelToken = guard.cancel(startId = 10)
        assertFalse(guard.owns(cancelToken - 1, startId = 9))
        assertFalse(guard.owns(cancelToken, startId = 9))
    }

    @Test
    fun olderCompletionCannotStopANewerStartId() {
        val guard = DownloadServiceRunGuard()
        val olderToken = guard.begin(startId = 20)
        val newerToken = guard.begin(startId = 21)

        assertFalse(guard.owns(olderToken, startId = 20))
        assertTrue(guard.owns(newerToken, startId = 21))
    }

    @Test
    fun cancelFollowedByNewDownloadLeavesOnlyTheNewRunAsOwner() {
        val guard = DownloadServiceRunGuard()
        val firstToken = guard.begin(startId = 30)
        guard.cancel(startId = 31)
        val afterCancelToken = guard.begin(startId = 32)

        assertFalse(guard.owns(firstToken, startId = 30))
        assertTrue(guard.owns(afterCancelToken, startId = 32))
    }
}
