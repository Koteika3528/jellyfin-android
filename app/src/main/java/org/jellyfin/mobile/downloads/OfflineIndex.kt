package org.jellyfin.mobile.downloads

import android.net.Uri
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import java.text.Collator
import java.util.Locale

/** A finished download with the library it belongs to (the first folder of its layout, see [OfflineLayout]). */
data class OfflineEntry(val files: DownloadFiles, val library: String) {
    val download get() = files.download
    val item: BaseItemDto get() = files.download.item
    val image: Uri? get() = files.files.firstOrNull { it.type == DownloadFileType.IMAGE_PRIMARY }?.uri
    val seriesImage: Uri? get() = files.files.firstOrNull { it.type == DownloadFileType.IMAGE_SERIES }?.uri
    val seasonImage: Uri? get() = files.files.firstOrNull { it.type == DownloadFileType.IMAGE_SEASON }?.uri
    val size: Long get() = files.files.sumOf { it.size }
    val isAudio: Boolean get() = item.mediaType == MediaType.AUDIO
    val isEpisode: Boolean get() = item.type == BaseItemKind.EPISODE
    val artist: String? get() = item.albumArtist?.takeIf { it.isNotBlank() } ?: item.artists?.firstOrNull { it.isNotBlank() }
}

/** An album, an artist, a genre or a series: a card that opens a list of downloads. */
data class OfflineGroup(
    val key: String,
    val title: String,
    val subtitle: String?,
    val image: Uri?,
    val entries: List<OfflineEntry>,
)

/**
 * The downloads arranged like the server library: libraries, then albums, artists, genres, series and movies.
 * Names for downloads without a known library come from [fallbackLibrary].
 */
class OfflineIndex(downloads: List<DownloadFiles>, fallbackLibrary: (BaseItemDto) -> String) {
    private val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    private val byName = compareBy<String, String>(collator) { it }

    /** Downloads that are queued, running or failed. */
    val active: List<DownloadFiles> = downloads.filter { it.download.status != DownloadStatus.DOWNLOADED }

    val entries: List<OfflineEntry>

    val libraries: List<String>

    init {
        val finished = downloads.filter { it.download.status == DownloadStatus.DOWNLOADED }
        val knownLibraries = finished.mapNotNull { files ->
            files.download.path.takeIf { '/' in it }?.substringBefore('/')
        }.toSet()
        entries = finished.map { files ->
            val path = files.download.path
            val library = when {
                '/' in path -> path.substringBefore('/')
                path in knownLibraries -> path
                // Downloads from before the library layout lie in one flat folder
                else -> fallbackLibrary(files.download.item)
            }
            OfflineEntry(files, library)
        }
        libraries = entries.map { it.library }.distinct().sortedWith(byName)
    }

    fun inLibrary(library: String) = entries.filter { it.library == library }

    fun albums(entries: List<OfflineEntry>, unknownAlbum: String): List<OfflineGroup> = entries
        .filter { it.isAudio }
        .groupBy { it.item.albumId?.toString() ?: "name:" + it.item.album.orEmpty() }
        .map { (key, tracks) ->
            val first = tracks.first()
            OfflineGroup(
                key = key,
                title = first.item.album?.takeIf { it.isNotBlank() } ?: unknownAlbum,
                subtitle = listOfNotNull(first.artist, tracks.firstNotNullOfOrNull { it.item.productionYear }?.toString())
                    .joinToString(" · ")
                    .ifEmpty { null },
                image = tracks.firstNotNullOfOrNull { it.image },
                entries = sortTracks(tracks),
            )
        }
        .sortedWith(compareBy(byName) { it.title })

    fun artists(entries: List<OfflineEntry>, unknownArtist: String): List<OfflineGroup> = entries
        .filter { it.isAudio }
        .groupBy { it.artist ?: unknownArtist }
        .map { (artist, tracks) ->
            OfflineGroup(
                key = "artist:$artist",
                title = artist,
                subtitle = null,
                image = tracks.firstNotNullOfOrNull { it.image },
                entries = sortTracks(tracks),
            )
        }
        .sortedWith(compareBy(byName) { it.title })

    /** A download can belong to several genres. */
    fun genres(entries: List<OfflineEntry>, noGenre: String): List<OfflineGroup> = entries
        .flatMap { entry -> entry.item.genres.orEmpty().filter { it.isNotBlank() }.ifEmpty { listOf(noGenre) }.map { it to entry } }
        .groupBy({ it.first }, { it.second })
        .map { (genre, items) ->
            OfflineGroup(
                key = "genre:$genre",
                title = genre,
                subtitle = null,
                image = items.firstNotNullOfOrNull { it.image },
                entries = if (items.all { it.isAudio }) sortTracks(items) else items.sortedWith(compareBy(byName) { it.item.name.orEmpty() }),
            )
        }
        .sortedWith(compareBy(byName) { it.title })

    fun series(entries: List<OfflineEntry>): List<OfflineGroup> = entries
        .filter { it.isEpisode }
        .groupBy { it.item.seriesId?.toString() ?: "name:" + it.item.seriesName.orEmpty() }
        .map { (key, episodes) ->
            val first = episodes.first()
            OfflineGroup(
                key = key,
                title = first.item.seriesName ?: first.item.name.orEmpty(),
                subtitle = null,
                image = episodes.firstNotNullOfOrNull { it.seriesImage } ?: episodes.firstNotNullOfOrNull { it.image },
                entries = episodes.sortedWith(
                    compareBy<OfflineEntry>({ it.item.parentIndexNumber ?: 0 }, { it.item.indexNumber ?: Int.MAX_VALUE }, { it.item.name.orEmpty() }),
                ),
            )
        }
        .sortedWith(compareBy(byName) { it.title })

    fun movies(entries: List<OfflineEntry>): List<OfflineEntry> = entries
        .filter { !it.isAudio && !it.isEpisode }
        .sortedWith(compareBy(byName) { it.item.name.orEmpty() })

    fun tracks(entries: List<OfflineEntry>): List<OfflineEntry> =
        entries.filter { it.isAudio }.sortedWith(compareBy(byName) { it.item.name.orEmpty() })

    /** Downloads whose name, album, artist, series or genre contains every word of [query]. */
    fun search(query: String): List<OfflineEntry> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { entry ->
            val item = entry.item
            val text = listOfNotNull(item.name, item.album, item.albumArtist, item.seriesName, entry.library)
                .plus(item.artists.orEmpty())
                .plus(item.genres.orEmpty())
                .joinToString(" ")
                .lowercase()
            words.all { it in text }
        }.sortedWith(compareBy(byName) { it.item.name.orEmpty() })
    }

    private fun sortTracks(tracks: List<OfflineEntry>) = tracks.sortedWith(
        compareBy<OfflineEntry>(
            { it.item.album.orEmpty() },
            { it.item.parentIndexNumber ?: 0 },
            { it.item.indexNumber ?: Int.MAX_VALUE },
            { it.item.name.orEmpty() },
        ),
    )
}
