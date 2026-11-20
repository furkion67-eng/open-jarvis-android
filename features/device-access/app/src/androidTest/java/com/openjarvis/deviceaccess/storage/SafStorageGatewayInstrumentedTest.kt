package com.openjarvis.deviceaccess.storage

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SafStorageGatewayInstrumentedTest {
    @Test
    fun reopenedGatewayDoesNotTrustARevokedGrantSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, "root:target")
        var currentGrants = listOf(snapshot(tree, read = true, write = true))
        val operations = FakeSafDocumentOperations(copyResult = target)
        val gateway = SafStorageGateway(context, { currentGrants }, operations)
        assertEquals(target, gateway.copy(source, target))

        val reopened = SafStorageGateway(context, { currentGrants }, operations)
        assertEquals(target, reopened.copy(source, target))

        currentGrants = emptyList()
        val error = runCatching { reopened.copy(source, target) }.exceptionOrNull()
        assertTrue("Revoked SAF grants must block the next operation", error is SecurityException)
    }

    @Test
    fun readOnlyGrantCannotAuthorizeCopyOrMove() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "root:parent")
        val readOnly = listOf(snapshot(tree, read = true, write = false))
        val gateway = SafStorageGateway(context, { readOnly }, FakeSafDocumentOperations())
        assertTrue(runCatching { gateway.copy(source, parent) }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching { gateway.move(source, parent, parent) }.exceptionOrNull() is SecurityException)
    }

    @Test
    fun providerUnsupportedCopyRemainsAnExplicitFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, "root:target")
        val grants = listOf(snapshot(tree, read = true, write = true))
        val gateway = SafStorageGateway(context, { grants }, FakeSafDocumentOperations(copyResult = null))
        val error = runCatching { gateway.copy(source, target) }.exceptionOrNull()
        assertTrue("A provider null result must not be reported as success", error is IOException)
    }

    private fun snapshot(tree: Uri, read: Boolean, write: Boolean) = StorageGrantSnapshot(
        uri = tree.toString(),
        authority = tree.authority,
        treeDocumentId = DocumentsContract.getTreeDocumentId(tree),
        hasReadPermission = read,
        hasWritePermission = write
    )

    private class FakeSafDocumentOperations(
        private val copyResult: Uri? = null,
        private val moveResult: Uri? = null
    ) : SafDocumentOperations {
        override fun createDocument(parent: Uri, mimeType: String, displayName: String): Uri? = null
        override fun renameDocument(uri: Uri, newDisplayName: String): Uri? = null
        override fun copyDocument(source: Uri, targetParent: Uri): Uri? = copyResult
        override fun moveDocument(source: Uri, sourceParent: Uri, targetParent: Uri): Uri? = moveResult
        override fun deleteDocument(uri: Uri): Boolean = false
    }
}
