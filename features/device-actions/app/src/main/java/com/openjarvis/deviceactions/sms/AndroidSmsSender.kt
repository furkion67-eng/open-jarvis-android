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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Sends only after a one-time explicit confirmation has been consumed. */
class AndroidSmsSender(context: Context) : SmsTransport {
    private val appContext = context.applicationContext
    private val attemptJournal = SmsAttemptJournal(SharedPreferencesSmsAttemptStore(appContext))
    val recoveredUnknownCount: Int = if (PROCESS_RECOVERY.compareAndSet(false, true)) {
        runCatching { attemptJournal.recoverPendingAsUnknown().size }.getOrDefault(0)
    } else 0

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
        val journalId = UUID.randomUUID().toString()
        try {
            attemptJournal.begin(journalId, parts.size)
        } catch (error: Exception) {
            callback(SmsSendOutcome.Failed(error.message ?: "SMS gönderim durumu güvenli biçimde kaydedilemedi."))
            return
        }
        lateinit var timeout: Runnable
        lateinit var finish: (SmsSendOutcome) -> Unit

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra(EXTRA_REQUEST_ID) != journalId) return
                val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
                if (partIndex < 0) return
                val trackedState = tracker.recordPlatformResult(partIndex, resultCode, Activity.RESULT_OK)
                val durableState = try {
                    attemptJournal.recordPlatformResult(journalId, partIndex, resultCode, Activity.RESULT_OK)
                } catch (error: Exception) {
                    tracker.markUnknown()
                    runCatching { attemptJournal.markUnknown(journalId) }
                    finish(SmsSendOutcome.Unknown("SMS callback sonucu güvenli biçimde kaydedilemedi: " + (error.message ?: "kayıt hatası")))
                    return
                }
                when {
                    durableState == SmsSendState.SENT && trackedState == SmsSendState.SENT -> finish(SmsSendOutcome.Sent)
                    durableState == SmsSendState.FAILED -> finish(SmsSendOutcome.Failed("Android SMS gönderim callback'i başarısız (kod " + resultCode + ")."))
                    durableState == SmsSendState.UNKNOWN -> finish(SmsSendOutcome.Unknown("SMS gönderim sonucu bilinmiyor; otomatik tekrar yapılmadı."))
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
                runCatching { attemptJournal.markUnknown(journalId) }
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
                    .setData(Uri.parse("jarvis-sms://" + journalId + "/" + index))
                    .putExtra(EXTRA_PART_INDEX, index)
                    .putExtra(EXTRA_REQUEST_ID, journalId)
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
            runCatching { attemptJournal.markFailed(journalId) }
            finish(SmsSendOutcome.PermissionDenied)
        } catch (error: Exception) {
            runCatching { attemptJournal.markFailed(journalId) }
            finish(SmsSendOutcome.Failed(error.message ?: "SMS Android tarafından başlatılamadı."))
        }
    }

    private companion object {
        val PROCESS_RECOVERY = AtomicBoolean(false)
        const val ACTION_SMS_SENT = "com.openjarvis.deviceactions.SMS_SENT"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_REQUEST_ID = "request_id"
        const val CALLBACK_TIMEOUT_MS = 120_000L
        val REQUEST_IDS = AtomicInteger(1000)
    }
}


/** Stores only opaque request IDs and callback outcomes, never SMS content or recipient data. */
private class SharedPreferencesSmsAttemptStore(context: Context) : SmsAttemptStore {
    private val preferences = context.getSharedPreferences("jarvis_sms_attempt_journal", Context.MODE_PRIVATE)
    override fun all(): List<SmsAttemptRecord> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { decode(key, it) }
    }
    override fun get(requestId: String): SmsAttemptRecord? =
        preferences.getString(requestId, null)?.let { decode(requestId, it) }
    override fun put(record: SmsAttemptRecord) {
        val json = JSONObject()
            .put("state", record.state.name)
            .put("updatedAtMillis", record.updatedAtMillis)
            .put("parts", JSONArray().apply { record.parts.forEach { put(it.name) } })
        check(preferences.edit().putString(record.requestId, json.toString()).commit()) {
            "SMS gönderim sonucu kalıcı olarak kaydedilemedi."
        }
    }
    override fun remove(requestId: String) { preferences.edit().remove(requestId).commit() }
    private fun decode(requestId: String, encoded: String): SmsAttemptRecord? = runCatching {
        val json = JSONObject(encoded)
        val array = json.getJSONArray("parts")
        val parts = List(array.length()) { index -> SmsPartResult.valueOf(array.getString(index)) }
        SmsAttemptRecord(requestId, parts, SmsSendState.valueOf(json.getString("state")), json.optLong("updatedAtMillis", 0L))
    }.getOrNull()
}
