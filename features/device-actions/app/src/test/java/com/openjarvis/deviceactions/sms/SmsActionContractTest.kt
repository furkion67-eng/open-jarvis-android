package com.openjarvis.deviceactions.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsActionContractTest {
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
    fun sentRequiresSuccessfulCallbackForEveryPart() {
        val tracker = SmsSendTracker(2)
        assertEquals(SmsSendState.PENDING, tracker.recordPart(0, SmsPartResult.SENT))
        assertEquals(SmsSendState.SENT, tracker.recordPart(1, SmsPartResult.SENT))
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
}
