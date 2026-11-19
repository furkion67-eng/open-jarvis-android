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
    fun configuredExecutorMissingIsNotPresentedAsRetryableExecution() {
        val task = task(
            status = JarvisTaskStatus.NOT_EXECUTED,
            errorCode = "EXECUTOR_NOT_CONFIGURED"
        )
        assertEquals(TaskReviewUiState.EXECUTOR_UNAVAILABLE, taskReviewUiState(task))
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
