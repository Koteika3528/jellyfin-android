package org.jellyfin.mobile.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID

/**
 * The download screen of videos: which episodes (by season), and how: the original file, or a file
 * the server makes with the chosen voice-overs and subtitles only, copied or encoded again.
 */
class DownloadOptionsViewModel : ViewModel(), KoinComponent {
    private val api: ApiClient by inject()
    private val storageManager: StorageManager by inject()

    /** Episodes of one season, or the movies (no [title]). */
    data class Season(val key: String, val title: String?, val items: List<BaseItemDto>)

    /**
     * A voice-over or subtitle track as the same for all episodes: same language and name.
     * @param count in how many of the chosen episodes it is.
     */
    data class Track(val key: String, val label: String, val count: Int)

    enum class Quality(val maxHeight: Int, val bitrate: Int) {
        P1080_HIGH(1080, 8_000_000),
        P1080(1080, 4_000_000),
        P720(720, 3_000_000),
        P720_LOW(720, 1_500_000),
        P480(480, 800_000),
    }

    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var title by mutableStateOf<String?>(null)
        private set
    var seasons by mutableStateOf<List<Season>>(emptyList())
        private set
    var availableBytes by mutableStateOf<Long?>(null)
        private set

    var selected by mutableStateOf<Set<UUID>>(emptySet())
        private set
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    var mode by mutableStateOf(DownloadMode.ORIGINAL)

    /** Tracks left out, by [Track.key]: tracks are kept unless unchecked, also ones of episodes chosen later. */
    var excludedAudio by mutableStateOf<Set<String>>(emptySet())
        private set
    var excludedSubtitles by mutableStateOf<Set<String>>(emptySet())
        private set

    var videoCodec by mutableStateOf(DownloadOptions.H264)
    var quality by mutableStateOf(Quality.P720)
    var audioCodec by mutableStateOf(DownloadOptions.AAC)

    private var items: List<BaseItemDto> = emptyList()

    val selectedItems: List<BaseItemDto> get() = items.filter { it.id in selected }

    /** More than one item: the list to choose from is shown. */
    val canChoose: Boolean get() = items.size > 1

    /** Only real files can be remuxed or transcoded by the server. */
    val canConvert: Boolean get() = items.any { it.mediaType == MediaType.VIDEO && it.mediaSources?.firstOrNull() != null }

    fun load(itemIds: List<UUID>) {
        if (items.isNotEmpty() || itemIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) {
                    itemIds.chunked(CHUNK).flatMap { chunk ->
                        api.itemsApi.getItems(ids = chunk, fields = setOf(ItemFields.MEDIA_SOURCES)).content.items
                    }
                }
                items = fetched.sortedWith(compareBy({ it.seriesName.orEmpty() }, { it.parentIndexNumber ?: Int.MAX_VALUE }, { it.indexNumber ?: Int.MAX_VALUE }))
                seasons = items.groupBy { it.seasonId?.toString() ?: it.parentIndexNumber?.toString() ?: "" }.map { (key, list) ->
                    val first = list.first()
                    Season(key, if (first.seriesName != null) first.seasonName ?: first.parentIndexNumber?.toString() else null, list)
                }
                title = items.firstNotNullOfOrNull { it.seriesName } ?: items.singleOrNull()?.name
                selected = items.map { it.id }.toSet()
                // One season opens by itself, several stay closed
                if (seasons.size == 1) expanded = seasons.map { it.key }.toSet()
                availableBytes = withContext(Dispatchers.IO) { storageManager.availableBytes() }
            } catch (e: Exception) {
                Timber.e(e, "Loading the items to download failed")
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun toggle(item: BaseItemDto) {
        selected = if (item.id in selected) selected - item.id else selected + item.id
    }

    /** All of the season, or none when all were chosen. */
    fun toggle(season: Season) {
        val ids = season.items.map { it.id }.toSet()
        selected = if (selected.containsAll(ids)) selected - ids else selected + ids
    }

    fun toggleExpanded(season: Season) {
        expanded = if (season.key in expanded) expanded - season.key else expanded + season.key
    }

    fun selectUnwatched() {
        selected = items.filter { it.userData?.played != true }.map { it.id }.toSet()
    }

    fun selectAll() {
        selected = items.map { it.id }.toSet()
    }

    fun toggleAudio(track: Track) {
        excludedAudio = if (track.key in excludedAudio) excludedAudio - track.key else excludedAudio + track.key
    }

    fun toggleSubtitle(track: Track) {
        excludedSubtitles = if (track.key in excludedSubtitles) excludedSubtitles - track.key else excludedSubtitles + track.key
    }

    /** Voice-overs of the chosen episodes. */
    val audioTracks: List<Track> get() = tracksOf(MediaStreamType.AUDIO)

    val subtitleTracks: List<Track> get() = tracksOf(MediaStreamType.SUBTITLE)

    private fun tracksOf(type: MediaStreamType): List<Track> {
        val found = LinkedHashMap<String, Pair<String, Int>>()
        for (item in selectedItems) {
            val streams = item.source()?.mediaStreams.orEmpty()
            for ((stream, key) in keyed(streams, type).distinctBy { it.second }) {
                val (name, count) = found[key] ?: (label(stream) to 0)
                found[key] = name to count + 1
            }
        }
        return found.map { (key, value) -> Track(key, value.first, value.second) }
    }

    /** How [item] is downloaded with what is chosen now (in [mode]). */
    fun optionsFor(item: BaseItemDto, mode: DownloadMode = this.mode): DownloadOptions {
        val source = item.source()
        if (mode == DownloadMode.ORIGINAL || source == null || item.mediaType != MediaType.VIDEO) {
            return DownloadOptions()
        }
        val streams = source.mediaStreams.orEmpty()
        val transcode = mode == DownloadMode.TRANSCODE
        val options = DownloadOptions(
            mode = mode,
            mediaSourceId = source.id,
            audioStreams = keyed(streams, MediaStreamType.AUDIO).filter { it.second !in excludedAudio }.map { it.first.index },
            subtitleStreams = keyed(streams, MediaStreamType.SUBTITLE).filter { it.second !in excludedSubtitles }.map { it.first.index },
            videoCodec = if (transcode) videoCodec else DownloadOptions.COPY,
            audioCodec = if (transcode) audioCodec else DownloadOptions.COPY,
            videoBitrate = if (transcode) quality.bitrate else null,
            maxHeight = if (transcode) quality.maxHeight else null,
            audioBitrate = if (transcode && audioCodec != DownloadOptions.COPY) DownloadOptions.defaultAudioBitrate(audioCodec) else null,
        )
        return options.copy(estimatedSize = estimate(source, options))
    }

    /** Size of the chosen items as [mode] would make them, null when the server does not know it. */
    fun estimatedSize(mode: DownloadMode = this.mode): Long? = selectedItems.sumOf { item ->
        val source = item.source() ?: return@sumOf 0L
        val options = optionsFor(item, mode)
        (if (options.isOriginal) source.size else options.estimatedSize) ?: 0L
    }.takeIf { it > 0 }

    fun sizeOf(item: BaseItemDto): Long? = item.source()?.size

    private fun BaseItemDto.source(): MediaSourceInfo? = mediaSources?.firstOrNull()

    private fun estimate(source: MediaSourceInfo, options: DownloadOptions): Long? {
        val seconds = (source.runTimeTicks ?: return source.size) / TICKS_PER_SECOND
        val streams = source.mediaStreams.orEmpty()
        val audio = DownloadOptions.keptAudio(streams, options.audioStreams)
        val audioRate = if (options.audioCodec == DownloadOptions.COPY) {
            audio.sumOf { it.bitRate ?: DEFAULT_AUDIO_RATE }.toLong()
        } else {
            audio.size.toLong() * (options.audioBitrate ?: DownloadOptions.defaultAudioBitrate(options.audioCodec))
        }
        val videoRate = if (options.videoCodec == DownloadOptions.COPY) {
            val video = DownloadOptions.mainVideo(streams)
            video?.bitRate?.toLong()
                ?: source.bitrate?.let { total -> total - streams.filter { it.type == MediaStreamType.AUDIO }.sumOf { it.bitRate ?: 0 } }?.toLong()
                ?: return source.size
        } else {
            (options.videoBitrate ?: 0).toLong()
        }
        val bytes = (videoRate + audioRate) * seconds / BITS_PER_BYTE
        // Copying never makes the file bigger
        val size = source.size
        return if (options.videoCodec == DownloadOptions.COPY && size != null) minOf(bytes, size) else bytes
    }

    companion object {
        private const val CHUNK = 50
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val BITS_PER_BYTE = 8
        private const val DEFAULT_AUDIO_RATE = 192_000

        /**
         * The tracks of [type] with the key that makes them the same in every episode: language and name,
         * or the position for tracks without either. External audio files are left out (the server keeps
         * only tracks of the file itself), external subtitles stay.
         */
        fun keyed(streams: List<MediaStream>, type: MediaStreamType): List<Pair<MediaStream, String>> {
            val ofType = streams.filter { stream ->
                stream.type == type && when (type) {
                    MediaStreamType.AUDIO -> !stream.isExternal
                    MediaStreamType.SUBTITLE -> !stream.isExternal || !stream.path.isNullOrEmpty()
                    else -> false
                }
            }
            return ofType.mapIndexed { position, stream ->
                val language = stream.language?.lowercase().orEmpty()
                val name = stream.title?.trim()?.lowercase().orEmpty()
                val key = if (language.isEmpty() && name.isEmpty()) "#$position" else "$language|$name|${stream.isForced}"
                stream to key
            }
        }

        fun label(stream: MediaStream): String = stream.displayTitle ?: listOfNotNull(stream.language, stream.title, stream.codec).joinToString(" · ")
    }
}
