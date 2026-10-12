package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.io.IOException
import java.util.UUID

class DownloadQueue(
    private val context: Context,
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val downloadNotificationManager: DownloadNotificationManager,
    private val storageManager: StorageManager,
    private val offlineLibrary: OfflineLibrary,
    okHttpClient: OkHttpClient,
) {
    /**
     * @param shared the file is shared by several downloads (album cover, series and season posters):
     * an existing copy is kept instead of being downloaded again.
     * @param optional a failure (e.g. a missing image) does not fail the whole download.
     */
    private data class QueuedFile(
        val file: DownloadFileEntity,
        val remoteUri: Uri,
        val shared: Boolean = false,
        val optional: Boolean = false,
    )

    private val _downloader = FileDownloader(okHttpClient)
    private val _downloads = mutableListOf<DownloadFiles>()

    suspend fun prepare(): Boolean {
        val queuedDownloads = downloadDao.getQueuedDownloads()
        _downloads.clear()
        _downloads.addAll(queuedDownloads)
        return _downloads.any()
    }

    suspend fun process() {
        while (_downloads.any()) {
            val iterator = _downloads.iterator()
            while (iterator.hasNext()) {
                val downloadWithFiles = iterator.next()
                process(downloadWithFiles)
                iterator.remove()
            }

            // Refetch the queued downloads
            prepare()
        }
    }

    private suspend fun process(downloadWithFiles: DownloadFiles) {
        // Mark as downloading
        var current = downloadWithFiles.download.copy(status = DownloadStatus.DOWNLOADING)
        downloadDao.update(current)
        val api = apiClientController.getApiClient(current.serverId, current.userId)

        try {
            val (prepared, queuedFiles) = prepareFiles(api, downloadWithFiles.copy(download = current))
            current = prepared

            val notificationProgressCallback = downloadNotificationManager.downloadFile(
                downloadWithFiles.download.id,
                downloadWithFiles.download.getDisplayName(context).orEmpty(),
            )

            for (queuedFile in queuedFiles) {
                if (queuedFile.optional) {
                    try {
                        download(api, queuedFile, notificationProgressCallback)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Skipping ${queuedFile.file.fileName}")
                        downloadDao.deleteFile(queuedFile.file.id)
                        DocumentFile.fromSingleUri(context, queuedFile.file.uri)
                            ?.takeIf { it.exists() && it.length() == 0L }
                            ?.delete()
                    }
                } else {
                    download(api, queuedFile, notificationProgressCallback)
                }
            }

            notificationProgressCallback.onEnd()
            downloadDao.update(current.copy(status = DownloadStatus.DOWNLOADED))
            offlineLibrary.scheduleWrite()
        } catch (e: CancellationException) {
            // The download could've been canceled by the app, in which case we need to refresh it before making changes
            val download = downloadDao.getDownload(downloadWithFiles.download.id)
            if (download?.status == DownloadStatus.DOWNLOADING) {
                downloadDao.update(download.copy(status = DownloadStatus.QUEUED))
            }
            throw e
        } catch (e: IOException) {
            downloadDao.update(current.copy(status = DownloadStatus.QUEUED))
            throw e
        } catch (e: Exception) {
            downloadDao.update(current.copy(status = DownloadStatus.ERROR))
            throw e
        }
    }

    private suspend fun download(
        api: ApiClient,
        queuedFile: QueuedFile,
        progressCallback: FileDownloader.ProgressCallback,
    ) {
        val (file, remoteUri) = queuedFile

        // Verify downloaded files and skip if valid
        if (file.status == DownloadStatus.DOWNLOADED && file.size > 0) {
            val documentFile = DocumentFile.fromSingleUri(context, file.uri)
            if (documentFile?.exists() == true && documentFile.length() == file.size) return
        }

        // Another download already brought the shared cover or poster
        if (queuedFile.shared) {
            val length = DocumentFile.fromSingleUri(context, file.uri)?.takeIf { it.exists() }?.length() ?: 0L
            if (length > 0) {
                downloadDao.updateFile(file.copy(size = length, status = DownloadStatus.DOWNLOADED))
                return
            }
        }

        val fileDescriptor = context.contentResolver.openFileDescriptor(file.uri, "rw")
            ?: error("Unable to open file descriptor for ${file.fileName}")

        downloadDao.updateFile(file.copy(status = DownloadStatus.DOWNLOADING))

        try {
            _downloader.downloadAndSave(
                api = api,
                from = remoteUri,
                to = fileDescriptor,
                progressCallback = progressCallback,
            )

            // Update file record with final size and status
            downloadDao.updateFile(
                file.copy(
                    size = DocumentFile.fromSingleUri(context, file.uri)?.length() ?: 0L,
                    status = DownloadStatus.DOWNLOADED,
                ),
            )
        } catch (e: IOException) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.QUEUED))
            throw e
        } catch (e: Exception) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.ERROR))
            throw e
        }
    }

    private suspend fun prepareFiles(api: ApiClient, downloadWithFiles: DownloadFiles): Pair<DownloadEntity, List<QueuedFile>> {
        val (download, files) = downloadWithFiles
        val item = download.item
        val layout = OfflineLayout.resolve(api, listOf(item))[item.id] ?: OfflineLayout.fromMetadata(item)

        // Files that are already there stay where they are; everything else follows the library layout
        val keepFolder = files.any { it.status == DownloadStatus.DOWNLOADED }
        val directory = if (keepFolder) download.path else layout.directory
        val updated = if (directory != download.path) download.copy(path = directory).also { downloadDao.update(it) } else download
        val prepared = downloadWithFiles.copy(download = updated)
        val fileName = if (keepFolder) {
            files.firstOrNull { it.type == DownloadFileType.ITEM }?.fileName?.substringAfterLast('/') ?: layout.fileName
        } else {
            layout.fileName
        }
        val placed = layout.copy(directory = directory, fileName = fileName)

        return updated to buildList {
            // Add image as first item so it can be shown in UI during downloads
            preparePrimaryImageFile(api, prepared, placed)?.let(::add)

            // Add main item second as it is (often) the largest and important file
            add(prepareMainFile(api, prepared, placed))

            if (item.type == BaseItemKind.EPISODE) addAll(prepareSeriesFiles(api, prepared, placed))
            if (item.type == BaseItemKind.AUDIO) registerAlbum(api, item, placed)
        }
    }

    private suspend fun prepareMainFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        layout: OfflineLayout,
    ) = QueuedFile(
        file = createOrUpdateFile(
            filter = { it.type == DownloadFileType.ITEM },
            downloadWithFiles = downloadWithFiles,
            type = DownloadFileType.ITEM,
            path = layout.filePath,
        ),
        remoteUri = api.libraryApi.getDownloadUrl(downloadWithFiles.download.item.id).toUri(),
    )

    /** The episode thumbnail, the movie poster, or the album cover shared by its tracks. */
    private suspend fun preparePrimaryImageFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        layout: OfflineLayout,
    ): QueuedFile? {
        val item = downloadWithFiles.download.item
        val albumImage = item.type == BaseItemKind.AUDIO && item.albumId != null && item.albumPrimaryImageTag != null
        val (imageItemId, imageTag) = when {
            albumImage -> item.albumId!! to item.albumPrimaryImageTag
            else -> item.id to (item.imageTags?.get(ImageType.PRIMARY) ?: return null)
        }

        return QueuedFile(
            file = createOrUpdateFile(
                filter = { it.type == DownloadFileType.IMAGE_PRIMARY },
                downloadWithFiles = downloadWithFiles,
                type = DownloadFileType.IMAGE_PRIMARY,
                path = layout.imagePath,
            ),
            remoteUri = imageUrl(api, imageItemId, imageTag),
            shared = layout.imageName == OfflineLayout.ALBUM_COVER,
            optional = true,
        )
    }

    /** Series and season posters of an episode, and their description for the manifest. */
    private suspend fun prepareSeriesFiles(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        layout: OfflineLayout,
    ): List<QueuedFile> = buildList {
        val item = downloadWithFiles.download.item

        item.seriesId?.let { seriesId -> fetchItem(api, seriesId) }?.let { series ->
            val path = layout.seriesImagePath()
            val tag = series.imageTags?.get(ImageType.PRIMARY)
            offlineLibrary.registerTitle(series, layout.seriesDirectory, path.takeIf { tag != null })
            if (path != null && tag != null) {
                add(prepareSharedImage(api, downloadWithFiles, DownloadFileType.IMAGE_SERIES, path, series.id, tag))
            }
        }

        item.seasonId?.let { seasonId -> fetchItem(api, seasonId) }?.let { season ->
            val path = layout.seasonImagePath(season.indexNumber ?: item.parentIndexNumber)
            val tag = season.imageTags?.get(ImageType.PRIMARY)
            offlineLibrary.registerTitle(season, layout.seasonDirectory, path.takeIf { tag != null })
            if (path != null && tag != null) {
                add(prepareSharedImage(api, downloadWithFiles, DownloadFileType.IMAGE_SEASON, path, season.id, tag))
            }
        }
    }

    private suspend fun registerAlbum(api: ApiClient, item: BaseItemDto, layout: OfflineLayout) {
        val album = item.albumId?.let { albumId -> fetchItem(api, albumId) } ?: return
        offlineLibrary.registerTitle(album, layout.directory, layout.imagePath.takeIf { item.albumPrimaryImageTag != null })
    }

    private suspend fun prepareSharedImage(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        type: DownloadFileType,
        path: String,
        imageItemId: UUID,
        tag: String,
    ) = QueuedFile(
        file = createOrUpdateFile(
            filter = { it.type == type },
            downloadWithFiles = downloadWithFiles,
            type = type,
            path = path,
        ),
        remoteUri = imageUrl(api, imageItemId, tag),
        shared = true,
        optional = true,
    )

    private suspend fun fetchItem(api: ApiClient, itemId: UUID): BaseItemDto? = runCatching {
        api.userLibraryApi.getItem(itemId).content
    }.getOrNull()

    private fun imageUrl(api: ApiClient, itemId: UUID, tag: String?) = api.imageApi.getItemImageUrl(
        itemId = itemId,
        imageType = ImageType.PRIMARY,
        tag = tag,
        format = ImageFormat.JPG,
    ).toUri()

    private suspend fun createOrUpdateFile(
        filter: (DownloadFileEntity) -> Boolean,
        downloadWithFiles: DownloadFiles,
        type: DownloadFileType,
        path: String,
    ): DownloadFileEntity {
        var downloadFile = downloadWithFiles.files.firstOrNull(filter)

        // Keep a file that was already downloaded (also when it lies in an older, flat folder)
        if (downloadFile != null && downloadFile.status == DownloadStatus.DOWNLOADED) {
            val existing = DocumentFile.fromSingleUri(context, downloadFile.uri)
            if (existing?.exists() == true && existing.length() == downloadFile.size) return downloadFile
        }

        val file = storageManager.createFile(path) ?: error("Unable to create file $path")
        val fileName = path

        if (downloadFile != null) {
            downloadFile = downloadFile.copy(
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )
            downloadDao.updateFile(downloadFile)
            return downloadFile
        } else {
            downloadFile = DownloadFileEntity(
                downloadId = downloadWithFiles.download.id,
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )

            val id = downloadDao.insertFile(downloadFile)
            downloadFile = downloadFile.copy(id = id)
            return downloadFile
        }
    }
}
