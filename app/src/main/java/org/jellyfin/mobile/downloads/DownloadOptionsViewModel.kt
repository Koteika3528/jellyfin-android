package org.jellyfin.mobile.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
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
import kotlin.math.pow

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
     * A voice-over or subtitle track, the same in all episodes of a season: same language and name.
     * @param count in how many of the season's chosen episodes it is, of [total].
     */
    data class Track(
        val key: String,
        val label: String,
        val count: Int,
        val total: Int,
        /** FRC: shown, not changed. */
        val forced: Boolean = false,
        /** In how many episodes it plays by default now. */
        val defaultCount: Int = 0,
    )

    /** The tracks of the chosen episodes of a season, chosen for the whole season. */
    data class SeasonTracks(val season: Season, val audio: List<Track>, val subtitles: List<Track>)

    enum class Resolution(val height: Int?) {
        SOURCE(null),
        P2160(2160),
        P1080(1080),
        P720(720),
        P480(480),
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

    /**
     * Tracks left out, by season and [Track.key] (see [trackId]): tracks are kept unless unchecked,
     * also ones of episodes chosen later.
     */
    var excludedAudio by mutableStateOf<Set<String>>(emptySet())
        private set
    var excludedSubtitles by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Def tracks chosen by season ([NO_DEFAULT] for subtitles: none); otherwise as most episodes have it. */
    private var defaultAudioChoice by mutableStateOf<Map<String, String>>(emptyMap())
    private var defaultSubtitleChoice by mutableStateOf<Map<String, String>>(emptyMap())

    var videoCodec by mutableStateOf(DownloadOptions.H264)
    var resolution by mutableStateOf(Resolution.P720)
    var audioCodec by mutableStateOf(DownloadOptions.AAC)

    /** Encoded audio keeps 5.1 and 7.1 (bigger), stereo otherwise. */
    var keepChannels by mutableStateOf(false)

    /** Some chosen episode has surround audio: keeping it is offered. */
    val hasSurround: Boolean
        get() = selectedItems.any { item -> item.source()?.mediaStreams.orEmpty().any { it.type == MediaStreamType.AUDIO && (it.channels ?: 0) > 2 } }

    /** The bitrate limit is typed in ([manualMbits]) instead of the recommended one. */
    var manualBitrate by mutableStateOf(false)
    var manualMbits by mutableStateOf("")

    private var items: List<BaseItemDto> = emptyList()

    val selectedItems: List<BaseItemDto> get() = items.filter { it.id in selected }

    /** More than one item: the list to choose from is shown. */
    val canChoose: Boolean get() = items.size > 1

    /** Only video files can be remuxed or transcoded by the server. */
    val canConvert: Boolean get() = items.any { it.canConvert() }

    /**
     * Downloads go to the server without encryption over the internet: the provider sees the traffic
     * and may slow it down (an https address of the server helps).
     */
    val insecureConnection: Boolean
        get() {
            val uri = api.baseUrl?.toUri() ?: return false
            return uri.scheme == "http" && !isLocalHost(uri.host.orEmpty())
        }

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
                seasons = items.groupBy { seasonKey(it) }.map { (key, list) ->
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

    fun isAudioKept(season: Season, track: Track) = trackId(season.key, track.key) !in excludedAudio

    fun isSubtitleKept(season: Season, track: Track) = trackId(season.key, track.key) !in excludedSubtitles

    fun toggleAudio(season: Season, track: Track) {
        val id = trackId(season.key, track.key)
        excludedAudio = if (id in excludedAudio) excludedAudio - id else excludedAudio + id
    }

    fun toggleSubtitle(season: Season, track: Track) {
        val id = trackId(season.key, track.key)
        excludedSubtitles = if (id in excludedSubtitles) excludedSubtitles - id else excludedSubtitles + id
    }

    fun setDefaultAudio(season: Season, track: Track) {
        defaultAudioChoice = defaultAudioChoice + (season.key to track.key)
    }

    /** [track] null: no subtitles by default. */
    fun setDefaultSubtitle(season: Season, track: Track?) {
        defaultSubtitleChoice = defaultSubtitleChoice + (season.key to (track?.key ?: NO_DEFAULT))
    }

    /** The Def voice-over of a season: chosen, or the one most episodes play by default, or the first. */
    fun defaultAudioOf(group: SeasonTracks): String? =
        defaultAudioChoice[group.season.key] ?: group.audio.filter { it.defaultCount > 0 }.maxByOrNull { it.defaultCount }?.key ?: group.audio.firstOrNull()?.key

    /** The Def subtitles of a season, null - none. */
    fun defaultSubtitleOf(group: SeasonTracks): String? {
        val chosen = defaultSubtitleChoice[group.season.key]
        if (chosen != null) return chosen.takeIf { it != NO_DEFAULT }
        return group.subtitles.filter { it.defaultCount > 0 }.maxByOrNull { it.defaultCount }?.key
    }

    /** Voice-overs and subtitles of the chosen episodes, season by season. */
    val trackGroups: List<SeasonTracks>
        get() = seasons.mapNotNull { season ->
            val chosen = season.items.filter { it.id in selected && it.canConvert() }
            if (chosen.isEmpty()) return@mapNotNull null
            SeasonTracks(season, tracksOf(chosen, MediaStreamType.AUDIO), tracksOf(chosen, MediaStreamType.SUBTITLE))
        }

    private fun tracksOf(episodes: List<BaseItemDto>, type: MediaStreamType): List<Track> {
        val found = LinkedHashMap<String, Track>()
        for (item in episodes) {
            val streams = item.source()?.mediaStreams.orEmpty()
            val keyedStreams = keyed(streams, type)
            for ((stream, key) in keyedStreams.distinctBy { it.second }) {
                val track = found[key] ?: Track(key, label(stream), 0, episodes.size, forced = stream.isForced)
                val isDefault = keyedStreams.any { it.second == key && it.first.isDefault }
                found[key] = track.copy(count = track.count + 1, defaultCount = track.defaultCount + if (isDefault) 1 else 0)
            }
        }
        return found.values.toList()
    }

    /** Def keys (audio, subtitles) of each season, for [optionsFor]. */
    private fun defaultsBySeason(): Map<String, Pair<String?, String?>> =
        trackGroups.associate { group -> group.season.key to (defaultAudioOf(group) to defaultSubtitleOf(group)) }

    /** How [item] is downloaded with what is chosen now (in [mode]). */
    fun optionsFor(
        item: BaseItemDto,
        mode: DownloadMode = this.mode,
        defaults: Map<String, Pair<String?, String?>> = defaultsBySeason(),
    ): DownloadOptions {
        val source = item.source()
        // Without a video track (or a file) the server cannot make it: the original then
        if (mode == DownloadMode.ORIGINAL || source == null || !item.canConvert()) return DownloadOptions()
        val streams = source.mediaStreams.orEmpty()
        val season = seasonKey(item)
        val transcode = mode == DownloadMode.TRANSCODE
        val keptAudio = keyed(streams, MediaStreamType.AUDIO).filter { trackId(season, it.second) !in excludedAudio }
        val keptSubtitles = keyed(streams, MediaStreamType.SUBTITLE).filter { trackId(season, it.second) !in excludedSubtitles }
        val (defaultAudioKey, defaultSubtitleKey) = defaults[season] ?: (null to null)
        val options = DownloadOptions(
            mode = mode,
            mediaSourceId = source.id,
            // None left (or none in this episode): the server keeps the default track
            audioStreams = keptAudio.map { it.first.index },
            subtitleStreams = keptSubtitles.map { it.first.index },
            // Def: the season's choice; an episode without it plays its first kept track
            defaultAudio = keptAudio.firstOrNull { it.second == defaultAudioKey }?.first?.index,
            defaultSubtitle = when (defaultSubtitleKey) {
                null -> DownloadOptions.NO_SUBTITLE
                else -> (keptSubtitles.firstOrNull { it.second == defaultSubtitleKey } ?: keptSubtitles.firstOrNull())?.first?.index ?: DownloadOptions.NO_SUBTITLE
            },
            videoCodec = if (transcode) videoCodec else DownloadOptions.COPY,
            audioCodec = if (transcode) audioCodec else DownloadOptions.COPY,
            videoBitrate = if (transcode) videoBitrate(source) else null,
            maxHeight = if (transcode) resolution.height else null,
            audioBitrate = if (transcode && audioCodec != DownloadOptions.COPY) DownloadOptions.defaultAudioBitrate(audioCodec) else null,
            keepChannels = transcode && audioCodec != DownloadOptions.COPY && keepChannels,
        )
        return options.copy(estimatedSize = estimate(source, options))
    }

    /** Size of the chosen items as [mode] would make them, null when the server does not know it. */
    fun estimatedSize(mode: DownloadMode = this.mode): Long? {
        val defaults = defaultsBySeason()
        return selectedItems.sumOf { item -> sizeWith(item, mode, defaults) }.takeIf { it > 0 }
    }

    private fun sizeWith(item: BaseItemDto, mode: DownloadMode, defaults: Map<String, Pair<String?, String?>>): Long {
        val source = item.source() ?: return 0L
        val options = optionsFor(item, mode, defaults)
        return (if (options.isOriginal) source.size else options.estimatedSize) ?: 0L
    }

    fun sizeOf(item: BaseItemDto): Long? = item.source()?.size

    /** The typed limit, bit/s, when it is a number. */
    val manualLimit: Int?
        get() = manualMbits.replace(',', '.').toFloatOrNull()?.takeIf { it > 0 }?.let { (it * MBIT).toInt() }

    /** The limit for the chosen codec and resolution (for the source resolution: of a 1080p file). */
    val recommendedLimit: Int get() = recommendedBitrate(videoCodec, resolution.height ?: DEFAULT_HEIGHT)

    /** Average video bitrate of the chosen episodes when transcoded, bit/s. */
    val averageBitrate: Int?
        get() = selectedItems.mapNotNull { item -> item.source()?.takeIf { item.canConvert() }?.let(::videoBitrate) }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()

    /**
     * Bitrate of the encoded video: what the source needs in the new codec and size (a weak source is
     * not blown up), at most the limit - typed in, or recommended for the codec and resolution.
     */
    fun videoBitrate(source: MediaSourceInfo): Int {
        val streams = source.mediaStreams.orEmpty()
        val video = DownloadOptions.mainVideo(streams)
        val sourceHeight = video?.height?.takeIf { it > 0 }
        val targetHeight = listOfNotNull(sourceHeight, resolution.height).minOrNull() ?: DEFAULT_HEIGHT
        val limit = (if (manualBitrate) manualLimit else null) ?: recommendedBitrate(videoCodec, targetHeight)

        val sourceRate = video?.bitRate
            ?: source.bitrate?.let { total -> total - streams.filter { it.type == MediaStreamType.AUDIO }.sumOf { it.bitRate ?: 0 } }?.takeIf { it > 0 }
        val needed = sourceRate?.let { rate ->
            val codecs = efficiency(videoCodec) / efficiency(video?.codec)
            val scale = if (sourceHeight != null) (targetHeight.toDouble() / sourceHeight).coerceAtMost(1.0).pow(SCALE_EXPONENT) else 1.0
            (rate * codecs * scale).toInt()
        }
        return minOf(needed ?: limit, limit).coerceAtLeast(MIN_BITRATE)
    }

    private fun BaseItemDto.source(): MediaSourceInfo? = mediaSources?.firstOrNull()

    private fun BaseItemDto.canConvert(): Boolean =
        mediaType == MediaType.VIDEO && source()?.let { DownloadOptions.mainVideo(it.mediaStreams.orEmpty()) } != null

    private fun estimate(source: MediaSourceInfo, options: DownloadOptions): Long? {
        val seconds = (source.runTimeTicks ?: return source.size) / TICKS_PER_SECOND
        val streams = source.mediaStreams.orEmpty()
        val audio = DownloadOptions.keptAudio(streams, options.audioStreams)
        val audioRate = if (options.audioCodec == DownloadOptions.COPY) {
            audio.sumOf { it.bitRate ?: DEFAULT_AUDIO_RATE }.toLong()
        } else {
            audio.sumOf { DownloadOptions.encodedAudioRate(it, options.audioCodec, options.audioBitrate, options.keepChannels).toLong() }
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
        const val MBIT = 1_000_000
        private const val DEFAULT_HEIGHT = 1080
        private const val MIN_BITRATE = 300_000

        /** A smaller picture needs less, but not in proportion to its area. */
        private const val SCALE_EXPONENT = 1.5

        private fun seasonKey(item: BaseItemDto) = item.seasonId?.toString() ?: item.parentIndexNumber?.toString() ?: ""

        private fun trackId(season: String, track: String) = "$season/$track"

        private const val NO_DEFAULT = "-"

        /** Bitrate that looks good for the codec at that height, bit/s (the limit unless typed in). */
        fun recommendedBitrate(codec: String, height: Int): Int {
            val hevc = codec == DownloadOptions.HEVC
            return when {
                height > 1440 -> if (hevc) 10_000_000 else 16_000_000
                height > 1080 -> if (hevc) 6_000_000 else 10_000_000
                height > 720 -> if (hevc) 3_500_000 else 6_000_000
                height > 480 -> if (hevc) 1_800_000 else 3_000_000
                height > 360 -> if (hevc) 900_000 else 1_500_000
                else -> if (hevc) 500_000 else 800_000
            }
        }

        /** Bits a codec needs for the same picture, compared with H.264. */
        private fun efficiency(codec: String?): Double = when (codec?.lowercase()) {
            "hevc", "h265" -> 0.6
            "av1" -> 0.5
            "vp9" -> 0.65
            "vc1", "wmv3" -> 1.2
            "mpeg4", "msmpeg4v3", "h263" -> 1.5
            "mpeg2video", "mpeg1video" -> 2.0
            else -> 1.0
        }

        private fun isLocalHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            val parts = h.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 100 && parts[1] in 64..127)
        }

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
