package org.jellyfin.mobile.downloads

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Small state of the downloads kept beside the database: followed shows (new episodes are downloaded
 * for them, see [OfflineMaintenance]), favorite marks set without the server, not sent yet, and how
 * videos are downloaded ([DownloadOptions]).
 */
class OfflineStore(context: Context) {
    private val prefs = context.getSharedPreferences("offline_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Follow(val seriesId: String, val serverId: Long, val userId: Long, val name: String? = null)

    private val _follows = MutableStateFlow(readFollows())
    val follows: StateFlow<List<Follow>> = _follows.asStateFlow()

    private val _favorites = MutableStateFlow(readFavorites())

    /** Favorite marks not sent yet, by item id. */
    val pendingFavorites: StateFlow<Map<String, Boolean>> = _favorites.asStateFlow()

    fun isFollowed(seriesId: UUID) = _follows.value.any { it.seriesId == seriesId.toString() }

    @Synchronized
    fun setFollowed(seriesId: UUID, serverId: Long, userId: Long, name: String?, followed: Boolean) {
        val others = _follows.value.filter { it.seriesId != seriesId.toString() }
        val updated = if (followed) others + Follow(seriesId.toString(), serverId, userId, name) else others
        prefs.edit { putString(KEY_FOLLOWS, json.encodeToString(ListSerializer(Follow.serializer()), updated)) }
        _follows.value = updated
    }

    @Synchronized
    fun setPendingFavorite(itemId: UUID, favorite: Boolean) = writeFavorites(_favorites.value + (itemId.toString() to favorite))

    /** The mark was sent, unless it changed again meanwhile. */
    @Synchronized
    fun clearPendingFavorite(itemId: UUID, sent: Boolean) {
        if (_favorites.value[itemId.toString()] == sent) writeFavorites(_favorites.value - itemId.toString())
    }

    private val _options = MutableStateFlow(readOptions())

    /** How each video not downloaded as the original file is made, by item id (see [DownloadOptions]). */
    fun optionsOf(itemId: UUID): DownloadOptions = _options.value[itemId.toString()] ?: DownloadOptions()

    @Synchronized
    fun setOptions(itemId: UUID, options: DownloadOptions) {
        val updated = if (options.isOriginal) _options.value - itemId.toString() else _options.value + (itemId.toString() to options)
        if (updated == _options.value) return
        prefs.edit { putString(KEY_OPTIONS, json.encodeToString(optionsSerializer, updated)) }
        _options.value = updated
    }

    private val _overrides = MutableStateFlow(readOverrides())

    /** Edits of downloads made on the phone, by item id (see [MetadataOverride]). */
    fun overrideOf(itemId: UUID): MetadataOverride? = _overrides.value[itemId.toString()]

    @Synchronized
    fun setOverride(itemId: UUID, override: MetadataOverride?) {
        val updated = if (override == null || override.edited.isEmpty) _overrides.value - itemId.toString() else _overrides.value + (itemId.toString() to override)
        if (updated == _overrides.value) return
        prefs.edit { putString(KEY_OVERRIDES, json.encodeToString(overridesSerializer, updated)) }
        _overrides.value = updated
    }

    /** What was last offered with "ask first", so the same offer does not come back on every start. */
    var lastOffer: String?
        get() = prefs.getString(KEY_LAST_OFFER, null)
        set(value) = prefs.edit { putString(KEY_LAST_OFFER, value) }

    private fun writeFavorites(map: Map<String, Boolean>) {
        prefs.edit { putString(KEY_FAVORITES, json.encodeToString(MapSerializer(String.serializer(), Boolean.serializer()), map)) }
        _favorites.value = map
    }

    private fun readFollows(): List<Follow> = runCatching {
        prefs.getString(KEY_FOLLOWS, null)?.let { json.decodeFromString(ListSerializer(Follow.serializer()), it) }
    }.getOrNull().orEmpty()

    private fun readFavorites(): Map<String, Boolean> = runCatching {
        prefs.getString(KEY_FAVORITES, null)?.let { json.decodeFromString(MapSerializer(String.serializer(), Boolean.serializer()), it) }
    }.getOrNull().orEmpty()

    private fun readOverrides(): Map<String, MetadataOverride> = runCatching {
        prefs.getString(KEY_OVERRIDES, null)?.let { json.decodeFromString(overridesSerializer, it) }
    }.getOrNull().orEmpty()

    private fun readOptions(): Map<String, DownloadOptions> = runCatching {
        prefs.getString(KEY_OPTIONS, null)?.let { json.decodeFromString(optionsSerializer, it) }
    }.getOrNull().orEmpty()

    companion object {
        private const val KEY_OPTIONS = "download_options"
        private const val KEY_OVERRIDES = "metadata_overrides"
        private val overridesSerializer = MapSerializer(String.serializer(), MetadataOverride.serializer())
        private val optionsSerializer = MapSerializer(String.serializer(), DownloadOptions.serializer())
        private const val KEY_FOLLOWS = "follows"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_LAST_OFFER = "last_offer"
    }
}
