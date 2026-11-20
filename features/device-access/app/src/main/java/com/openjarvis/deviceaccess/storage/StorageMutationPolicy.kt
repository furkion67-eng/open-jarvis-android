package com.openjarvis.deviceaccess.storage

enum class StorageTransferOperation { COPY, MOVE }

data class StorageRenamePreview(
    val uri: String,
    val originalName: String,
    val newName: String
)

data class StorageTransferPreview(
    val operation: StorageTransferOperation,
    val sourceUri: String,
    val sourceName: String,
    val targetParentUri: String,
    val targetName: String
)

/** Revalidates the exact source and destination shown before a user confirms a mutation. */
object StorageMutationPolicy {
    fun renameMatches(
        preview: StorageRenamePreview,
        currentUri: String,
        currentName: String,
        requestedNewName: String
    ): Boolean =
        preview.uri == currentUri &&
            preview.originalName.isNotBlank() &&
            preview.originalName == currentName &&
            preview.newName.isNotBlank() &&
            preview.newName == requestedNewName &&
            preview.newName != currentName

    fun transferMatches(
        preview: StorageTransferPreview,
        currentSourceUri: String,
        currentSourceName: String,
        currentTargetParentUri: String,
        currentTargetName: String
    ): Boolean =
        preview.sourceUri == currentSourceUri &&
            preview.sourceName.isNotBlank() &&
            preview.sourceName == currentSourceName &&
            preview.targetParentUri == currentTargetParentUri &&
            preview.targetName.isNotBlank() &&
            preview.targetName == currentTargetName &&
            preview.sourceUri != preview.targetParentUri
}
