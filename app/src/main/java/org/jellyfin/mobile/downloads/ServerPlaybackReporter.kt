package org.jellyfin.mobile.downloads

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import timber.log.Timber
import java.util.UUID

/**
 * Tells the server what the app's music player streams from it (start, progress, pause, stop), as the web
 * player did: the server shows it among the sessions and counts the plays. Tracks played from downloads
 * are not reported (music is not marked as played from them).
 */
class ServerPlaybackReporter(private val player: Player, private val api: ApiClient) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private class Tracked(val itemId: UUID, val playSessionId: String)

    private var current: Tracked? = null
    private var lastPositionMs = 0L
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            if (current != null) {
                lastPositionMs = player.currentPosition
                if (++ticks % PROGRESS_EVERY_TICKS == 0 && player.isPlaying) progress()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        player.addListener(this)
        handler.postDelayed(tick, TICK_MS)
        start(player.currentMediaItem)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // The same track after the queue changed (turned around): it plays on, nothing to report
        val id = mediaItem?.mediaMetadata?.extras?.getString(StreamedMusic.EXTRA_ITEM_ID)
        if (id != null && id == current?.itemId?.toString() && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        stopped(lastPositionMs)
        lastPositionMs = 0L
        start(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        lastPositionMs = player.currentPosition
        progress()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            stopped(if (playbackState == Player.STATE_ENDED) player.duration.coerceAtLeast(lastPositionMs) else lastPositionMs)
        } else if (current == null) {
            start(player.currentMediaItem)
        }
    }

    fun release() {
        stopped(lastPositionMs)
        player.removeListener(this)
        handler.removeCallbacks(tick)
        scope.cancel()
    }

    private fun start(item: MediaItem?) {
        val extras = item?.mediaMetadata?.extras ?: return
        if (!extras.getBoolean(StreamedMusic.EXTRA_STREAMED)) return
        val itemId = extras.getString(StreamedMusic.EXTRA_ITEM_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val tracked = Tracked(itemId, UUID.randomUUID().toString().replace("-", ""))
        current = tracked
        ticks = 0
        val info = PlaybackStartInfo(
            canSeek = true,
            itemId = tracked.itemId,
            isPaused = !player.playWhenReady,
            isMuted = false,
            positionTicks = player.currentPosition * TICKS_PER_MS,
            playMethod = PlayMethod.DIRECT_STREAM,
            playSessionId = tracked.playSessionId,
            repeatMode = repeatMode(),
            playbackOrder = if (player.shuffleModeEnabled) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT,
        )
        send { api.playStateApi.reportPlaybackStart(info) }
    }

    private fun progress() {
        val tracked = current ?: return
        val info = PlaybackProgressInfo(
            canSeek = true,
            itemId = tracked.itemId,
            isPaused = !player.isPlaying,
            isMuted = false,
            positionTicks = lastPositionMs * TICKS_PER_MS,
            playMethod = PlayMethod.DIRECT_STREAM,
            playSessionId = tracked.playSessionId,
            repeatMode = repeatMode(),
            playbackOrder = if (player.shuffleModeEnabled) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT,
        )
        send { api.playStateApi.reportPlaybackProgress(info) }
    }

    private fun stopped(positionMs: Long) {
        val tracked = current ?: return
        current = null
        val info = PlaybackStopInfo(
            itemId = tracked.itemId,
            positionTicks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
            playSessionId = tracked.playSessionId,
            failed = false,
        )
        send { api.playStateApi.reportPlaybackStopped(info) }
    }

    private fun repeatMode() = when (player.repeatMode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.REPEAT_ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.REPEAT_ALL
        else -> RepeatMode.REPEAT_NONE
    }

    private fun send(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                // No connection: the play is not counted, nothing else breaks
                Timber.i(e, "Reporting music playback failed")
            }
        }
    }

    private companion object {
        const val TICKS_PER_MS = 10_000L
        const val TICK_MS = 1_000L
        const val PROGRESS_EVERY_TICKS = 10
    }
}
