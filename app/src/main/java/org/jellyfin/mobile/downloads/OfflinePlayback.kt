package org.jellyfin.mobile.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.PlayStateDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.PlayStateEntity
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import timber.log.Timber
import java.util.UUID

/**
 * What was watched from the downloads without the server: the position to resume from and whether a
 * video was finished; and favorite marks (music is never marked as played, only as favorite). The server
 * learns it with [sync] once it is reachable again.
 */
class OfflinePlayback(
    private val playStateDao: PlayStateDao,
    private val downloadDao: DownloadDao,
    private val apiClientController: ApiClientController,
    private val offlineStore: OfflineStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    data class Progress(val positionTicks: Long, val played: Boolean, val runTimeTicks: Long?) {
        val percent: Float? get() = runTimeTicks?.takeIf { it > 0 && positionTicks > 0 }?.let { (positionTicks.toFloat() / it).coerceIn(0f, 1f) }
    }

    /** Local play states by item, for [progressOf]. */
    fun observeStates(): Flow<Map<UUID, PlayStateEntity>> = playStateDao.observeAll().map { list -> list.associateBy { it.itemId } }

    /** Progress of a downloaded item: the local state while the server does not know it yet, otherwise the server's. */
    fun progressOf(download: DownloadEntity, local: PlayStateEntity?): Progress? {
        val userData = download.item.userData
        val runTime = download.item.runTimeTicks
        return when {
            local != null && (local.pendingSync || userData == null) -> Progress(local.positionTicks, local.played, runTime)
            userData != null -> Progress(userData.playbackPositionTicks, userData.played, runTime)
            else -> null
        }
    }

    /** Where to resume a downloaded item, or null to start from the beginning. */
    suspend fun resumePositionTicks(itemId: UUID): Long? = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownloadByItemId(itemId) ?: return@withContext null
        val progress = progressOf(download, playStateDao.get(itemId)) ?: return@withContext null
        progress.positionTicks.takeIf { !progress.played && it > 0 }
    }

    /**
     * Remembers where playback of a downloaded item stopped. Near the end (or [finished]) counts as played,
     * like the server does.
     */
    fun save(itemId: UUID, positionTicks: Long, runTimeTicks: Long?, finished: Boolean) {
        scope.launch {
            val nearEnd = runTimeTicks != null && runTimeTicks > 0 && positionTicks >= runTimeTicks * PLAYED_RATIO
            val played = finished || nearEnd
            playStateDao.upsert(
                PlayStateEntity(
                    itemId = itemId,
                    positionTicks = if (played) 0 else positionTicks.coerceAtLeast(0),
                    played = played || (playStateDao.get(itemId)?.played == true && positionTicks <= 0),
                    updatedAt = System.currentTimeMillis(),
                    pendingSync = true,
                ),
            )
        }
    }

    /** A favorite mark of a downloaded item, also without the server. */
    fun isFavorite(download: DownloadEntity, pending: Map<String, Boolean>): Boolean =
        pending[download.itemId.toString()] ?: (download.item.userData?.isFavorite == true)

    fun setFavorite(itemId: UUID, favorite: Boolean) {
        offlineStore.setPendingFavorite(itemId, favorite)
        scope.launch {
            val download = downloadDao.getDownloadByItemId(itemId) ?: return@launch
            val userData = download.item.userData ?: return@launch
            downloadDao.update(download.copy(item = download.item.copy(userData = userData.copy(isFavorite = favorite))))
        }
        syncInBackground()
    }

    /** Sends what the server does not know yet. Quietly stops at the first connection failure. */
    fun syncInBackground() {
        scope.launch { sync() }
    }

    suspend fun sync(): Unit = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            for (state in playStateDao.getPending()) {
                val download = downloadDao.getDownloadByItemId(state.itemId)
                val api = download?.let { runCatching { apiClientController.getApiClient(it.serverId, it.userId) }.getOrNull() }
                if (download == null || api == null) {
                    // The download or its account is gone: nothing to send it with
                    playStateDao.markSynced(state.itemId, state.updatedAt)
                    continue
                }
                // Music is not marked as played (also states saved by earlier versions)
                if (download.item.mediaType == MediaType.AUDIO) {
                    playStateDao.markSynced(state.itemId, state.updatedAt)
                    continue
                }
                try {
                    if (state.played) {
                        api.playStateApi.markPlayedItem(itemId = state.itemId)
                    } else {
                        api.playStateApi.reportPlaybackStopped(
                            PlaybackStopInfo(itemId = state.itemId, positionTicks = state.positionTicks, failed = false),
                        )
                    }
                    playStateDao.markSynced(state.itemId, state.updatedAt)
                    rememberOnDownload(download, state)
                } catch (e: InvalidStatusException) {
                    // The server refuses (item removed, no access): keep it local only
                    Timber.w(e, "Server refused play state of ${state.itemId}")
                    playStateDao.markSynced(state.itemId, state.updatedAt)
                } catch (e: Exception) {
                    Timber.i(e, "Server unreachable, play states stay pending")
                    return@withLock
                }
            }

            for ((id, favorite) in offlineStore.pendingFavorites.value) {
                val itemId = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                val download = downloadDao.getDownloadByItemId(itemId)
                val api = download?.let { runCatching { apiClientController.getApiClient(it.serverId, it.userId) }.getOrNull() }
                if (api == null) {
                    offlineStore.clearPendingFavorite(itemId, favorite)
                    continue
                }
                try {
                    if (favorite) api.userLibraryApi.markFavoriteItem(itemId = itemId) else api.userLibraryApi.unmarkFavoriteItem(itemId = itemId)
                    offlineStore.clearPendingFavorite(itemId, favorite)
                } catch (e: InvalidStatusException) {
                    Timber.w(e, "Server refused favorite of $itemId")
                    offlineStore.clearPendingFavorite(itemId, favorite)
                } catch (e: Exception) {
                    Timber.i(e, "Server unreachable, favorites stay pending")
                    return@withLock
                }
            }
        }
    }

    /** The download keeps the server's view of the item, now including what was sent. */
    private suspend fun rememberOnDownload(download: DownloadEntity, state: PlayStateEntity) {
        val userData = download.item.userData ?: return
        val updated = userData.copy(
            played = state.played || userData.played,
            playbackPositionTicks = if (state.played) 0 else state.positionTicks,
        )
        downloadDao.update(download.copy(item = download.item.copy(userData = updated)))
    }

    companion object {
        /** The server counts an item as played from 90% on. */
        private const val PLAYED_RATIO = 0.9
    }
}
