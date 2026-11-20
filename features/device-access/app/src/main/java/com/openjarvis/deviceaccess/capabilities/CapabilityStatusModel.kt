package com.openjarvis.deviceaccess.capabilities

enum class CapabilityState {
    ENABLED,
    NOT_REQUESTED,
    DENIED,
    REVOKED,
    REQUIRES_SETTINGS,
    NOT_AVAILABLE
}

data class CapabilityStatusItem(
    val id: String,
    val title: String,
    val state: CapabilityState,
    val detail: String
)

object CapabilityStatusPolicy {
    fun runtimePermission(
        grantedNow: Boolean,
        wasRequested: Boolean,
        wasGrantedBefore: Boolean
    ): CapabilityState = when {
        grantedNow -> CapabilityState.ENABLED
        wasGrantedBefore -> CapabilityState.REVOKED
        wasRequested -> CapabilityState.DENIED
        else -> CapabilityState.NOT_REQUESTED
    }

    fun persistedReadGrant(savedUri: String?, actualUri: String?, hasReadPermission: Boolean): Boolean =
        !savedUri.isNullOrBlank() && savedUri == actualUri && hasReadPermission
}
