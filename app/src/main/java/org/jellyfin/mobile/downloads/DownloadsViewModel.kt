package org.jellyfin.mobile.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.data.entity.PlayStateEntity
import org.jellyfin.mobile.app.AppPreferences
import java.util.UUID
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.model.api.MediaType
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

class DownloadsViewModel : ViewModel(), KoinComponent {

    private val downloadDao: DownloadDao by inject()
    private val downloadManager: DownloadManager by inject()
    private val activityEventHandler: ActivityEventHandler by inject()
    private val storageManager: StorageManager by inject()
    private val offlineLibrary: OfflineLibrary by inject()
    private val offlinePlayback: OfflinePlayback by inject()
    private val offlineStore: OfflineStore by inject()
    private val musicPlayer = DownloadedMusicPlayer(get())

    val nowPlaying: StateFlow<DownloadedMusicPlayer.NowPlaying?> = musicPlayer.nowPlaying

    /** Where downloads were left: shown on rows and used to continue a show. */
    val playStates: StateFlow<Map<UUID, PlayStateEntity>> = offlinePlayback.observeStates()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    private val offlineMaintenance: OfflineMaintenance by inject()
    private val appPreferences: AppPreferences by inject()

    /** Free space where the downloads are, when known. */
    private val _availableBytes = MutableStateFlow<Long?>(null)
    val availableBytes: StateFlow<Long?> = _availableBytes.asStateFlow()

    val storageLimitBytes: Long get() = appPreferences.storageLimitGb * DownloadManager.BYTES_PER_GB

    val newEpisodesEnabled: Boolean get() = appPreferences.newEpisodesMode != AutoMode.OFF

    /** Followed shows: new episodes are downloaded for them (see the settings). */
    val follows: StateFlow<List<OfflineStore.Follow>> = offlineStore.follows

    fun refreshAvailableBytes() {
        viewModelScope.launch { _availableBytes.value = withContext(Dispatchers.IO) { storageManager.availableBytes() } }
    }

    fun setFollowed(download: DownloadEntity, followed: Boolean) {
        val seriesId = download.item.seriesId ?: return
        offlineStore.setFollowed(seriesId, download.serverId, download.userId, download.item.seriesName, followed)
        if (followed) offlineMaintenance.runInBackground()
    }

    /** Deletes the downloads chosen on the storage page, with their files. */
    fun deleteDownloads(downloads: List<DownloadEntity>) {
        viewModelScope.launch { downloads.forEach { downloadManager.delete(it.id, deleteFiles = true) } }
    }

    /** Favorite marks not sent to the server yet, for [isFavorite]. */
    val pendingFavorites: StateFlow<Map<String, Boolean>> = offlineStore.pendingFavorites

    fun isFavorite(download: DownloadEntity, pending: Map<String, Boolean>) = offlinePlayback.isFavorite(download, pending)

    fun toggleFavorite(download: DownloadEntity, pending: Map<String, Boolean>) =
        offlinePlayback.setFavorite(download.itemId, !isFavorite(download, pending))

    fun progressOf(download: DownloadEntity, states: Map<UUID, PlayStateEntity>): OfflinePlayback.Progress? =
        offlinePlayback.progressOf(download, states[download.itemId])

    /** Plays a downloaded video from its beginning instead of where it was left. */
    fun openFromStart(download: DownloadEntity) {
        activityEventHandler.emit(
            ActivityEvent.LaunchNativePlayer(
                PlayOptions(
                    ids = listOf(download.itemId),
                    mediaSourceId = download.itemId.toString(),
                    startIndex = 0,
                    startPosition = kotlin.time.Duration.ZERO,
                    audioStreamIndex = null,
                    subtitleStreamIndex = null,
                    playFromDownloads = true,
                ),
            ),
        )
    }

    init {
        musicPlayer.connect()
        syncLibrary()
    }

    /**
     * Brings back downloads listed in the library file of the storage folder (after a clean install
     * or when another folder was chosen), then rewrites the file from the app's database.
     */
    private fun syncLibrary() {
        viewModelScope.launch {
            runCatching {
                offlineLibrary.restore()
                // First what was watched here, then what the server knows (also from other devices)
                offlinePlayback.sync()
                downloadManager.refreshMetadata()
                offlineLibrary.write()
            }
        }
    }

    val downloads: StateFlow<List<DownloadFiles>> = downloadDao
        .getAllDownloadsWithFiles()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _storageLocation = MutableStateFlow(storageManager.getStorageLocation())
    val storageLocation = _storageLocation.asStateFlow()

    private val _storageLocationAccessible = MutableStateFlow(storageManager.isStorageLocationAccessible())
    val storageLocationAccessible = _storageLocationAccessible.asStateFlow()

    fun openDownload(download: DownloadEntity) {
        when (download.item.mediaType) {
            MediaType.VIDEO -> {
                val playOptions = PlayOptions(
                    ids = listOf(download.itemId),
                    mediaSourceId = download.itemId.toString(),
                    startIndex = 0,
                    startPosition = null,
                    audioStreamIndex = null,
                    subtitleStreamIndex = null,
                    playFromDownloads = true,
                )
                activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions))
            }

            MediaType.AUDIO -> playMusic(download)

            MediaType.PHOTO,
            MediaType.BOOK,
            MediaType.UNKNOWN -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        downloadDao.getFiles(download.id).firstOrNull { it.type == DownloadFileType.ITEM }?.uri
                    }?.let {
                        activityEventHandler.emit(ActivityEvent.OpenUrl(it.toString(), true))
                    }
                }
            }
        }
    }

    /**
     * Plays the downloaded track inside the app. The queue is every downloaded track of the same album,
     * in disc and track order, so the album plays on from the selected track.
     */
    private fun playMusic(download: DownloadEntity) {
        viewModelScope.launch {
            val (items, startIndex) = withContext(Dispatchers.IO) {
                val albumId = download.item.albumId
                val tracks = downloadDao.getAllDownloadsWithFiles().first()
                    .filter { (other, _) ->
                        other.id == download.id || albumId != null &&
                            other.serverId == download.serverId &&
                            other.item.mediaType == MediaType.AUDIO &&
                            other.item.albumId == albumId
                    }
                    .filter { it.download.id == download.id || storageManager.verify(it) }
                    .sortedWith(
                        compareBy<DownloadFiles>(
                            { it.download.item.parentIndexNumber ?: 0 },
                            { it.download.item.indexNumber ?: Int.MAX_VALUE },
                            { it.download.item.name.orEmpty() },
                        ),
                    )
                val playable = tracks.mapNotNull { track -> DownloadedMusicPlayer.toMediaItem(track)?.let { track to it } }
                playable.map { it.second } to playable.indexOfFirst { it.first.download.id == download.id }
            }
            if (startIndex >= 0) musicPlayer.play(items, startIndex)
        }
    }

    /** Plays downloaded tracks in the given order (an album, an artist, a genre, search results). */
    fun playTracks(tracks: List<OfflineEntry>, startIndex: Int, shuffle: Boolean) {
        val start = tracks.getOrNull(startIndex)
        val ordered = if (shuffle) tracks.shuffled() else tracks
        val items = ordered.mapNotNull { DownloadedMusicPlayer.toMediaItem(it.files) }
        if (items.isEmpty()) return
        val index = if (shuffle || start == null) 0 else ordered.indexOf(start).coerceAtLeast(0)
        musicPlayer.play(items, index.coerceAtMost(items.lastIndex))
    }

    fun pauseDownloads() {
        viewModelScope.launch { downloadManager.pauseAll() }
    }

    fun resumeDownloads() {
        viewModelScope.launch { downloadManager.resumeAll() }
    }

    fun cancelAllDownloads() {
        viewModelScope.launch { downloadManager.cancelAll() }
    }

    fun togglePlayPause() = musicPlayer.togglePlayPause()

    fun playNext() = musicPlayer.next()

    fun playPrevious() = musicPlayer.previous()

    fun stopMusic() = musicPlayer.stop()

    override fun onCleared() {
        musicPlayer.release()
    }

    fun download(download: DownloadEntity) {
        viewModelScope.launch {
            downloadManager.resume(download)
        }
    }

    fun removeDownload(download: DownloadEntity, deleteFiles: Boolean) {
        viewModelScope.launch {
            downloadManager.delete(download.id, deleteFiles)
        }
    }

    fun changeStorageLocation(uri: android.net.Uri) {
        storageManager.changeStorageLocation(uri)
        _storageLocation.value = storageManager.getStorageLocation()
        _storageLocationAccessible.value = storageManager.isStorageLocationAccessible()
        syncLibrary()
    }
}
