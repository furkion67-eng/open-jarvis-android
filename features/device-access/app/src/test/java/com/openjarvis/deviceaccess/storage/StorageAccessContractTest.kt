package com.openjarvis.deviceaccess.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessContractTest {
    @Test
    fun deletionRequiresSameUriAndExactUnchangedName() {
        assertTrue(
            StorageDeletionPolicy.isConfirmed(
                "content://provider/doc/1", "rapor.pdf",
                "content://provider/doc/1", "rapor.pdf"
            )
        )
        assertFalse(
            StorageDeletionPolicy.isConfirmed(
                "content://provider/doc/1", "rapor.pdf",
                "content://provider/doc/2", "rapor.pdf"
            )
        )
        assertFalse(
            StorageDeletionPolicy.isConfirmed(
                "content://provider/doc/1", "rapor.pdf",
                "content://provider/doc/1", "rapor-yeni.pdf"
            )
        )
        assertFalse(
            StorageDeletionPolicy.isConfirmed(
                "content://provider/doc/1", "",
                "content://provider/doc/1", ""
            )
        )
    }
}
