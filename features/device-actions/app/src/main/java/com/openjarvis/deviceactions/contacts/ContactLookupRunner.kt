package com.openjarvis.deviceactions.contacts

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps synchronous ContactsContract ContentResolver work off the Compose/main thread. */
internal class ContactLookupRunner(
    private val resolver: AndroidContactsResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun resolve(query: String): ContactLookupResult = withContext(ioDispatcher) { resolver.resolve(query) }
}
