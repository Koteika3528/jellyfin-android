package org.jellyfin.mobile.downloads

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live progress of running downloads (bytes, total and speed of the file being downloaded), for the
 * downloads screen and the notification; it is not kept in the database.
 */
object DownloadProgressTracker {
    data class Live(val downloaded: Long, val total: Long, val bytesPerSecond: Long)

    private val _progress = MutableStateFlow<Map<Long, Live>>(emptyMap())
    val progress: StateFlow<Map<Long, Live>> = _progress.asStateFlow()

    fun update(downloadId: Long, live: Live) = _progress.update { it + (downloadId to live) }

    fun remove(downloadId: Long) = _progress.update { it - downloadId }

    /** Measures the speed over the last seconds, smoothed so the number does not jump with every buffer. */
    class SpeedMeter {
        private var lastBytes = -1L
        private var lastTime = 0L
        var bytesPerSecond = 0L
            private set

        /** Returns true when a new measurement was taken (about once per [INTERVAL_MS]). */
        fun sample(downloaded: Long): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (lastBytes < 0 || downloaded < lastBytes) {
                lastBytes = downloaded
                lastTime = now
                return true
            }
            val elapsed = now - lastTime
            if (elapsed < INTERVAL_MS) return false
            val current = (downloaded - lastBytes) * MS_PER_SECOND / elapsed
            bytesPerSecond = if (bytesPerSecond == 0L) current else (bytesPerSecond * 2 + current) / 3
            lastBytes = downloaded
            lastTime = now
            return true
        }

        private companion object {
            const val INTERVAL_MS = 1000L
            const val MS_PER_SECOND = 1000L
        }
    }
}
