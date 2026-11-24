package com.openjarvis.deviceactions.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.rule.provider.ProviderTestRule
import com.openjarvis.commands.ContactChoicePaging
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import com.openjarvis.deviceactions.sms.ConfirmedSmsDispatcher
import com.openjarvis.deviceactions.sms.SmsConfirmationGate
import com.openjarvis.deviceactions.sms.SmsPermission
import com.openjarvis.deviceactions.sms.SmsSendOutcome
import com.openjarvis.deviceactions.sms.SmsTransport

@RunWith(AndroidJUnit4::class)
class AndroidContactsResolverProviderInstrumentedTest {
    @Rule
    @JvmField
    val providerRule: ProviderTestRule =
        ProviderTestRule.Builder(FakeContactsProvider::class.java, ContactsContract.AUTHORITY).build()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val contentResolver
        get() = providerRule.resolver

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
    }

    @Test
    fun readContactsDeniedDoesNotQueryContactsContractProvider() {
        val result = resolver(permissionGranted = false).resolve("İpek Yılmaz")

        assertTrue(result is ContactLookupResult.PermissionDenied)
        assertEquals(0, FakeContactsProvider.queryCount)
    }

    @Test
    fun permissionRevokedByProviderDuringQueryReturnsDeniedNotNoMatch() {
        FakeContactsProvider.failure = SecurityException("READ_CONTACTS revoked during query")

        val result = resolver(permissionGranted = true).resolve("İpek Yılmaz")

        assertTrue(result is ContactLookupResult.PermissionDenied)
        assertEquals(1, FakeContactsProvider.queryCount)
    }

    @Test
    fun exactTurkishNameResolvesUniquePhoneThroughContactsContractUriAndProjection() {
        FakeContactsProvider.rows = listOf(
            FakePhoneRow(7L, "İpek Yılmaz", "+90 532 111 22 33", "Cep")
        )

        val result = resolver().resolve("ipek yilmaz")

        assertTrue(result is ContactLookupResult.Unique)
        assertEquals("+90 532 111 22 33", (result as ContactLookupResult.Unique).phone.number)
        assertEquals(Phone.CONTENT_URI, FakeContactsProvider.lastUri)
        assertTrue(FakeContactsProvider.lastSortOrder.orEmpty().contains("DISPLAY_NAME_PRIMARY"))
        assertTrue(FakeContactsProvider.lastProjection.contains(Phone.CONTACT_ID))
        assertTrue(FakeContactsProvider.lastProjection.contains(Phone.NUMBER))
    }

    @Test
    fun contactLookupRunnerInvokesContentResolverOffMainThreadWhenCalledFromMain() {
        FakeContactsProvider.rows = listOf(FakePhoneRow(7L, "İpek Yılmaz", "+90 532 111 22 33", "Cep"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mainThread = AtomicReference<Thread>()
        val queryThread = AtomicReference<Thread>()
        var result: ContactLookupResult? = null

        instrumentation.runOnMainSync {
            mainThread.set(Thread.currentThread())
            runBlocking {
                result = ContactLookupRunner(
                    resolver(queryThreadObserver = { queryThread.set(it) })
                ).resolve("İpek Yılmaz")
            }
        }

        assertTrue(result is ContactLookupResult.Unique)
        assertNotSame("Contacts lookup must leave the actual Android main thread.", mainThread.get(), queryThread.get())
        assertTrue("The query-boundary thread must be captured.", queryThread.get() != null)
    }

    @Test
    fun sameNameContactsAreSortedAndPagedWithoutDroppingCandidates() {
        FakeContactsProvider.rows = listOf(
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

        val first = ContactChoicePaging.page(candidates, requestedPageIndex = 0, pageSize = 3)
        val second = ContactChoicePaging.page(candidates, requestedPageIndex = 1, pageSize = 3)
        val last = ContactChoicePaging.page(candidates, requestedPageIndex = 2, pageSize = 3)
        assertEquals(listOf(10L, 20L, 30L), first.items.map { it.contactId })
        assertEquals(listOf(40L, 50L, 60L), second.items.map { it.contactId })
        assertEquals(listOf(70L), last.items.map { it.contactId })
    }

    @Test
    fun oneContactWithMultipleNumbersRequiresExplicitNumberChoice() {
        FakeContactsProvider.rows = listOf(
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
    fun selectedProviderContactAndExactMessageRequireOneExplicitConfirmation() {
        FakeContactsProvider.rows = listOf(
            FakePhoneRow(9L, "Zeynep Kaya", "+90 555 444 44 44", "İş"),
            FakePhoneRow(9L, "Zeynep Kaya", "+90 555 333 33 33", "Cep")
        )
        val lookup = resolver().resolve("Zeynep Kaya")
        assertTrue(lookup is ContactLookupResult.ChooseNumber)
        val choice = lookup as ContactLookupResult.ChooseNumber
        val selectedPhone = choice.contact.phoneNumbers.first()
        val exactMessage = "Yarın saat 10.00'da görüşelim."

        val gate = SmsConfirmationGate()
        var transportCalls = 0
        var deliveredDraft: com.openjarvis.deviceactions.sms.SmsDraft? = null
        var outcome: SmsSendOutcome? = null
        val dispatcher = ConfirmedSmsDispatcher(
            permission = SmsPermission { true },
            gate = gate,
            transport = SmsTransport { draft, callback ->
                transportCalls += 1
                deliveredDraft = draft
                callback(SmsSendOutcome.Sent)
            }
        )

        assertSame(null, gate.confirm("not-prepared"))
        assertEquals(0, transportCalls)
        val review = gate.prepare(choice.contact.displayName, selectedPhone.number, exactMessage)
        val confirmation = gate.confirm(review.reviewId)!!
        assertEquals(0, transportCalls)

        dispatcher.dispatch(confirmation) { outcome = it }
        assertEquals(1, transportCalls)
        assertEquals("Zeynep Kaya", deliveredDraft?.recipientName)
        assertEquals(selectedPhone.number, deliveredDraft?.phoneNumber)
        assertEquals(exactMessage, deliveredDraft?.exactMessage)
        assertSame(SmsSendOutcome.Sent, outcome)

        dispatcher.dispatch(confirmation) { outcome = it }
        assertEquals(1, transportCalls)
        assertSame(SmsSendOutcome.Rejected, outcome)
    }

    @Test
    fun noMatchIsExplicitAfterARealProviderQuery() {
        FakeContactsProvider.rows = listOf(FakePhoneRow(1L, "Ayşe Yılmaz", "+90 555 111 11 11", "Cep"))

        val result = resolver().resolve("Mehmet")

        assertTrue(result is ContactLookupResult.NoMatch)
        assertEquals(1, FakeContactsProvider.queryCount)
    }

    @Test
    fun nullProviderCursorAndProviderFailureAreUnavailable() {
        FakeContactsProvider.returnNull = true
        assertTrue(resolver().resolve("İpek") is ContactLookupResult.Unavailable)

        FakeContactsProvider.returnNull = false
        FakeContactsProvider.failure = IllegalStateException("contacts provider unavailable")
        assertTrue(resolver().resolve("İpek") is ContactLookupResult.Unavailable)
    }

    private fun resolver(
        permissionGranted: Boolean = true,
        queryThreadObserver: ((Thread) -> Unit)? = null
    ): AndroidContactsResolver =
        AndroidContactsResolver(
            context = context,
            contentResolver = contentResolver,
            hasReadContactsPermission = { permissionGranted },
            queryThreadObserver = queryThreadObserver
        )

    data class FakePhoneRow(
        val contactId: Long,
        val displayName: String,
        val number: String,
        val label: String
    )

    class FakeContactsProvider : ContentProvider() {
        companion object {
            var rows: List<FakePhoneRow> = emptyList()
            var failure: RuntimeException? = null
            var returnNull: Boolean = false
            var queryCount: Int = 0
            var lastUri: Uri? = null
            var lastSortOrder: String? = null
            var lastProjection: List<String> = emptyList()
            var queryLooper: Looper? = null

            fun reset() {
                rows = emptyList()
                failure = null
                returnNull = false
                queryCount = 0
                lastUri = null
                lastSortOrder = null
                lastProjection = emptyList()
                queryLooper = null
            }
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            queryCount += 1
            queryLooper = Looper.myLooper()
            lastUri = uri
            lastSortOrder = sortOrder
            lastProjection = projection?.map { it }.orEmpty()
            failure?.let { throw it }
            if (returnNull) return null

            val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(
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

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }
}
