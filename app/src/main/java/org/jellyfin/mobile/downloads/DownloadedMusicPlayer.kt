package org.jellyfin.mobile.downloads

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Connection of the downloads screen to [DownloadedMusicService].
 * Playback keeps going after [release], the service runs in the foreground while playing.
 */
class DownloadedMusicPlayer(private val context: Context) {
    data class NowPlaying(
        /** Item id of the track. */
        val mediaId: String?,
        val title: String?,
        val subtitle: String?,
        val artworkUri: Uri?,
        val isPlaying: Boolean,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        /** [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL] or [Player.REPEAT_MODE_ONE]. */
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val shuffle: Boolean = false,
        /** Position of the track in the queue, and the queue's length (changes when the queue changes). */
        val index: Int = 0,
        val count: Int = 0,
        /** The queue plays from the end to the start. */
        val reversed: Boolean = false,
    )

    data class QueueEntry(val title: String?, val subtitle: String?, val artworkUri: Uri?)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            this@DownloadedMusicPlayer.controller = null
            controllerFuture = null
            _nowPlaying.value = null
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { current ->
            block(current)
            return
        }

        val future = controllerFuture ?: MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, DownloadedMusicService::class.java)),
        ).setListener(controllerListener).buildAsync().also { controllerFuture = it }

        future.addListener(
            {
                if (controllerFuture !== future) return@addListener
                val connected = runCatching { future.get() }.getOrNull()
                if (connected == null) {
                    controllerFuture = null
                    return@addListener
                }
                if (controller == null) {
                    controller = connected
                    connected.addListener(playerListener)
                    update()
                }
                block(connected)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Shows what is already playing, e.g. after returning to the screen. */
    fun connect() = withController { }

    /** Whether the queue was turned around ([setReversed]); a new queue starts in its own order. */
    private var reversed = false

    fun play(items: List<MediaItem>, startIndex: Int, startPositionMs: Long = 0L) {
        if (items.isEmpty()) return
        reversed = false
        withController { player ->
            player.setMediaItems(items, startIndex.coerceIn(items.indices), startPositionMs.coerceAtLeast(0L))
            player.prepare()
            player.play()
        }
    }

    /** Adds to the queue: right after the current track ([next]) or at its end. */
    fun add(items: List<MediaItem>, next: Boolean) {
        if (items.isEmpty()) return
        withController { player ->
            if (player.mediaItemCount == 0) {
                play(items, 0)
                return@withController
            }
            if (next) player.addMediaItems(player.currentMediaItemIndex + 1, items) else player.addMediaItems(items)
        }
    }

    fun playIndex(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        player.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        controller?.run {
            if (playbackState == Player.STATE_ENDED) seekToDefaultPosition(0)
            prepare()
            play()
        }
    }

    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    /** Shuffle and the reverse order exclude each other: shuffling turns the queue back. */
    fun setShuffle(enabled: Boolean) {
        val player = controller ?: return
        if (enabled && reversed) setReversed(false)
        player.shuffleModeEnabled = enabled
    }

    /**
     * Plays the queue from the end to the start: the queue is turned around, the current track goes on
     * where it was. Repeat works with it; shuffle is turned off.
     */
    fun setReversed(enabled: Boolean) {
        val player = controller ?: return
        if (enabled == reversed || player.mediaItemCount == 0) return
        if (enabled && player.shuffleModeEnabled) player.shuffleModeEnabled = false
        val count = player.mediaItemCount
        val items = (0 until count).map { player.getMediaItemAt(it) }.reversed()
        val index = count - 1 - player.currentMediaItemIndex
        val position = player.currentPosition
        val playing = player.playWhenReady
        reversed = enabled
        player.setMediaItems(items, index, position)
        player.prepare()
        player.playWhenReady = playing
        update()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** Where the current track is, and how long it is (0 when unknown); read on demand, not in [nowPlaying]. */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    /** The queue in its order (not the shuffled one), for the now playing screen. */
    fun queue(): List<QueueEntry> {
        val player = controller ?: return emptyList()
        return (0 until player.mediaItemCount).map { index ->
            val metadata = player.getMediaItemAt(index).mediaMetadata
            QueueEntry(
                title = metadata.title?.toString(),
                subtitle = listOfNotNull(metadata.artist, metadata.albumTitle).map(CharSequence::toString).filter(String::isNotBlank).distinct().joinToString(" — ").ifEmpty { null },
                artworkUri = metadata.artworkUri,
            )
        }
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
            player.prepare()
            player.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        _nowPlaying.value = null
    }

    private fun update() {
        val player = controller
        if (player == null || player.mediaItemCount == 0) {
            _nowPlaying.value = null
            return
        }
        val metadata = player.mediaMetadata
        _nowPlaying.value = NowPlaying(
            mediaId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString(),
            subtitle = listOfNotNull(metadata.artist, metadata.albumTitle)
                .map(CharSequence::toString)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" — ")
                .ifEmpty { null },
            artworkUri = metadata.artworkUri,
            isPlaying = player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING,
            hasPrevious = player.mediaItemCount > 0,
            hasNext = player.hasNextMediaItem(),
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
            index = player.currentMediaItemIndex,
            count = player.mediaItemCount,
            reversed = reversed,
        )
    }

    companion object {
        /** Builds a playable item from a completed download, null if the audio file is missing. */
        fun toMediaItem(download: DownloadFiles): MediaItem? {
            val item: BaseItemDto = download.download.item
            val file = download.files.find { it.type == DownloadFileType.ITEM } ?: return null
            val artwork = download.files.find { it.type == DownloadFileType.IMAGE_PRIMARY }?.uri

            val metadata = MediaMetadata.Builder()
                .setTitle(item.name)
                .setArtist(item.artists?.joinToString().takeUnless { it.isNullOrBlank() } ?: item.albumArtist)
                .setAlbumTitle(item.album)
                .setAlbumArtist(item.albumArtist)
                .setTrackNumber(item.indexNumber)
                .setDiscNumber(item.parentIndexNumber)
                .setArtworkUri(artwork)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()

            return MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(file.uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(file.uri).build())
                .setMediaMetadata(metadata)
                .build()
        }
    }
}
