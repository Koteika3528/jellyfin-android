package org.jellyfin.mobile.downloads

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import org.jellyfin.mobile.R
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.util.UUID

class DownloadManager(
    private val context: Context,
    private val api: ApiClient,
    private val downloadDao: DownloadDao,
    private val appPreferences: AppPreferences,
    private val storageManager: StorageManager,
    private val offlineLibrary: OfflineLibrary,
) {
    companion object {
        /**
         * How many items can be processed at once in [enqueueItems]. If more items are enqueued at once they will be
         * split into separate download chunks.
         */
        private const val ITEMS_BATCH = 25

        const val BYTES_PER_GB = 1_000_000_000L
        private const val LOW_SPACE_BYTES = 2 * BYTES_PER_GB

        /** What a download keeps of its item: files and streams to play it, genres and overview to browse it offline. */
        private val ITEM_FIELDS = setOf(ItemFields.MEDIA_SOURCES, ItemFields.PATH, ItemFields.GENRES, ItemFields.OVERVIEW)
    }

    suspend fun enqueueItems(
        server: ServerEntity,
        user: UserEntity,
        items: Collection<UUID>,
    ) = withContext(Dispatchers.IO) {
        for (itemsChunk in items.chunked(ITEMS_BATCH)) {
            val existingItems = downloadDao.getDownloadsByItemIds(itemsChunk)
                .filter { it.serverId == server.id }
                .associateBy { it.itemId }

            val response by api.itemsApi.getItems(
                ids = itemsChunk,
                fields = ITEM_FIELDS,
            )

            // Sanity check, this shouldn't happen really
            if (response.items.size != itemsChunk.size) {
                error(
                    "Requested ${itemsChunk.size} items but only got ${response.items.size}. Indicating one or multiple items do not exist.",
                )
            }

            for (item in response.items) {
                var downloadEntity = existingItems[item.id]
                if (downloadEntity != null) {
                    // If the item already exists we just update the local information for it and requeue it
                    // this will force the download worker to recheck the local file in case it is missing or changed
                    downloadEntity = downloadEntity.copy(
                        item = item,
                        status = DownloadStatus.QUEUED,
                        modifiedAt = System.currentTimeMillis(),
                    )
                    downloadDao.update(downloadEntity)
                } else {
                    // Otherwise we create a new one
                    downloadEntity = DownloadEntity(
                        serverId = server.id,
                        userId = user.id,
                        itemId = item.id,
                        item = item,
                        path = OfflineLayout.fromMetadata(item).directory,
                    )
                    downloadDao.insert(downloadEntity)
                }
            }
        }

        if (!DownloadWorker.isActive(context)) {
            DownloadWorker.start(context, appPreferences)
        }

        warnAboutSpace()
    }

    /** "Warn when the device runs out of space": little free space, or the space set aside is used up. */
    private suspend fun warnAboutSpace() {
        if (!appPreferences.lowSpaceWarning) return
        val free = storageManager.availableBytes()
        val used = downloadDao.getCompletedDownloads().sumOf { download -> download.files.sumOf { it.size } }
        val limit = appPreferences.storageLimitGb * BYTES_PER_GB
        val message = when {
            free != null && free < LOW_SPACE_BYTES ->
                context.getString(R.string.downloads_warning_low_space, Formatter.formatShortFileSize(context, LOW_SPACE_BYTES))
            limit > 0 && used >= limit -> context.getString(
                R.string.downloads_warning_limit,
                Formatter.formatShortFileSize(context, used),
                Formatter.formatShortFileSize(context, limit),
            )
            else -> return
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * Updates the downloaded items from the server: what was watched elsewhere, and what older downloads
     * were made without (genres, overview, to browse by genre offline). Only for the current account,
     * and quietly does nothing without a connection.
     */
    suspend fun refreshMetadata() = withContext(Dispatchers.IO) {
        val serverId = appPreferences.currentServerId ?: return@withContext
        val userId = appPreferences.currentUserId ?: return@withContext
        val stale = downloadDao.getCompletedDownloads()
            .map { it.download }
            .filter { it.serverId == serverId && it.userId == userId }

        for (chunk in stale.chunked(ITEMS_BATCH)) {
            val items = runCatching {
                api.itemsApi.getItems(ids = chunk.map { it.itemId }, fields = ITEM_FIELDS).content.items
            }.getOrNull() ?: return@withContext
            val byId = items.associateBy { it.id }
            for (download in chunk) {
                val fresh = byId[download.itemId] ?: continue
                downloadDao.update(download.copy(item = fresh.copy(genres = fresh.genres ?: emptyList())))
            }
        }
    }

        /**
     * Picks up downloads interrupted by the system (the app was killed, the work failed): they stay
     * "downloading" in the database with nothing running. Called when the app starts.
     */
    suspend fun resumeInterrupted() = withContext(Dispatchers.IO) {
        val pending = downloadDao.getQueuedDownloads()
        if (pending.isEmpty() || DownloadWorker.isScheduled(context)) return@withContext
        for ((download, _) in pending) {
            if (download.status == DownloadStatus.DOWNLOADING) downloadDao.update(download.copy(status = DownloadStatus.QUEUED))
        }
        Timber.i("Resuming ${pending.size} interrupted downloads")
        DownloadWorker.start(context, appPreferences)
    }

    suspend fun resume(downloadEntity: DownloadEntity) = withContext(Dispatchers.IO) {
        downloadDao.update(
            downloadEntity.copy(
                status = DownloadStatus.QUEUED,
                modifiedAt = System.currentTimeMillis(),
            ),
        )

        if (!DownloadWorker.isActive(context)) {
            DownloadWorker.start(context, appPreferences)
        }
    }

    suspend fun cancel(id: Long) = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownload(id) ?: return@withContext
        downloadDao.update(download.copy(status = DownloadStatus.CANCELLED))

        if (download.status == DownloadStatus.DOWNLOADING) {
            DownloadWorker.restart(context, appPreferences)
        }
    }

    suspend fun delete(id: Long, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownload(id) ?: return@withContext
        val files = downloadDao.getFiles(id)

        // Covers and posters shared with other downloads stay (counted before this download's rows go away)
        val removable = if (deleteFiles) files.filter { file -> downloadDao.countOtherFilesWithUri(file.uri, id) == 0 } else emptyList()

        downloadDao.delete(id)

        if (download.status == DownloadStatus.DOWNLOADING) {
            DownloadWorker.restart(context, appPreferences)
        }

        if (deleteFiles) {
            for (file in removable) {
                runCatching { DocumentFile.fromSingleUri(context, file.uri)?.delete() }
            }
            // Remove folders left empty: the item's folder and, for episodes, the season and series folders
            val folders = buildSet {
                add(download.path)
                files.forEach { file -> add(offlineLibrary.relativePath(download, file).substringBeforeLast('/', "")) }
            }
            folders.sortedByDescending { it.length }.forEach(storageManager::deleteEmptyDirectories)
        }

        if (deleteFiles) offlineLibrary.scheduleWrite() else offlineLibrary.dismiss(download.itemId)
    }
}
