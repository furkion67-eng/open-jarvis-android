package com.openjarvis.commands

import com.openjarvis.deviceactions.sms.ConfirmedSmsDispatcher
import com.openjarvis.deviceactions.sms.SmsConfirmationGate
import com.openjarvis.deviceactions.sms.SmsPermission
import com.openjarvis.deviceactions.sms.SmsSendOutcome
import com.openjarvis.deviceactions.sms.SmsTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactChoicePagingTest {
    private val candidates = (1..6).map { index ->
        ContactMatcher.Match("Kişi $index", "+90555000000$index", 100)
    }

    @Test
    fun sixthCandidateIsVisibleOnNextPageAndCanBeSelectedByItsDisplayedOrdinal() {
        val firstPage = ContactChoicePaging.page(candidates, requestedPageIndex = 0)

        assertEquals(5, firstPage.items.size)
        assertTrue(firstPage.hasNext)
        assertFalse(firstPage.items.contains(candidates[5]))

        val secondPageIndex = ContactChoicePaging.navigate(
            command = "sonraki sayfa",
            currentPageIndex = firstPage.pageIndex,
            totalItems = candidates.size
        )!!
        val secondPage = ContactChoicePaging.page(candidates, secondPageIndex)

        assertEquals(2, secondPage.pageCount)
        assertEquals(6, secondPage.startOrdinal)
        assertEquals(listOf(candidates[5]), secondPage.items)

        val selection = ContactChoiceSelector.select("6", candidates)
        assertTrue(selection is ContactChoiceSelector.Result.Selected)
        assertEquals(candidates[5], (selection as ContactChoiceSelector.Result.Selected).match)
    }

    @Test
    fun selectingSixthCandidateDoesNotSendUntilSeparateConfirmationTokenIsConsumed() {
        val selection = ContactChoiceSelector.select("6", candidates)
            as ContactChoiceSelector.Result.Selected
        val selected = selection.match
        var transportCalls = 0
        val gate = SmsConfirmationGate()

        // Contact selection alone cannot mint or consume an SMS confirmation.
        assertNull(gate.confirm("not-prepared"))
        assertEquals(0, transportCalls)

        val review = gate.prepare(selected.label, selected.value, "Altıncı kişiye mesaj")
        val confirmation = gate.confirm(review.reviewId)!!
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { true },
            gate = gate,
            transport = SmsTransport { _, callback ->
                transportCalls += 1
                callback(SmsSendOutcome.Sent)
            }
        )

        assertEquals(0, transportCalls)
        dispatcher.dispatch(confirmation) {}
        assertEquals(1, transportCalls)
    }

    @Test
    fun navigationNormalizesTurkishCasePunctuationAndRepeatedWhitespace() {
        assertEquals(
            1,
            ContactChoicePaging.navigate("  SONRAKI   SAYFA. ", 0, candidates.size)
        )
        assertEquals(
            0,
            ContactChoicePaging.navigate("  ÖNCEKİ\tSAYFA! ", 1, candidates.size)
        )
        assertEquals(
            1,
            ContactChoicePaging.navigate("!!! SONRAKI, SAYFA!!!", 0, candidates.size)
        )
        assertEquals(
            0,
            ContactChoicePaging.navigate("ÖNCEKİ? SAYFA", 1, candidates.size)
        )
    }

    @Test
    fun explicitNavigationDoesNotChangePageForAnUnrecognizedCommandOrPastLastPage() {
        assertNull(ContactChoicePaging.navigate("altıncı kişi", 0, candidates.size))
        assertNull(ContactChoicePaging.navigate("sonraki sayfa", 1, candidates.size))
        assertNull(ContactChoicePaging.navigate("önceki sayfa", 0, candidates.size))
        assertEquals(0, ContactChoicePaging.navigate("önceki sayfa", 1, candidates.size))
    }
}
