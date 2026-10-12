package org.jellyfin.mobile.downloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.Locale
import java.util.UUID

/**
 * Where a download lives inside the storage location: the same folders as in the server library,
 * starting with the library name, e.g. `Shows/Show/Season 01/Show S01E01.mkv`.
 * All directories are relative to the storage location and use "/".
 */
data class OfflineLayout(
    /** Folder of the item's files. */
    val directory: String,
    /** Name of the main file (as on the server). */
    val fileName: String,
    /** Name of the primary image next to the main file, shared by all tracks of an album. */
    val imageName: String,
    /** Series folder of an episode: its poster goes there. */
    val seriesDirectory: String? = null,
    /** Season folder of an episode. */
    val seasonDirectory: String? = null,
) {
    val filePath: String get() = joinPath(directory, fileName)
    val imagePath: String get() = joinPath(directory, imageName)

    /** The season poster: `poster.jpg` in a season folder, `seasonNN-poster.jpg` next to the series poster otherwise. */
    fun seasonImagePath(seasonNumber: Int?): String? {
        val season = seasonDirectory ?: return null
        val series = seriesDirectory
        if (series == null || season != series) return joinPath(season, POSTER)
        val name = when (seasonNumber) {
            null -> return null
            0 -> "season-specials-poster.jpg"
            else -> "season%02d-poster.jpg".format(Locale.ROOT, seasonNumber)
        }
        return joinPath(series, name)
    }

    fun seriesImagePath(): String? = seriesDirectory?.let { joinPath(it, POSTER) }

    companion object {
        const val POSTER = "poster.jpg"
        const val ALBUM_COVER = "cover.jpg"

        private val invalidCharacters = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

        fun joinPath(vararg parts: String?): String = parts
            .filterNotNull()
            .flatMap { it.split('/') }
            .filter { it.isNotEmpty() }
            .joinToString("/")

        /** Makes a folder or file name valid on any storage (FAT SD cards included). */
        fun sanitize(segment: String): String = segment
            .replace(invalidCharacters, "_")
            .trim()
            .trimEnd('.')
            .ifEmpty { "_" }

        private fun sanitizePath(path: String?): String? = path
            ?.split('/', '\\')
            ?.filter { it.isNotBlank() && it != "." && it != ".." }
            ?.joinToString("/") { sanitize(it) }
            ?.ifEmpty { null }

        private fun parentOf(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }

        /** The layout from the item alone, for servers without [resolve] support. */
        fun fromMetadata(item: BaseItemDto, libraryName: String? = null): OfflineLayout =
            build(item, libraryName, server = null)

        private fun build(item: BaseItemDto, libraryName: String?, server: ServerPath?): OfflineLayout {
            val library = sanitize(libraryName ?: server?.libraryName ?: defaultLibraryName(item))
            val serverFile = sanitizePath(server?.relativePath)

            val fileName = serverFile?.substringAfterLast('/')
                ?: item.path?.substringAfterLast('/')?.substringAfterLast('\\')?.let(::sanitize)?.takeIf { it != "_" }
                ?: sanitize(listOfNotNull(item.name ?: item.id.toString(), item.container?.substringBefore(',')).joinToString("."))

            val directory = when {
                serverFile != null -> joinPath(library, parentOf(serverFile))
                else -> joinPath(library, metadataFolder(item))
            }

            val seriesDirectory: String?
            val seasonDirectory: String?
            if (item.type == BaseItemKind.EPISODE) {
                seriesDirectory = sanitizePath(server?.seriesPath)?.let { joinPath(library, it) }
                    ?: item.seriesName?.let { joinPath(library, sanitize(it)) }
                seasonDirectory = sanitizePath(server?.seasonPath)?.let { joinPath(library, it) } ?: directory
            } else {
                seriesDirectory = null
                seasonDirectory = null
            }

            val baseName = fileName.substringBeforeLast('.', fileName)
            val imageName = when (item.type) {
                BaseItemKind.AUDIO -> ALBUM_COVER
                BaseItemKind.EPISODE -> "$baseName-thumb.jpg"
                BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> "$baseName-poster.jpg"
                else -> "$baseName.jpg"
            }

            return OfflineLayout(directory, fileName, imageName, seriesDirectory, seasonDirectory)
        }

        private fun defaultLibraryName(item: BaseItemDto) = when (item.type) {
            BaseItemKind.EPISODE -> "Shows"
            BaseItemKind.MOVIE -> "Movies"
            BaseItemKind.AUDIO -> "Music"
            BaseItemKind.MUSIC_VIDEO -> "Music Videos"
            else -> "Other"
        }

        private fun metadataFolder(item: BaseItemDto): String? = when (item.type) {
            BaseItemKind.EPISODE -> joinPath(
                item.seriesName?.let(::sanitize),
                when (val season = item.parentIndexNumber) {
                    null -> item.seasonName?.let(::sanitize)
                    0 -> "Specials"
                    else -> "Season %02d".format(Locale.ROOT, season)
                },
            )
            BaseItemKind.MOVIE -> sanitize(listOfNotNull(item.name, item.productionYear?.let { "($it)" }).joinToString(" "))
            BaseItemKind.AUDIO -> joinPath(
                (item.albumArtist ?: item.artists?.firstOrNull())?.let(::sanitize),
                item.album?.let(::sanitize),
            )
            else -> null
        }.takeUnless { it.isNullOrEmpty() }

        /**
         * Asks the server where the items lie in their libraries (fork endpoint `Items/OfflinePaths`).
         * Items the server does not describe get the library from their ancestors and folders from metadata.
         */
        suspend fun resolve(api: ApiClient, items: Collection<BaseItemDto>): Map<UUID, OfflineLayout> {
            if (items.isEmpty()) return emptyMap()

            val serverPaths = runCatching {
                items.chunked(CHUNK).flatMap { chunk ->
                    api.get<List<ServerPath>>(
                        "/Items/OfflinePaths",
                        queryParameters = mapOf("ids" to chunk.joinToString(",") { it.id.toString() }),
                    ).content
                }.mapNotNull { path -> path.itemId.toUUIDOrNull()?.let { it to path } }.toMap()
            }.onFailure { err ->
                Timber.i(err, "Server does not report library paths, using metadata")
            }.getOrDefault(emptyMap())

            return items.associate { item ->
                val server = serverPaths[item.id]
                val library = if (server?.libraryName == null) findLibraryName(api, item.id) else null
                item.id to build(item, library, server)
            }
        }

        private suspend fun findLibraryName(api: ApiClient, itemId: UUID): String? = runCatching {
            api.libraryApi.getAncestors(itemId).content
                .firstOrNull { it.type == BaseItemKind.COLLECTION_FOLDER }
                ?.name
        }.getOrNull()

        private const val CHUNK = 50
    }

    @Serializable
    private data class ServerPath(
        @SerialName("ItemId") val itemId: String,
        @SerialName("LibraryName") val libraryName: String? = null,
        @SerialName("RelativePath") val relativePath: String? = null,
        @SerialName("SeriesPath") val seriesPath: String? = null,
        @SerialName("SeasonPath") val seasonPath: String? = null,
        @SerialName("AlbumPath") val albumPath: String? = null,
    )
}
