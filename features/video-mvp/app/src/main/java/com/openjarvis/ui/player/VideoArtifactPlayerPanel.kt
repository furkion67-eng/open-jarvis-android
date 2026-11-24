package com.openjarvis.ui.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Trace
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openjarvis.tasks.JarvisTaskEntity
import com.openjarvis.tasks.JarvisTaskRepository
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VideoArtifactMetadata
import com.openjarvis.tasks.VideoArtifactMetadataCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal sealed interface PlaybackAvailability {
    object Empty : PlaybackAvailability
    object Loading : PlaybackAvailability
    data class Ready(val uri: Uri, val metadata: VideoArtifactMetadata, val thumbnailUri: Uri?) : PlaybackAvailability
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
                if (
                    duration <= 0L ||
                    width != metadata.width ||
                    height != metadata.height ||
                    height <= width ||
                    abs(duration - metadata.durationMs) > 2_000L ||
                    !hasAudio
                ) {
                    return PlaybackAvailability.Unavailable("MP4 video/ses izleri saklanan metadata ile eşleşmiyor.")
                }
            } finally {
                runCatching { retriever.release() }
            }

            val captionsText = context.contentResolver.openInputStream(captionsUri)?.use { input ->
                VideoCaptionsContentPolicy.readValidated(input)
            } ?: return PlaybackAvailability.Unavailable(
                "SRT altyazısı okunamıyor, bozuk veya 2 MB doğrulama sınırını aşıyor. İzin kaldırılmış olabilir."
            )
            val thumbnailUri = metadata.thumbnailUri?.let(::parseContentUri)
            val readableThumbnail = thumbnailUri?.takeIf { candidate ->
                runCatching {
                    context.contentResolver.openFileDescriptor(candidate, "r")?.use { true } == true
                }.getOrDefault(false)
            }
            PlaybackAvailability.Ready(videoUri, metadata, readableThumbnail)
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

private const val VIDEO_ARCHIVE_PAGE_SIZE = 8

internal fun resolveVideoArchiveSelection(
    selectedId: String?,
    visiblePage: List<JarvisTaskEntity>
): String? = selectedId ?: visiblePage.firstOrNull()?.id

/** Embedded player restored from the last verified durable task; it never launches an external player. */
@Composable
fun VideoArtifactPlayerPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var repository by remember(context) { mutableStateOf<JarvisTaskRepository?>(null) }
    var repositoryError by remember(context) { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        try {
            // Keep the archive's Room bootstrap from competing with the launcher's first
            // interactive frames. Task controls and video creation use the same two-frame
            // deferral before entering the process-wide single-flight repository bootstrap.
            withFrameNanos { }
            withFrameNanos { }
            repository = JarvisTaskRepository.getInstanceOnIo(context)
        } catch (cancelled: CancellationException) {
            // Leaving the Dashboard must cancel the deferred bootstrap; do not turn
            // lifecycle cancellation into a visible repository failure.
            throw cancelled
        } catch (error: Exception) {
            repositoryError = error.localizedMessage ?: "Yerel görev arşivi açılamadı."
        }
    }
    var archivePage by rememberSaveable { mutableStateOf(0) }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val archiveFlow = remember(repository, archivePage) {
        repository?.observeSuccessfulVideoArtifacts(
            limit = VIDEO_ARCHIVE_PAGE_SIZE + 1,
            offset = archivePage * VIDEO_ARCHIVE_PAGE_SIZE
        ) ?: flowOf(emptyList())
    }
    val archiveRows by archiveFlow.collectAsState(initial = emptyList())
    val archiveItems = archiveRows.take(VIDEO_ARCHIVE_PAGE_SIZE)
    val hasNextArchivePage = archiveRows.size > VIDEO_ARCHIVE_PAGE_SIZE
    val selectedTaskFlow = remember(repository, selectedTaskId) {
        selectedTaskId?.let { repository?.observeTaskById(it) } ?: flowOf(null)
    }
    val selectedTaskFromStore by selectedTaskFlow.collectAsState(initial = null)
    val selectedTask = selectedTaskFromStore
        ?: archiveItems.firstOrNull { it.id == selectedTaskId }
        ?: if (selectedTaskId == null) archiveItems.firstOrNull() else null

    LaunchedEffect(archiveItems, selectedTaskId) {
        val nextSelection = resolveVideoArchiveSelection(selectedTaskId, archiveItems)
        if (nextSelection != selectedTaskId) selectedTaskId = nextSelection
    }

    var retryKey by remember(selectedTask?.id, selectedTask?.videoUri) { mutableStateOf(0) }
    var availability by remember(selectedTask?.id, selectedTask?.videoUri) {
        mutableStateOf<PlaybackAvailability>(PlaybackAvailability.Empty)
    }
    var player by remember(selectedTask?.id, selectedTask?.videoUri, retryKey) { mutableStateOf<VideoView?>(null) }
    val playerAttemptId = "${selectedTask?.id?.toString().orEmpty()}|${selectedTask?.videoUri.orEmpty()}|$retryKey"
    var playerPreparation by remember(playerAttemptId) {
        mutableStateOf(VideoPlayerPreparationState.preparing(playerAttemptId))
    }

    // The same callback bridge is used by VideoView listeners and its focused JVM regression test.
    val playerCallbackBridge = remember(playerAttemptId) {
        VideoPlayerCallbackBridge(playerAttemptId) { nextState ->
            playerPreparation = nextState
        }
    }

    DisposableEffect(player) {
        onDispose { player?.stopPlayback() }
    }

    LaunchedEffect(
        selectedTask?.id,
        selectedTask?.videoUri,
        selectedTask?.captionsUri,
        selectedTask?.videoArtifactMetadataJson,
        retryKey
    ) {
        
        val task = selectedTask
        availability = if (task == null) PlaybackAvailability.Empty else PlaybackAvailability.Loading
        if (task != null) {
            availability = withContext(Dispatchers.IO) {
                Trace.beginSection("JARVIS.player.restore")
                try {
                    VideoArtifactPlaybackValidator.inspect(
                        context = context,
                        videoUriText = task.videoUri,
                        captionsUriText = task.captionsUri,
                        metadataJson = task.videoArtifactMetadataJson
                    )
                } finally {
                    Trace.endSection()
                }
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("JARVIS video arşivi", style = MaterialTheme.typography.titleMedium)
            if (repository == null && repositoryError == null) {
                Text(
                    "Kalıcı görev arşivi ve önceki görev durumları arka planda yükleniyor…",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (repositoryError != null) {
                Text(
                    "Görev arşivi açılamadı: " + repositoryError,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text("Kaydedilmiş videolar", style = MaterialTheme.typography.titleSmall)
                if (archiveItems.isEmpty()) {
                    Text(
                        if (archivePage == 0) {
                            "Henüz doğrulanmış bir video görevi yok. Video üretimi tamamlandığında arşive eklenir."
                        } else {
                            "Bu arşiv sayfasında başka video yok."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(archiveItems, key = { it.id }) { artifact ->
                            TextButton(
                                onClick = { selectedTaskId = artifact.id },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    (if (artifact.id == selectedTaskId) "✓ " else "") + artifact.goal,
                                    color = if (artifact.id == selectedTaskId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sayfa " + (archivePage + 1), style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            enabled = archivePage > 0,
                            onClick = { archivePage = (archivePage - 1).coerceAtLeast(0) }
                        ) { Text("Önceki") }
                        TextButton(
                            enabled = hasNextArchivePage,
                            onClick = { archivePage += 1 }
                        ) { Text("Sonraki") }
                    }
                }
                if (selectedTaskId != null && selectedTask == null) {
                    Text("Seçilen video kaydı kalıcı arşivden yükleniyor…", style = MaterialTheme.typography.bodySmall)
                } else if (selectedTask == null) {
                    Text("Oynatmak için arşivden bir video seç.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(selectedTask.goal, style = MaterialTheme.typography.titleSmall)
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
                        val thumbnailUri = state.thumbnailUri
                        Text(
                            metadata.width.toString() + "×" + metadata.height + " · " +
                                (metadata.durationMs / 1_000L) + " sn · " + metadata.narrationLocale,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (thumbnailUri != null) {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().height(170.dp),
                                factory = { imageContext ->
                                    ImageView(imageContext).apply {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        contentDescription = metadata.thumbnailAltText
                                        runCatching { setImageURI(thumbnailUri) }
                                    }
                                },
                                update = { image ->
                                    image.contentDescription = metadata.thumbnailAltText
                                    runCatching { image.setImageURI(thumbnailUri) }
                                }
                            )
                        } else {
                            Text(
                                "Kapak görseli şu anda okunamıyor; doğrulanmış MP4 yine JARVIS içinde oynatılabilir.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        metadata.captionLocale?.let {
                            Text("Altyazı dili: " + it + " · Kanca: " + (metadata.hookDurationMs ?: 0L) + " ms")
                        }
                        metadata.audioDescription?.let {
                            Text("Ses: " + it, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (metadata.backgroundMusicIncluded) "Arka plan müziği mevcut" else "Arka plan müziği eklenmedi",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (metadata.attributionManifestComplete) {
                            Text("Görsel atıfları", style = MaterialTheme.typography.titleSmall)
                            Text(metadata.attributionCredits.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Text("Araştırma kaynakları", style = MaterialTheme.typography.titleSmall)
                            Text(metadata.researchCredits.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Text("Kapak açıklaması: " + metadata.thumbnailAltText.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            metadata.attributionManifest.forEach { asset ->
                                Text(
                                    "Sahne " + (asset.sceneIndex + 1) + " · " + asset.sceneId.orEmpty() +
                                        "\nVarlık: " + asset.assetId + "\nSHA-256: " + asset.sha256 +
                                        "\nKaynak: " + asset.sourceUrl.orEmpty() +
                                        "\nÜretici: " + asset.creator.orEmpty() +
                                        "\nLisans: " + asset.licenseId.orEmpty() + " " + asset.licenseVersion.orEmpty() +
                                        "\nLisans URL: " + asset.licenseUrl.orEmpty() +
                                        "\nAtıf: " + asset.attributionText.orEmpty() +
                                        "\nAlınma zamanı (epoch ms): " + (asset.retrievedAtEpochMs ?: 0L) +
                                        "\nCache anahtarı: " + asset.cacheKey.orEmpty() +
                                        "\nCache: " + asset.cacheMetadata.orEmpty() +
                                        "\nTicari kullanım: " + asset.commercialUseAllowed +
                                        " · Türev: " + asset.derivativesAllowed +
                                        " · Share-alike: " + asset.shareAlikeRequired +
                                        " · Çıktı lisansı: " + asset.requiredOutputLicense.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            metadata.claimSources.forEach { source ->
                                Text(
                                    "İddia: " + source.claimId + " · Sahne: " + source.sceneId +
                                        "\n" + source.publisher + " · " + source.checkedOn +
                                        "\n" + source.supportedClaim + "\n" + source.sourceUrl +
                                        (source.uncertaintyNote?.let { "\nNot: " + it } ?: ""),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        key(playerAttemptId, state.uri) {
                            AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f),
                            factory = { viewContext ->
                                VideoView(viewContext).apply {
                                    val controller = MediaController(viewContext)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setOnPreparedListener {
                                        playerCallbackBridge.onPrepared(playerAttemptId)
                                    }
                                    setOnInfoListener { _, what, _ ->
                                        when (what) {
                                            MediaPlayer.MEDIA_INFO_BUFFERING_START -> playerCallbackBridge.onBufferingStarted(playerAttemptId)
                                            MediaPlayer.MEDIA_INFO_BUFFERING_END -> playerCallbackBridge.onBufferingEnded(playerAttemptId)
                                        }
                                        false
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        playerCallbackBridge.onError(
                                            "Video JARVIS içinde oynatılamadı. Yeniden denetleyebilir veya tekrar deneyebilirsin.",
                                            playerAttemptId
                                        )
                                        true
                                    }
                                    tag = state.uri.toString()
                                    playerCallbackBridge.beginAttempt(playerAttemptId)
                                    setVideoURI(state.uri)
                                }
                            },
                            update = { view ->
                                if (view.tag != state.uri.toString()) {
                                    playerCallbackBridge.beginAttempt(playerAttemptId)
                                    view.tag = state.uri.toString()
                                    view.setVideoURI(state.uri)
                                }
                                player = view
                            }
                            )
                        }
                        VideoPlayerPreparationControls(
                            state = playerPreparation,
                            canPlay = player != null && playerPreparation.canPlay,
                            onPlay = { player?.start() },
                            onReplay = {
                                player?.seekTo(0)
                                player?.start()
                            },
                            onRetry = { retryKey++ }
                        )
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
}


/**
 * Shared control surface for production playback and instrumentation verification.
 * The test tags expose no user data; they let the Android test assert the exact Compose state.
 */
@Composable
internal fun VideoPlayerPreparationControls(
    state: VideoPlayerPreparationState,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit
) {
    if (state.isLoading) {
        Row(
            modifier = Modifier.testTag("video-player-loading"),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator()
            Text("Video oynatıcı hazırlanıyor veya arabelleğe alınıyor…")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.testTag("video-player-play")
        ) { Text("Oynat") }
        TextButton(
            onClick = onReplay,
            enabled = canPlay,
            modifier = Modifier.testTag("video-player-replay")
        ) { Text("Baştan oynat") }
        TextButton(
            onClick = onRetry,
            modifier = Modifier.testTag("video-player-retry")
        ) { Text("Yeniden denetle") }
    }
    state.errorMessage?.let {
        Text(
            it,
            modifier = Modifier.testTag("video-player-error"),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
