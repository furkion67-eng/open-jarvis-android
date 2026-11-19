package com.openjarvis.ui.dashboard

import android.content.ClipData
import android.content.Intent
import android.net.Uri
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
    var exportedUri by remember { mutableStateOf<Uri?>(null) }
    var captionsUri by remember { mutableStateOf<Uri?>(null) }
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
            Text("Türkçe sesli yerel video üretimi", style = MaterialTheme.typography.titleMedium)
            Text(
                "Konu başlığından beş yerel prosedürel sahne, Türkçe TTS anlatım, AAC ses, dikey H.264 MP4 ve konuşma sürelerine göre SRT üretir.",
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
                        exportedUri = null
                        captionsUri = null
                        status = "Storyboard hazırlanıyor…"
                        try {
                            val result = VideoStoryPipeline(context).render(
                                prompt = topic,
                                onProgress = { done, total ->
                                    // Renderer invokes this callback on Dispatchers.IO. StateFlow is thread-safe.
                                    progressForThisRender.report(done, total)
                                },
                                onStage = { stage -> status = stage }
                            )
                            progressForThisRender.finish()
                            output = "${result.video.file.name} · ${result.video.sizeBytes / 1024} KB"
                            exportedUri = result.exportedUri
                            captionsUri = result.captionsUri
                            narrationText = result.narrationText
                            status = "Türkçe sesli MP4 hazır: ${result.video.width}×${result.video.height}, ${result.video.durationMs / 1000} sn, ${result.sceneCount} sahne, AAC ses · ${result.sourceLabel}"
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
                Text(if (rendering) "Video hazırlanıyor" else "Türkçe sesli video oluştur")
            }
            if (rendering) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { renderJob?.cancel() }) { Text("İptal et") }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            output?.let { Text("Kaydedildi: $it", style = MaterialTheme.typography.bodySmall) }
            output?.let { Text("Dosya: $it", style = MaterialTheme.typography.bodySmall) }
            captionsUri?.let { Text("SRT altyazı: İndirilenler / Open JARVIS", style = MaterialTheme.typography.bodySmall) }
            exportedUri?.let { uri ->
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, "JARVIS video", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Videoyu paylaş"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Videoyu paylaş…") }
            }
            narrationText?.let {
                Text("Türkçe anlatım metni: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "MP4, Türkçe TTS anlatımını AAC ses iziyle ve yerel prosedürel vektör sahnelerini H.264 olarak içerir. Zamanlı SRT ayrıca İndirilenler/Open JARVIS klasörüne kaydedilir. Çizimler cihazda üretilir; AI görseli değildir. Paylaşım menüsü yalnızca kullanıcı seçtiğinde açılır; otomatik YouTube/TikTok yayını yapılmaz.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
