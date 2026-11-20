package com.openjarvis.deviceaccess.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

internal interface SafDocumentOperations {
    fun createDocument(parent: Uri, mimeType: String, displayName: String): Uri?
    fun renameDocument(uri: Uri, newDisplayName: String): Uri?
    fun copyDocument(source: Uri, targetParent: Uri): Uri?
    fun moveDocument(source: Uri, sourceParent: Uri, targetParent: Uri): Uri?
    fun deleteDocument(uri: Uri): Boolean
}

private class AndroidSafDocumentOperations(private val resolver: ContentResolver) : SafDocumentOperations {
    override fun createDocument(parent: Uri, mimeType: String, displayName: String): Uri? =
        DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
    override fun renameDocument(uri: Uri, newDisplayName: String): Uri? =
        DocumentsContract.renameDocument(resolver, uri, newDisplayName)
    override fun copyDocument(source: Uri, targetParent: Uri): Uri? =
        DocumentsContract.copyDocument(resolver, source, targetParent)
    override fun moveDocument(source: Uri, sourceParent: Uri, targetParent: Uri): Uri? =
        DocumentsContract.moveDocument(resolver, source, sourceParent, targetParent)
    override fun deleteDocument(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)
}

/**
 * Shared-storage access limited to document/tree URIs explicitly selected by the user
 * through ACTION_OPEN_DOCUMENT or ACTION_OPEN_DOCUMENT_TREE.
 *
 * This gateway intentionally does not enumerate arbitrary external storage
 * or another application's private sandbox.
 */
class SafStorageGateway(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private var grantSnapshotReader: () -> List<StorageGrantSnapshot> = {
        resolver.persistedUriPermissions.mapNotNull { grant ->
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(grant.uri) }.getOrNull()
                ?: return@mapNotNull null
            StorageGrantSnapshot(grant.uri.toString(), grant.uri.authority, treeId, grant.isReadPermission, grant.isWritePermission)
        }
    }
    private var documentOperations: SafDocumentOperations = AndroidSafDocumentOperations(resolver)

    internal constructor(
        context: Context,
        grantSnapshotReader: () -> List<StorageGrantSnapshot>,
        documentOperations: SafDocumentOperations
    ) : this(context) {
        this.grantSnapshotReader = grantSnapshotReader
        this.documentOperations = documentOperations
    }

    data class Entry(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val sizeBytes: Long?,
        val lastModifiedMillis: Long?,
        val isDirectory: Boolean
    )

    data class DeleteConfirmation internal constructor(
        internal val target: Uri,
        internal val exactDisplayName: String
    )

    /** Persist only read/write flags actually returned by the Android picker. */
    fun persistPickerGrant(uri: Uri, returnedFlags: Int) {
        val persistable = returnedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(persistable != 0) { "Android kalıcı dosya izni vermedi." }
        resolver.takePersistableUriPermission(uri, persistable)
    }

    fun listChildren(treeUri: Uri, parentDocumentId: String): List<Entry> {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        requireAccess(parentUri, requireWrite = false)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val entries = mutableListOf<Entry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeColumn)
                entries += Entry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(idColumn)
                    ),
                    name = cursor.getString(nameColumn) ?: "Adsız dosya",
                    mimeType = mime,
                    sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else null,
                    lastModifiedMillis = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) else null,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        } ?: throw IOException("Klasör sağlayıcısı yanıt vermedi.")
        return entries
    }

    fun createDirectory(parentDocumentUri: Uri, displayName: String): Uri =
        create(parentDocumentUri, DocumentsContract.Document.MIME_TYPE_DIR, displayName)

    fun createFile(parentDocumentUri: Uri, mimeType: String, displayName: String): Uri =
        create(parentDocumentUri, mimeType, displayName)

    private fun create(parent: Uri, mimeType: String, name: String): Uri {
        require(name.isNotBlank()) { "Dosya veya klasör adı boş olamaz." }
        requireAccess(parent, requireWrite = true)
        return documentOperations.createDocument(parent, mimeType, name)
            ?: throw IOException("Dosya sağlayıcısı yeni öğeyi oluşturamadı.")
    }

    fun read(uri: Uri, maxBytes: Int = MAX_READ_BYTES): ByteArray {
        require(maxBytes in 1..MAX_READ_BYTES) { "Dosya okuma sınırı geçersiz." }
        requireAccess(uri, requireWrite = false)
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Dosya okunamadı veya erişim izni kaldırılmış.")
        return input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (total + count > maxBytes) throw IOException("Dosya ${maxBytes} bayt sınırını aşıyor; büyük medya belleğe alınmadı.")
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        }
    }

    fun write(uri: Uri, bytes: ByteArray) {
        requireAccess(uri, requireWrite = true)
        resolver.openOutputStream(uri, "wt")?.use {
            it.write(bytes)
            it.flush()
        } ?: throw IOException("Dosya yazılamadı veya erişim izni kaldırılmış.")
    }

    fun rename(uri: Uri, newDisplayName: String): Uri {
        require(newDisplayName.isNotBlank()) { "Yeni ad boş olamaz." }
        requireAccess(uri, requireWrite = true)
        return documentOperations.renameDocument(uri, newDisplayName)
            ?: throw IOException("Dosya yeniden adlandırılamadı.")
    }

    fun copy(source: Uri, targetParent: Uri): Uri {
        requireAccess(source, requireWrite = false)
        requireAccess(targetParent, requireWrite = true)
        return documentOperations.copyDocument(source, targetParent)
            ?: throw IOException("Dosya kopyalanamadı; sağlayıcı bu işlemi desteklemiyor.")
    }

    fun move(source: Uri, sourceParent: Uri, targetParent: Uri): Uri {
        requireAccess(source, requireWrite = true)
        requireAccess(sourceParent, requireWrite = true)
        requireAccess(targetParent, requireWrite = true)
        return documentOperations.moveDocument(source, sourceParent, targetParent)
            ?: throw IOException("Dosya taşınamadı; sağlayıcı bu işlemi desteklemiyor.")
    }

    /** UI must display the exact target and ask the user to confirm its current name. */
    fun confirmDeletion(uri: Uri, userTypedExactName: String): DeleteConfirmation {
        val actualName = queryDisplayName(uri)
        require(StorageDeletionPolicy.isConfirmed(uri.toString(), userTypedExactName, uri.toString(), actualName)) {
            "Silme onayı için dosya adını tam olarak yazmalısın."
        }
        return DeleteConfirmation(uri, actualName)
    }

    fun delete(uri: Uri, confirmation: DeleteConfirmation) {
        requireAccess(uri, requireWrite = true)
        require(StorageDeletionPolicy.isConfirmed(
            confirmation.target.toString(),
            confirmation.exactDisplayName,
            uri.toString(),
            queryDisplayName(uri)
        )) { "Dosya veya onay değişti; silme işlemini yeniden onayla." }
        if (!documentOperations.deleteDocument(uri)) {
            throw IOException("Dosya silinemedi.")
        }
    }

    private fun requireAccess(uri: Uri, requireWrite: Boolean) {
        val uriTreeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val allowed = StorageUriGrantPolicy.allows(
            uriAuthority = uri.authority,
            uriTreeDocumentId = uriTreeId,
            actualGrants = grantSnapshotReader(),
            requireWrite = requireWrite
        )
        if (!allowed) {
            throw SecurityException("JARVIS yalnızca seçilmiş ve halen izinli klasörlerin içindeki dosyalara erişebilir.")
        }
    }

    fun displayName(uri: Uri): String = queryDisplayName(uri)

    private companion object { const val MAX_READ_BYTES = 8 * 1024 * 1024 }

    private fun queryDisplayName(uri: Uri): String {
        requireAccess(uri, requireWrite = false)
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(
                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                ) ?: throw IOException("Dosya adı okunamadı.")
            }
        }
        throw IOException("Dosyaya erişilemiyor; izin kaldırılmış olabilir.")
    }
}

internal object StorageDeletionPolicy {
    fun isConfirmed(
        confirmedUri: String,
        confirmedName: String,
        requestedUri: String,
        currentName: String
    ): Boolean = confirmedUri == requestedUri &&
        confirmedName.isNotBlank() &&
        confirmedName == currentName
}
