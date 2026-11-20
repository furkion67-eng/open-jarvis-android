package com.openjarvis.deviceaccess.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.openjarvis.deviceaccess.capabilities.AndroidCapabilityStatusReader
import java.util.LinkedHashSet

data class StorageGrantSnapshot(
    val uri: String,
    val authority: String?,
    val treeDocumentId: String?,
    val hasReadPermission: Boolean,
    val hasWritePermission: Boolean
)

object StorageUriGrantPolicy {
    fun hasPersistableGrant(returnedFlags: Int, persistableFlag: Int): Boolean =
        returnedFlags and persistableFlag != 0

    fun persistableFlags(returnedFlags: Int, readFlag: Int, writeFlag: Int): Int =
        returnedFlags and (readFlag or writeFlag)

    fun isStillReadable(savedUri: String?, actualUri: String?, hasReadPermission: Boolean): Boolean =
        !savedUri.isNullOrBlank() && savedUri == actualUri && hasReadPermission

    fun hasUriGrant(
        savedUri: String?,
        actualGrants: List<StorageGrantSnapshot>,
        requireWrite: Boolean
    ): Boolean = !savedUri.isNullOrBlank() && actualGrants.any { grant ->
        grant.uri == savedUri && grant.hasReadPermission && (!requireWrite || grant.hasWritePermission)
    }

    fun activeSavedUris(savedUris: Set<String>, actualGrants: List<StorageGrantSnapshot>): Set<String> =
        savedUris.filter { hasUriGrant(it, actualGrants, requireWrite = false) }.toSet()

    fun allows(
        uriText: String,
        uriAuthority: String?,
        uriTreeDocumentId: String?,
        actualGrants: List<StorageGrantSnapshot>,
        requireWrite: Boolean
    ): Boolean = actualGrants.any { grant ->
        val exactDocumentGrant = grant.uri == uriText &&
            grant.hasReadPermission &&
            (!requireWrite || grant.hasWritePermission)
        exactDocumentGrant || StorageScopePolicy.isWithinGrantedTree(
            uriAuthority = uriAuthority,
            uriTreeDocumentId = uriTreeDocumentId,
            grantAuthority = grant.authority,
            grantTreeDocumentId = grant.treeDocumentId,
            hasReadPermission = grant.hasReadPermission,
            hasWritePermission = grant.hasWritePermission,
            requireWrite = requireWrite
        )
    }
}

internal interface StorageUriGrantAccess {
    fun takePersistableUriPermission(uri: Uri, flags: Int)
    fun releasePersistableUriPermission(uri: Uri, flags: Int)
    fun snapshots(): List<StorageGrantSnapshot>
}

private class AndroidStorageUriGrantAccess(context: Context) : StorageUriGrantAccess {
    private val resolver = context.applicationContext.contentResolver

    override fun takePersistableUriPermission(uri: Uri, flags: Int) =
        resolver.takePersistableUriPermission(uri, flags)

    override fun releasePersistableUriPermission(uri: Uri, flags: Int) =
        resolver.releasePersistableUriPermission(uri, flags)

    override fun snapshots(): List<StorageGrantSnapshot> = resolver.persistedUriPermissions.mapNotNull { grant ->
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(grant.uri) }.getOrNull()
        StorageGrantSnapshot(
            uri = grant.uri.toString(),
            authority = grant.uri.authority,
            treeDocumentId = treeId,
            hasReadPermission = grant.isReadPermission,
            hasWritePermission = grant.isWritePermission
        )
    }
}

/** Persists only picker-returned URI grants and revalidates them after process restart. */
class StorageUriGrantRegistry internal constructor(
    context: Context,
    private val grantAccess: StorageUriGrantAccess
) {
    constructor(context: Context) : this(context.applicationContext, AndroidStorageUriGrantAccess(context))

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "jarvis_storage_uri_grants",
        Context.MODE_PRIVATE
    )

    fun persistPickerResult(uri: Uri, returnedFlags: Int, makeLast: Boolean = true) {
        try {
            require(StorageUriGrantPolicy.hasPersistableGrant(returnedFlags, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) {
                "Android dosya seçicisi kalıcı URI izni vermedi."
            }
            val flags = StorageUriGrantPolicy.persistableFlags(
                returnedFlags,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                "Android dosya seçicisi kalıcı okuma izni vermedi."
            }
            grantAccess.takePersistableUriPermission(uri, flags)
            val actualGrant = grantAccess.snapshots().firstOrNull { it.uri == uri.toString() }
            AndroidCapabilityStatusReader(appContext).recordStoragePickerResult(
                readGranted = actualGrant?.hasReadPermission == true,
                writeGranted = actualGrant?.hasWritePermission == true
            )
            require(StorageUriGrantPolicy.hasUriGrant(uri.toString(), grantAccess.snapshots(), requireWrite = false)) {
                "Android seçilen dosya veya klasör için kalıcı okuma izni kaydetmedi."
            }

            val saved = LinkedHashSet(preferences.getStringSet(KEY_URIS, emptySet()).orEmpty())
            saved += uri.toString()
            val editor = preferences.edit().putStringSet(KEY_URIS, saved)
            if (makeLast) editor.putString(KEY_LAST_URI, uri.toString())
            check(editor.commit()) { "Seçilen dosya/klasör URI kaydı kalıcılaştırılamadı." }
        } catch (error: Exception) {
            val actualGrant = grantAccess.snapshots().firstOrNull { it.uri == uri.toString() }
            AndroidCapabilityStatusReader(appContext).recordStoragePickerResult(
                readGranted = actualGrant?.hasReadPermission == true,
                writeGranted = actualGrant?.hasWritePermission == true
            )
            throw error
        }
    }

    fun lastGrantedTreeUri(): Uri? {
        val saved = preferences.getString(KEY_LAST_URI, null) ?: return null
        if (!StorageUriGrantPolicy.hasUriGrant(saved, grantAccess.snapshots(), requireWrite = false)) {
            removeSaved(saved)
            return null
        }
        return Uri.parse(saved)
    }

    fun grantedTreeUris(): List<Uri> {
        val saved = preferences.getStringSet(KEY_URIS, emptySet()).orEmpty()
        val valid = StorageUriGrantPolicy.activeSavedUris(saved, grantAccess.snapshots())
        check(preferences.edit().putStringSet(KEY_URIS, valid).commit()) {
            "Geçersiz URI izinleri temizlenemedi."
        }
        return valid.map(Uri::parse)
    }

    fun forget(uri: Uri) {
        val grant = grantAccess.snapshots().firstOrNull { it.uri == uri.toString() }
        if (grant != null) {
            var flags = 0
            if (grant.hasReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (grant.hasWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { grantAccess.releasePersistableUriPermission(uri, flags) }
        }
        removeSaved(uri.toString())
    }

    private fun removeSaved(uri: String) {
        val saved = LinkedHashSet(preferences.getStringSet(KEY_URIS, emptySet()).orEmpty())
        saved.remove(uri)
        val editor = preferences.edit().putStringSet(KEY_URIS, saved)
        if (preferences.getString(KEY_LAST_URI, null) == uri) editor.remove(KEY_LAST_URI)
        check(editor.commit()) { "URI izin kaydı temizlenemedi." }
    }

    private companion object {
        const val KEY_URIS = "uris"
        const val KEY_LAST_URI = "last_uri"
    }
}
