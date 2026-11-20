package com.openjarvis.deviceaccess.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageScopePolicyTest {
    @Test
    fun samePersistedTreeAllowsRead() {
        assertTrue(StorageScopePolicy.isWithinGrantedTree("provider", "root", "provider", "root", true, false, false))
    }

    @Test
    fun differentAuthorityOrTreeIsRejected() {
        assertFalse(StorageScopePolicy.isWithinGrantedTree("other", "root", "provider", "root", true, true, false))
        assertFalse(StorageScopePolicy.isWithinGrantedTree("provider", "other-root", "provider", "root", true, true, false))
    }

    @Test
    fun missingReadGrantIsRejected() {
        assertFalse(StorageScopePolicy.isWithinGrantedTree("provider", "root", "provider", "root", false, true, false))
    }

    @Test
    fun writeRequiresWriteGrant() {
        assertFalse(StorageScopePolicy.isWithinGrantedTree("provider", "root", "provider", "root", true, false, true))
        assertTrue(StorageScopePolicy.isWithinGrantedTree("provider", "root", "provider", "root", true, true, true))
    }

    @Test
    fun missingTreeUriIsRejected() {
        assertFalse(StorageScopePolicy.isWithinGrantedTree("provider", null, "provider", "root", true, true, false))
    }
}
