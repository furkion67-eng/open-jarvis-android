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
    fun processRestartRestoreRequiresMatchingLiveReadGrant() {
        assertTrue(StorageUriGrantPolicy.isStillReadable("content://tree/1", "content://tree/1", true))
        assertFalse(StorageUriGrantPolicy.isStillReadable("content://tree/1", "content://tree/1", false))
        assertFalse(StorageUriGrantPolicy.isStillReadable("content://tree/1", "content://tree/2", true))
        assertFalse(StorageUriGrantPolicy.isStillReadable(null, "content://tree/1", true))
    }
}
