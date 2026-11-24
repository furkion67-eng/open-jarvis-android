package com.openjarvis.deviceactions.sms

import android.app.Activity
import android.content.Intent
import android.os.Looper
import com.openjarvis.tasks.DeviceActionOutcomeCode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class SmsConfirmationInstrumentedTest {
    @Test
    fun actionSendToCreatesOnlySmsDraftAndNeverMarksSent() {
        val exactMessage = "Bu mesaj SMS uygulamasında taslak olarak açılır."
        val intent = SmsDraftHandoff.createIntent("+905551234567", exactMessage)

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data?.scheme)
        assertEquals("+905551234567", intent.data?.schemeSpecificPart)
        assertEquals(exactMessage, intent.getStringExtra("sms_body"))
        assertEquals(DeviceActionOutcomeCode.DRAFT, SmsDraftHandoff.outcomeCode)
        assertTrue(SmsDraftHandoff.outcomeCode != DeviceActionOutcomeCode.SENT)
        // Deliberately do not call startActivity or SmsManager: this is a draft contract test only.
    }


    @Test
    fun confirmedDispatchEntersFakeTransportOffMainLooper() = runBlocking {
        val mainLooper = Looper.getMainLooper()
        var transportLooper: Looper? = mainLooper
        var outcome: SmsSendOutcome? = null
        val gate = SmsConfirmationGate()
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { true },
            gate = gate,
            transport = SmsTransport { _, callback ->
                transportLooper = Looper.myLooper()
                callback(SmsSendOutcome.Sent)
            }
        )
        val review = gate.prepare("Test Contact", "+905551112233", "No real SMS")
        val confirmation = gate.confirm(review.reviewId)!!

        SmsDispatchIoRunner().dispatch(dispatcher, confirmation) { outcome = it }

        assertNotSame(mainLooper, transportLooper)
        assertSame(SmsSendOutcome.Sent, outcome)
    }

    @Test
    fun journalAndMultipartCallbackPersistenceRunOffMainLooper() = runBlocking {
        val mainLooper = Looper.getMainLooper()
        val store = LooperRecordingSmsAttemptStore()
        val journal = SmsAttemptJournal(store)
        val journalIo = SmsAttemptJournalIo(journal)
        val recorderIo = SmsPlatformCallbackRecorderIo(
            SmsPlatformCallbackRecorder("io-multipart", journal, Activity.RESULT_OK)
        )

        journalIo.begin("io-multipart", 2)
        assertEquals(SmsSendState.PENDING, recorderIo.record(0, Activity.RESULT_OK))
        assertEquals(SmsSendState.SENT, recorderIo.record(1, Activity.RESULT_OK))

        assertEquals(SmsSendState.SENT, store.get("io-multipart")?.state)
        assertTrue(store.writeLoopers.size >= 3)
        assertTrue(store.writeLoopers.none { it === mainLooper })
    }

    private class LooperRecordingSmsAttemptStore : SmsAttemptStore {
        private val records = ConcurrentHashMap<String, SmsAttemptRecord>()
        val writeLoopers = java.util.Collections.synchronizedList(mutableListOf<Looper?>())
        override fun all(): List<SmsAttemptRecord> = records.values.toList()
        override fun get(requestId: String): SmsAttemptRecord? = records[requestId]
        override fun put(record: SmsAttemptRecord) {
            writeLoopers += Looper.myLooper()
            records[record.requestId] = record
        }
        override fun remove(requestId: String) {
            writeLoopers += Looper.myLooper()
            records.remove(requestId)
        }
    }

    @Test
    fun actionSendToRejectsBlankDraftInputs() {
        assertTrue(runCatching { SmsDraftHandoff.createIntent(" ", "message") }.isFailure)
        assertTrue(runCatching { SmsDraftHandoff.createIntent("+905551234567", " ") }.isFailure)
    }

    @Test
    fun deniedSendPermissionNeverInvokesTransport() {
        val gate = SmsConfirmationGate()
        var transportCalls = 0
        var outcome: SmsSendOutcome? = null
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { false },
            gate = gate,
            transport = SmsTransport { _, callback ->
                transportCalls += 1
                callback(SmsSendOutcome.Sent)
            }
        )
        val review = gate.prepare("Test Contact", "+905551112233", "Exact test message")
        val confirmation = gate.confirm(review.reviewId)!!

        dispatcher.dispatch(confirmation) { outcome = it }

        assertEquals(0, transportCalls)
        assertSame(SmsSendOutcome.PermissionDenied, outcome)
    }

    @Test
    fun oneTimeConfirmationAllowsOnlyOneFakeTransportSend() {
        val gate = SmsConfirmationGate()
        var transportCalls = 0
        var receivedDraft: SmsDraft? = null
        var outcome: SmsSendOutcome? = null
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { true },
            gate = gate,
            transport = SmsTransport { draft, callback ->
                transportCalls += 1
                receivedDraft = draft
                callback(SmsSendOutcome.Sent)
            }
        )
        val review = gate.prepare("Ayşe Test", "+905551234567", "Tam mesaj gövdesi")
        val confirmation = gate.confirm(review.reviewId)!!

        dispatcher.dispatch(confirmation) { outcome = it }
        dispatcher.dispatch(confirmation) { outcome = it }

        assertEquals(1, transportCalls)
        assertEquals("Ayşe Test", receivedDraft?.recipientName)
        assertEquals("+905551234567", receivedDraft?.phoneNumber)
        assertEquals("Tam mesaj gövdesi", receivedDraft?.exactMessage)
        assertSame(SmsSendOutcome.Rejected, outcome)
    }

    @Test
    fun missingPlatformCallbackRemainsUnknownNotSent() {
        val gate = SmsConfirmationGate()
        var outcome: SmsSendOutcome? = null
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { true },
            gate = gate,
            transport = SmsTransport { _, callback ->
                callback(SmsSendOutcome.Unknown("callback missing"))
            }
        )
        val review = gate.prepare("Test Contact", "+905551112233", "No real SMS")
        val confirmation = gate.confirm(review.reviewId)!!

        dispatcher.dispatch(confirmation) { outcome = it }

        assertTrue(outcome is SmsSendOutcome.Unknown)
    }
}
