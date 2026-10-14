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
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaSourceInfo
import timber.log.Timber
import java.util.UUID

class DownloadManager(
    private val context: Context,
    private val api: ApiClient,
    private val downloadDao: DownloadDao,
    private val appPreferences: AppPreferences,
    private val storageManager: StorageManager,
    private val offlineLibrary: OfflineLibrary,
    private val downloadNotificationManager: DownloadNotificationManager,
    private val offlineStore: OfflineStore,
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

    /**
     * @param options how videos are downloaded (chosen on the download screen); items without keep
     * how they were downloaded before, new ones are downloaded as the original file.
     */
    suspend fun enqueueItems(
        server: ServerEntity,
        user: UserEntity,
        items: Collection<UUID>,
        options: Map<UUID, DownloadOptions> = emptyMap(),
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

            for (fetched in withAlbumNames(response.items)) {
                var downloadEntity = existingItems[fetched.id]
                val previous = offlineStore.optionsOf(fetched.id)
                val itemOptions = options[fetched.id] ?: previous
                // The stored item describes the file that is downloaded: only its tracks, numbered as in it
                val item = withEdits(fetched.copy(mediaSources = downloadedSources(fetched.mediaSources, itemOptions)))
                // Another way of downloading: the old file goes, the next one is made anew
                if (downloadEntity != null && itemOptions != previous) removeMainFile(downloadEntity.id)
                offlineStore.setOptions(fetched.id, itemOptions)
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

    /** The server's item with the edits made on the phone (which remember what the server says now). */
    private fun withEdits(item: BaseItemDto): BaseItemDto {
        val override = offlineStore.overrideOf(item.id) ?: return item
        val updated = override.copy(original = MetadataFields.of(item))
        offlineStore.setOverride(item.id, updated)
        return updated.applyTo(item)
    }

    /**
     * Edits [downloads] on the phone: the set fields of [changes] (several downloads - e.g. an album: the
     * fields they share). The server is not changed.
     */
    suspend fun editMetadata(downloads: List<DownloadEntity>, changes: MetadataFields) = withContext(Dispatchers.IO) {
        if (changes.isEmpty) return@withContext
        for (download in downloads) {
            val current = downloadDao.getDownload(download.id) ?: continue
            val previous = offlineStore.overrideOf(current.itemId)
            val original = previous?.original ?: MetadataFields.of(current.item)
            val override = MetadataOverride(original, (previous?.edited ?: MetadataFields()).merge(changes))
            offlineStore.setOverride(current.itemId, override)
            downloadDao.update(current.copy(item = override.applyTo(previous?.restore(current.item) ?: current.item)))
        }
        offlineLibrary.scheduleWrite()
    }

    /** Back to what the server says (as it was last known, also without a connection). */
    suspend fun resetMetadata(downloads: List<DownloadEntity>) = withContext(Dispatchers.IO) {
        for (download in downloads) {
            val current = downloadDao.getDownload(download.id) ?: continue
            val override = offlineStore.overrideOf(current.itemId) ?: continue
            offlineStore.setOverride(current.itemId, null)
            downloadDao.update(current.copy(item = override.restore(current.item)))
        }
        offlineLibrary.scheduleWrite()
    }

    private fun downloadedSources(sources: List<MediaSourceInfo>?, options: DownloadOptions): List<MediaSourceInfo>? {
        if (options.isOriginal || sources.isNullOrEmpty()) return sources
        val source = sources.firstOrNull { it.id == options.mediaSourceId } ?: sources.first()
        return listOf(options.rewrite(source))
    }

    private suspend fun removeMainFile(downloadId: Long) {
        val main = downloadDao.getFiles(downloadId).firstOrNull { it.type == DownloadFileType.ITEM } ?: return
        runCatching { DocumentFile.fromSingleUri(context, main.uri)?.delete() }
        downloadDao.deleteFile(main.id)
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
            val byId = withAlbumNames(items).associateBy { it.id }
            for (download in chunk) {
                val fresh = byId[download.itemId] ?: continue
                // A remuxed or transcoded file keeps the description of its own tracks
                val sources = if (offlineStore.optionsOf(download.itemId).isOriginal) fresh.mediaSources else download.item.mediaSources
                downloadDao.update(download.copy(item = withEdits(fresh.copy(genres = fresh.genres ?: emptyList(), mediaSources = sources))))
            }
        }
    }

    /**
     * Tracks show their album as it is now: the album's own name and artists. A track keeps the ones of its
     * file tags, also after the album was edited on the server (older servers do not pass the edit on).
     */
    private suspend fun withAlbumNames(items: List<BaseItemDto>): List<BaseItemDto> {
        val albumIds = items.filter { it.type == BaseItemKind.AUDIO }.mapNotNull { it.albumId }.distinct()
        if (albumIds.isEmpty()) return items
        val albums = albumIds.chunked(ITEMS_BATCH).flatMap { chunk ->
            runCatching { api.itemsApi.getItems(ids = chunk).content.items }.getOrNull().orEmpty()
        }.associateBy { it.id }
        return items.map { item ->
            val album = item.albumId?.let(albums::get) ?: return@map item
            item.copy(
                album = album.name?.takeIf { it.isNotBlank() } ?: item.album,
                albumArtist = album.albumArtist?.takeIf { it.isNotBlank() } ?: item.albumArtist,
                albumArtists = album.albumArtists?.takeIf { it.isNotEmpty() } ?: item.albumArtists,
            )
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

    /** Pauses every queued and running download (notification and downloads screen). */
    suspend fun pauseAll() = withContext(Dispatchers.IO) {
        val pending = downloadDao.getQueuedDownloads()
        if (pending.isEmpty()) return@withContext
        // Paused first, so the stopped work does not put them back into the queue
        for ((download, _) in pending) downloadDao.update(download.copy(status = DownloadStatus.PAUSED))
        DownloadWorker.stop(context)
        pending.forEach { DownloadProgressTracker.remove(it.download.id) }
        downloadNotificationManager.showPaused(pending.size)
    }

    suspend fun resumeAll() = withContext(Dispatchers.IO) {
        val paused = downloadDao.getPausedDownloads()
        for (download in paused) downloadDao.update(download.copy(status = DownloadStatus.QUEUED))
        downloadNotificationManager.hidePaused()
        if (paused.isNotEmpty() && !DownloadWorker.isActive(context)) DownloadWorker.start(context, appPreferences)
    }

    /** Cancels everything that is not downloaded yet, with its partial files. */
    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        // Out of the queue first, so the work does not pick the next one meanwhile
        val unfinished = downloadDao.getUnfinishedDownloads()
        for (download in unfinished) downloadDao.update(download.copy(status = DownloadStatus.PAUSED))
        DownloadWorker.stop(context)
        for (download in unfinished) {
            DownloadProgressTracker.remove(download.id)
            delete(download.id, deleteFiles = true)
        }
        downloadNotificationManager.hidePaused()
        downloadNotificationManager.hideProgress()
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
        offlineStore.setOptions(download.itemId, DownloadOptions())

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
