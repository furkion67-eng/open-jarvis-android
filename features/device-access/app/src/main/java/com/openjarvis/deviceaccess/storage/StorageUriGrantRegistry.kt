package com.openjarvis.deviceaccess.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.LinkedHashSet

object StorageUriGrantPolicy {
    fun persistableFlags(returnedFlags: Int, readFlag: Int, writeFlag: Int): Int =
        returnedFlags and (readFlag or writeFlag)

    fun isStillReadable(savedUri: String?, actualUri: String?, hasReadPermission: Boolean): Boolean =
        !savedUri.isNullOrBlank() && savedUri == actualUri && hasReadPermission
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
        val actual = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        require(actual) { "Android seçilen klasör için kalıcı okuma izni kaydetmedi." }

        val saved = LinkedHashSet(preferences.getStringSet(KEY_URIS, emptySet()).orEmpty())
        saved += uri.toString()
        val editor = preferences.edit().putStringSet(KEY_URIS, saved)
        if (makeLast) editor.putString(KEY_LAST_URI, uri.toString())
        editor.apply()
    }

    fun lastGrantedTreeUri(): Uri? {
        val saved = preferences.getString(KEY_LAST_URI, null) ?: return null
        val actual = resolver.persistedUriPermissions.firstOrNull {
            it.uri.toString() == saved && it.isReadPermission
        }
        if (!StorageUriGrantPolicy.isStillReadable(saved, actual?.uri?.toString(), actual?.isReadPermission == true)) {
            removeSaved(saved)
            return null
        }
        return Uri.parse(saved)
    }

    fun grantedTreeUris(): List<Uri> {
        val actualReadable = resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val saved = preferences.getStringSet(KEY_URIS, emptySet()).orEmpty()
        val valid = saved.filter { it in actualReadable }.toSet()
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
