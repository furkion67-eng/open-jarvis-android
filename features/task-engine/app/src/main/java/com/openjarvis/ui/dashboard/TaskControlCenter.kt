package com.openjarvis.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openjarvis.tasks.JarvisTaskCheckpoint
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskRepository
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VIDEO_TASK_EXECUTION_MODE
import com.openjarvis.ui.theme.VoidColor
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun TaskControlCenter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repositoryResult = remember(context) {
        runCatching { JarvisTaskRepository.getInstance(context.applicationContext) }
    }

    if (repositoryResult.isFailure) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = VoidColor.Void900),
            shape = RoundedCornerShape(22.dp)
        ) {
            Text(
                text = "Görev hafızası açılamadı: ${repositoryResult.exceptionOrNull()?.localizedMessage ?: "Bilinmeyen hata"}",
                color = VoidColor.Red,
                modifier = Modifier.padding(18.dp)
            )
        }
        return
    }

    val repository = repositoryResult.getOrThrow()
    val tasks by repository.observeRecentTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var goal by rememberSaveable { mutableStateOf("") }
    var submissionKey by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        repository.recoverInterruptedTasks()
    }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            notice = null
            try {
                action()
            } catch (error: Exception) {
                notice = error.localizedMessage ?: "İşlem tamamlanamadı."
            } finally {
                busy = false
            }
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = VoidColor.Void900),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, VoidColor.BorderGlow.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Görev kontrol merkezi",
                color = VoidColor.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Yerel görev hafızası açık • Bulut yürütücüsü bağlı değil",
                color = VoidColor.Amber,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = goal,
                onValueChange = { value ->
                    goal = value.take(1000)
                    submissionKey = UUID.randomUUID().toString()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Yeni görev") },
                placeholder = { Text("Örn. Sakarya'daki etkinlikleri araştır ve özetle") },
                minLines = 2,
                maxLines = 4,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
            Button(
                onClick = {
                    val requestedGoal = goal.trim()
                    val requestKey = submissionKey
                    if (requestedGoal.isNotEmpty()) {
                        runAction {
                            repository.createTask(requestedGoal, requestKey)
                            goal = ""
                            submissionKey = UUID.randomUUID().toString()
                        }
                    }
                },
                enabled = goal.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Kaydediliyor…" else "Görevi kaydet ve planla")
            }
            notice?.let {
                Text(text = it, color = VoidColor.Red, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Son görevler",
                color = VoidColor.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (tasks.isEmpty()) {
                Text(
                    text = "Henüz görev yok. Yeni bir istek yazarak yerel plan taslağı oluşturabilirsin.",
                    color = VoidColor.TextDisabled,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                tasks.distinctBy { it.id }.forEach { task ->
                    TaskItem(
                        task = task,
                        enabled = !busy,
                        onAcceptPlan = { runAction { repository.acceptPlan(task.id) } },
                        onRetry = { runAction { repository.retry(task.id) } },
                        onCancel = { runAction { repository.cancel(task.id) } }
                    )
                }
            }
        }
    }
}


internal enum class TaskReviewUiState {
    PLAN_REVIEW,
    ACTION_APPROVAL_REQUIRED,
    EXECUTOR_UNAVAILABLE,
    INTERRUPTED,
    NONE
}

internal fun shouldShowDeterminateProgress(task: JarvisTaskEntity): Boolean =
    task.executionMode != "local_plan_preview" &&
        task.executionMode != VIDEO_TASK_EXECUTION_MODE &&
        taskReviewUiState(task) != TaskReviewUiState.EXECUTOR_UNAVAILABLE &&
        taskReviewUiState(task) != TaskReviewUiState.INTERRUPTED

internal fun shouldShowIndeterminateProgress(task: JarvisTaskEntity): Boolean =
    task.executionMode == VIDEO_TASK_EXECUTION_MODE &&
        JarvisTaskStatus.fromStorage(task.status) == JarvisTaskStatus.RUNNING

internal fun taskReviewUiState(task: JarvisTaskEntity): TaskReviewUiState {
    val status = JarvisTaskStatus.fromStorage(task.status)
    return when {
        status == JarvisTaskStatus.WAITING_FOR_USER &&
            task.checkpoint == JarvisTaskCheckpoint.PLAN_CREATED &&
            task.planAcceptedAt == null -> TaskReviewUiState.PLAN_REVIEW

        status == JarvisTaskStatus.WAITING_FOR_USER &&
            task.planAcceptedAt != null &&
            task.checkpoint == JarvisTaskCheckpoint.PLAN_REVIEWED &&
            (task.errorCode == "APPROVAL_REQUIRED" || (task.requiresApproval && !task.approvalGranted)) ->
            TaskReviewUiState.ACTION_APPROVAL_REQUIRED

        status == JarvisTaskStatus.INTERRUPTED ||
            (status == JarvisTaskStatus.WAITING_FOR_NETWORK && task.errorCode == "APP_RESTARTED") ->
            TaskReviewUiState.INTERRUPTED

        status == JarvisTaskStatus.NOT_EXECUTED ||
            (status == JarvisTaskStatus.WAITING_FOR_NETWORK && task.errorCode == "EXECUTOR_NOT_CONFIGURED") ->
            TaskReviewUiState.EXECUTOR_UNAVAILABLE

        else -> TaskReviewUiState.NONE
    }
}

@Composable
internal fun TaskItem(
    task: JarvisTaskEntity,
    enabled: Boolean,
    onAcceptPlan: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val status = JarvisTaskStatus.fromStorage(task.status)
    val reviewState = taskReviewUiState(task)
    Card(
        colors = CardDefaults.cardColors(containerColor = VoidColor.Void950),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, VoidColor.BorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(task.goal, color = VoidColor.TextPrimary, fontWeight = FontWeight.Medium)
            Text(
                text = when {
                    status == JarvisTaskStatus.NOT_EXECUTED ||
                        reviewState == TaskReviewUiState.EXECUTOR_UNAVAILABLE -> "Yürütülmedi · yürütücü yok"
                    status == JarvisTaskStatus.INTERRUPTED ||
                        reviewState == TaskReviewUiState.INTERRUPTED -> "Yürütülmedi · güvenli yeniden deneme bekliyor"
                    task.executionMode == "local_plan_preview" -> "${status.turkishLabel} · önizleme"
                    task.executionMode == VIDEO_TASK_EXECUTION_MODE && status == JarvisTaskStatus.WAITING_FOR_NETWORK -> "Yürütülmedi · güvenli yeniden deneme bekliyor"
                    task.executionMode == VIDEO_TASK_EXECUTION_MODE && status == JarvisTaskStatus.RUNNING -> "Türkçe video oluşturuluyor"
                    task.executionMode == VIDEO_TASK_EXECUTION_MODE && status == JarvisTaskStatus.SUCCEEDED -> "Türkçe sesli video hazır"
                    task.executionMode == VIDEO_TASK_EXECUTION_MODE && status == JarvisTaskStatus.FAILED -> "Türkçe video oluşturulamadı"
                    else -> "${status.turkishLabel} • ${task.progressPercent}%"
                },
                color = if (status == JarvisTaskStatus.FAILED) VoidColor.Red else VoidColor.Cyan,
                style = MaterialTheme.typography.labelMedium
            )
            if (task.executionMode == "local_plan_preview") {
                Text(
                    "Önizleme · gerçek cihaz veya harici işlem yapılmadı",
                    color = VoidColor.Amber,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (shouldShowDeterminateProgress(task)) {
                LinearProgressIndicator(
                    progress = task.progressPercent.coerceIn(0, 100) / 100f,
                    modifier = Modifier.fillMaxWidth().testTag("task-progress-indicator")
                )
            } else if (shouldShowIndeterminateProgress(task)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().testTag("task-progress-indeterminate")
                )
            }
            task.currentStep?.let {
                Text(it, color = VoidColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            task.outputSummary?.let {
                Text(it, color = VoidColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            task.errorMessage?.let {
                Text(it, color = VoidColor.Amber, style = MaterialTheme.typography.bodySmall)
            }
            when (reviewState) {
                TaskReviewUiState.PLAN_REVIEW -> TextButton(onClick = onAcceptPlan, enabled = enabled) {
                    Text("Planı kabul et")
                }
                TaskReviewUiState.ACTION_APPROVAL_REQUIRED -> Text(
                    "Plan onaylandı; dış işlem için ayrıca onay gerekiyor. Bu işlem yürütülmedi.",
                    color = VoidColor.Amber,
                    style = MaterialTheme.typography.bodySmall
                )
                TaskReviewUiState.EXECUTOR_UNAVAILABLE -> Text(
                    "Bu görev yalnızca yerel plan taslağıdır; yürütücü yapılandırılmadığı için çalıştırılmadı.",
                    color = VoidColor.Amber,
                    style = MaterialTheme.typography.bodySmall
                )
                TaskReviewUiState.INTERRUPTED -> Text(
                    "Uygulama yeniden başlatıldığı için görev otomatik yürütülmedi. Sonucu kontrol edip güvenli biçimde yeniden deneyebilirsin.",
                    color = VoidColor.Amber,
                    style = MaterialTheme.typography.bodySmall
                )
                TaskReviewUiState.NONE -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if ((status == JarvisTaskStatus.WAITING_FOR_NETWORK &&
                        reviewState != TaskReviewUiState.EXECUTOR_UNAVAILABLE) ||
                    status == JarvisTaskStatus.NOT_EXECUTED ||
                    status == JarvisTaskStatus.INTERRUPTED ||
                    status == JarvisTaskStatus.FAILED
                ) {
                    TextButton(onClick = onRetry, enabled = enabled) {
                        Text("Yeniden dene")
                    }
                }
                if (!status.isFinal && status != JarvisTaskStatus.FAILED) {
                    TextButton(onClick = onCancel, enabled = enabled) {
                        Text("İptal et", color = VoidColor.Red)
                    }
                }
                if (status == JarvisTaskStatus.FAILED) {
                    TextButton(onClick = onCancel, enabled = enabled) {
                        Text("Kapat", color = VoidColor.Red)
                    }
                }
            }
        }
    }
}
