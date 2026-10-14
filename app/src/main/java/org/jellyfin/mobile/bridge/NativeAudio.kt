package org.jellyfin.mobile.bridge

import android.webkit.JavascriptInterface
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer
import org.jellyfin.mobile.downloads.StreamedMusic
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber

/**
 * Fork: the web client's music player (NativeAudioPlugin.js) hands music to the app's player: the queue,
 * play / pause / seek, next and previous, repeat and shuffle. Calls come on the web view's thread and go
 * on to the player on the main one.
 */
@Suppress("unused")
class NativeAudio(
    private val appPreferences: AppPreferences,
    private val musicPlayer: DownloadedMusicPlayer,
    private val streamedMusic: StreamedMusic,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private class PlayRequest(
        val items: List<JsonObject> = emptyList(),
        val startIndex: Int = 0,
        val startPositionTicks: Long = 0,
        val next: Boolean = false,
    )

    @JavascriptInterface
    fun isEnabled(): Boolean = appPreferences.nativeMusicPlayer

    @JavascriptInterface
    fun play(raw: String) {
        val request = parse(raw) ?: return
        scope.launch {
            val items = streamedMusic.toMediaItems(tracks(request))
            musicPlayer.play(items, request.startIndex, request.startPositionTicks / TICKS_PER_MS)
        }
    }

    @JavascriptInterface
    fun queue(raw: String) {
        val request = parse(raw) ?: return
        scope.launch { musicPlayer.add(streamedMusic.toMediaItems(tracks(request)), request.next) }
    }

    @JavascriptInterface
    fun pause() = onMain { musicPlayer.pause() }

    @JavascriptInterface
    fun resume() = onMain { musicPlayer.resume() }

    @JavascriptInterface
    fun stop() = onMain { musicPlayer.stop() }

    @JavascriptInterface
    fun next() = onMain { musicPlayer.next() }

    @JavascriptInterface
    fun previous() = onMain { musicPlayer.previous() }

    @JavascriptInterface
    fun seekMs(positionMs: Long) = onMain { musicPlayer.seekTo(positionMs) }

    @JavascriptInterface
    fun playIndex(index: Int) = onMain { musicPlayer.playIndex(index) }

    /** RepeatNone, RepeatAll, RepeatOne - as the web client names them. */
    @JavascriptInterface
    fun setRepeatMode(mode: String) = onMain {
        musicPlayer.setRepeatMode(
            when (mode) {
                "RepeatAll" -> Player.REPEAT_MODE_ALL
                "RepeatOne" -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            },
        )
    }

    @JavascriptInterface
    fun setShuffle(enabled: Boolean) = onMain { musicPlayer.setShuffle(enabled) }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun parse(raw: String): PlayRequest? = try {
        json.decodeFromString<PlayRequest>(raw)
    } catch (e: Exception) {
        Timber.e(e, "Invalid music play request")
        null
    }

    /** Items the web client knows (full server items), the ones that do not read are left out. */
    private fun tracks(request: PlayRequest): List<BaseItemDto> = request.items.mapNotNull { item ->
        runCatching { json.decodeFromJsonElement(BaseItemDto.serializer(), item) }.getOrNull()
    }

    private companion object {
        const val TICKS_PER_MS = 10_000L
    }
}
