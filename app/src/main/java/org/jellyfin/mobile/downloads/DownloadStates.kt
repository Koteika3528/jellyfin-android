package org.jellyfin.mobile.downloads

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.mobile.data.entity.DownloadEntity
import java.util.UUID

/**
 * What is downloaded, for the badges of the web client (`NativeShell.getDownloadStates()`):
 * item ids, and the ids of series, seasons and albums with downloaded items, mapped to
 * "downloaded" or "downloading" (anything still downloading wins for a series or an album).
 */
object DownloadStates {
    private const val DOWNLOADED = "downloaded"
    private const val DOWNLOADING = "downloading"

    fun toJson(downloads: List<DownloadEntity>): String {
        val states = mutableMapOf<String, String>()
        for (download in downloads) {
            val state = when (download.status) {
                DownloadStatus.DOWNLOADED -> DOWNLOADED
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> DOWNLOADING
                else -> continue
            }
            states[key(download.itemId)] = state
            val item = download.item
            for (parent in listOfNotNull(item.seriesId, item.seasonId, item.albumId)) {
                val key = key(parent)
                if (states[key] != DOWNLOADING) states[key] = state
            }
        }
        return buildJsonObject { states.forEach { (id, state) -> put(id, state) } }.toString()
    }

    /** The web client uses ids without dashes. */
    private fun key(id: UUID) = id.toString().replace("-", "")
}
