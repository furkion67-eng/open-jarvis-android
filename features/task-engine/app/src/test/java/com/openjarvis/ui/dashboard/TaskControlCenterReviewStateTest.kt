package com.openjarvis.ui.dashboard

import com.openjarvis.tasks.JarvisTaskCheckpoint
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VIDEO_TASK_EXECUTION_MODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskControlCenterReviewStateTest {

    @Test
    fun unacceptedPlanShowsOnlyPlanReviewAction() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_CREATED
        )
        assertEquals(TaskReviewUiState.PLAN_REVIEW, taskReviewUiState(task))
    }

    @Test
    fun acceptedPlanWithMissingExternalApprovalShowsSeparateApprovalState() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = 123L,
            requiresApproval = true,
            approvalGranted = false,
            errorCode = "APPROVAL_REQUIRED"
        )
        assertEquals(TaskReviewUiState.ACTION_APPROVAL_REQUIRED, taskReviewUiState(task))
    }

    @Test
    fun acceptedPlanAwaitingExternalApprovalShowsNoSyntheticProgress() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = 123L,
            requiresApproval = true,
            approvalGranted = false,
            errorCode = "APPROVAL_REQUIRED",
            executionMode = "remote.device-action",
            progressPercent = 25
        )
        assertEquals(TaskReviewUiState.ACTION_APPROVAL_REQUIRED, taskReviewUiState(task))
        assertEquals("Harici işlem onayı bekleniyor · yürütülmedi", taskStatusLabel(task))
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun unacceptedExternalPlanShowsNoExecutionProgressOrPercent() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_CREATED,
            executionMode = "remote.device-action",
            progressPercent = 25
        )
        assertEquals(TaskReviewUiState.PLAN_REVIEW, taskReviewUiState(task))
        assertEquals("Plan onayı bekleniyor · yürütülmedi", taskStatusLabel(task))
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun configuredExecutorMissingIsNotPresentedAsRetryableExecution() {
        val task = task(
            status = JarvisTaskStatus.NOT_EXECUTED,
            errorCode = "EXECUTOR_NOT_CONFIGURED"
        )
        assertEquals(TaskReviewUiState.EXECUTOR_UNAVAILABLE, taskReviewUiState(task))
        assertFalse(shouldOfferRetry(task))
    }

    @Test
    fun interruptedTaskOffersOnlyAnExplicitManualRetry() {
        val task = task(
            status = JarvisTaskStatus.INTERRUPTED,
            errorCode = "APP_RESTARTED",
            executionMode = "remote.device-action"
        )
        assertTrue(shouldOfferRetry(task))
    }

    @Test
    fun ordinaryNetworkWaitOffersAnExplicitManualRetry() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_NETWORK,
            errorCode = "NETWORK_UNAVAILABLE",
            executionMode = "remote.device-action"
        )
        assertTrue(shouldOfferRetry(task))
    }

    @Test
    fun ordinaryNetworkWaitShowsNoSyntheticProgressOrPercent() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_NETWORK,
            errorCode = "NETWORK_UNAVAILABLE",
            executionMode = "remote.device-action",
            progressPercent = 25
        )
        assertEquals("Ağ bağlantısı bekleniyor", taskStatusLabel(task))
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun planningAndGenericExecutionUseIndeterminateProgressUntilMeasured() {
        val planning = task(
            status = JarvisTaskStatus.PLANNING,
            executionMode = "remote.device-action",
            progressPercent = 10
        )
        assertEquals("Planlanıyor", taskStatusLabel(planning))
        assertFalse(shouldShowDeterminateProgress(planning))
        assertTrue(shouldShowIndeterminateProgress(planning))

        val running = task(
            status = JarvisTaskStatus.RUNNING,
            executionMode = "remote.device-action",
            progressPercent = 50
        )
        assertEquals("Çalışıyor", taskStatusLabel(running))
        assertFalse(shouldShowDeterminateProgress(running))
        assertTrue(shouldShowIndeterminateProgress(running))
    }

    @Test
    fun onlyVerifiedGenericSuccessShowsDeterminateCompletion() {
        val task = task(
            status = JarvisTaskStatus.SUCCEEDED,
            executionMode = "remote.device-action",
            progressPercent = 100
        )
        assertEquals("Tamamlandı", taskStatusLabel(task))
        assertTrue(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun localPlanPreviewNeverClaimsDeterminateExecutionProgress() {
        val task = task(
            status = JarvisTaskStatus.NOT_EXECUTED,
            errorCode = "EXECUTOR_NOT_CONFIGURED"
        )
        assertEquals(false, shouldShowDeterminateProgress(task))
    }

    @Test
    fun activeVideoRenderUsesIndeterminateProgressWithoutSyntheticPercent() {
        val task = task(
            status = JarvisTaskStatus.RUNNING,
            executionMode = VIDEO_TASK_EXECUTION_MODE
        )
        assertFalse(shouldShowDeterminateProgress(task))
        assertTrue(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun interruptedVideoTaskShowsNoSyntheticProgress() {
        val task = task(
            status = JarvisTaskStatus.INTERRUPTED,
            errorCode = "APP_RESTARTED",
            executionMode = VIDEO_TASK_EXECUTION_MODE
        )
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun interruptedExternalTaskDoesNotShowStaleDeterminateProgress() {
        val task = task(
            status = JarvisTaskStatus.INTERRUPTED,
            errorCode = "APP_RESTARTED",
            executionMode = "remote.device-action",
            progressPercent = 65
        )
        assertEquals(TaskReviewUiState.INTERRUPTED, taskReviewUiState(task))
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun videoPreviewDoesNotShowExecutionProgressBeforePlanAcceptance() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_CREATED,
            executionMode = "local_plan_preview"
        )
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun legacyWaitingTaskWithoutReviewCheckpointCannotBeAcceptedAgain() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = null,
            planAcceptedAt = 123L,
            errorCode = "APPROVAL_REQUIRED"
        )
        assertEquals(TaskReviewUiState.NONE, taskReviewUiState(task))
    }

    @Test
    fun failedExternalTaskDoesNotShowSyntheticCompletionPercentOrProgressBar() {
        val task = task(
            status = JarvisTaskStatus.FAILED,
            executionMode = "remote.device-action",
            progressPercent = 100
        )
        assertEquals("Başarısız", taskStatusLabel(task))
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
        assertEquals(0, JarvisTaskStatus.FAILED.defaultProgress)
    }

    @Test
    fun failedVideoTaskShowsFailureWithoutCompletionPercentOrProgressBar() {
        val task = task(
            status = JarvisTaskStatus.FAILED,
            executionMode = VIDEO_TASK_EXECUTION_MODE,
            progressPercent = 100
        )
        assertEquals("Türkçe video oluşturulamadı", taskStatusLabel(task))
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    @Test
    fun cancelledTaskDoesNotShowProgressOrCompletionPercent() {
        val task = task(
            status = JarvisTaskStatus.CANCELLED,
            executionMode = "remote.device-action",
            progressPercent = 0
        )
        assertEquals("İptal edildi", taskStatusLabel(task))
        assertFalse(shouldShowDeterminateProgress(task))
        assertFalse(shouldShowIndeterminateProgress(task))
    }

    private fun task(
        status: JarvisTaskStatus,
        checkpoint: String? = null,
        planAcceptedAt: Long? = null,
        requiresApproval: Boolean = false,
        approvalGranted: Boolean = false,
        errorCode: String? = null,
        executionMode: String = "local_plan_preview",
        progressPercent: Int = 0
    ) = JarvisTaskEntity(
        id = "task-1",
        goal = "WhatsApp mesajı gönder",
        status = status.storageValue,
        checkpoint = checkpoint,
        planAcceptedAt = planAcceptedAt,
        requiresApproval = requiresApproval,
        approvalGranted = approvalGranted,
        errorCode = errorCode,
        executionMode = executionMode,
        progressPercent = progressPercent,
        idempotencyKey = "test-key"
    )
}
