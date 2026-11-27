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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal fun createAndroidSmsDispatcher(context: Context, gate: SmsConfirmationGate): ConfirmedSmsDispatcher {
    val appContext = context.applicationContext
    val sender = AndroidSmsSender(appContext)
    return ConfirmedSmsDispatcher(
        permission = SmsPermission {
            appContext.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        },
        gate = gate,
        transport = sender
    )
}

private class AndroidSmsSender(context: Context) : SmsTransport {
    private val appContext = context.applicationContext
    private val attemptJournal = SmsAttemptJournal(SharedPreferencesSmsAttemptStore(appContext))
    private val journalIo = SmsAttemptJournalIo(attemptJournal)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun recoverPendingAttempts(): Int = PROCESS_RECOVERY_MUTEX.withLock {
        if (processRecoveryComplete) return@withLock 0
        val recovered = journalIo.recoverPendingAsUnknown().size
        processRecoveryComplete = true
        recovered
    }

    override fun send(draft: SmsDraft, callback: (SmsSendOutcome) -> Unit) {
        ioScope.launch { sendOnIo(draft, callback) }
    }

    private suspend fun sendOnIo(draft: SmsDraft, callback: (SmsSendOutcome) -> Unit) {
        val recoveryResult = runSmsJournalOperation { recoverPendingAttempts() }
        if (recoveryResult.isFailure) {
            val error = recoveryResult.exceptionOrNull()
            deliver(
                callback,
                SmsSendOutcome.Unknown(
                    "Önceki SMS denemeleri kurtarılamadı; yeni gönderim başlatılmadı: " +
                        (error?.localizedMessage ?: "kayıt hatası")
                )
            )
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            deliver(callback, SmsSendOutcome.PermissionDenied)
            return
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            deliver(callback, SmsSendOutcome.Unsupported)
            return
        }
        val manager = try {
            SmsManager.getDefault()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            deliver(callback, SmsSendOutcome.Unsupported)
            return
        }
        val parts = try {
            manager.divideMessage(draft.exactMessage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            deliver(callback, SmsSendOutcome.Failed(error.message ?: "SMS bölümlendirilemedi."))
            return
        }
        if (parts.isNullOrEmpty()) {
            deliver(callback, SmsSendOutcome.Failed("SMS içeriği boş veya geçersiz.")); return
        }

        val journalId = UUID.randomUUID().toString()
        var journalStarted = false
        val beginResult = runSmsJournalOperation { journalIo.begin(journalId, parts.size) }
        if (beginResult.isFailure) {
            val error = beginResult.exceptionOrNull()
            deliver(
                callback,
                SmsSendOutcome.Failed(
                    error?.message ?: "SMS deneme kaydı yazılamadı; mesaj gönderilmedi."
                )
            )
            return
        }
        journalStarted = true

        val finished = AtomicBoolean(false)
        val registered = AtomicBoolean(false)
        val callbackRecorderIo = SmsPlatformCallbackRecorderIo(
            SmsPlatformCallbackRecorder(journalId, attemptJournal, Activity.RESULT_OK)
        )
        lateinit var timeout: Runnable
        lateinit var finish: (SmsSendOutcome) -> Unit

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra(EXTRA_REQUEST_ID) != journalId) return
                val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
                if (partIndex < 0) return
                val pendingResult = goAsync()
                val callbackResultCode = resultCode
                ioScope.launch {
                    try {
                        val recordResult = runSmsJournalOperation {
                            callbackRecorderIo.record(partIndex, callbackResultCode)
                        }
                        if (recordResult.isFailure) {
                            val error = recordResult.exceptionOrNull()
                            // Keep the attempt conservative if the callback journal cannot be
                            // updated. Any still-PENDING record becomes UNKNOWN on process recovery.
                            runSmsJournalOperation { callbackRecorderIo.markUnknown() }
                            finish(
                                SmsSendOutcome.Unknown(
                                    "SMS callback kalıcı kaydı başarısız: " +
                                        (error?.localizedMessage ?: "kayıt hatası")
                                )
                            )
                        } else {
                            when (recordResult.getOrThrow()) {
                                SmsSendState.SENT -> finish(SmsSendOutcome.Sent)
                                SmsSendState.FAILED -> finish(
                                    SmsSendOutcome.Failed("Android SMS callback başarısız (kod $callbackResultCode).")
                                )
                                SmsSendState.UNKNOWN -> finish(
                                    SmsSendOutcome.Unknown("SMS sonucu bilinmiyor; otomatik tekrar yapılmadı.")
                                )
                                SmsSendState.PENDING -> Unit
                            }
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }

        finish = { outcome ->
            if (finished.compareAndSet(false, true)) {
                mainHandler.post {
                    mainHandler.removeCallbacks(timeout)
                    if (registered.compareAndSet(true, false)) runCatching { appContext.unregisterReceiver(receiver) }
                    callback(outcome)
                }
            }
        }
        timeout = Runnable {
            ioScope.launch {
                val markUnknownResult = runSmsJournalOperation { callbackRecorderIo.markUnknown() }
                val state = markUnknownResult.getOrNull() ?: SmsSendState.UNKNOWN
                if (state == SmsSendState.UNKNOWN) {
                    finish(SmsSendOutcome.Unknown("SMS callback zaman aşımına uğradı; SENT işaretlenmedi."))
                }
            }
        }

        try {
            val filter = IntentFilter(ACTION_SMS_SENT)
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
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
                    appContext, REQUEST_IDS.incrementAndGet(), callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            mainHandler.postDelayed(timeout, CALLBACK_TIMEOUT_MS)
            if (parts.size == 1) {
                manager.sendTextMessage(draft.phoneNumber, null, parts[0], sentIntents[0], null)
            } else {
                manager.sendMultipartTextMessage(draft.phoneNumber, null, ArrayList(parts), sentIntents, null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            val persisted = if (journalStarted) {
                runSmsJournalOperation { journalIo.markFailed(journalId) }.getOrNull()
            } else null
            finish(
                if (journalStarted && persisted == SmsSendState.FAILED) SmsSendOutcome.PermissionDenied
                else SmsSendOutcome.Unknown("SMS izni veya deneme sonucu doğrulanamadı; otomatik tekrar yapılmadı.")
            )
        } catch (error: Exception) {
            val persisted = if (journalStarted) {
                runSmsJournalOperation { journalIo.markFailed(journalId) }.getOrNull()
            } else null
            finish(
                if (journalStarted && persisted == SmsSendState.FAILED) {
                    SmsSendOutcome.Failed(error.message ?: "SMS başlatılamadı.")
                } else {
                    SmsSendOutcome.Unknown("SMS başlatma sonucu kalıcı olarak doğrulanamadı; otomatik tekrar yapılmadı.")
                }
            )
        }
    }

    private fun deliver(callback: (SmsSendOutcome) -> Unit, outcome: SmsSendOutcome) {
        mainHandler.post { callback(outcome) }
    }

    private companion object {
        val PROCESS_RECOVERY_MUTEX = Mutex()
        var processRecoveryComplete = false
        const val ACTION_SMS_SENT = "com.openjarvis.deviceactions.SMS_SENT"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_REQUEST_ID = "request_id"
        const val CALLBACK_TIMEOUT_MS = 120_000L
        val REQUEST_IDS = AtomicInteger(1000)
    }
}

private class SharedPreferencesSmsAttemptStore(context: Context) : SmsAttemptStore {
    private val preferences = context.getSharedPreferences("jarvis_sms_attempt_journal", Context.MODE_PRIVATE)
    override fun all(): List<SmsAttemptRecord> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { decode(key, it) }
    }
    override fun get(requestId: String): SmsAttemptRecord? = preferences.getString(requestId, null)?.let { decode(requestId, it) }
    override fun put(record: SmsAttemptRecord) {
        val json = JSONObject().put("state", record.state.name)
            .put("updatedAtMillis", record.updatedAtMillis)
            .put("parts", JSONArray().apply { record.parts.forEach { put(it.name) } })
        check(preferences.edit().putString(record.requestId, json.toString()).commit()) {
            "SMS gönderim sonucu kalıcı olarak kaydedilemedi."
        }
    }
    override fun remove(requestId: String) {
        check(preferences.edit().remove(requestId).commit()) { "SMS deneme geçmişi temizlenemedi." }
    }
    private fun decode(requestId: String, encoded: String): SmsAttemptRecord? = runCatching {
        val json = JSONObject(encoded)
        val array = json.getJSONArray("parts")
        val parts = List(array.length()) { index -> SmsPartResult.valueOf(array.getString(index)) }
        SmsAttemptRecord(requestId, parts, SmsSendState.valueOf(json.getString("state")), json.optLong("updatedAtMillis", 0L))
    }.getOrNull()
}
