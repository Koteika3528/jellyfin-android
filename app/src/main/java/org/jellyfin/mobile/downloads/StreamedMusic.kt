package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/**
 * Music from the web client for the app's player: a downloaded track plays from its file, any other one is
 * streamed from the server (the original file when the player can play it, otherwise MP3 at the bitrate
 * the network allows). Streamed tracks are reported to the server by the player service.
 */
class StreamedMusic(
    private val context: Context,
    private val api: ApiClient,
    private val downloadDao: DownloadDao,
    private val appPreferences: AppPreferences,
) {
    suspend fun toMediaItems(items: List<BaseItemDto>): List<MediaItem> = withContext(Dispatchers.IO) {
        val downloaded = downloadDao.getDownloadsByItemIds(items.map { it.id })
            .filter { it.status == DownloadStatus.DOWNLOADED }
            .associateBy { it.itemId }
        items.map { item ->
            val download = downloaded[item.id]
            val files = download?.let { downloadDao.getFiles(it.id) }.orEmpty()
            val localFile = files.firstOrNull { it.type == DownloadFileType.ITEM && it.status == DownloadStatus.DOWNLOADED }?.uri
            val localImage = files.firstOrNull { it.type == DownloadFileType.IMAGE_PRIMARY }?.uri
            build(item, localFile ?: streamUri(item.id), localImage ?: artworkUri(item), streamed = localFile == null)
        }
    }

    private fun streamUri(itemId: UUID): Uri {
        // Mobile data: no more than the setting allows, also for the original file
        val meteredCap = appPreferences.meteredMaxBitrate.takeIf { it > 0 && NetworkState.isMetered(context) }
        val maxBitrate = meteredCap ?: MAX_BITRATE
        return api.createUrl("/Audio/$itemId/universal").toUri().buildUpon()
            .appendQueryParameter("DeviceId", api.deviceInfo.id)
            .appendQueryParameter("MaxStreamingBitrate", maxBitrate.toString())
            .appendQueryParameter("Container", CONTAINERS)
            .appendQueryParameter("TranscodingContainer", "mp3")
            .appendQueryParameter("TranscodingProtocol", "http")
            .appendQueryParameter("AudioCodec", "mp3")
            .appendQueryParameter("AudioBitRate", minOf(maxBitrate, TRANSCODE_BITRATE).toString())
            .appendQueryParameter("EnableRedirection", "true")
            .appendQueryParameter("EnableRemoteMedia", "false")
            .appendQueryParameter("PlaySessionId", UUID.randomUUID().toString().replace("-", ""))
            .appendQueryParameter("ApiKey", api.accessToken)
            .build()
    }

    private fun artworkUri(item: BaseItemDto): Uri? {
        val (imageItem, tag) = when {
            item.albumId != null && item.albumPrimaryImageTag != null -> item.albumId!! to item.albumPrimaryImageTag
            else -> item.id to (item.imageTags?.get(ImageType.PRIMARY) ?: return null)
        }
        return api.imageApi.getItemImageUrl(itemId = imageItem, imageType = ImageType.PRIMARY, tag = tag, maxWidth = ARTWORK_SIZE).toUri()
    }

    companion object {
        /** Extras of a track's metadata: its item, and whether it is streamed (then reported to the server). */
        const val EXTRA_ITEM_ID = "jellyfin_item_id"
        const val EXTRA_STREAMED = "jellyfin_streamed"

        private const val MAX_BITRATE = 140_000_000
        private const val TRANSCODE_BITRATE = 320_000
        private const val ARTWORK_SIZE = 600

        /** What the player plays as it is; anything else the server converts to MP3. */
        private const val CONTAINERS = "opus,webm|opus,mp3,aac,m4a|aac,m4a|alac,m4b|aac,flac,webma,webm|webma,wav,ogg,mka"

        fun build(item: BaseItemDto, uri: Uri, artwork: Uri?, streamed: Boolean): MediaItem {
            val extras = Bundle().apply {
                putString(EXTRA_ITEM_ID, item.id.toString())
                putBoolean(EXTRA_STREAMED, streamed)
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(item.name)
                .setArtist(item.artists?.joinToString().takeUnless { it.isNullOrBlank() } ?: item.albumArtist)
                .setAlbumTitle(item.album)
                .setAlbumArtist(item.albumArtist)
                .setTrackNumber(item.indexNumber)
                .setDiscNumber(item.parentIndexNumber)
                .setArtworkUri(artwork)
                .setDurationMs(item.runTimeTicks?.let { it / TICKS_PER_MS })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build()

            return MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(metadata)
                .build()
        }

        private const val TICKS_PER_MS = 10_000L
    }
}
