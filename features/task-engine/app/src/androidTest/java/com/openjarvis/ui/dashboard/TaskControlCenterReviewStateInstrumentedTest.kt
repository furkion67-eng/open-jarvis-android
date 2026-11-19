package com.openjarvis.ui.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjarvis.tasks.JarvisTaskCheckpoint
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VIDEO_TASK_EXECUTION_MODE
import org.junit.Assert.assertTrue
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

        assertTextAbsent("Planı kabul et")
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
    fun missingExecutorIsPersistedNotExecutedAndAllowsExplicitRecheck() {
        val task = task(
            status = JarvisTaskStatus.NOT_EXECUTED,
            errorCode = "EXECUTOR_NOT_CONFIGURED"
        )
        composeRule.setContent {
            MaterialTheme {
                TaskItem(task, enabled = true, onAcceptPlan = {}, onRetry = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Yürütülmedi · yürütücü yok").assertIsDisplayed()
        assertTextAbsent("25%")
        assertTrue(composeRule.onAllNodesWithTag("task-progress-indicator").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText(
            "Bu görev yalnızca yerel plan taslağıdır; yürütücü yapılandırılmadığı için çalıştırılmadı."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Yeniden dene").assertIsDisplayed()
    }

    @Test
    fun activeVideoRenderShowsIndeterminateProgressWithoutPercentage() {
        val task = task(
            status = JarvisTaskStatus.RUNNING,
            executionMode = VIDEO_TASK_EXECUTION_MODE
        )
        composeRule.setContent {
            MaterialTheme {
                TaskItem(task, enabled = true, onAcceptPlan = {}, onRetry = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Türkçe video oluşturuluyor").assertIsDisplayed()
        assertTextAbsent("50%")
        assertTrue(composeRule.onAllNodesWithTag("task-progress-indeterminate").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithTag("task-progress-indicator").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun interruptedVideoTaskShowsNotExecutedWithoutPercentOrProgressBar() {
        val task = task(
            status = JarvisTaskStatus.INTERRUPTED,
            errorCode = "APP_RESTARTED",
            executionMode = VIDEO_TASK_EXECUTION_MODE
        )
        composeRule.setContent {
            MaterialTheme {
                TaskItem(task, enabled = true, onAcceptPlan = {}, onRetry = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Yürütülmedi · güvenli yeniden deneme bekliyor").assertIsDisplayed()
        assertTextAbsent("25%")
        assertTrue(composeRule.onAllNodesWithTag("task-progress-indicator").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("task-progress-indeterminate").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun interruptedExternalTaskShowsNotExecutedWithoutStalePercentOrProgressBar() {
        val task = task(
            status = JarvisTaskStatus.INTERRUPTED,
            errorCode = "APP_RESTARTED",
            executionMode = "remote.device-action",
            progressPercent = 65
        )
        composeRule.setContent {
            MaterialTheme {
                TaskItem(task, enabled = true, onAcceptPlan = {}, onRetry = {}, onCancel = {})
            }
        }

        composeRule.onNodeWithText("Yürütülmedi · güvenli yeniden deneme bekliyor").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Uygulama yeniden başlatıldığı için görev otomatik yürütülmedi. Sonucu kontrol edip güvenli biçimde yeniden deneyebilirsin."
        ).assertIsDisplayed()
        assertTextAbsent("65%")
        assertTrue(composeRule.onAllNodesWithTag("task-progress-indicator").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("task-progress-indeterminate").fetchSemanticsNodes().isEmpty())
    }

    private fun assertTextAbsent(text: String) {
        assertTrue(
            "Expected no semantics node with text: $text",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        )
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
