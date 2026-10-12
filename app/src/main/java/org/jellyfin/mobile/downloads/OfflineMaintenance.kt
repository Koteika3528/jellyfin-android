package org.jellyfin.mobile.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import java.util.UUID

/**
 * Keeps the downloads tidy once the server is reachable, as the settings say (everything is off by default):
 * downloads new episodes of followed shows (all new ones, or the next N unwatched), and deletes
 * watched episodes and movies. "Ask first" offers it in a dialog instead, once per set of items.
 */
class OfflineMaintenance(
    private val appPreferences: AppPreferences,
    private val downloadDao: DownloadDao,
    private val userDao: UserDao,
    private val downloadManager: DownloadManager,
    private val offlinePlayback: OfflinePlayback,
    private val offlineStore: OfflineStore,
    private val apiClientController: ApiClientController,
    private val activityEventHandler: ActivityEventHandler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** An episode to download for a followed show. */
    data class NewEpisode(val seriesName: String?, val item: BaseItemDto)

    fun runInBackground() {
        scope.launch { runCatching { run() }.onFailure { Timber.w(it, "Downloads maintenance failed") } }
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val newMode = appPreferences.newEpisodesMode
            val deleteMode = appPreferences.deleteWatchedMode
            if (newMode == AutoMode.OFF && deleteMode == AutoMode.OFF) return@withLock

            val serverId = appPreferences.currentServerId ?: return@withLock
            val userId = appPreferences.currentUserId ?: return@withLock

            offlinePlayback.sync()
            val newEpisodes = if (newMode != AutoMode.OFF) findNewEpisodes(serverId, userId) else emptyList()
            val watched = if (deleteMode != AutoMode.OFF) findWatched() else emptyList()

            if (newMode == AutoMode.AUTO && newEpisodes.isNotEmpty()) download(serverId, userId, newEpisodes)
            if (deleteMode == AutoMode.AUTO) watched.forEach { downloadManager.delete(it.id, deleteFiles = true) }

            val offerNew = newEpisodes.takeIf { newMode == AutoMode.ASK }.orEmpty()
            val offerWatched = watched.takeIf { deleteMode == AutoMode.ASK }.orEmpty()
            if (offerNew.isEmpty() && offerWatched.isEmpty()) return@withLock

            // The same offer is not repeated: only when something new joins it
            val key = (offerNew.map { it.item.id } + offerWatched.map { it.itemId }).map(UUID::toString).sorted().joinToString(",")
            if (key == offlineStore.lastOffer) return@withLock
            offlineStore.lastOffer = key

            val sizes = offerWatched.associate { download -> download.id to downloadDao.getFiles(download.id).sumOf { it.size } }
            activityEventHandler.emit(
                ActivityEvent.OfferDownloadsMaintenance(
                    newEpisodes = offerNew.map { it.item.id to label(it) },
                    watchedIds = offerWatched.map { it.id },
                    watchedBytes = sizes.values.sum(),
                ),
            )
        }
    }

    /** Downloads the offered episodes (the "Download" button of the dialog). */
    fun downloadInBackground(itemIds: List<UUID>) {
        scope.launch {
            val serverId = appPreferences.currentServerId ?: return@launch
            val userId = appPreferences.currentUserId ?: return@launch
            val serverUser = userDao.getServerUser(serverId, userId) ?: return@launch
            runCatching { downloadManager.enqueueItems(serverUser.server, serverUser.user, itemIds) }
                .onFailure { Timber.w(it, "Failed to download new episodes") }
        }
    }

    fun deleteInBackground(downloadIds: List<Long>) {
        scope.launch { downloadIds.forEach { downloadManager.delete(it, deleteFiles = true) } }
    }

    /** Watched episodes and movies (music is never "watched"). */
    suspend fun findWatched(): List<DownloadEntity> {
        val states = offlinePlayback.currentStates()
        return downloadDao.getCompletedDownloads()
            .map { it.download }
            .filter { it.item.mediaType != MediaType.AUDIO }
            .filter { offlinePlayback.progressOf(it, states[it.itemId])?.played == true }
    }

    private suspend fun findNewEpisodes(serverId: Long, userId: Long): List<NewEpisode> {
        val follows = offlineStore.follows.value.filter { it.serverId == serverId && it.userId == userId }
        if (follows.isEmpty()) return emptyList()
        val api = apiClientController.getApiClient(serverId, userId)
        val known = downloadDao.getAllItemIds().toSet()
        val keep = appPreferences.keepEpisodes
        val states = offlinePlayback.currentStates()

        return follows.flatMap { follow ->
            val seriesId = runCatching { UUID.fromString(follow.seriesId) }.getOrNull() ?: return@flatMap emptyList()
            val episodes = runCatching {
                api.tvShowsApi.getEpisodes(seriesId = seriesId, isMissing = false, enableUserData = true).content.items
            }.getOrElse { err ->
                Timber.i(err, "No episodes for ${follow.name}")
                return@flatMap emptyList()
            }

            // Watched here but not sent yet counts too
            val played = episodes.map { episode -> states[episode.id]?.takeIf { it.pendingSync }?.played ?: (episode.userData?.played == true) }
            val lastPlayed = played.indexOfLast { it }
            val ahead = episodes.indices.drop(lastPlayed + 1).filterNot { played[it] }.map { episodes[it] }
            val wanted = if (keep > 0) ahead.take(keep) else ahead
            wanted.filter { it.id !in known }.map { NewEpisode(follow.name ?: it.seriesName, it) }
        }
    }

    private suspend fun download(serverId: Long, userId: Long, episodes: List<NewEpisode>) {
        val serverUser = userDao.getServerUser(serverId, userId) ?: return
        downloadManager.enqueueItems(serverUser.server, serverUser.user, episodes.map { it.item.id })
    }

    private fun label(episode: NewEpisode): String {
        val item = episode.item
        val number = listOfNotNull(item.parentIndexNumber?.let { "S$it" }, item.indexNumber?.let { "E$it" }).joinToString(":")
        return listOfNotNull(episode.seriesName, number.ifEmpty { null }, item.name).joinToString(" · ")
    }
}
