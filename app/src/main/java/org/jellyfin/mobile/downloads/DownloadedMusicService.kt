package org.jellyfin.mobile.downloads

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.jellyfin.mobile.MainActivity
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject

/**
 * Plays downloaded music from local files, without the server and without the web UI.
 * The queue is set by [DownloadedMusicPlayer]; the notification and lock screen controls come from media3.
 */
class DownloadedMusicService : MediaSessionService() {
    private val offlinePlayback: OfflinePlayback by inject()
    private var mediaSession: MediaSession? = null
    private var currentItemId: String? = null

    /** A track played to its end counts as played, the server learns it when it is reachable. */
    private val playedListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) markPlayed(currentItemId)
            currentItemId = mediaItem?.mediaId
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) markPlayed(currentItemId)
        }
    }

    private fun markPlayed(mediaId: String?) {
        val itemId = mediaId?.toUUIDOrNull() ?: return
        offlinePlayback.save(itemId, positionTicks = 0, runTimeTicks = null, finished = true)
        offlinePlayback.syncInBackground()
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Extension renderers: the bundled FFmpeg decoder covers formats without a system codec (ALAC, DTS, ...)
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playedListener)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private class SessionCallback : MediaSession.Callback {
        /**
         * The local configuration (uri) of a [MediaItem] is not passed from controllers to the session,
         * so the file uri travels in the request metadata and is restored here.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNullTo(mutableListOf()) { item ->
                when {
                    item.localConfiguration != null -> item
                    item.requestMetadata.mediaUri != null -> item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                    else -> null
                }
            }
            return Futures.immediateFuture(resolved)
        }
    }

    companion object {
        private const val SESSION_ID = "downloaded_music"
    }
}
