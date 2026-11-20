package com.openjarvis.deviceaccess.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.util.LinkedHashSet

data class StorageGrantSnapshot(
    val uri: String,
    val authority: String?,
    val treeDocumentId: String?,
    val hasReadPermission: Boolean,
    val hasWritePermission: Boolean
)

object StorageUriGrantPolicy {
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
        uriAuthority: String?,
        uriTreeDocumentId: String?,
        actualGrants: List<StorageGrantSnapshot>,
        requireWrite: Boolean
    ): Boolean = actualGrants.any { grant ->
        StorageScopePolicy.isWithinGrantedTree(
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

/** Persists only picker-returned URI grants and revalidates them after process restart. */
class StorageUriGrantRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("jarvis_storage_uri_grants", Context.MODE_PRIVATE)

    fun persistPickerResult(uri: Uri, returnedFlags: Int, makeLast: Boolean = true) {
        val flags = StorageUriGrantPolicy.persistableFlags(
            returnedFlags,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "Android dosya seçicisi kalıcı okuma izni vermedi."
        }
        resolver.takePersistableUriPermission(uri, flags)
        require(StorageUriGrantPolicy.hasUriGrant(uri.toString(), persistedGrantSnapshots(), requireWrite = false)) {
            "Android seçilen klasör için kalıcı okuma izni kaydetmedi."
        }

        val saved = LinkedHashSet(preferences.getStringSet(KEY_URIS, emptySet()).orEmpty())
        saved += uri.toString()
        val editor = preferences.edit().putStringSet(KEY_URIS, saved)
        if (makeLast) editor.putString(KEY_LAST_URI, uri.toString())
        editor.apply()
    }

    fun lastGrantedTreeUri(): Uri? {
        val saved = preferences.getString(KEY_LAST_URI, null) ?: return null
        if (!StorageUriGrantPolicy.hasUriGrant(saved, persistedGrantSnapshots(), requireWrite = false)) {
            removeSaved(saved)
            return null
        }
        return Uri.parse(saved)
    }

    fun grantedTreeUris(): List<Uri> {
        val saved = preferences.getStringSet(KEY_URIS, emptySet()).orEmpty()
        val valid = StorageUriGrantPolicy.activeSavedUris(saved, persistedGrantSnapshots())
        preferences.edit().putStringSet(KEY_URIS, valid).apply()
        return valid.map(Uri::parse)
    }

    fun forget(uri: Uri) {
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (grant != null) {
            var flags = 0
            if (grant.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (grant.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { resolver.releasePersistableUriPermission(uri, flags) }
        }
        removeSaved(uri.toString())
    }

    private fun persistedGrantSnapshots(): List<StorageGrantSnapshot> =
        resolver.persistedUriPermissions.mapNotNull { grant ->
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(grant.uri) }.getOrNull()
                ?: return@mapNotNull null
            StorageGrantSnapshot(
                uri = grant.uri.toString(),
                authority = grant.uri.authority,
                treeDocumentId = treeId,
                hasReadPermission = grant.isReadPermission,
                hasWritePermission = grant.isWritePermission
            )
        }

    private fun removeSaved(uri: String) {
        val saved = LinkedHashSet(preferences.getStringSet(KEY_URIS, emptySet()).orEmpty())
        saved.remove(uri)
        val editor = preferences.edit().putStringSet(KEY_URIS, saved)
        if (preferences.getString(KEY_LAST_URI, null) == uri) editor.remove(KEY_LAST_URI)
        editor.apply()
    }

    private companion object {
        const val KEY_URIS = "uris"
        const val KEY_LAST_URI = "last_uri"
    }
}
