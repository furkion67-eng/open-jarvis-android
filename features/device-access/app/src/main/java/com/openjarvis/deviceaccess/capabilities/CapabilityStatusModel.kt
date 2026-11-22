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

    fun permissionSet(
        grantedCount: Int,
        wasRequested: Boolean,
        wasGrantedBefore: Boolean
    ): CapabilityState = runtimePermission(
        grantedNow = grantedCount > 0,
        wasRequested = wasRequested,
        wasGrantedBefore = wasGrantedBefore
    )

    fun delegatedAction(available: Boolean): CapabilityState =
        if (available) CapabilityState.ENABLED else CapabilityState.NOT_AVAILABLE

    fun persistedReadGrant(savedUri: String?, actualUri: String?, hasReadPermission: Boolean): Boolean =
        !savedUri.isNullOrBlank() && savedUri == actualUri && hasReadPermission

    fun accessibilityService(declared: Boolean, enabled: Boolean): CapabilityState = when {
        enabled && declared -> CapabilityState.ENABLED
        declared -> CapabilityState.REQUIRES_SETTINGS
        else -> CapabilityState.NOT_AVAILABLE
    }

    /** Direct calling is distinct from the user-confirmed ACTION_DIAL handoff. */
    fun directCallDetail(state: CapabilityState): String = when (state) {
        CapabilityState.ENABLED ->
            "CALL_PHONE izni etkin. JARVIS doğrudan aramayı yalnızca ayrı açık kullanıcı onayından sonra başlatabilir; bu izin onay adımını atlamaz."
        CapabilityState.NOT_REQUESTED ->
            "CALL_PHONE izni henüz istenmedi. JARVIS arama için ACTION_DIAL ekranını açabilir; aramayı Android ekranında sen başlatırsın."
        CapabilityState.DENIED ->
            "CALL_PHONE izni reddedildi. Doğrudan arama kapalıdır; ayrı onaydan sonra ACTION_DIAL açılır ve aramayı sen başlatırsın."
        CapabilityState.REVOKED ->
            "CALL_PHONE izni geri alındı. Doğrudan arama kapalıdır; ayrı onaydan sonra ACTION_DIAL açılır ve aramayı sen başlatırsın."
        CapabilityState.REQUIRES_SETTINGS ->
            "Doğrudan arama için Android uygulama izinlerini kontrol et. İzin olmasa da ACTION_DIAL ile aramayı sen başlatabilirsin."
        CapabilityState.NOT_AVAILABLE ->
            "Bu host doğrudan arama iznini sunmuyor. Ayrı onaydan sonra ACTION_DIAL açılır; aramayı sen başlatırsın."
    }

    /** Keep Android-owned revocation/settings routes visible even while a capability is enabled. */
    fun shouldShowSettingsShortcut(id: String, state: CapabilityState): Boolean = when (id) {
        "overlay", "accessibility", "call-phone" -> true
        else -> state != CapabilityState.ENABLED &&
            state != CapabilityState.NOT_AVAILABLE &&
            !id.startsWith("shared-storage")
    }
}
