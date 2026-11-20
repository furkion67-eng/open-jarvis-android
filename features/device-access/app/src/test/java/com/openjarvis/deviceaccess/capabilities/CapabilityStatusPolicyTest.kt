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
    fun partialMediaPermissionIsEnabledOnlyForTheGrantedSubset() {
        assertEquals(
            CapabilityState.ENABLED,
            CapabilityStatusPolicy.permissionSet(1, true, false)
        )
        assertEquals(
            CapabilityState.ENABLED,
            CapabilityStatusPolicy.permissionSet(2, true, true)
        )
        assertEquals(
            CapabilityState.DENIED,
            CapabilityStatusPolicy.permissionSet(0, true, false)
        )
        assertEquals(
            CapabilityState.REVOKED,
            CapabilityStatusPolicy.permissionSet(0, true, true)
        )
        assertEquals(
            CapabilityState.NOT_REQUESTED,
            CapabilityStatusPolicy.permissionSet(0, false, false)
        )
    }

    @Test
    fun delegatedCameraAndSpeechActionsAreNotReportedAvailableWithoutAHandler() {
        assertEquals(CapabilityState.ENABLED, CapabilityStatusPolicy.delegatedAction(true))
        assertEquals(CapabilityState.NOT_AVAILABLE, CapabilityStatusPolicy.delegatedAction(false))
    }

    @Test
    fun storageReadAndWriteHistoryCanBeReportedSeparately() {
        val readOnlySelection = CapabilityStatusPolicy.runtimePermission(
            grantedNow = true,
            wasRequested = true,
            wasGrantedBefore = true
        )
        val writeDenied = CapabilityStatusPolicy.runtimePermission(
            grantedNow = false,
            wasRequested = true,
            wasGrantedBefore = false
        )
        assertEquals(CapabilityState.ENABLED, readOnlySelection)
        assertEquals(CapabilityState.DENIED, writeDenied)

        val revokedWrite = CapabilityStatusPolicy.runtimePermission(
            grantedNow = false,
            wasRequested = true,
            wasGrantedBefore = true
        )
        assertEquals(CapabilityState.REVOKED, revokedWrite)
    }

    @Test
    fun accessibilityRequiresSettingsOnlyWhenServiceIsActuallyDeclared() {
        assertEquals(CapabilityState.NOT_AVAILABLE, CapabilityStatusPolicy.accessibilityService(false, false))
        assertEquals(CapabilityState.REQUIRES_SETTINGS, CapabilityStatusPolicy.accessibilityService(true, false))
        assertEquals(CapabilityState.ENABLED, CapabilityStatusPolicy.accessibilityService(true, true))
        assertEquals(CapabilityState.NOT_AVAILABLE, CapabilityStatusPolicy.accessibilityService(false, true))
    }

    @Test
    fun persistedUriMustStillHaveReadGrant() {
        assertTrue(CapabilityStatusPolicy.persistedReadGrant("content://tree/1", "content://tree/1", true))
        assertFalse(CapabilityStatusPolicy.persistedReadGrant("content://tree/1", "content://tree/1", false))
        assertFalse(CapabilityStatusPolicy.persistedReadGrant("content://tree/1", "content://tree/2", true))
        assertFalse(CapabilityStatusPolicy.persistedReadGrant(null, "content://tree/1", true))
    }
}
