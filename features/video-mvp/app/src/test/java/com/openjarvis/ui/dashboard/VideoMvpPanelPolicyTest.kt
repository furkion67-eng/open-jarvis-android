package com.openjarvis.ui.dashboard

import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMvpPanelPolicyTest {
    @Test
    fun missingExecutorIsNotPresentedAsRetryable() {
        assertFalse(videoTask(JarvisTaskStatus.NOT_EXECUTED, "EXECUTOR_NOT_CONFIGURED").let(::shouldOfferVideoTaskRetry))
    }

    @Test
    fun legacyNetworkWaitWithMissingExecutorIsNotPresentedAsRetryable() {
        assertFalse(videoTask(JarvisTaskStatus.WAITING_FOR_NETWORK, "EXECUTOR_NOT_CONFIGURED").let(::shouldOfferVideoTaskRetry))
    }

    @Test
    fun interruptedTaskOffersOnlyExplicitManualRetry() {
        assertTrue(videoTask(JarvisTaskStatus.INTERRUPTED, "APP_RESTARTED").let(::shouldOfferVideoTaskRetry))
    }

    @Test
    fun ordinaryNetworkWaitOffersExplicitManualRetry() {
        assertTrue(videoTask(JarvisTaskStatus.WAITING_FOR_NETWORK, "NETWORK_UNAVAILABLE").let(::shouldOfferVideoTaskRetry))
    }

    @Test
    fun failedTaskOffersExplicitManualRetry() {
        assertTrue(videoTask(JarvisTaskStatus.FAILED, "EXECUTION_FAILED").let(::shouldOfferVideoTaskRetry))
    }

    @Test
    fun successfulAndCancelledTasksDoNotOfferRetry() {
        assertFalse(videoTask(JarvisTaskStatus.SUCCEEDED, null).let(::shouldOfferVideoTaskRetry))
        assertFalse(videoTask(JarvisTaskStatus.CANCELLED, null).let(::shouldOfferVideoTaskRetry))
    }

    private fun videoTask(status: JarvisTaskStatus, errorCode: String?) = JarvisTaskEntity(
        id = "video-task",
        goal = "İstanbul'un Fethi hakkında video oluştur",
        status = status.storageValue,
        errorCode = errorCode,
        executionMode = "local.video-render",
        idempotencyKey = "video-task-key"
    )
}
