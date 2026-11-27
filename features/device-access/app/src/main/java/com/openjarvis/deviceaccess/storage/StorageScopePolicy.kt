package com.openjarvis.deviceaccess.storage

object StorageScopePolicy {
    fun isWithinGrantedTree(
        uriAuthority: String?,
        uriTreeDocumentId: String?,
        grantAuthority: String?,
        grantTreeDocumentId: String?,
        hasReadPermission: Boolean,
        hasWritePermission: Boolean,
        requireWrite: Boolean
    ): Boolean =
        !uriAuthority.isNullOrBlank() &&
            uriAuthority == grantAuthority &&
            !uriTreeDocumentId.isNullOrBlank() &&
            uriTreeDocumentId == grantTreeDocumentId &&
            hasReadPermission &&
            (!requireWrite || hasWritePermission)
}
