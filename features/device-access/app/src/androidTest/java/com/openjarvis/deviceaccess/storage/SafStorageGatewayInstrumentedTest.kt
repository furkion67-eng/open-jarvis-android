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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class SafStorageGatewayInstrumentedTest {
    @Test
    fun reopenedGatewayDoesNotTrustARevokedGrantSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, "root:target")
        var currentGrants = listOf(snapshot(tree, read = true, write = true))
        val operations = FakeSafDocumentOperations(
            names = mutableMapOf(source.toString() to "source.txt", target.toString() to "target"),
            copyResult = target
        )
        val gateway = SafStorageGateway(context, { currentGrants }, operations)
        assertEquals(target, gateway.copy(source, target, "source.txt", "target"))

        val reopened = SafStorageGateway(context, { currentGrants }, operations)
        assertEquals(target, reopened.copy(source, target, "source.txt", "target"))

        currentGrants = emptyList()
        val error = runCatching { reopened.copy(source, target, "source.txt", "target") }.exceptionOrNull()
        assertTrue("Revoked SAF grants must block the next operation", error is SecurityException)
    }

    @Test
    fun readOnlyGrantCannotAuthorizeCopyOrMove() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "root:parent")
        val readOnly = listOf(snapshot(tree, read = true, write = false))
        val gateway = SafStorageGateway(
            context,
            { readOnly },
            FakeSafDocumentOperations(names = mutableMapOf(source.toString() to "source.txt", parent.toString() to "parent"))
        )
        assertTrue(runCatching { gateway.copy(source, parent, "source.txt", "parent") }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching { gateway.move(source, parent, parent, "source.txt", "parent") }.exceptionOrNull() is SecurityException)
    }

    @Test
    fun directPersistedDocumentGrantWorksOnlyForThatExactUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = Uri.parse("content://jarvis.fake.documents/document/one")
        val renamed = Uri.parse("content://jarvis.fake.documents/document/one-renamed")
        val grants = listOf(StorageGrantSnapshot(selected.toString(), selected.authority, null, true, true))
        val operations = FakeSafDocumentOperations(
            names = mutableMapOf(selected.toString() to "one.txt"),
            renameResult = renamed
        )
        val gateway = SafStorageGateway(context, { grants }, operations)
        assertEquals(renamed, gateway.rename(selected, "renamed.txt", "one.txt"))
        val other = Uri.parse("content://jarvis.fake.documents/document/two")
        assertTrue(runCatching { gateway.rename(other, "not-allowed.txt", "one.txt") }.exceptionOrNull() is SecurityException)
    }

    @Test
    fun providerUnsupportedCopyRemainsAnExplicitFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, "root:target")
        val grants = listOf(snapshot(tree, read = true, write = true))
        val operations = FakeSafDocumentOperations(
            names = mutableMapOf(source.toString() to "source.txt", target.toString() to "target"),
            copyResult = null
        )
        val gateway = SafStorageGateway(context, { grants }, operations)
        val error = runCatching { gateway.copy(source, target, "source.txt", "target") }.exceptionOrNull()
        assertTrue("A provider null result must not be reported as success", error is IOException)
    }

    @Test
    fun renameRevalidatesTheExactOriginalNameImmediatelyBeforeMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val grants = listOf(snapshot(tree, read = true, write = true))
        val names = mutableMapOf(source.toString() to "source.txt")
        val operations = FakeSafDocumentOperations(names = names, renameResult = source)
        val gateway = SafStorageGateway(context, { grants }, operations)

        names[source.toString()] = "renamed-by-provider.txt"
        val error = runCatching { gateway.rename(source, "new-name.txt", "source.txt") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, operations.renameCalls)
    }

    @Test
    fun copyRevalidatesSourceAndTargetNamesImmediatelyBeforeMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, "root:target")
        val grants = listOf(snapshot(tree, read = true, write = true))
        val names = mutableMapOf(source.toString() to "source.txt", target.toString() to "target")
        val operations = FakeSafDocumentOperations(names = names, copyResult = target)
        val gateway = SafStorageGateway(context, { grants }, operations)

        names[target.toString()] = "different-target"
        val error = runCatching { gateway.copy(source, target, "source.txt", "target") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, operations.copyCalls)
    }

    @Test
    fun moveRevalidatesSourceAndTargetNamesImmediatelyBeforeMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val sourceParent = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source-parent")
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, "root:target")
        val grants = listOf(snapshot(tree, read = true, write = true))
        val names = mutableMapOf(
            source.toString() to "source.txt",
            sourceParent.toString() to "source-parent",
            target.toString() to "target"
        )
        val operations = FakeSafDocumentOperations(names = names, moveResult = source)
        val gateway = SafStorageGateway(context, { grants }, operations)

        names[source.toString()] = "provider-renamed-source.txt"
        val error = runCatching {
            gateway.move(source, sourceParent, target, "source.txt", "target")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, operations.moveCalls)
    }

    @Test
    fun deleteRevalidatesTheExactNameAfterTheUserConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:source.txt")
        val grants = listOf(snapshot(tree, read = true, write = true))
        val names = mutableMapOf(source.toString() to "source.txt")
        val operations = FakeSafDocumentOperations(names = names)
        val gateway = SafStorageGateway(context, { grants }, operations)
        val confirmation = gateway.confirmDeletion(source, "source.txt")

        names[source.toString()] = "changed-after-confirmation.txt"
        val error = runCatching { gateway.delete(source, confirmation) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, operations.deleteCalls)
    }

    @Test
    fun streamToCopiesLargeDocumentsUsingBoundedMultipleChunks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:large.bin")
        val grants = listOf(snapshot(tree, read = true, write = false))
        val bytes = ByteArray(64 * 1024 + 37) { index -> (index % 251).toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            var largestRequestedChunk = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRequestedChunk = maxOf(largestRequestedChunk, length)
                return super.read(buffer, offset, minOf(length, 4096))
            }
        }
        val gateway = SafStorageGateway(
            context,
            { grants },
            FakeSafDocumentOperations(),
            { input }
        )
        val output = ByteArrayOutputStream()

        val copied = gateway.streamTo(
            uri = source,
            output = output,
            maxBytes = bytes.size.toLong(),
            chunkSizeBytes = 4096
        )

        assertEquals(bytes.size.toLong(), copied)
        org.junit.Assert.assertArrayEquals(bytes, output.toByteArray())
        assertEquals(4096, input.largestRequestedChunk)
    }

    @Test
    fun streamToStopsBeforeNextChunkWhenPersistedGrantIsRevoked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://jarvis.fake.documents/tree/root")
        val source = DocumentsContract.buildDocumentUriUsingTree(tree, "root:revoked-during-read.bin")
        var currentGrants = listOf(snapshot(tree, read = true, write = false))
        val input = object : ByteArrayInputStream(ByteArray(32) { it.toByte() }) {
            var revokedAfterFirstChunk = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, minOf(length, 4))
                if (!revokedAfterFirstChunk && count > 0) {
                    revokedAfterFirstChunk = true
                    currentGrants = emptyList()
                }
                return count
            }
        }
        val gateway = SafStorageGateway(
            context,
            { currentGrants },
            FakeSafDocumentOperations(),
            { input }
        )
        val output = ByteArrayOutputStream()

        val error = runCatching {
            gateway.streamTo(source, output, maxBytes = 32, chunkSizeBytes = 4)
        }.exceptionOrNull()

        assertTrue("A revoked grant must stop the next read chunk", error is SecurityException)
        assertEquals("Only the first authorized chunk may be copied", 4, output.size())
    }

    private fun snapshot(tree: Uri, read: Boolean, write: Boolean) = StorageGrantSnapshot(
        uri = tree.toString(),
        authority = tree.authority,
        treeDocumentId = DocumentsContract.getTreeDocumentId(tree),
        hasReadPermission = read,
        hasWritePermission = write
    )

    private class FakeSafDocumentOperations(
        private val names: MutableMap<String, String> = mutableMapOf(),
        private val copyResult: Uri? = null,
        private val moveResult: Uri? = null,
        private val renameResult: Uri? = null
    ) : SafDocumentOperations {
        var copyCalls = 0
        var moveCalls = 0
        var renameCalls = 0
        var deleteCalls = 0

        override fun createDocument(parent: Uri, mimeType: String, displayName: String): Uri? = null
        override fun renameDocument(uri: Uri, newDisplayName: String): Uri? {
            renameCalls++
            return renameResult
        }
        override fun copyDocument(source: Uri, targetParent: Uri): Uri? {
            copyCalls++
            return copyResult
        }
        override fun moveDocument(source: Uri, sourceParent: Uri, targetParent: Uri): Uri? {
            moveCalls++
            return moveResult
        }
        override fun deleteDocument(uri: Uri): Boolean {
            deleteCalls++
            return true
        }
        override fun queryDisplayName(uri: Uri): String? = names[uri.toString()]
    }
}
