package com.openjarvis.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openjarvis.video.VideoRenderProgress
import com.openjarvis.video.VideoRenderSpec
import com.openjarvis.video.VideoStoryPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun VideoMvpPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var topic by remember { mutableStateOf("İstanbul'un Fethi") }
    var status by remember { mutableStateOf("Henüz video oluşturulmadı.") }
    var renderProgress by remember { mutableStateOf(VideoRenderProgress()) }
    val progress by renderProgress.progress.collectAsState()
    var output by remember { mutableStateOf<String?>(null) }
    var narrationText by remember { mutableStateOf<String?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var renderJob by remember { mutableStateOf<Job?>(null) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Yerel video taslağı (sessiz)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Konu başlığından yerel prosedürel vektör sahneleri, 20 saniyelik storyboard, zamanlı SRT ve sessiz dikey MP4 taslağı üretir.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it.take(VideoRenderSpec.MAX_TITLE_LENGTH) },
                label = { Text("Video konusu") },
                singleLine = true,
                enabled = !rendering,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val progressForThisRender = VideoRenderProgress()
                    renderProgress = progressForThisRender
                    renderJob = scope.launch {
                        rendering = true
                        output = null
                        narrationText = null
                        status = "Storyboard ve MP4 hazırlanıyor…"
                        try {
                            val result = VideoStoryPipeline(context).render(topic) { done, total ->
                                // Renderer invokes this callback on Dispatchers.IO. StateFlow is thread-safe;
                                // Compose observes it through collectAsState on the UI side.
                                progressForThisRender.report(done, total)
                            }
                            progressForThisRender.finish()
                            output = "${result.video.file.name} · ${result.video.sizeBytes / 1024} KB · ${result.captionsFile.name}"
                            narrationText = result.narrationText
                            status = "Sessiz taslak hazır: ${result.video.width}×${result.video.height}, ${result.video.durationMs / 1000} sn, ${result.sceneCount} sahne · ${result.sourceLabel}"
                        } catch (cancelled: CancellationException) {
                            progressForThisRender.stop()
                            status = "Video oluşturma iptal edildi."
                            throw cancelled
                        } catch (error: Exception) {
                            progressForThisRender.stop()
                            status = when (error) {
                                is IllegalArgumentException -> error.message ?: "Video ayarları geçersiz."
                                else -> error.message ?: "Video oluşturulamadı."
                            }
                        } finally {
                            progressForThisRender.stop()
                            rendering = false
                            renderJob = null
                        }
                    }
                },
                enabled = !rendering && topic.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (rendering) "Video hazırlanıyor" else "Video taslağı oluştur")
            }
            if (rendering) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { renderJob?.cancel() }) { Text("İptal et") }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            output?.let { Text("Kaydedildi: $it", style = MaterialTheme.typography.bodySmall) }
            narrationText?.let {
                Text("Seslendirme metni (henüz ses dosyası değil): $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "MP4 uygulamanın özel alanında, zamanlı altyazı ayrı SRT dosyasında saklanır. Video hâlâ sessizdir. Görseller cihazda çizilen prosedürel vektörlerdir; AI üretimi veya tarihî fotoğraf değildir. Türkçe TTS ses izi, galeri dışa aktarımı ve sosyal medya paylaşımı henüz eklenmedi.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
