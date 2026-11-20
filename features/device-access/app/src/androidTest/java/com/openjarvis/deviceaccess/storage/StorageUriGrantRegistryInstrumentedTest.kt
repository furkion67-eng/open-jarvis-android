package com.openjarvis.deviceaccess.storage

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageUriGrantRegistryInstrumentedTest {
    @Test
    fun returnedReadWriteFlagsPersistAndRestoreAcrossRegistryReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("jarvis_storage_uri_grants", 0)
        val history = context.getSharedPreferences("jarvis_capability_history", 0)
        preferences.edit().clear().commit()
        history.edit().clear().commit()
        val access = FakeStorageUriGrantAccess()
        val uri = Uri.parse("content://jarvis.fake.documents/tree/root")
        val returnedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        StorageUriGrantRegistry(context, access).persistPickerResult(uri, returnedFlags)

        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            access.lastTakenFlags
        )
        val reopened = StorageUriGrantRegistry(context, access)
        assertEquals(uri, reopened.lastGrantedTreeUri())
        assertEquals(listOf(uri), reopened.grantedTreeUris())
        assertTrue(StorageUriGrantPolicy.hasUriGrant(uri.toString(), access.snapshots(), requireWrite = true))
        assertTrue(history.getBoolean("requested.shared-storage-read", false))
        assertTrue(history.getBoolean("grantedBefore.shared-storage-read", false))
        assertTrue(history.getBoolean("requested.shared-storage-write", false))
        assertTrue(history.getBoolean("grantedBefore.shared-storage-write", false))
        preferences.edit().clear().commit()
        history.edit().clear().commit()
    }

    @Test
    fun revokedPersistedGrantIsRemovedAfterRegistryReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("jarvis_storage_uri_grants", 0)
        preferences.edit().clear().commit()
        val access = FakeStorageUriGrantAccess()
        val uri = Uri.parse("content://jarvis.fake.documents/tree/revoked")
        val registry = StorageUriGrantRegistry(context, access)
        registry.persistPickerResult(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        access.revoke(uri)

        val reopened = StorageUriGrantRegistry(context, access)
        assertNull(reopened.lastGrantedTreeUri())
        assertTrue(reopened.grantedTreeUris().isEmpty())
        assertFalse(preferences.getStringSet("uris", emptySet()).orEmpty().contains(uri.toString()))
        preferences.edit().clear().commit()
    }

    @Test
    fun writeAccessIsNeverInventedWhenPickerReturnsReadOnlyGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("jarvis_storage_uri_grants", 0)
        val history = context.getSharedPreferences("jarvis_capability_history", 0)
        preferences.edit().clear().commit()
        history.edit().clear().commit()
        val access = FakeStorageUriGrantAccess()
        val uri = Uri.parse("content://jarvis.fake.documents/tree/read-only")
        StorageUriGrantRegistry(context, access).persistPickerResult(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        assertTrue(StorageUriGrantPolicy.hasUriGrant(uri.toString(), access.snapshots(), requireWrite = false))
        assertFalse(StorageUriGrantPolicy.hasUriGrant(uri.toString(), access.snapshots(), requireWrite = true))
        assertTrue(history.getBoolean("requested.shared-storage-read", false))
        assertTrue(history.getBoolean("grantedBefore.shared-storage-read", false))
        assertTrue(history.getBoolean("requested.shared-storage-write", false))
        assertFalse(history.getBoolean("grantedBefore.shared-storage-write", false))
        preferences.edit().clear().commit()
        history.edit().clear().commit()
    }

    @Test
    fun pickerResultWithoutReadPermissionIsRejectedBeforePersisting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val access = FakeStorageUriGrantAccess()
        val uri = Uri.parse("content://jarvis.fake.documents/tree/no-read")
        val error = runCatching {
            StorageUriGrantRegistry(context, access).persistPickerResult(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertNull(access.lastTakenFlags)
    }

    @Test
    fun nonPersistablePickerResultIsRejectedBeforeTakingAnyGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("jarvis_storage_uri_grants", 0)
        preferences.edit().clear().commit()
        val access = FakeStorageUriGrantAccess()
        val uri = Uri.parse("content://jarvis.fake.documents/tree/not-persistable")
        val error = runCatching {
            StorageUriGrantRegistry(context, access).persistPickerResult(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertNull(access.lastTakenFlags)
        preferences.edit().clear().commit()
    }

    private class FakeStorageUriGrantAccess : StorageUriGrantAccess {
        private val grants = linkedMapOf<String, StorageGrantSnapshot>()
        var lastTakenFlags: Int? = null
            private set

        override fun takePersistableUriPermission(uri: Uri, flags: Int) {
            lastTakenFlags = flags
            grants[uri.toString()] = StorageGrantSnapshot(
                uri = uri.toString(),
                authority = uri.authority,
                treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull(),
                hasReadPermission = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
                hasWritePermission = flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
            )
        }

        override fun releasePersistableUriPermission(uri: Uri, flags: Int) { grants.remove(uri.toString()) }
        override fun snapshots(): List<StorageGrantSnapshot> = grants.values.toList()
        fun revoke(uri: Uri) { grants.remove(uri.toString()) }
    }
}
