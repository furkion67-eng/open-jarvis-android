package com.openjarvis.ui.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjarvis.tasks.JarvisTaskCheckpoint
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskControlCenterReviewStateInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun acceptedPlanShowsSeparateApprovalMessageAndNoPlanAcceptButton() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
            planAcceptedAt = 123L,
            requiresApproval = true,
            approvalGranted = false,
            errorCode = "APPROVAL_REQUIRED"
        )
        composeRule.setContent {
            MaterialTheme {
                TaskItem(task, enabled = true, onAcceptPlan = {}, onRetry = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Planı kabul et").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Plan onaylandı; dış işlem için ayrıca onay gerekiyor. Bu işlem yürütülmedi."
        ).assertIsDisplayed()
    }

    @Test
    fun unacceptedPlanShowsPlanAcceptButton() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_USER,
            checkpoint = JarvisTaskCheckpoint.PLAN_CREATED
        )
        composeRule.setContent {
            MaterialTheme {
                TaskItem(task, enabled = true, onAcceptPlan = {}, onRetry = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Planı kabul et").assertIsDisplayed()
    }

    @Test
    fun missingExecutorIsClearlyNotExecutedAndDoesNotOfferRetry() {
        val task = task(
            status = JarvisTaskStatus.WAITING_FOR_NETWORK,
            errorCode = "EXECUTOR_NOT_CONFIGURED"
        )
        composeRule.setContent {
            MaterialTheme {
                TaskItem(task, enabled = true, onAcceptPlan = {}, onRetry = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Yürütülmedi · yürütücü yok • 25%").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Bu görev yalnızca yerel plan taslağıdır; yürütücü yapılandırılmadığı için çalıştırılmadı."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Yeniden dene").assertDoesNotExist()
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
