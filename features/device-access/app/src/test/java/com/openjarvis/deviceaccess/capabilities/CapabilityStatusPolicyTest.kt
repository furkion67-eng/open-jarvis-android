package com.openjarvis.deviceaccess.capabilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityStatusPolicyTest {
    @Test
    fun permissionHistoryDistinguishesNeverRequestedDeniedAndRevoked() {
        assertEquals(
            CapabilityState.NOT_REQUESTED,
            CapabilityStatusPolicy.runtimePermission(false, false, false)
        )
        assertEquals(
            CapabilityState.DENIED,
            CapabilityStatusPolicy.runtimePermission(false, true, false)
        )
        assertEquals(
            CapabilityState.REVOKED,
            CapabilityStatusPolicy.runtimePermission(false, true, true)
        )
        assertEquals(
            CapabilityState.ENABLED,
            CapabilityStatusPolicy.runtimePermission(true, true, true)
        )
    }

    @Test
    fun persistedUriMustStillHaveReadGrant() {
        assertTrue(CapabilityStatusPolicy.persistedReadGrant("content://tree/1", "content://tree/1", true))
        assertFalse(CapabilityStatusPolicy.persistedReadGrant("content://tree/1", "content://tree/1", false))
        assertFalse(CapabilityStatusPolicy.persistedReadGrant("content://tree/1", "content://tree/2", true))
        assertFalse(CapabilityStatusPolicy.persistedReadGrant(null, "content://tree/1", true))
    }
}
