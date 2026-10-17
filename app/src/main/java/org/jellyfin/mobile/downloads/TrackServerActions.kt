package org.jellyfin.mobile.downloads

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemUpdateApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.NameGuidPair
import java.io.File
import java.io.IOException
import java.util.UUID

/** What the server allows now: whether it answers, and whether the user may change items there (administrators). */
data class ServerAccess(val online: Boolean, val canEdit: Boolean)

/** A link that works for a while (the server's share links last 24 hours). */
data class TimedLink(val url: String, val expiresAt: String)

/**
 * What the music player does with a track on the server: share links (fork endpoint `Shares`), a link to the
 * audio itself, its file to send, and changing its details for everyone.
 */
class TrackServerActions(private val api: ApiClient, private val okHttpClient: OkHttpClient) {
    suspend fun access(): ServerAccess = withContext(Dispatchers.IO) {
        try {
            withTimeout(ACCESS_TIMEOUT_MS) {
                val user = api.userApi.getCurrentUser().content
                ServerAccess(online = true, canEdit = user.policy?.isAdministrator == true)
            }
        } catch (_: TimeoutCancellationException) {
            ServerAccess(online = false, canEdit = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No connection, signed out, the server refused
            ServerAccess(online = false, canEdit = false)
        }
    }

    /** A link to the track's page that anyone can open for 24 hours, as "Share" of the web client makes. */
    suspend fun shareLink(itemId: UUID): TimedLink = withContext(Dispatchers.IO) {
        val share = createShare(itemId)
        TimedLink("${baseUrl()}/web/#/share?code=${Uri.encode(share.code)}", share.expiresAt)
    }

    /**
     * A link to the audio file that plays anywhere (a browser, another player) for 24 hours. It is signed in
     * as the guest of a share link, who sees only this track: the user's own sign-in never leaves the phone.
     */
    suspend fun streamLink(itemId: UUID): TimedLink = withContext(Dispatchers.IO) {
        val share = createShare(itemId)
        // Without the app's sign-in headers: the guest gets a device of its own, the app's session stays
        val request = Request.Builder()
            .url("${baseUrl()}/Shares/Code/${Uri.encode(share.code)}/Session")
            .post(ByteArray(0).toRequestBody())
            .build()
        val session = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Opening the share link failed: ${response.code}")
            json.decodeFromString(ShareSession.serializer(), response.body?.string().orEmpty())
        }
        val token = session.authentication.accessToken
        TimedLink("${baseUrl()}/Audio/$itemId/stream?static=true&ApiKey=${Uri.encode(token)}", session.expiresAt)
    }

    /** Marks or unmarks the track as a favorite on the server. */
    suspend fun setFavorite(itemId: UUID, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (favorite) api.userLibraryApi.markFavoriteItem(itemId = itemId) else api.userLibraryApi.unmarkFavoriteItem(itemId = itemId)
        Unit
    }

    /**
     * The track's original file fetched into [directory] (emptied first: only the last shared track is kept),
     * named "Artist - Title.ext" for the app it is sent to.
     */
    suspend fun fetchFile(itemId: UUID, directory: File): File = withContext(Dispatchers.IO) {
        val item = api.userLibraryApi.getItem(itemId).content
        val extension = item.path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
            ?: item.container?.substringBefore(',')?.takeIf(String::isNotBlank)
            ?: "mp3"
        val artist = item.artists?.firstOrNull() ?: item.albumArtist
        val name = listOfNotNull(artist, item.name).joinToString(" - ").ifBlank { itemId.toString() }
        val fileName = "${name.replace(UNSAFE_CHARACTERS, "_").take(MAX_NAME)}.$extension"

        directory.deleteRecursively()
        directory.mkdirs()
        val target = File(directory, fileName)
        val partial = File(directory, "$fileName.part")
        val request = Request.Builder().url(api.libraryApi.getDownloadUrl(itemId)).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Fetching the track failed: ${response.code}")
            val body = response.body ?: throw IOException("Fetching the track failed: no data")
            body.byteStream().use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
        }
        if (!partial.renameTo(target)) throw IOException("Could not keep the fetched track")
        target
    }

    /** The track as the server has it, with everything an update has to send back. */
    suspend fun item(itemId: UUID): BaseItemDto = withContext(Dispatchers.IO) {
        api.userLibraryApi.getItem(itemId).content
    }

    /** Changes the set fields of [changes] on the server (everyone sees them); the item as it is now. */
    suspend fun edit(item: BaseItemDto, changes: MetadataFields): BaseItemDto = withContext(Dispatchers.IO) {
        val updated = changes.applyTo(item).let { edited ->
            edited.copy(
                // The server takes artists from these lists, not from the plain names
                artistItems = changes.artists?.let { names -> names.map { name -> pair(name, item.artistItems) } } ?: item.artistItems,
                albumArtists = changes.albumArtist?.let { name -> listOf(pair(name, item.albumArtists)) } ?: item.albumArtists,
            )
        }
        api.itemUpdateApi.updateItem(item.id, updated)
        updated
    }

    private fun pair(name: String, known: List<NameGuidPair>?) =
        known?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NameGuidPair(name = name, id = UUID(0L, 0L))

    private suspend fun createShare(itemId: UUID): ShareResponse =
        api.post<ShareResponse>("/Shares", requestBody = CreateShareRequest(itemId.toString())).content

    private fun baseUrl(): String = requireNotNull(api.baseUrl) { "No server" }.trimEnd('/')

    @Serializable
    private data class CreateShareRequest(@SerialName("ItemId") val itemId: String)

    @Serializable
    private data class ShareResponse(
        @SerialName("Code") val code: String,
        @SerialName("ExpiresAt") val expiresAt: String,
    )

    @Serializable
    private data class ShareSession(
        @SerialName("Authentication") val authentication: Authentication,
        @SerialName("ExpiresAt") val expiresAt: String,
    )

    @Serializable
    private data class Authentication(@SerialName("AccessToken") val accessToken: String)

    private companion object {
        const val ACCESS_TIMEOUT_MS = 4_000L
        const val MAX_EXTENSION = 5
        const val MAX_NAME = 120
        val UNSAFE_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
        val json = Json { ignoreUnknownKeys = true }
    }
}
