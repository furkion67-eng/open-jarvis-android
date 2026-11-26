package com.openjarvis.deviceactions.contacts

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactResolutionInstrumentedTest {
    @Test
    fun deniedPermissionDoesNotReadContacts() {
        var queryCalls = 0
        val useCase = ContactLookupUseCase(
            hasReadContactsPermission = { false },
            loadPhoneRows = { queryCalls += 1; emptyList() }
        )

        assertSameKind(ContactLookupResult.PermissionDenied, useCase.resolve("Ayşe"))
        assertEquals(0, queryCalls)
    }

    @Test
    fun noMatchAndUniqueContactHaveExplicitOutcomes() {
        val rows = listOf(
            ContactPhoneRecord(1, "Ayşe Yılmaz", "+905551112233", "Mobile")
        )
        assertTrue(ContactResolver.resolve("Mehmet", rows) is ContactLookupResult.NoMatch)
        val unique = ContactResolver.resolve("Ayşe Yılmaz", rows)
        assertTrue(unique is ContactLookupResult.Unique)
        assertEquals("+905551112233", (unique as ContactLookupResult.Unique).phone.number)
    }

    @Test
    fun duplicateContactsAndMultipleNumbersRequireUserChoice() {
        val duplicateRows = listOf(
            ContactPhoneRecord(1, "Ali Demir", "+905551111111", "Mobile"),
            ContactPhoneRecord(2, "Ali Demir", "+905552222222", "Work")
        )
        assertTrue(ContactResolver.resolve("Ali Demir", duplicateRows) is ContactLookupResult.ChooseContact)

        val multipleNumbers = listOf(
            ContactPhoneRecord(3, "Zeynep Kaya", "+905553333333", "Mobile"),
            ContactPhoneRecord(3, "Zeynep Kaya", "+905554444444", "Work")
        )
        assertTrue(ContactResolver.resolve("Zeynep Kaya", multipleNumbers) is ContactLookupResult.ChooseNumber)
    }

    private fun assertSameKind(expected: ContactLookupResult, actual: ContactLookupResult) {
        assertEquals(expected::class, actual::class)
    }
}
