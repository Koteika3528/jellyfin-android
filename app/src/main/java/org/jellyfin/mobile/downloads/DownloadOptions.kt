package org.jellyfin.mobile.downloads

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import java.util.UUID

enum class DownloadMode {
    /** The file as it is on the server: no load on the server, continues after a broken connection. */
    ORIGINAL,

    /** Only the chosen tracks, copied as they are into one Matroska file. */
    REMUX,

    /** The server encodes the video (H.264 or HEVC) and maybe the audio again. */
    TRANSCODE,
}

/**
 * How a video is downloaded. Anything but [DownloadMode.ORIGINAL] is made by the server
 * (`Videos/{id}/OfflineFile`) while downloading: the size is not known in advance and a broken
 * download starts over.
 *
 * @param audioStreams indexes of the audio tracks to keep (in the server's numbering); none - the default one.
 * @param subtitleStreams indexes of the subtitle tracks to keep.
 * @param estimatedSize the expected size, shown while downloading.
 */
@Serializable
data class DownloadOptions(
    val mode: DownloadMode = DownloadMode.ORIGINAL,
    val mediaSourceId: String? = null,
    val audioStreams: List<Int> = emptyList(),
    val subtitleStreams: List<Int> = emptyList(),
    val videoCodec: String = COPY,
    val audioCodec: String = COPY,
    val videoBitrate: Int? = null,
    val maxHeight: Int? = null,
    val audioBitrate: Int? = null,
    val estimatedSize: Long? = null,
    /** Encoded audio keeps 5.1 and 7.1 instead of being mixed to stereo. */
    val keepChannels: Boolean = false,
) {
    val isOriginal: Boolean get() = mode == DownloadMode.ORIGINAL

    fun remoteUri(api: ApiClient, itemId: UUID): Uri = api.createUrl("/Videos/$itemId/OfflineFile").toUri().buildUpon().apply {
        mediaSourceId?.let { appendQueryParameter("mediaSourceId", it) }
        if (audioStreams.isNotEmpty()) appendQueryParameter("audioStreams", audioStreams.joinToString(","))
        if (subtitleStreams.isNotEmpty()) appendQueryParameter("subtitleStreams", subtitleStreams.joinToString(","))
        appendQueryParameter("videoCodec", videoCodec)
        appendQueryParameter("audioCodec", audioCodec)
        videoBitrate?.let { appendQueryParameter("videoBitrate", it.toString()) }
        maxHeight?.let { appendQueryParameter("maxHeight", it.toString()) }
        audioBitrate?.let { appendQueryParameter("audioBitrate", it.toString()) }
        if (keepChannels) appendQueryParameter("keepChannels", "true")
    }.build()

    /** `Show S01E01.mp4` → `Show S01E01.mkv`, or `Show S01E01.hevc.mkv` when encoded. */
    fun fileName(original: String): String {
        if (isOriginal) return original
        val base = original.substringBeforeLast('.', original)
        return if (videoCodec == COPY) "$base.mkv" else "$base.$videoCodec.mkv"
    }

    /**
     * The media source of the downloaded file: only the kept streams, numbered as they are in the file
     * (video, audio, subtitles, as the server writes them), so the player picks the right tracks offline.
     */
    fun rewrite(source: MediaSourceInfo): MediaSourceInfo {
        if (isOriginal) return source
        val streams = source.mediaStreams.orEmpty()
        val video = mainVideo(streams)
        val audio = keptAudio(streams, audioStreams)
        val subtitles = keptSubtitles(streams, subtitleStreams)

        val kept = buildList {
            video?.let { add(rewriteVideo(it)) }
            audio.forEachIndexed { position, stream -> add(rewriteAudio(stream, position == 0)) }
            subtitles.forEach { add(rewriteSubtitle(it)) }
        }.mapIndexed { index, stream -> stream.copy(index = index) }

        val firstAudio = if (video != null) 1 else 0
        val firstSubtitle = firstAudio + audio.size
        val defaultSubtitle = subtitles.indexOfFirst { it.index == source.defaultSubtitleStreamIndex }.takeIf { it >= 0 }

        return source.copy(
            container = MATROSKA,
            mediaStreams = kept,
            mediaAttachments = if (subtitles.any { it.codec.equals("ass", true) || it.codec.equals("ssa", true) }) source.mediaAttachments else null,
            defaultAudioStreamIndex = if (audio.isNotEmpty()) firstAudio else null,
            defaultSubtitleStreamIndex = defaultSubtitle?.let { firstSubtitle + it },
            size = estimatedSize ?: source.size,
            bitrate = kept.mapNotNull { it.bitRate }.sum().takeIf { it > 0 } ?: source.bitrate,
        )
    }

    private fun rewriteVideo(stream: MediaStream): MediaStream {
        if (videoCodec == COPY) return stream
        val sourceHeight = stream.height
        val height = sourceHeight?.let { maxHeight?.coerceAtMost(it) ?: it }
        val width = stream.width?.let { width ->
            if (sourceHeight != null && sourceHeight > 0 && height != null) width * height / sourceHeight / 2 * 2 else width
        }
        return stream.copy(
            codec = videoCodec,
            codecTag = null,
            profile = null,
            level = null,
            bitRate = videoBitrate ?: stream.bitRate,
            height = height,
            width = width,
            pixelFormat = "yuv420p",
            bitDepth = 8,
            videoRange = VideoRange.SDR,
            videoRangeType = VideoRangeType.SDR,
            isAvc = videoCodec == H264,
        )
    }

    private fun rewriteAudio(stream: MediaStream, default: Boolean): MediaStream {
        val encoded = if (audioCodec == COPY) {
            stream
        } else {
            val channels = encodedChannels(stream, keepChannels)
            stream.copy(
                codec = audioCodec,
                codecTag = null,
                profile = null,
                channels = channels,
                channelLayout = if (channels <= 2) (if (channels == 1) "mono" else "stereo") else stream.channelLayout,
                bitRate = encodedAudioRate(stream, audioCodec, audioBitrate, keepChannels),
            )
        }
        return encoded.copy(isDefault = default)
    }

    private fun rewriteSubtitle(stream: MediaStream) = stream.copy(
        codec = if (stream.codec?.lowercase() in TO_SRT) "srt" else stream.codec,
        isExternal = false,
        deliveryMethod = SubtitleDeliveryMethod.EMBED,
        deliveryUrl = null,
        isExternalUrl = null,
        path = null,
    )

    companion object {
        const val COPY = "copy"
        const val H264 = "h264"
        const val HEVC = "hevc"
        const val AAC = "aac"
        const val OPUS = "opus"
        private const val MATROSKA = "mkv"

        private val COVER_CODECS = setOf("mjpeg", "png", "bmp", "gif", "webp")
        private val TO_SRT = setOf("mov_text", "tx3g", "text", "eia_608")

        /** What the server encodes with when no audio bitrate is given. */
        fun defaultAudioBitrate(codec: String) = if (codec == OPUS) 128_000 else 160_000

        /** Channels of an encoded track, as the server makes them: mono stays mono, surround only when kept. */
        fun encodedChannels(stream: MediaStream, keepChannels: Boolean): Int =
            (stream.channels ?: 2).coerceIn(1, if (keepChannels) 8 else 2)

        /** Bitrate of an encoded track, as the server gives it: the stereo rate, more for more channels. */
        fun encodedAudioRate(stream: MediaStream, codec: String, stereoRate: Int?, keepChannels: Boolean): Int {
            val rate = (stereoRate ?: defaultAudioBitrate(codec)).coerceIn(32_000, 640_000)
            val channels = encodedChannels(stream, keepChannels)
            return if (channels > 2) minOf(rate * channels / 2, 1_024_000) else rate
        }

        /** The video the server keeps (not a cover picture). */
        fun mainVideo(streams: List<MediaStream>): MediaStream? = streams.firstOrNull { stream ->
            stream.type == MediaStreamType.VIDEO && !stream.isExternal && stream.codec?.lowercase() !in COVER_CODECS
        }

        /** The audio tracks the server keeps for [chosen]: as in the file, the default one when none fits. */
        fun keptAudio(streams: List<MediaStream>, chosen: Collection<Int>): List<MediaStream> {
            val embedded = streams.filter { it.type == MediaStreamType.AUDIO && !it.isExternal }
            return embedded.filter { it.index in chosen }
                .ifEmpty { listOfNotNull(embedded.firstOrNull { it.isDefault } ?: embedded.firstOrNull()) }
        }

        /** The subtitle tracks the server keeps for [chosen], external files included. */
        fun keptSubtitles(streams: List<MediaStream>, chosen: Collection<Int>): List<MediaStream> = streams.filter { stream ->
            stream.type == MediaStreamType.SUBTITLE && stream.index in chosen && (!stream.isExternal || !stream.path.isNullOrEmpty())
        }
    }
}
