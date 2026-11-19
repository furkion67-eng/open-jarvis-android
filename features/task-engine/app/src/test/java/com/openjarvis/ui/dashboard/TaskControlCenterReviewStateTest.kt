package com.openjarvis.ui.dashboard

import com.openjarvis.tasks.JarvisTaskCheckpoint
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import org.junit.Assert.assertEquals
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
            status = JarvisTaskStatus.WAITING_FOR_NETWORK,
            errorCode = "EXECUTOR_NOT_CONFIGURED"
        )
        assertEquals(TaskReviewUiState.EXECUTOR_UNAVAILABLE, taskReviewUiState(task))
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
        errorCode: String? = null
    ) = JarvisTaskEntity(
        id = "task-1",
        goal = "WhatsApp mesajı gönder",
        status = status.storageValue,
        checkpoint = checkpoint,
        planAcceptedAt = planAcceptedAt,
        requiresApproval = requiresApproval,
        approvalGranted = approvalGranted,
        errorCode = errorCode,
        executionMode = "local_plan_preview",
        idempotencyKey = "test-key"
    )
}
