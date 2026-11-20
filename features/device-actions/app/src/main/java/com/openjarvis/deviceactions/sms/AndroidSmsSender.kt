package com.openjarvis.deviceactions.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Sends only after a one-time explicit confirmation has been consumed. */
class AndroidSmsSender(context: Context) : SmsTransport {
    private val appContext = context.applicationContext

    override fun send(draft: SmsDraft, callback: (SmsSendOutcome) -> Unit) {
        if (appContext.checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            callback(SmsSendOutcome.PermissionDenied)
            return
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            callback(SmsSendOutcome.Unsupported)
            return
        }

        val manager = try {
            SmsManager.getDefault()
        } catch (_: Exception) {
            callback(SmsSendOutcome.Unsupported)
            return
        }
        val parts = try {
            manager.divideMessage(draft.exactMessage)
        } catch (error: Exception) {
            callback(SmsSendOutcome.Failed(error.message ?: "SMS bölümlendirilemedi."))
            return
        }
        if (parts.isNullOrEmpty()) {
            callback(SmsSendOutcome.Failed("SMS içeriği boş veya geçersiz."))
            return
        }

        val tracker = SmsSendTracker(parts.size)
        val finished = AtomicBoolean(false)
        val registered = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val requestId = REQUEST_IDS.incrementAndGet()
        lateinit var timeout: Runnable
        lateinit var finish: (SmsSendOutcome) -> Unit

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val partIndex = intent?.getIntExtra(EXTRA_PART_INDEX, -1) ?: -1
                if (partIndex < 0) return
                val partResult = if (resultCode == Activity.RESULT_OK) {
                    SmsPartResult.SENT
                } else {
                    SmsPartResult.FAILED
                }
                when (tracker.recordPart(partIndex, partResult)) {
                    SmsSendState.SENT -> finish(SmsSendOutcome.Sent)
                    SmsSendState.FAILED -> finish(
                        SmsSendOutcome.Failed("Android SMS gönderim callback'i başarısız (kod " + resultCode + ").")
                    )
                    else -> Unit
                }
            }
        }

        finish = { outcome ->
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                if (registered.compareAndSet(true, false)) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                }
                callback(outcome)
            }
        }
        timeout = Runnable {
            if (tracker.markUnknown() == SmsSendState.UNKNOWN) {
                finish(SmsSendOutcome.Unknown("Android gönderim callback'i zamanında gelmedi; SENT olarak işaretlenmedi."))
            }
        }

        try {
            val filter = IntentFilter(ACTION_SMS_SENT)
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            registered.set(true)

            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                val callbackIntent = Intent(ACTION_SMS_SENT)
                    .setPackage(appContext.packageName)
                    .setData(Uri.parse("jarvis-sms://" + requestId + "/" + index))
                    .putExtra(EXTRA_PART_INDEX, index)
                sentIntents += PendingIntent.getBroadcast(
                    appContext,
                    REQUEST_IDS.incrementAndGet(),
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            handler.postDelayed(timeout, CALLBACK_TIMEOUT_MS)
            if (parts.size == 1) {
                manager.sendTextMessage(draft.phoneNumber, null, parts[0], sentIntents[0], null)
            } else {
                manager.sendMultipartTextMessage(
                    draft.phoneNumber,
                    null,
                    ArrayList(parts),
                    sentIntents,
                    null
                )
            }
        } catch (_: SecurityException) {
            finish(SmsSendOutcome.PermissionDenied)
        } catch (error: Exception) {
            finish(SmsSendOutcome.Failed(error.message ?: "SMS Android tarafından başlatılamadı."))
        }
    }

    private companion object {
        const val ACTION_SMS_SENT = "com.openjarvis.deviceactions.SMS_SENT"
        const val EXTRA_PART_INDEX = "part_index"
        const val CALLBACK_TIMEOUT_MS = 120_000L
        val REQUEST_IDS = AtomicInteger(1000)
    }
}
