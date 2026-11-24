package com.openjarvis.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Infrastructure-only overlay copied into the extracted app androidTest source set during CI. */
@RunWith(AndroidJUnit4::class)
class ContactChoicePagingCiOverlayTest {
    @Test
    fun sixthAndLaterCandidatesRemainReachableWithStableGlobalOrdinals() {
        val candidates = (1..12).map { index ->
            ContactMatcher.Match("Kişi $index", "+90555000000$index", 100)
        }
        val first = ContactChoicePaging.page(candidates, 0)
        assertEquals(5, first.items.size)
        assertEquals(1, first.startOrdinal)
        assertTrue(first.hasNext)
        assertFalse(first.items.contains(candidates[5]))
        val secondIndex = ContactChoicePaging.navigate("SONRAKI SAYFA.", 0, candidates.size)
            ?: throw AssertionError("next page command did not advance")
        val second = ContactChoicePaging.page(candidates, secondIndex)
        assertEquals(6, second.startOrdinal)
        assertEquals(candidates[5], second.items.first())
        val thirdIndex = ContactChoicePaging.navigate("sonraki, sayfa!", second.pageIndex, candidates.size)
            ?: throw AssertionError("third page command did not advance")
        val third = ContactChoicePaging.page(candidates, thirdIndex)
        assertEquals(11, third.startOrdinal)
        assertEquals(candidates.drop(10), third.items)
        assertFalse(third.hasNext)
        assertNull(ContactChoicePaging.navigate("sonraki sayfa", third.pageIndex, candidates.size))
    }

    @Test
    fun actionPhrasesAreNotMistakenForPageNavigation() {
        assertNull(ContactChoicePaging.navigate("altıncı kişiyi ara", 0, 6))
        assertNull(ContactChoicePaging.navigate("önceki sayfa", 0, 6))
    }
}
