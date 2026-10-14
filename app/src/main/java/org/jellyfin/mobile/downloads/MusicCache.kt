package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.jellyfin.mobile.app.AppPreferences
import timber.log.Timber
import java.io.File

/** When the cache of streamed music is emptied. */
enum class MusicCacheMode(val intValue: Int) {
    /** Nothing is kept. */
    OFF(0),

    /** Kept while the app runs, emptied when it starts again. */
    CLEAR_ON_START(1),

    /** Tracks not played for some days go (see [AppPreferences.musicCacheDays]). */
    KEEP_DAYS(2),

    /** Only the size limit: the least recently played tracks go first. */
    SIZE_LIMIT(3),
    ;

    companion object {
        fun of(value: Int) = entries.firstOrNull { it.intValue == value } ?: SIZE_LIMIT
    }
}

/**
 * Streamed music kept on the phone, as messengers keep media: a track played again (or replayed with
 * repeat) comes from the cache instead of the server, what is missing is fetched. Only music: videos are
 * too big for it to pay off. Downloaded tracks do not go through it. Never bigger than the size limit.
 */
class MusicCache(
    context: Context,
    private val databaseProvider: DatabaseProvider,
    private val appPreferences: AppPreferences,
) {
    private val directory = File(context.cacheDir, DIRECTORY)

    val mode: MusicCacheMode get() = MusicCacheMode.of(appPreferences.musicCacheMode)

    private var simpleCache: SimpleCache? = null

    /** Opened once per run of the app (the cache allows one instance per folder), cleaned up then. */
    @Synchronized
    private fun cache(): SimpleCache = simpleCache ?: SimpleCache(
        directory,
        LeastRecentlyUsedCacheEvictor(appPreferences.musicCacheLimitMb * BYTES_PER_MB),
        databaseProvider,
    ).also { cache ->
        simpleCache = cache
        cleanUp(cache)
    }

    private fun cleanUp(cache: SimpleCache) {
        try {
            when (mode) {
                MusicCacheMode.OFF, MusicCacheMode.CLEAR_ON_START -> cache.keys.toList().forEach(cache::removeResource)
                MusicCacheMode.KEEP_DAYS -> {
                    val oldest = System.currentTimeMillis() - appPreferences.musicCacheDays * MS_PER_DAY
                    for (key in cache.keys.toList()) {
                        val lastPlayed = cache.getCachedSpans(key).maxOfOrNull { it.lastTouchTimestamp } ?: 0L
                        if (lastPlayed < oldest) cache.removeResource(key)
                    }
                }
                MusicCacheMode.SIZE_LIMIT -> Unit
            }
        } catch (e: Exception) {
            Timber.w(e, "Cleaning the music cache failed")
        }
    }

    /** Space taken now. */
    fun usedBytes(): Long = try {
        cache().cacheSpace
    } catch (e: Exception) {
        0L
    }

    fun clear() {
        try {
            val cache = cache()
            cache.keys.toList().forEach(cache::removeResource)
        } catch (e: Exception) {
            Timber.w(e, "Clearing the music cache failed")
        }
    }

    /**
     * Data for the music player: streams from the server through the cache (unless it is off), local files
     * directly. The key is the track and its bitrate, not the address (each address has its own session id).
     */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
        if (mode == MusicCacheMode.OFF) {
            // Opened anyway, so what was kept before is removed
            runCatching { cache() }
            return upstream
        }
        val cached = CacheDataSource.Factory()
            .setCache(cache())
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { dataSpec -> cacheKey(dataSpec.uri) ?: dataSpec.key ?: dataSpec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { SchemeRoutingDataSource(cached.createDataSource(), upstream.createDataSource()) }
    }

    /** `/Audio/{id}/universal?...MaxStreamingBitrate=...` -> `audio-{id}-{bitrate}`. */
    private fun cacheKey(uri: Uri): String? {
        val segments = uri.pathSegments
        val audio = segments.indexOfFirst { it.equals("Audio", ignoreCase = true) }
        if (audio < 0 || audio + 1 >= segments.size) return null
        return "audio-${segments[audio + 1]}-${uri.getQueryParameter("MaxStreamingBitrate").orEmpty()}"
    }

    /** Over the network through the cache, files on the phone as they are. */
    private class SchemeRoutingDataSource(private val cached: DataSource, private val direct: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme.orEmpty()
            val source = if (scheme.startsWith("http", ignoreCase = true)) cached else direct
            current = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            requireNotNull(current) { "Not opened" }.read(buffer, offset, length)

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

        override fun close() {
            current?.close()
            current = null
        }
    }

    private companion object {
        const val DIRECTORY = "music_cache"
        const val BYTES_PER_MB = 1024L * 1024L
        const val MS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
