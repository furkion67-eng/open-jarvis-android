package com.openjarvis.deviceaccess.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageMutationPolicyTest {
    @Test
    fun renameRequiresExactUnchangedSourceAndPreviewedNewName() {
        val preview = StorageRenamePreview(
            uri = "content://provider/tree/root/document/root%3Areport.pdf",
            originalName = "report.pdf",
            newName = "final-report.pdf"
        )
        assertTrue(
            StorageMutationPolicy.renameMatches(
                preview,
                preview.uri,
                "report.pdf",
                "final-report.pdf"
            )
        )
        assertFalse(
            StorageMutationPolicy.renameMatches(
                preview,
                preview.uri,
                "renamed-by-another-app.pdf",
                "final-report.pdf"
            )
        )
        assertFalse(
            StorageMutationPolicy.renameMatches(
                preview,
                preview.uri,
                "report.pdf",
                "different-name.pdf"
            )
        )
        assertFalse(
            StorageMutationPolicy.renameMatches(
                preview.copy(newName = " "),
                preview.uri,
                "report.pdf",
                " "
            )
        )
    }

    @Test
    fun copyAndMoveRequireExactSourceAndDestinationPreview() {
        val preview = StorageTransferPreview(
            operation = StorageTransferOperation.MOVE,
            sourceUri = "content://provider/tree/source/document/source%3Aclip.mp4",
            sourceName = "clip.mp4",
            targetParentUri = "content://provider/tree/destination/document/destination",
            targetName = "Videos"
        )
        assertTrue(
            StorageMutationPolicy.transferMatches(
                preview,
                preview.sourceUri,
                preview.sourceName,
                preview.targetParentUri,
                preview.targetName
            )
        )
        assertFalse(
            StorageMutationPolicy.transferMatches(
                preview,
                preview.sourceUri,
                "different.mp4",
                preview.targetParentUri,
                preview.targetName
            )
        )
        assertFalse(
            StorageMutationPolicy.transferMatches(
                preview,
                preview.sourceUri,
                preview.sourceName,
                "content://provider/tree/other/document/other",
                preview.targetName
            )
        )
        assertFalse(
            StorageMutationPolicy.transferMatches(
                preview,
                preview.sourceUri,
                preview.sourceName,
                preview.targetParentUri,
                "Other folder"
            )
        )
    }

    @Test
    fun deletionRequiresTheSameUriAndExactCurrentDisplayName() {
        val uri = "content://provider/tree/root/document/root%3Areport.pdf"
        assertTrue(StorageDeletionPolicy.isConfirmed(uri, "report.pdf", uri, "report.pdf"))
        assertFalse(StorageDeletionPolicy.isConfirmed(uri, "report.pdf", uri, "renamed.pdf"))
        assertFalse(StorageDeletionPolicy.isConfirmed(uri, "report.pdf", "content://provider/tree/other", "report.pdf"))
        assertFalse(StorageDeletionPolicy.isConfirmed(uri, " ", uri, "report.pdf"))
    }

    @Test
    fun transferCannotTargetTheSourceDocumentItself() {
        val source = "content://provider/tree/root/document/root%3Afile.txt"
        val preview = StorageTransferPreview(
            operation = StorageTransferOperation.COPY,
            sourceUri = source,
            sourceName = "file.txt",
            targetParentUri = source,
            targetName = "file.txt"
        )
        assertFalse(
            StorageMutationPolicy.transferMatches(
                preview,
                source,
                "file.txt",
                source,
                "file.txt"
            )
        )
    }
}
