package com.openjarvis.deviceactions.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsActionContractTest {
    private class InMemorySmsAttemptStore : SmsAttemptStore {
        private val records = linkedMapOf<String, SmsAttemptRecord>()
        override fun all(): List<SmsAttemptRecord> = records.values.toList()
        override fun get(requestId: String): SmsAttemptRecord? = records[requestId]
        override fun put(record: SmsAttemptRecord) { records[record.requestId] = record }
        override fun remove(requestId: String) { records.remove(requestId) }
    }
    @Test
    fun messageCannotBeConsumedBeforeExplicitConfirmation() {
        val gate = SmsConfirmationGate()
        val review = gate.prepare("İpek Yılmaz", "+905321112233", "Merhaba")
        assertNull(gate.consume(ConfirmedSmsAction(review.reviewId, "not-issued")))
        assertNotNull(gate.confirm(review.reviewId))
    }

    @Test
    fun confirmationIsSingleUse() {
        val gate = SmsConfirmationGate()
        val review = gate.prepare("İpek Yılmaz", "+905321112233", "Tam metin")
        val confirmation = gate.confirm(review.reviewId)!!
        val draft = gate.consume(confirmation)
        assertEquals("Tam metin", draft?.exactMessage)
        assertNull(gate.consume(confirmation))
    }

    @Test
    fun forgedTokenCannotConsumeLegitimateConfirmation() {
        val gate = SmsConfirmationGate()
        val review = gate.prepare("İpek Yılmaz", "+905321112233", "Tam metin")
        val legitimate = gate.confirm(review.reviewId)!!
        assertNull(gate.consume(ConfirmedSmsAction(review.reviewId, "wrong-token")))
        assertEquals("Tam metin", gate.consume(legitimate)?.exactMessage)
    }

    @Test
    fun deniedPermissionCancelsConfirmationAndNeverCallsTransport() {
        val gate = SmsConfirmationGate()
        val review = gate.prepare("İpek Yılmaz", "+905321112233", "Merhaba")
        val confirmation = gate.confirm(review.reviewId)!!
        var transportCalled = false
        var outcome: SmsSendOutcome? = null
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { false },
            gate = gate,
            transport = SmsTransport { _, _ -> transportCalled = true }
        )
        dispatcher.dispatch(confirmation) { outcome = it }
        assertEquals(SmsSendOutcome.PermissionDenied, outcome)
        assertEquals(false, transportCalled)
        assertNull(gate.consume(confirmation))
    }

    @Test
    fun dispatcherConsumesConfirmationBeforeTransportAndRejectsReplay() {
        val gate = SmsConfirmationGate()
        val review = gate.prepare("İpek Yılmaz", "+905321112233", "Tam ve kesin mesaj")
        val confirmation = gate.confirm(review.reviewId)!!
        val sentDrafts = mutableListOf<SmsDraft>()
        val outcomes = mutableListOf<SmsSendOutcome>()
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { true },
            gate = gate,
            transport = SmsTransport { draft, callback ->
                sentDrafts += draft
                callback(SmsSendOutcome.Sent)
            }
        )

        dispatcher.dispatch(confirmation) { outcomes += it }
        dispatcher.dispatch(confirmation) { outcomes += it }

        assertEquals(1, sentDrafts.size)
        assertEquals(review.recipientName, sentDrafts.single().recipientName)
        assertEquals(review.phoneNumber, sentDrafts.single().phoneNumber)
        assertEquals(review.exactMessage, sentDrafts.single().exactMessage)
        assertEquals(listOf(SmsSendOutcome.Sent, SmsSendOutcome.Rejected), outcomes)
    }

    @Test
    fun sentRequiresSuccessfulCallbackForEveryPart() {
        val tracker = SmsSendTracker(2)
        assertEquals(SmsSendState.PENDING, tracker.recordPart(0, SmsPartResult.SENT))
        assertEquals(SmsSendState.SENT, tracker.recordPart(1, SmsPartResult.SENT))
    }

    @Test
    fun androidPlatformResultCodesMapOnlyResultOkToSentForEveryPart() {
        val tracker = SmsSendTracker(3)
        assertEquals(SmsSendState.PENDING, tracker.recordPlatformResult(0, -1, -1))
        assertEquals(SmsSendState.PENDING, tracker.recordPlatformResult(1, -1, -1))
        assertEquals(SmsSendState.SENT, tracker.recordPlatformResult(2, -1, -1))
    }

    @Test
    fun anyNonOkPlatformPartFailsAndLatePartsCannotPromoteToSent() {
        val tracker = SmsSendTracker(2)
        assertEquals(SmsSendState.PENDING, tracker.recordPlatformResult(0, -1, -1))
        assertEquals(SmsSendState.FAILED, tracker.recordPlatformResult(1, 0, -1))
        assertEquals(SmsSendState.FAILED, tracker.recordPlatformResult(1, -1, -1))
    }

    @Test
    fun callbackAfterProcessLossUnknownCannotBecomeSent() {
        val tracker = SmsSendTracker(1)
        assertEquals(SmsSendState.UNKNOWN, tracker.markUnknown())
        assertEquals(SmsSendState.UNKNOWN, tracker.recordPlatformResult(0, -1, -1))
    }

    @Test
    fun changingNameQueryOrResolvedRecipientInvalidatesOldSelection() {
        assertTrue(SmsSelectionInvalidationPolicy.queryChanged("Ayşe", "Ali"))
        assertTrue(SmsSelectionInvalidationPolicy.recipientChanged("Ayşe", "+905551111111", "Ali", "+905552222222"))
        assertTrue(SmsSelectionInvalidationPolicy.recipientChanged("Ayşe", "+905551111111", "Ayşe", "+905553333333"))
        assertEquals(false, SmsSelectionInvalidationPolicy.queryChanged("Ayşe", "Ayşe"))
        assertEquals(false, SmsSelectionInvalidationPolicy.recipientChanged("Ayşe", "+905551111111", "Ayşe", "+905551111111"))
    }

    @Test
    fun durableJournalRequiresEveryMultipartCallbackToBeResultOk() {
        val store = InMemorySmsAttemptStore()
        val journal = SmsAttemptJournal(store)
        journal.begin("request-1", 2)
        assertEquals(SmsSendState.PENDING, journal.recordPlatformResult("request-1", 0, -1, -1))
        assertEquals(SmsSendState.SENT, journal.recordPlatformResult("request-1", 1, -1, -1))
        assertEquals(SmsSendState.SENT, store.get("request-1")?.state)
    }

    @Test
    fun processReopenTurnsMissingSmsCallbackIntoUnknownWithoutReplay() {
        val store = InMemorySmsAttemptStore()
        val beforeRestart = SmsAttemptJournal(store)
        beforeRestart.begin("request-restart", 2)
        beforeRestart.recordPlatformResult("request-restart", 0, -1, -1)

        val afterRestart = SmsAttemptJournal(store)
        assertEquals(1, afterRestart.recoverPendingAsUnknown().size)
        assertEquals(SmsSendState.UNKNOWN, store.get("request-restart")?.state)
        assertEquals(SmsSendState.UNKNOWN, afterRestart.recordPlatformResult("request-restart", 1, -1, -1))
    }

    @Test
    fun failedPartCanNeverBecomeSent() {
        val tracker = SmsSendTracker(2)
        assertEquals(SmsSendState.FAILED, tracker.recordPart(0, SmsPartResult.FAILED))
        assertEquals(SmsSendState.FAILED, tracker.recordPart(1, SmsPartResult.SENT))
    }

    @Test
    fun missingCallbackIsUnknownNotSent() {
        val tracker = SmsSendTracker(1)
        assertEquals(SmsSendState.UNKNOWN, tracker.markUnknown())
    }

    @Test
    fun invalidPhoneAndEmptyMessageCannotCreateReview() {
        val gate = SmsConfirmationGate()
        assertTrue(runCatching { gate.prepare("İpek", "123abc456", "Merhaba") }.isFailure)
        assertTrue(runCatching { gate.prepare("İpek", "abc", "Merhaba") }.isFailure)
        assertTrue(runCatching { gate.prepare("İpek", "+905321112233", "  ") }.isFailure)
    }
    @Test
    fun platformCallbackRecorderPersistsEveryMultipartResultOkBeforeSent() {
        val store = InMemorySmsAttemptStore()
        val journal = SmsAttemptJournal(store)
        journal.begin("receiver-ok", 3)
        val recorder = SmsPlatformCallbackRecorder("receiver-ok", journal, -1)

        assertEquals(SmsSendState.PENDING, recorder.record(0, -1))
        assertEquals(SmsSendState.PENDING, recorder.record(1, -1))
        assertEquals(SmsSendState.SENT, recorder.record(2, -1))
        assertEquals(SmsSendState.SENT, store.get("receiver-ok")?.state)
    }

    @Test
    fun platformCallbackRecorderKeepsFailureTerminalWhenLaterMultipartCallbackSucceeds() {
        val store = InMemorySmsAttemptStore()
        val journal = SmsAttemptJournal(store)
        journal.begin("receiver-failed", 3)
        val recorder = SmsPlatformCallbackRecorder("receiver-failed", journal, -1)

        assertEquals(SmsSendState.PENDING, recorder.record(0, -1))
        assertEquals(SmsSendState.FAILED, recorder.record(1, 0))
        assertEquals(SmsSendState.FAILED, recorder.record(2, -1))
        assertEquals(SmsSendState.FAILED, store.get("receiver-failed")?.state)
    }

    @Test
    fun callbackAfterProcessRecoveryRemainsUnknownAndCannotBecomeSent() {
        val store = InMemorySmsAttemptStore()
        val beforeRestart = SmsAttemptJournal(store)
        beforeRestart.begin("receiver-restart", 2)
        assertEquals(SmsSendState.PENDING, beforeRestart.recordPlatformResult("receiver-restart", 0, -1, -1))

        val afterRestart = SmsAttemptJournal(store)
        assertEquals(1, afterRestart.recoverPendingAsUnknown().size)
        val recorder = SmsPlatformCallbackRecorder("receiver-restart", afterRestart, -1)
        assertEquals(SmsSendState.UNKNOWN, recorder.record(1, -1))
        assertEquals(SmsSendState.UNKNOWN, store.get("receiver-restart")?.state)
    }

    @Test
    fun duplicateMultipartCallbackCannotCountAsASecondPart() {
        val store = InMemorySmsAttemptStore()
        val journal = SmsAttemptJournal(store)
        journal.begin("receiver-duplicate", 2)
        val recorder = SmsPlatformCallbackRecorder("receiver-duplicate", journal, -1)

        assertEquals(SmsSendState.PENDING, recorder.record(0, -1))
        assertEquals(SmsSendState.PENDING, recorder.record(0, -1))
        assertEquals(SmsSendState.SENT, recorder.record(1, -1))
    }


    @Test
    fun dispatcherExposesRecoveredUnknownCountForUiWithoutExposingTransport() {
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { true },
            gate = SmsConfirmationGate(),
            transport = SmsTransport { _, callback -> callback(SmsSendOutcome.Rejected) },
            recoveredUnknownCount = 2
        )

        assertEquals(2, dispatcher.recoveredUnknownCount)
    }

}
