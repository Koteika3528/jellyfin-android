package org.jellyfin.mobile.events

import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerWebPreferences
import org.json.JSONArray
import java.util.UUID

sealed class ActivityEvent {
    class ChangeFullscreen(val isFullscreen: Boolean) : ActivityEvent()
    class LaunchNativePlayer(val playOptions: PlayOptions, val preferences: PlayerWebPreferences? = null) : ActivityEvent()
    class OpenUrl(val uri: String, val grantReadPermission: Boolean = false) : ActivityEvent()
    class DownloadItems(val itemIds: Collection<UUID>) : ActivityEvent()
    class CastMessage(val action: String, val args: JSONArray) : ActivityEvent()
    data object RequestBluetoothPermission : ActivityEvent()
    data object OpenSettings : ActivityEvent()
    data object SelectServer : ActivityEvent()
    data object ExitApp : ActivityEvent()
    data object OpenDownloads : ActivityEvent()

    /** The server did not answer: show the downloads, if there are any, instead of only the server selection. */
    data object ServerUnreachable : ActivityEvent()

    /** "Ask first": new episodes of followed shows to download, watched downloads to delete. */
    class OfferDownloadsMaintenance(
        val newEpisodes: List<Pair<UUID, String>>,
        val watchedIds: List<Long>,
        val watchedBytes: Long,
    ) : ActivityEvent()

    /** Mobile data is not to be used: only the downloads open. */
    data object OpenDownloadsWifiOnly : ActivityEvent()

    /** Opens the downloads on the storage page. */
    data object OpenDownloadsStorage : ActivityEvent()
}
