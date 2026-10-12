package org.jellyfin.mobile.downloads

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Instant
import java.util.UUID

/**
 * The downloads database next to the files: `jellyfin-library.json` in the storage location.
 *
 * It lists every downloaded item with its files (paths relative to the storage location, laid out like
 * the server library, see [OfflineLayout]), the series, seasons and albums they belong to, and the item
 * as the server describes it. After a clean install, choosing the same folder (and signing in to the
 * same server) brings the downloads back with [restore]. The format is shared with the Windows 10
 * Mobile client.
 */
class OfflineLibrary(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val serverDao: ServerDao,
    private val userDao: UserDao,
    private val appPreferences: AppPreferences,
    private val storageManager: StorageManager,
    private val apiClient: ApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pendingWrite: Job? = null

    /** Items removed from the app with their files kept: they leave the manifest instead of coming back. */
    private val dismissed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Series, seasons and albums, kept in the manifest itself. */
    private var titles: MutableMap<String, ManifestTitle>? = null

    /** Remembers a series, season or album of a download (written with the next [scheduleWrite]). */
    fun registerTitle(title: BaseItemDto, folder: String?, image: String?) {
        val entry = ManifestTitle(
            id = title.id.toString(),
            serverId = title.serverId,
            type = title.type.serialName,
            name = title.name,
            seriesId = title.seriesId?.toString(),
            seriesName = title.seriesName,
            indexNumber = title.indexNumber,
            productionYear = title.productionYear,
            overview = title.overview,
            folder = folder,
            image = image,
            item = runCatching { json.encodeToJsonElement(BaseItemDto.serializer(), title) }.getOrNull(),
        )
        scope.launch {
            mutex.withLock {
                val known = loadTitles()
                val previous = known[entry.id]
                known[entry.id] = entry.copy(
                    folder = entry.folder ?: previous?.folder,
                    image = entry.image ?: previous?.image,
                )
            }
        }
    }

    /** The item was removed from the app but its files were kept. */
    fun dismiss(itemId: UUID) {
        dismissed += itemId.toString()
        scheduleWrite()
    }

    /** Rewrites the manifest a moment later, so a finished album is written once rather than per track. */
    fun scheduleWrite() {
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(WRITE_DELAY_MS)
            write()
        }
    }

    suspend fun write() = withContext(Dispatchers.IO) { writeLocked() }

    private suspend fun writeLocked(): Unit = mutex.withLock {
        val root = storageManager.getStorageLocation()?.takeIf { it.canWrite() } ?: return@withLock
        val downloads = downloadDao.getCompletedDownloads()
        val servers = mutableMapOf<String, ManifestServer>()
        val own = downloads.mapNotNull { download -> toManifestItem(download, servers) }

        // Entries the app does not know (not restored yet, another server, another app) stay while their files exist
        val existing = readManifest()
        val appItems = downloadDao.getAllItemIds().map(UUID::toString).toSet()
        val foreign = existing?.items.orEmpty().filter { entry ->
            entry.id.toUUIDOrNull()?.toString() !in appItems &&
                entry.id !in dismissed &&
                storageManager.resolveFile(entry.file) != null
        }
        existing?.servers.orEmpty()
            .filter { server -> foreign.any { it.serverId == server.id } }
            .forEach { server -> servers.putIfAbsentCompat(server.id ?: server.address.orEmpty(), server) }
        val items = own + foreign

        val referenced = items.flatMap { listOfNotNull(it.seriesId, it.seasonId, it.albumId) }.toSet()
        val knownTitles = loadTitles()
        knownTitles.keys.retainAll(referenced)
        for (item in items) {
            val albumId = item.albumId ?: continue
            if (albumId !in knownTitles) {
                knownTitles[albumId] = ManifestTitle(
                    id = albumId,
                    serverId = item.serverId,
                    type = BaseItemKind.MUSIC_ALBUM.serialName,
                    name = item.album,
                    seriesName = item.albumArtist,
                    folder = item.folder,
                    image = item.image,
                )
            }
        }

        val manifest = Manifest(
            updated = Instant.now().toString(),
            servers = servers.values.toList(),
            titles = knownTitles.values.sortedBy { it.id },
            items = items,
        )
        runCatching {
            writeText(root, json.encodeToString(Manifest.serializer(), manifest))
        }.onFailure { err ->
            Timber.e(err, "Failed to write $MANIFEST_FILE")
        }
    }

    /**
     * Adds the downloads listed in the manifest that the app does not know yet (after a clean install
     * or when another folder was chosen). Returns how many were added.
     */
    suspend fun restore(): Int = withContext(Dispatchers.IO) { restoreLocked() }

    private suspend fun restoreLocked(): Int = mutex.withLock {
        val manifest = readManifest() ?: return@withLock 0
        val accounts = mutableMapOf<String?, Pair<ServerEntity, UserEntity>?>()
        var restored = 0

        for (entry in manifest.items) {
            if (entry.id in dismissed) continue
            val item = entry.item?.let { element ->
                runCatching { json.decodeFromJsonElement(BaseItemDto.serializer(), element) }.getOrNull()
            } ?: continue
            if (downloadDao.getDownloadByItemId(item.id) != null) continue

            val server = manifest.servers.firstOrNull { it.id == entry.serverId } ?: manifest.servers.firstOrNull()
            if (entry.serverId !in accounts) accounts[entry.serverId] = findAccount(server)
            val (serverEntity, userEntity) = accounts[entry.serverId] ?: continue

            val mainPath = entry.files.firstOrNull { it.type == DownloadFileType.ITEM.name }?.path ?: entry.file
            val mainFile = storageManager.resolveFile(mainPath) ?: continue

            val downloadId = downloadDao.insert(
                DownloadEntity(
                    serverId = serverEntity.id,
                    userId = userEntity.id,
                    itemId = item.id,
                    path = entry.folder,
                    item = item,
                    status = DownloadStatus.DOWNLOADED,
                ),
            )

            val files = entry.files.ifEmpty { listOf(ManifestFile(DownloadFileType.ITEM.name, entry.file)) }
            for (file in files) {
                val type = runCatching { DownloadFileType.valueOf(file.type) }.getOrNull() ?: continue
                val document = if (file.path == mainPath) mainFile else storageManager.resolveFile(file.path) ?: continue
                downloadDao.insertFile(
                    DownloadFileEntity(
                        downloadId = downloadId,
                        type = type,
                        size = document.length(),
                        fileName = file.path,
                        uri = document.uri,
                        status = DownloadStatus.DOWNLOADED,
                    ),
                )
            }
            restored++
        }

        if (titles == null) {
            titles = manifest.titles.associateByTo(mutableMapOf()) { it.id }
        } else {
            val known = titles!!
            manifest.titles.forEach { title -> known.getOrPut(title.id) { title } }
        }
        restored
    }.also { restored ->
        if (restored > 0) Timber.i("Restored $restored downloads from $MANIFEST_FILE")
    }

    /** The relative path of a download file: new files store it, older ones only their name inside the download folder. */
    fun relativePath(download: DownloadEntity, file: DownloadFileEntity): String =
        if ('/' in file.fileName) file.fileName else OfflineLayout.joinPath(download.path, file.fileName)

    private suspend fun toManifestItem(download: DownloadFiles, servers: MutableMap<String, ManifestServer>): ManifestItem? {
        val (entity, files) = download
        val item = entity.item
        val main = files.firstOrNull { it.type == DownloadFileType.ITEM } ?: return null

        val serverUser = userDao.getServerUser(entity.serverId, entity.userId)
        val serverKey = item.serverId ?: serverUser?.server?.hostname ?: entity.serverId.toString()
        if (serverKey !in servers && serverUser != null) {
            servers[serverKey] = ManifestServer(
                id = item.serverId,
                address = serverUser.server.hostname,
                userId = serverUser.user.userId.toString(),
            )
        }

        return ManifestItem(
            id = item.id.toString(),
            serverId = item.serverId,
            type = item.type.serialName,
            mediaType = item.mediaType.serialName,
            name = item.name,
            folder = entity.path,
            file = relativePath(entity, main),
            image = files.firstOrNull { it.type == DownloadFileType.IMAGE_PRIMARY }?.let { relativePath(entity, it) },
            size = files.sumOf { it.size },
            seriesId = item.seriesId?.toString(),
            seriesName = item.seriesName,
            seasonId = item.seasonId?.toString(),
            seasonName = item.seasonName,
            parentIndexNumber = item.parentIndexNumber,
            indexNumber = item.indexNumber,
            indexNumberEnd = item.indexNumberEnd,
            albumId = item.albumId?.toString(),
            album = item.album,
            albumArtist = item.albumArtist,
            artists = item.artists,
            productionYear = item.productionYear,
            runTimeTicks = item.runTimeTicks,
            container = item.container,
            overview = item.overview,
            files = files.map { ManifestFile(it.type.name, relativePath(entity, it), it.size) },
            item = runCatching { json.encodeToJsonElement(BaseItemDto.serializer(), item) }.getOrNull(),
        )
    }

    /** The account to attach restored downloads to: the same server address, or the current server if it is the same server. */
    private suspend fun findAccount(server: ManifestServer?): Pair<ServerEntity, UserEntity>? {
        val manifestUserId = server?.userId?.toUUIDOrNull()

        val byAddress = server?.address?.let { serverDao.getServerByHostname(it) }
        val serverEntity = byAddress ?: run {
            val current = appPreferences.currentServerId?.let { serverDao.getServer(it) } ?: return null
            val currentId = runCatching { apiClient.systemApi.getPublicSystemInfo().content.id }.getOrNull()
            current.takeIf { server?.id == null || sameId(currentId, server.id) }
        } ?: return null

        val userEntity = manifestUserId?.let { userDao.getByUserId(serverEntity.id, it) }
            ?: appPreferences.currentUserId?.let { userDao.getUser(it) }?.takeIf { it.serverId == serverEntity.id }
            ?: userDao.getAllForServer(serverEntity.id).firstOrNull()
            ?: return null

        return serverEntity to userEntity
    }

    private fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V) {
        if (key !in this) put(key, value)
    }

    private fun sameId(a: String?, b: String?) =
        a != null && b != null && a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)

    private fun loadTitles(): MutableMap<String, ManifestTitle> =
        titles ?: (readManifest()?.titles.orEmpty().associateByTo(mutableMapOf()) { it.id }).also { titles = it }

    private fun readManifest(): Manifest? {
        val root = storageManager.getStorageLocation() ?: return null
        val file = root.findFile(MANIFEST_FILE) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                json.decodeFromString(Manifest.serializer(), input.readBytes().decodeToString())
            }
        }.onFailure { err ->
            Timber.e(err, "Failed to read $MANIFEST_FILE")
        }.getOrNull()?.takeIf { it.format == FORMAT }
    }

    /** Writes a temporary file first, so a crash mid-write never leaves a broken manifest behind. */
    private fun writeText(root: DocumentFile, text: String) {
        val bytes = text.encodeToByteArray()
        val temp = root.findFile(TEMP_FILE) ?: root.createFile("", TEMP_FILE) ?: error("Unable to create $TEMP_FILE")
        context.contentResolver.openOutputStream(temp.uri, "wt")?.use { it.write(bytes) } ?: error("Unable to write $TEMP_FILE")

        root.findFile(MANIFEST_FILE)?.delete()
        if (!temp.renameTo(MANIFEST_FILE)) {
            // Some providers cannot rename: write in place instead.
            val target = root.findFile(MANIFEST_FILE) ?: root.createFile("", MANIFEST_FILE) ?: error("Unable to create $MANIFEST_FILE")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
            temp.delete()
        }
    }

    companion object {
        const val MANIFEST_FILE = "jellyfin-library.json"
        private const val TEMP_FILE = "jellyfin-library.json.tmp"
        const val FORMAT = "jellyfin-offline-library"
        private const val WRITE_DELAY_MS = 1500L

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
            allowSpecialFloatingPointValues = true
        }
    }

    @Serializable
    data class Manifest(
        @SerialName("Format") val format: String = FORMAT,
        @SerialName("Version") val version: Int = 1,
        @SerialName("Generator") val generator: String = "Jellyfin Android",
        @SerialName("Updated") val updated: String? = null,
        @SerialName("Servers") val servers: List<ManifestServer> = emptyList(),
        @SerialName("Titles") val titles: List<ManifestTitle> = emptyList(),
        @SerialName("Items") val items: List<ManifestItem> = emptyList(),
    )

    @Serializable
    data class ManifestServer(
        /** Jellyfin server id. */
        @SerialName("Id") val id: String? = null,
        @SerialName("Address") val address: String? = null,
        /** Jellyfin user id the downloads belong to. */
        @SerialName("UserId") val userId: String? = null,
    )

    /** A series, season or album. */
    @Serializable
    data class ManifestTitle(
        @SerialName("Id") val id: String,
        @SerialName("ServerId") val serverId: String? = null,
        @SerialName("Type") val type: String,
        @SerialName("Name") val name: String? = null,
        @SerialName("SeriesId") val seriesId: String? = null,
        @SerialName("SeriesName") val seriesName: String? = null,
        @SerialName("IndexNumber") val indexNumber: Int? = null,
        @SerialName("ProductionYear") val productionYear: Int? = null,
        @SerialName("Overview") val overview: String? = null,
        /** Folder of the title, relative to the storage location. */
        @SerialName("Folder") val folder: String? = null,
        /** Poster or cover, relative to the storage location. */
        @SerialName("Image") val image: String? = null,
        @SerialName("Item") val item: JsonElement? = null,
    )

    @Serializable
    data class ManifestItem(
        @SerialName("Id") val id: String,
        @SerialName("ServerId") val serverId: String? = null,
        @SerialName("Type") val type: String,
        @SerialName("MediaType") val mediaType: String? = null,
        @SerialName("Name") val name: String? = null,
        @SerialName("Folder") val folder: String,
        /** The main file, relative to the storage location. */
        @SerialName("File") val file: String,
        @SerialName("Image") val image: String? = null,
        @SerialName("Size") val size: Long = 0,
        @SerialName("SeriesId") val seriesId: String? = null,
        @SerialName("SeriesName") val seriesName: String? = null,
        @SerialName("SeasonId") val seasonId: String? = null,
        @SerialName("SeasonName") val seasonName: String? = null,
        @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
        @SerialName("IndexNumber") val indexNumber: Int? = null,
        @SerialName("IndexNumberEnd") val indexNumberEnd: Int? = null,
        @SerialName("AlbumId") val albumId: String? = null,
        @SerialName("Album") val album: String? = null,
        @SerialName("AlbumArtist") val albumArtist: String? = null,
        @SerialName("Artists") val artists: List<String>? = null,
        @SerialName("ProductionYear") val productionYear: Int? = null,
        @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
        @SerialName("Container") val container: String? = null,
        @SerialName("Overview") val overview: String? = null,
        @SerialName("Files") val files: List<ManifestFile> = emptyList(),
        /** The item as the server describes it (BaseItemDto). */
        @SerialName("Item") val item: JsonElement? = null,
    )

    @Serializable
    data class ManifestFile(
        /** ITEM, IMAGE_PRIMARY, IMAGE_SERIES, IMAGE_SEASON, SUBTITLE or AUDIO. */
        @SerialName("Type") val type: String,
        @SerialName("Path") val path: String,
        @SerialName("Size") val size: Long = 0,
    )
}
