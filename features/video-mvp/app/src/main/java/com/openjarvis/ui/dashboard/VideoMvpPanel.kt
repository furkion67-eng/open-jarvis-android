package com.openjarvis.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openjarvis.tasks.JarvisTaskRepository
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.JarvisTaskCheckpoint
import kotlinx.coroutines.launch
import java.util.UUID

/** Video creation is always a durable plan -> explicit acceptance -> verified local executor flow. */
@Composable
fun VideoMvpPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { JarvisTaskRepository.getInstance(context) }
    val tasks by repository.observeRecentTasks(limit = 30).collectAsState(initial = emptyList())

    var topic by rememberSaveable { mutableStateOf("İstanbul'un Fethi") }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestKey by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var creating by remember { mutableStateOf(false) }
    var accepting by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("Video konusu gir ve önce plan taslağını oluştur.") }

    val task = tasks.firstOrNull { it.id == taskId }
    val status = task?.let { JarvisTaskStatus.fromStorage(it.status) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Türkçe sesli video üretimi", style = MaterialTheme.typography.titleMedium)
            Text(
                "JARVIS önce kalıcı bir plan hazırlar. Video yalnızca planı açıkça kabul ettiğinde cihazda üretilir; MP4 ve SRT doğrulanmadan görev tamamlandı sayılmaz.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it.take(100) },
                label = { Text("Video konusu") },
                singleLine = true,
                enabled = !creating && !accepting,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = topic.isNotBlank() && !creating && !accepting,
                onClick = {
                    scope.launch {
                        creating = true
                        message = "Kalıcı video planı hazırlanıyor…"
                        try {
                            val id = repository.createTask(
                                goal = topic.trim() + " hakkında video oluştur",
                                idempotencyKey = "video-panel:" + requestKey
                            )
                            taskId = id
                            requestKey = UUID.randomUUID().toString()
                            message = "Plan hazır. Gerçek video üretimi başlamadı; aşağıdaki planı gözden geçirip kabul et."
                        } catch (error: Exception) {
                            message = "Plan oluşturulamadı: " + (error.message ?: "bilinmeyen hata")
                        } finally {
                            creating = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (creating) "Plan hazırlanıyor…" else "Video planı oluştur")
            }

            Text(message, style = MaterialTheme.typography.bodySmall)
            if (task != null && status != null) {
                Text(task.outputSummary.orEmpty(), style = MaterialTheme.typography.bodySmall)
                when (status) {
                    JarvisTaskStatus.WAITING_FOR_USER -> {
                        if (task.checkpoint == JarvisTaskCheckpoint.PLAN_CREATED && task.planAcceptedAt == null) {
                            Button(
                                enabled = !accepting,
                                onClick = {
                                    scope.launch {
                                        accepting = true
                                        message = "Plan kabul edildi. Yerel video yürütücüsü başlatılıyor…"
                                        try {
                                            repository.acceptPlan(task.id)
                                            message = "Görev sonucu bekleniyor. MP4 ve SRT doğrulanınca önizleme aşağıda açılacak."
                                        } catch (error: Exception) {
                                            message = "Plan yürütülemedi: " + (error.message ?: "bilinmeyen hata")
                                        } finally {
                                            accepting = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (accepting) "Başlatılıyor…" else "Planı kabul et ve videoyu oluştur") }
                        } else {
                            Text("Ayrı bir kullanıcı veya işlem onayı bekleniyor.", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    JarvisTaskStatus.RUNNING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("Video hazırlanıyor. Doğrulanmış ilerleme verisi gelene kadar yüzde gösterilmiyor.")
                        }
                    }
                    JarvisTaskStatus.SUCCEEDED -> {
                        Text(
                            "MP4 ve SRT doğrulandı. Uygulama içi oynatıcıda önizleme hazır.",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    JarvisTaskStatus.NOT_EXECUTED,
                    JarvisTaskStatus.INTERRUPTED,
                    JarvisTaskStatus.FAILED,
                    JarvisTaskStatus.WAITING_FOR_NETWORK -> {
                        Text(
                            task.errorMessage ?: task.currentStep ?: status.turkishLabel,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    message = "Yalnızca açık yeniden deneme isteği gönderiliyor; otomatik tekrar yapılmıyor."
                                    repository.retry(task.id)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Görevi elle yeniden dene") }
                    }
                    JarvisTaskStatus.QUEUED,
                    JarvisTaskStatus.PLANNING -> {
                        Text("Görev hazırlanıyor; henüz video yürütülmedi.")
                    }
                    JarvisTaskStatus.CANCELLED -> {
                        Text("Video görevi iptal edildi.")
                    }
                }
                Text("Görev: " + task.id, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Video otomatik paylaşılmaz. Başarı yalnızca okunabilir MediaStore MP4 ve SRT kanıtı kaydedildikten sonra gösterilir.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
