package org.jellyfin.mobile.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadStatus
import timber.log.Timber

class StorageManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {
    val defaultStorageLocation
        get() = Environment.getExternalStorageDirectory().resolve(context.getString(R.string.app_name_short)).toUri()

    fun getStorageLocation() = appPreferences.storageLocation?.toUri()?.let {
        DocumentFile.fromTreeUri(context, it)
    }

    fun isStorageLocationAccessible(): Boolean {
        val documentFile = getStorageLocation()
        return documentFile != null && documentFile.exists() && documentFile.canWrite()
    }

    fun changeStorageLocation(location: Uri): Boolean {
        if (appPreferences.storageLocation?.toUri() == location) return true

        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                location,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )

            appPreferences.storageLocation = location.toString()
            getStorageLocation()?.let(::ensureNoMedia)
        }.onFailure { err ->
            Timber.e(err, "Failed to change storage location to $location")
        }.isFailure
    }

    fun verify(download: DownloadFiles): Boolean {
        if (download.files.isEmpty()) return false

        for (file in download.files) {
            if (file.status != DownloadStatus.DOWNLOADED) return false
            val documentFile = DocumentFile.fromSingleUri(context, file.uri)
            if (documentFile == null || !documentFile.exists() || documentFile.length() != file.size) {
                return false
            }
        }

        return true
    }

    /**
     * Finds (or with [create] makes) the folder at the "/"-separated [path] inside the storage location.
     */
    fun resolveDirectory(path: String, create: Boolean): DocumentFile? {
        var directory = getStorageLocation() ?: return null
        for (segment in path.split('/').filter(String::isNotEmpty)) {
            val existing = directory.findFile(segment)
            directory = when {
                existing != null && existing.isDirectory -> existing
                create && existing == null -> directory.createDirectory(segment) ?: return null
                else -> return null
            }
        }
        return directory
    }

    /** Finds the file at the "/"-separated [path] inside the storage location. */
    fun resolveFile(path: String): DocumentFile? {
        val directory = resolveDirectory(path.substringBeforeLast('/', ""), create = false) ?: return null
        return directory.findFile(path.substringAfterLast('/'))?.takeIf { it.isFile }
    }

    /** Finds or creates the file at the "/"-separated [path], making its folders as needed. */
    fun createFile(path: String): DocumentFile? {
        val directory = resolveDirectory(path.substringBeforeLast('/', ""), create = true) ?: return null
        val name = path.substringAfterLast('/')
        return directory.findFile(name)?.takeIf { it.isFile } ?: directory.createFile("", name)
    }

    /** Removes the folder at [path] and its parents while they are empty (never the storage location itself). */
    fun deleteEmptyDirectories(path: String) {
        var current = path.split('/').filter(String::isNotEmpty)
        while (current.isNotEmpty()) {
            val directory = resolveDirectory(current.joinToString("/"), create = false) ?: return
            if (directory.listFiles().isNotEmpty() || !directory.delete()) return
            current = current.dropLast(1)
        }
    }

    private fun ensureNoMedia(documentFile: DocumentFile) {
        if (documentFile.findFile(NOMEDIA_FILE) == null) {
            documentFile.createFile("", NOMEDIA_FILE)
        }
    }

    companion object {
        const val NOMEDIA_FILE = ".nomedia"
    }
}
