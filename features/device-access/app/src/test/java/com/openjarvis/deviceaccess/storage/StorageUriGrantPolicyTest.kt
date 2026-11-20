package com.openjarvis.deviceaccess.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUriGrantPolicyTest {
    @Test
    fun onlyPickerReturnedReadWriteFlagsArePersisted() {
        val read = 1
        val write = 2
        val persistable = 4
        assertEquals(read or write, StorageUriGrantPolicy.persistableFlags(read or persistable, read, write))
        assertEquals(write, StorageUriGrantPolicy.persistableFlags(write or persistable, read, write))
        assertEquals(0, StorageUriGrantPolicy.persistableFlags(persistable, read, write))
    }

    @Test
    fun onlyAnExplicitPersistablePickerFlagAllowsTakingTheGrant() {
        val read = 1
        val persistable = 4
        assertTrue(StorageUriGrantPolicy.hasPersistableGrant(read or persistable, persistable))
        assertFalse(StorageUriGrantPolicy.hasPersistableGrant(read, persistable))
    }

    @Test
    fun processRestartRestoreRequiresMatchingLiveReadGrant() {
        assertTrue(StorageUriGrantPolicy.isStillReadable("content://tree/1", "content://tree/1", true))
        assertFalse(StorageUriGrantPolicy.isStillReadable("content://tree/1", "content://tree/1", false))
        assertFalse(StorageUriGrantPolicy.isStillReadable("content://tree/1", "content://tree/2", true))
        assertFalse(StorageUriGrantPolicy.isStillReadable(null, "content://tree/1", true))
    }

    @Test
    fun savedTreeIsRestoredOnlyWhileMatchingLiveGrantRemains() {
        val treeUri = "content://provider/tree/root"
        val live = listOf(
            StorageGrantSnapshot(treeUri, "provider", "root", true, true)
        )
        assertEquals(setOf(treeUri), StorageUriGrantPolicy.activeSavedUris(setOf(treeUri), live))
        assertTrue(StorageUriGrantPolicy.activeSavedUris(setOf(treeUri), emptyList()).isEmpty())
    }

    @Test
    fun directDocumentGrantAllowsOnlyTheExactPersistedContentUri() {
        val selected = "content://provider/document/123"
        val directGrant = listOf(StorageGrantSnapshot(selected, "provider", null, true, true))
        assertTrue(StorageUriGrantPolicy.allows(selected, "provider", null, directGrant, requireWrite = false))
        assertTrue(StorageUriGrantPolicy.allows(selected, "provider", null, directGrant, requireWrite = true))
        assertFalse(StorageUriGrantPolicy.allows("content://provider/document/124", "provider", null, directGrant, requireWrite = false))
    }

    @Test
    fun readOnlyGrantAllowsReadButNeverMutation() {
        val treeUri = "content://provider/tree/root"
        val readOnly = listOf(StorageGrantSnapshot(treeUri, "provider", "root", true, false))
        assertTrue(StorageUriGrantPolicy.hasUriGrant(treeUri, readOnly, requireWrite = false))
        assertFalse(StorageUriGrantPolicy.hasUriGrant(treeUri, readOnly, requireWrite = true))
        assertFalse(StorageUriGrantPolicy.allows(treeUri, "provider", "root", readOnly, requireWrite = true))
    }
}


