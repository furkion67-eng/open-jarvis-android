package com.openjarvis.deviceactions.contacts

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.test.mock.MockContentProvider
import android.test.mock.MockContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AndroidContactsResolverProviderInstrumentedTest {
    private lateinit var provider: FakeContactsProvider
    private lateinit var contentResolver: MockContentResolver
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        provider = FakeContactsProvider()
        contentResolver = MockContentResolver().apply {
            addProvider(ContactsContract.AUTHORITY, provider)
        }
    }

    @Test
    fun readContactsDeniedDoesNotQueryContactsContractProvider() {
        val result = resolver(permissionGranted = false).resolve("İpek Yılmaz")

        assertTrue(result is ContactLookupResult.PermissionDenied)
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun permissionRevokedByProviderDuringQueryReturnsDeniedNotNoMatch() {
        provider.failure = SecurityException("READ_CONTACTS revoked during query")

        val result = resolver(permissionGranted = true).resolve("İpek Yılmaz")

        assertTrue(result is ContactLookupResult.PermissionDenied)
        assertEquals(1, provider.queryCount)
    }

    @Test
    fun exactTurkishNameResolvesUniquePhoneThroughContactsContractUriAndProjection() {
        provider.rows = listOf(
            FakePhoneRow(7L, "İpek Yılmaz", "+90 532 111 22 33", "Cep")
        )

        val result = resolver().resolve("ipek yilmaz")

        assertTrue(result is ContactLookupResult.Unique)
        assertEquals("+90 532 111 22 33", (result as ContactLookupResult.Unique).phone.number)
        assertEquals(Phone.CONTENT_URI, provider.lastUri)
        assertTrue(provider.lastSortOrder.orEmpty().contains("DISPLAY_NAME_PRIMARY"))
        assertTrue(provider.lastProjection.contains(Phone.CONTACT_ID))
        assertTrue(provider.lastProjection.contains(Phone.NUMBER))
    }

    @Test
    fun sameNameContactsAreSortedAndPagedWithoutDroppingCandidates() {
        provider.rows = listOf(
            FakePhoneRow(30L, "Ali Demir", "+90 555 000 0030", "Cep"),
            FakePhoneRow(10L, "Ali Demir", "+90 555 000 0010", "Cep"),
            FakePhoneRow(20L, "Ali Demir", "+90 555 000 0020", "Cep"),
            FakePhoneRow(40L, "Ali Demir", "+90 555 000 0040", "Cep"),
            FakePhoneRow(50L, "Ali Demir", "+90 555 000 0050", "Cep"),
            FakePhoneRow(60L, "Ali Demir", "+90 555 000 0060", "Cep"),
            FakePhoneRow(70L, "Ali Demir", "+90 555 000 0070", "Cep")
        )

        val result = resolver().resolve("Ali Demir")

        assertTrue(result is ContactLookupResult.ChooseContact)
        val candidates = (result as ContactLookupResult.ChooseContact).candidates
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L), candidates.map { it.contactId })

        val first = ContactCandidatePager.page(candidates, requestedPage = 0, pageSize = 3)
        val second = ContactCandidatePager.page(candidates, requestedPage = 1, pageSize = 3)
        val last = ContactCandidatePager.page(candidates, requestedPage = 2, pageSize = 3)
        assertEquals(listOf(10L, 20L, 30L), first.items.map { it.contactId })
        assertEquals(listOf(40L, 50L, 60L), second.items.map { it.contactId })
        assertEquals(listOf(70L), last.items.map { it.contactId })
    }

    @Test
    fun oneContactWithMultipleNumbersRequiresExplicitNumberChoice() {
        provider.rows = listOf(
            FakePhoneRow(9L, "Zeynep Kaya", "+90 555 444 44 44", "İş"),
            FakePhoneRow(9L, "Zeynep Kaya", "+90 555 333 33 33", "Cep")
        )

        val result = resolver().resolve("Zeynep Kaya")

        assertTrue(result is ContactLookupResult.ChooseNumber)
        assertEquals(
            listOf("Cep", "İş"),
            (result as ContactLookupResult.ChooseNumber).contact.phoneNumbers.map { it.label }
        )
    }

    @Test
    fun noMatchIsExplicitAfterARealProviderQuery() {
        provider.rows = listOf(FakePhoneRow(1L, "Ayşe Yılmaz", "+90 555 111 11 11", "Cep"))

        val result = resolver().resolve("Mehmet")

        assertTrue(result is ContactLookupResult.NoMatch)
        assertEquals(1, provider.queryCount)
    }

    @Test
    fun nullProviderCursorAndProviderFailureAreUnavailable() {
        provider.returnNull = true
        assertTrue(resolver().resolve("İpek") is ContactLookupResult.Unavailable)

        provider.returnNull = false
        provider.failure = IllegalStateException("contacts provider unavailable")
        assertTrue(resolver().resolve("İpek") is ContactLookupResult.Unavailable)
    }

    private fun resolver(permissionGranted: Boolean = true): AndroidContactsResolver =
        AndroidContactsResolver(
            context = context,
            contentResolver = contentResolver,
            hasReadContactsPermission = { permissionGranted }
        )

    private data class FakePhoneRow(
        val contactId: Long,
        val displayName: String,
        val number: String,
        val label: String
    )

    private class FakeContactsProvider : MockContentProvider() {
        var rows: List<FakePhoneRow> = emptyList()
        var failure: RuntimeException? = null
        var returnNull: Boolean = false
        var queryCount: Int = 0
        var lastUri: Uri? = null
        var lastSortOrder: String? = null
        var lastProjection: List<String> = emptyList()

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            queryCount += 1
            lastUri = uri
            lastSortOrder = sortOrder
            lastProjection = projection?.toList().orEmpty()
            failure?.let { throw it }
            if (returnNull) return null

            val columns = projection?.toTypedArray() ?: arrayOf(
                Phone.CONTACT_ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                Phone.NUMBER,
                Phone.TYPE,
                Phone.LABEL
            )
            val cursor = MatrixCursor(columns)
            rows.sortedWith(
                compareBy<FakePhoneRow> { it.displayName.lowercase(Locale.forLanguageTag("tr-TR")) }
                    .thenBy { it.contactId }
                    .thenBy { it.number }
            ).forEach { row ->
                val values = columns.map { column ->
                    when (column) {
                        Phone.CONTACT_ID -> row.contactId
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY -> row.displayName
                        Phone.NUMBER -> row.number
                        Phone.TYPE -> Phone.TYPE_CUSTOM
                        Phone.LABEL -> row.label
                        else -> null
                    }
                }.toTypedArray()
                cursor.addRow(values)
            }
            return cursor
        }
    }
}
