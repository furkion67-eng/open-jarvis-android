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
    var repository by remember(context) { mutableStateOf<JarvisTaskRepository?>(null) }
    var repositoryError by remember(context) { mutableStateOf<String?>(null) }
    var repositoryLoading by remember(context) { mutableStateOf(true) }
    var repositoryLoadAttempt by remember(context) { mutableStateOf(0) }

    LaunchedEffect(context, repositoryLoadAttempt) {
        repositoryLoading = true
        repositoryError = null
        try {
            repository = JarvisTaskRepository.getInstanceOnIo(context.applicationContext)
        } catch (error: Exception) {
            repositoryError = error.localizedMessage ?: "Bilinmeyen hata"
        } finally {
            repositoryLoading = false
        }
    }

    if (repository == null) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = VoidColor.Void900),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (repositoryLoading) {
                        "Görev hafızası arka planda yükleniyor…"
                    } else {
                        "Görev hafızası açılamadı: " + (repositoryError ?: "Bilinmeyen hata")
                    },
                    color = if (repositoryLoading) VoidColor.TextSecondary else VoidColor.Red
                )
                if (!repositoryLoading) {
                    TextButton(onClick = { repositoryLoadAttempt++ }) {
                        Text("Yeniden dene")
                    }
                }
            }
        }
        return
    }

    val activeRepository = requireNotNull(repository)
    val tasksFlow = remember(activeRepository) { activeRepository.observeRecentTasks() }
    val tasks by tasksFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var goal by rememberSaveable { mutableStateOf("") }
    var submissionKey by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

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
                            activeRepository.createTask(requestedGoal, requestKey)
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
                        onAcceptPlan = { runAction { activeRepository.acceptPlan(task.id) } },
                        onRetry = { runAction { activeRepository.retry(task.id) } },
                        onCancel = { runAction { activeRepository.cancel(task.id) } }
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

internal fun shouldShowDeterminateProgress(task: JarvisTaskEntity): Boolean {
    // No executor currently reports measured intermediate progress. Only a verified terminal
    // success may display a determinate 100% bar; waiting/running states must not invent a percent.
    return JarvisTaskStatus.fromStorage(task.status) == JarvisTaskStatus.SUCCEEDED &&
        task.progressPercent >= 100 &&
        task.executionMode != "local_plan_preview" &&
        task.executionMode != VIDEO_TASK_EXECUTION_MODE
}

internal fun taskStatusLabel(task: JarvisTaskEntity): String {
    val status = JarvisTaskStatus.fromStorage(task.status)
    val reviewState = taskReviewUiState(task)
    return when {
        status == JarvisTaskStatus.NOT_EXECUTED ||
            reviewState == TaskReviewUiState.EXECUTOR_UNAVAILABLE -> "Yürütülmedi · yürütücü yok"
        status == JarvisTaskStatus.INTERRUPTED ||
            reviewState == TaskReviewUiState.INTERRUPTED -> "Yürütülmedi · güvenli yeniden deneme bekliyor"
        reviewState == TaskReviewUiState.ACTION_APPROVAL_REQUIRED ->
            "Harici işlem onayı bekleniyor · yürütülmedi"
        reviewState == TaskReviewUiState.PLAN_REVIEW && task.executionMode != "local_plan_preview" ->
            "Plan onayı bekleniyor · yürütülmedi"
        status == JarvisTaskStatus.FAILED && task.executionMode == VIDEO_TASK_EXECUTION_MODE ->
            "Türkçe video oluşturulamadı"
        status == JarvisTaskStatus.FAILED -> status.turkishLabel
        status == JarvisTaskStatus.CANCELLED -> status.turkishLabel
        task.executionMode == "local_plan_preview" -> "${status.turkishLabel} · önizleme"
        task.executionMode == VIDEO_TASK_EXECUTION_MODE && status == JarvisTaskStatus.WAITING_FOR_NETWORK ->
            "Yürütülmedi · güvenli yeniden deneme bekliyor"
        task.executionMode == VIDEO_TASK_EXECUTION_MODE && status == JarvisTaskStatus.RUNNING ->
            "Türkçe video oluşturuluyor"
        task.executionMode == VIDEO_TASK_EXECUTION_MODE && status == JarvisTaskStatus.SUCCEEDED ->
            "Türkçe sesli video hazır"
        else -> status.turkishLabel
    }
}

internal fun shouldShowIndeterminateProgress(task: JarvisTaskEntity): Boolean {
    val status = JarvisTaskStatus.fromStorage(task.status)
    // Planning and execution are active work, but there is no measured percentage yet.
    return status == JarvisTaskStatus.PLANNING || status == JarvisTaskStatus.RUNNING
}

/** A missing executor is a configuration state, not an actionable retry. */
internal fun shouldOfferRetry(task: JarvisTaskEntity): Boolean {
    val status = JarvisTaskStatus.fromStorage(task.status)
    if (taskReviewUiState(task) == TaskReviewUiState.EXECUTOR_UNAVAILABLE) return false
    return status == JarvisTaskStatus.WAITING_FOR_NETWORK ||
        status == JarvisTaskStatus.INTERRUPTED ||
        status == JarvisTaskStatus.FAILED
}

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
                text = taskStatusLabel(task),
                color = when {
                    status == JarvisTaskStatus.FAILED -> VoidColor.Red
                    reviewState == TaskReviewUiState.ACTION_APPROVAL_REQUIRED ||
                        reviewState == TaskReviewUiState.PLAN_REVIEW ||
                        reviewState == TaskReviewUiState.EXECUTOR_UNAVAILABLE ||
                        reviewState == TaskReviewUiState.INTERRUPTED -> VoidColor.Amber
                    else -> VoidColor.Cyan
                },
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
                if (shouldOfferRetry(task)) {
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
