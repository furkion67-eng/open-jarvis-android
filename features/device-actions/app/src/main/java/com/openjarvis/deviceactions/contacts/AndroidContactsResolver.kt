package com.openjarvis.deviceactions.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

class AndroidContactsResolver(
    context: Context,
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver,
    hasReadContactsPermission: () -> Boolean = {
        context.applicationContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    },
    /** Test seam records the caller at the ContentResolver boundary, before Binder dispatch. */
    private val queryThreadObserver: ((Thread) -> Unit)? = null
) {
    private val appContext = context.applicationContext
    private val useCase = ContactLookupUseCase(
        hasReadContactsPermission = hasReadContactsPermission,
        loadPhoneRows = ::queryPhoneRows
    )

    fun resolve(displayNameQuery: String): ContactLookupResult = useCase.resolve(displayNameQuery)

    private fun queryPhoneRows(): List<ContactPhoneRecord> {
        val projection = arrayOf(
            Phone.CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL
        )
        val rows = mutableListOf<ContactPhoneRecord>()
        // Observe the actual caller thread before ContentResolver can cross into provider Binder code.
        queryThreadObserver?.invoke(Thread.currentThread())
        contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val numberColumn = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            val typeColumn = cursor.getColumnIndexOrThrow(Phone.TYPE)
            val labelColumn = cursor.getColumnIndexOrThrow(Phone.LABEL)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberColumn).orEmpty()
                val name = cursor.getString(nameColumn).orEmpty()
                if (name.isBlank() || number.isBlank()) continue
                val label = Phone.getTypeLabel(
                    appContext.resources,
                    cursor.getInt(typeColumn),
                    cursor.getString(labelColumn)
                ).toString()
                rows += ContactPhoneRecord(
                    contactId = cursor.getLong(idColumn),
                    displayName = name,
                    number = number,
                    label = label
                )
            }
        } ?: throw IllegalStateException("Android Rehber sağlayıcısı yanıt vermedi.")
        return rows
    }
}
