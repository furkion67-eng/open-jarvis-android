package com.openjarvis.deviceaccess.storage

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class StorageAccessPanelInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pickerGrantIsPersistedBrowserSearchOpenShareAndRevokeStayWithinSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("jarvis_storage_uri_grants", 0).edit().clear().commit()

        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AJARVIS")
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val reportUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/report.txt")
        val hiddenUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/hidden.log")
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/archive")
        val insideUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/archive/inside.txt")

        val grantAccess = FakeStorageUriGrantAccess()
        val registry = StorageUriGrantRegistry(context, grantAccess)
        val documents = FakeSafDocumentOperations(
            names = mutableMapOf(
                reportUri.toString() to "report.txt",
                hiddenUri.toString() to "hidden.log",
                folderUri.toString() to "archive",
                insideUri.toString() to "inside.txt"
            )
        )
        val gateway = SafStorageGateway(context, { grantAccess.snapshots() }, documents)
        val launchedIntents = CopyOnWriteArrayList<Intent>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        val hooks = StorageAccessPanelTestHooks(
            grantRegistry = registry,
            gateway = gateway,
            launchTreePicker = { onResult -> onResult(tree, flags) },
            launchIntent = { launchedIntents += it },
            listChildren = { selectedTree, parentId ->
                assertEquals(tree, selectedTree)
                when (parentId) {
                    rootId -> listOf(
                        SafStorageGateway.Entry(reportUri, "report.txt", "text/plain", 12L, null, false),
                        SafStorageGateway.Entry(hiddenUri, "hidden.log", "text/plain", 8L, null, false),
                        SafStorageGateway.Entry(folderUri, "archive", DocumentsContract.Document.MIME_TYPE_DIR, null, null, true)
                    )
                    "$rootId/archive" -> listOf(
                        SafStorageGateway.Entry(insideUri, "inside.txt", "text/plain", 5L, null, false)
                    )
                    else -> emptyList()
                }
            }
        )

        composeRule.setContent { StorageAccessPanelForTest(testHooks = hooks) }
        composeRule.onNodeWithText("Henüz klasör erişimi verilmedi.").assertExists()
        composeRule.onNodeWithText("Klasör seç").performClick()

        composeRule.waitUntil(5_000) {
            registry.lastGrantedTreeUri() == tree && launchedIntents.isEmpty()
        }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("report.txt", substring = true).assertExists() }.isSuccess
        }
        assertEquals(listOf(tree), registry.grantedTreeUris())

        composeRule.onNodeWithText("Bu klasörde dosya ara").performTextInput("report")
        composeRule.onNodeWithText("report.txt", substring = true).assertExists()
        composeRule.onNodeWithText("hidden.log", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Bu klasörde dosya ara").performTextClearance()

        composeRule.onNodeWithText("report.txt", substring = true).performClick()
        assertEquals(1, launchedIntents.size)
        val openIntent = launchedIntents.last()
        assertEquals(Intent.ACTION_VIEW, openIntent.action)
        assertEquals(reportUri, openIntent.data)
        assertEquals("text/plain", openIntent.type)
        assertTrue(openIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        composeRule.onNodeWithText("Paylaş").performClick()
        assertEquals(2, launchedIntents.size)
        val chooser = launchedIntents.last()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val shareIntent = chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        assertNotNull(shareIntent)
        assertEquals(Intent.ACTION_SEND, shareIntent?.action)
        @Suppress("DEPRECATION")
        val sharedUri = shareIntent?.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        assertEquals(reportUri, sharedUri)
        assertTrue(shareIntent!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(reportUri, shareIntent.clipData?.getItemAt(0)?.uri)

        composeRule.onNodeWithText("archive", substring = true).performClick()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("inside.txt", substring = true).assertExists() }.isSuccess
        }
        composeRule.onNodeWithText("report.txt", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Erişimi kaldır").performClick()
        composeRule.waitUntil(5_000) { registry.grantedTreeUris().isEmpty() }
        assertTrue(registry.lastGrantedTreeUri() == null)
        composeRule.onNodeWithText("Seçilen klasör erişimi kaldırıldı. JARVIS artık bu klasöre erişemez.").assertExists()
        composeRule.onNodeWithText("inside.txt", substring = true).assertDoesNotExist()
    }

    @Test
    fun deletionRequiresExactVisibleNameAndSeparateConfirmationBeforeGatewayMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("jarvis_storage_uri_grants", 0).edit().clear().commit()

        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AJARVIS-DELETE")
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val reportUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/report.txt")
        val grantAccess = FakeStorageUriGrantAccess()
        val registry = StorageUriGrantRegistry(context, grantAccess)
        val documents = FakeSafDocumentOperations(
            names = mutableMapOf(reportUri.toString() to "report.txt")
        )
        val gateway = SafStorageGateway(context, { grantAccess.snapshots() }, documents)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        val hooks = StorageAccessPanelTestHooks(
            grantRegistry = registry,
            gateway = gateway,
            launchTreePicker = { onResult -> onResult(tree, flags) },
            listChildren = { _, parentId ->
                if (parentId == rootId) {
                    listOf(SafStorageGateway.Entry(reportUri, "report.txt", "text/plain", 12L, null, false))
                } else emptyList()
            }
        )

        composeRule.setContent { StorageAccessPanelForTest(testHooks = hooks) }
        composeRule.onNodeWithText("Klasör seç").performClick()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("report.txt", substring = true).assertExists() }.isSuccess
        }

        composeRule.onNodeWithText("Silme onayı").performClick()
        composeRule.onNodeWithText("Silinecek dosya: report.txt").assertExists()
        val confirm = composeRule.onNodeWithText("Onayla ve sil")
        confirm.assertIsNotEnabled()
        composeRule.onNodeWithText("Onaylamak için dosya adını aynen yaz").performTextInput("wrong.txt")
        confirm.assertIsNotEnabled()
        assertEquals(0, documents.deleteCalls.get())

        composeRule.onNodeWithText("Onaylamak için dosya adını aynen yaz").performTextClearance()
        composeRule.onNodeWithText("Onaylamak için dosya adını aynen yaz").performTextInput("report.txt")
        confirm.assertIsEnabled()
        confirm.performClick()
        composeRule.waitUntil(5_000) { documents.deleteCalls.get() == 1 }
        assertEquals(1, documents.deleteCalls.get())
        composeRule.onNodeWithText("Dosya silindi.").assertExists()
    }

    private class FakeStorageUriGrantAccess : StorageUriGrantAccess {
        private val grants = linkedMapOf<String, StorageGrantSnapshot>()

        override fun takePersistableUriPermission(uri: Uri, flags: Int) {
            val old = grants[uri.toString()]
            val read = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            val write = flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
            grants[uri.toString()] = StorageGrantSnapshot(
                uri = uri.toString(),
                authority = uri.authority,
                treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull(),
                hasReadPermission = read || old?.hasReadPermission == true,
                hasWritePermission = write || old?.hasWritePermission == true
            )
        }

        override fun releasePersistableUriPermission(uri: Uri, flags: Int) {
            grants.remove(uri.toString())
        }

        override fun snapshots(): List<StorageGrantSnapshot> = grants.values.toList()
    }

    private class FakeSafDocumentOperations(
        private val names: MutableMap<String, String>
    ) : SafDocumentOperations {
        val deleteCalls = AtomicInteger(0)

        override fun createDocument(parent: Uri, mimeType: String, displayName: String): Uri? = null
        override fun renameDocument(uri: Uri, newDisplayName: String): Uri? = null
        override fun copyDocument(source: Uri, targetParent: Uri): Uri? = null
        override fun moveDocument(source: Uri, sourceParent: Uri, targetParent: Uri): Uri? = null

        override fun deleteDocument(uri: Uri): Boolean {
            deleteCalls.incrementAndGet()
            names.remove(uri.toString())
            return true
        }

        override fun queryDisplayName(uri: Uri): String? = names[uri.toString()]
    }
}
