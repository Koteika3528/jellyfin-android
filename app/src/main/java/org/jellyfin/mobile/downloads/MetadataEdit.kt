package org.jellyfin.mobile.downloads

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * What a download is shown as, the fields that can be changed on the phone: null - not set (for edits:
 * left as it is). Edits stay on the phone only, the server keeps its own.
 */
@Serializable
data class MetadataFields(
    val name: String? = null,
    val artists: List<String>? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val productionYear: Int? = null,
) {
    val isEmpty: Boolean get() = this == MetadataFields()

    /** The fields of [other] that are set win. */
    fun merge(other: MetadataFields) = MetadataFields(
        name = other.name ?: name,
        artists = other.artists ?: artists,
        album = other.album ?: album,
        albumArtist = other.albumArtist ?: albumArtist,
        indexNumber = other.indexNumber ?: indexNumber,
        parentIndexNumber = other.parentIndexNumber ?: parentIndexNumber,
        productionYear = other.productionYear ?: productionYear,
    )

    /** The item with these fields (those that are set). */
    fun applyTo(item: BaseItemDto): BaseItemDto = item.copy(
        name = name ?: item.name,
        artists = artists ?: item.artists,
        album = album ?: item.album,
        albumArtist = albumArtist ?: item.albumArtist,
        indexNumber = indexNumber ?: item.indexNumber,
        parentIndexNumber = parentIndexNumber ?: item.parentIndexNumber,
        productionYear = productionYear ?: item.productionYear,
    )

    companion object {
        fun of(item: BaseItemDto) = MetadataFields(
            name = item.name,
            artists = item.artists,
            album = item.album,
            albumArtist = item.albumArtist,
            indexNumber = item.indexNumber,
            parentIndexNumber = item.parentIndexNumber,
            productionYear = item.productionYear,
        )
    }
}

/**
 * Edits of a download made on the phone, with what the server says ([original]): the edits are put over
 * every update from the server, and "as on the server" works without a connection.
 */
@Serializable
data class MetadataOverride(val original: MetadataFields, val edited: MetadataFields) {
    /** The server's item as edited. */
    fun applyTo(item: BaseItemDto): BaseItemDto = edited.applyTo(item)

    /** The item as the server has it, without the edits. */
    fun restore(item: BaseItemDto): BaseItemDto = item.copy(
        name = original.name,
        artists = original.artists,
        album = original.album,
        albumArtist = original.albumArtist,
        indexNumber = original.indexNumber,
        parentIndexNumber = original.parentIndexNumber,
        productionYear = original.productionYear,
    )
}
