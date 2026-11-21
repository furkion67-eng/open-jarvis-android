package com.openjarvis.deviceactions.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolutionTest {
    private val rows = listOf(
        ContactPhoneRecord(1, "İpek Yılmaz", "+90 532 111 22 33", "Cep"),
        ContactPhoneRecord(2, "Işık Demir", "+90 533 444 55 66", "Cep"),
        ContactPhoneRecord(3, "Ali Veli", "+90 534 111 22 33", "Cep"),
        ContactPhoneRecord(3, "Ali Veli", "+90 212 111 22 33", "İş"),
        ContactPhoneRecord(4, "Ali Veli Can", "+90 535 111 22 33", "Cep")
    )

    @Test
    fun uniqueTurkishNameResolvesContactAndNumberWithoutManualInput() {
        val result = ContactResolver.resolve("ipek yilmaz", rows)
        assertTrue(result is ContactLookupResult.Unique)
        assertEquals("+90 532 111 22 33", (result as ContactLookupResult.Unique).phone.number)
    }

    @Test
    fun multipleContactsRequireExplicitChoice() {
        val result = ContactResolver.resolve("ali", rows)
        assertTrue(result is ContactLookupResult.ChooseContact)
        assertEquals(2, (result as ContactLookupResult.ChooseContact).candidates.size)
    }

    @Test
    fun oneContactWithMultipleNumbersRequiresNumberChoice() {
        val result = ContactResolver.resolve("ali veli", rows)
        assertTrue(result is ContactLookupResult.ChooseNumber)
        assertEquals(2, (result as ContactLookupResult.ChooseNumber).contact.phoneNumbers.size)
    }

    @Test
    fun noMatchIsExplicit() {
        assertTrue(ContactResolver.resolve("olmayan kişi", rows) is ContactLookupResult.NoMatch)
    }

    @Test
    fun blankQueryIsRejected() {
        assertTrue(ContactResolver.resolve("   ", rows) is ContactLookupResult.InvalidQuery)
    }

    @Test
    fun providerFailureIsUnavailableRatherThanReportedAsNoMatch() {
        val useCase = ContactLookupUseCase(
            hasReadContactsPermission = { true },
            loadPhoneRows = { throw IllegalStateException("provider unavailable") }
        )
        val result = useCase.resolve("İpek")
        assertTrue(result is ContactLookupResult.Unavailable)
    }

    @Test
    fun deniedPermissionNeverQueriesProvider() {
        var queried = false
        val useCase = ContactLookupUseCase(
            hasReadContactsPermission = { false },
            loadPhoneRows = { queried = true; rows }
        )
        assertTrue(useCase.resolve("İpek") is ContactLookupResult.PermissionDenied)
        assertEquals(false, queried)
    }

    @Test
    fun revokedPermissionDuringQueryIsReportedAsDenied() {
        val useCase = ContactLookupUseCase(
            hasReadContactsPermission = { true },
            loadPhoneRows = { throw SecurityException("revoked") }
        )
        assertTrue(useCase.resolve("İpek") is ContactLookupResult.PermissionDenied)
    }
}
