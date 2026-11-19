package com.openjarvis.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openjarvis.R
import com.openjarvis.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runGuard = DownloadServiceRunGuard()
    private val downloadMutex = Mutex()
    private var downloadJob: Job? = null
    private val modelManager by lazy(LazyThreadSafetyMode.NONE) { ModelManager(this) }

    private val _progress = MutableStateFlow(0f)
    val progress = _progress

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start path is promoted before validation or asynchronous work. This includes
        // malformed requests delivered through startForegroundService().
        startForeground(NOTIFICATION_ID, createNotification(0f))

        when (val command = DownloadServiceCommandParser.parse(
            action = intent?.action,
            tierName = intent?.getStringExtra(EXTRA_TIER)
        )) {
            is DownloadServiceCommand.Download -> startDownload(command.tier, startId)
            DownloadServiceCommand.Cancel -> cancelDownload(startId)
            is DownloadServiceCommand.Reject -> {
                showErrorNotification(command.reason.turkishMessage)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(tier: ModelManager.ModelTier, startId: Int) {
        // Invalidate the previous run before cancelling it. Its finally block must not stop
        // this newer start or remove its notification.
        val token = runGuard.begin(startId)
        downloadJob?.cancel()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // ModelManager performs blocking HTTP reads on IO. Serialize access so a
                // cancelled but non-cooperative old read cannot write the same model file
                // concurrently with a replacement download.
                val result = downloadMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    modelManager.downloadModel(tier) { nextProgress ->
                        if (runGuard.owns(token, startId)) {
                            _progress.value = nextProgress
                            updateNotification(nextProgress)
                        }
                    }
                }
                // ModelManager currently wraps work in runCatching; this explicit check
                // ensures a swallowed CancellationException cannot be reported as failure.
                currentCoroutineContext().ensureActive()

                withContext(Dispatchers.Main.immediate) {
                    if (runGuard.owns(token, startId)) {
                        result.fold(
                            onSuccess = {
                                showCompletionNotification(true, "Model kullanıma hazır.")
                            },
                            onFailure = { error ->
                                showErrorNotification(error.message ?: "Model indirilemedi.")
                            }
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (runGuard.owns(token, startId)) {
                        showErrorNotification(error.message ?: "Model indirilemedi.")
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    if (runGuard.owns(token, startId)) {
                        downloadJob = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                }
            }
        }

        downloadJob = job
        job.start()
    }

    private fun cancelDownload(startId: Int) {
        // Cancellation is a new service start. It invalidates older completion callbacks,
        // and stopSelfResult(startId) cannot stop a later startId.
        runGuard.cancel(startId)
        downloadJob?.cancel()
        downloadJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun createNotification(progress: Float): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Model indiriliyor…")
            .setContentText("%${(progress * 100).toInt()} tamamlandı")
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, (progress * 100).toInt(), false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(progress))
    }

    private fun showCompletionNotification(success: Boolean, detail: String) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (success) "İndirme tamamlandı" else "İndirme başarısız")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(message: String) {
        showCompletionNotification(false, message)
    }

    override fun onDestroy() {
        runGuard.invalidate()
        downloadJob?.cancel()
        downloadJob = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_ID = 1002

        const val ACTION_DOWNLOAD = "com.openjarvis.DOWNLOAD_MODEL"
        const val ACTION_CANCEL = "com.openjarvis.CANCEL_DOWNLOAD"
        const val EXTRA_TIER = "tier"

        fun startDownload(context: Context, tier: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_TIER, tier)
            }
            context.startForegroundService(intent)
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
