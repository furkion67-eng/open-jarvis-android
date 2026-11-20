package com.openjarvis.ui.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openjarvis.tasks.JarvisTaskRepository
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VideoArtifactMetadata
import com.openjarvis.tasks.VideoArtifactMetadataCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PlaybackAvailability {
    data object Empty : PlaybackAvailability
    data object Loading : PlaybackAvailability
    data class Ready(val uri: Uri, val metadata: VideoArtifactMetadata) : PlaybackAvailability
    data class Unavailable(val reason: String) : PlaybackAvailability
}

/** Revalidates both persisted content URIs on every app/process reopen. */
internal object VideoArtifactPlaybackValidator {
    fun inspect(
        context: Context,
        videoUriText: String?,
        captionsUriText: String?,
        metadataJson: String?
    ): PlaybackAvailability {
        if (videoUriText.isNullOrBlank() || captionsUriText.isNullOrBlank()) {
            return PlaybackAvailability.Unavailable("Video veya altyazı URI kaydı eksik.")
        }
        val videoUri = parseContentUri(videoUriText)
            ?: return PlaybackAvailability.Unavailable("Kaydedilen video URI'si geçerli bir content:// adresi değil.")
        val captionsUri = parseContentUri(captionsUriText)
            ?: return PlaybackAvailability.Unavailable("Kaydedilen altyazı URI'si geçerli bir content:// adresi değil.")
        val metadata = VideoArtifactMetadataCodec.decode(metadataJson)
            ?: return PlaybackAvailability.Unavailable("Video metadata kaydı okunamadı; görev sonucu yeniden doğrulanmalı.")

        return try {
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { }
                ?: return PlaybackAvailability.Unavailable("Video dosyası artık okunamıyor. İzin kaldırılmış olabilir.")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                if (duration <= 0L || width <= 0 || height <= width || !hasAudio) {
                    return PlaybackAvailability.Unavailable("MP4 video/ses izleri artık doğrulanamıyor.")
                }
            } finally {
                runCatching { retriever.release() }
            }

            val captionsText = context.contentResolver.openInputStream(captionsUri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: return PlaybackAvailability.Unavailable("SRT altyazısı artık okunamıyor. İzin kaldırılmış olabilir.")
            if (captionsText.isBlank() || !captionsText.contains("-->")) {
                return PlaybackAvailability.Unavailable("SRT altyazı içeriği doğrulanamadı.")
            }
            PlaybackAvailability.Ready(videoUri, metadata)
        } catch (error: SecurityException) {
            PlaybackAvailability.Unavailable("Android medya erişim izni geri alınmış: " + (error.message ?: "erişim reddedildi"))
        } catch (error: Exception) {
            PlaybackAvailability.Unavailable("Video JARVIS içinde açılamadı: " + (error.message ?: "dosya erişim hatası"))
        }
    }

    private fun parseContentUri(value: String): Uri? = runCatching {
        Uri.parse(value).takeIf {
            it.scheme.equals("content", ignoreCase = true) && !it.authority.isNullOrBlank()
        }
    }.getOrNull()
}

/** Embedded player restored from the last verified durable task; it never launches an external player. */
@Composable
fun VideoArtifactPlayerPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { JarvisTaskRepository.getInstance(context) }
    val tasks by repository.observeRecentTasks(limit = 30).collectAsState(initial = emptyList())
    val latest = tasks.firstOrNull {
        it.status == JarvisTaskStatus.SUCCEEDED.storageValue &&
            !it.videoUri.isNullOrBlank() &&
            !it.captionsUri.isNullOrBlank()
    }

    var retryKey by remember(latest?.id, latest?.videoUri) { mutableStateOf(0) }
    var availability by remember(latest?.id, latest?.videoUri) {
        mutableStateOf<PlaybackAvailability>(PlaybackAvailability.Empty)
    }
    var player by remember(latest?.videoUri) { mutableStateOf<VideoView?>(null) }
    var playbackError by remember(latest?.id, latest?.videoUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(
        latest?.id,
        latest?.videoUri,
        latest?.captionsUri,
        latest?.videoArtifactMetadataJson,
        retryKey
    ) {
        val task = latest
        availability = if (task == null) PlaybackAvailability.Empty else PlaybackAvailability.Loading
        if (task != null) {
            availability = withContext(Dispatchers.IO) {
                VideoArtifactPlaybackValidator.inspect(
                    context = context,
                    videoUriText = task.videoUri,
                    captionsUriText = task.captionsUri,
                    metadataJson = task.videoArtifactMetadataJson
                )
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("JARVIS video arşivi", style = MaterialTheme.typography.titleMedium)
            if (latest == null) {
                Text(
                    "Henüz doğrulanmış bir video görevi yok. Video üretimi tamamlandığında önizleme burada görünür.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(latest.goal, style = MaterialTheme.typography.titleSmall)
                when (val state = availability) {
                    PlaybackAvailability.Empty -> Text("Henüz video seçilmedi.")
                    PlaybackAvailability.Loading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("Kaydedilmiş MP4 ve SRT erişimi doğrulanıyor…")
                        }
                    }
                    is PlaybackAvailability.Unavailable -> {
                        Text(state.reason, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { retryKey++ }) { Text("Erişimi yeniden denetle") }
                    }
                    is PlaybackAvailability.Ready -> {
                        val metadata = state.metadata
                        Text(
                            "\${metadata.width}×\${metadata.height} · \${metadata.durationMs / 1_000L} sn · \${metadata.narrationLocale}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f),
                            factory = { viewContext ->
                                VideoView(viewContext).apply {
                                    val controller = MediaController(viewContext)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setOnPreparedListener { playbackError = null }
                                    setOnErrorListener { _, _, _ ->
                                        playbackError = "Video JARVIS içinde oynatılamadı. Yeniden denetleyebilir veya tekrar deneyebilirsin."
                                        true
                                    }
                                    tag = state.uri.toString()
                                    setVideoURI(state.uri)
                                    player = this
                                }
                            },
                            update = { view ->
                                if (view.tag != state.uri.toString()) {
                                    view.tag = state.uri.toString()
                                    view.setVideoURI(state.uri)
                                }
                                player = view
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { player?.start() }, enabled = player != null) { Text("Oynat") }
                            TextButton(
                                onClick = {
                                    player?.seekTo(0)
                                    player?.start()
                                },
                                enabled = player != null
                            ) { Text("Baştan oynat") }
                            TextButton(onClick = { retryKey++ }) { Text("Yeniden denetle") }
                        }
                        playbackError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("MP4 ve SRT URI'leri görev kaydında saklanır; JARVIS yeniden açıldığında tekrar kontrol edilir.", style = MaterialTheme.typography.bodySmall)
                        if (!metadata.attributionManifestComplete) {
                            Text(
                                "Kaynak/atıf manifesti henüz Shorts renderer tarafından sağlanmadı; eksik kaynak bilgisi uydurulmadı.",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
