package org.jellyfin.mobile.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.MetadataFields
import org.jellyfin.sdk.model.api.MediaType

/**
 * "Edit details" of downloads, on the phone only. One download: all its fields; several (an album, a
 * selection): the fields they share - empty with "different" when they differ, a field left as it is is not
 * changed. Music has artist, album, numbers; videos the title and the year.
 */
@Composable
fun MetadataDialog(
    downloads: List<DownloadEntity>,
    edited: Boolean,
    onSave: (MetadataFields) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) = MetadataDialog(
    items = remember(downloads) { downloads.map { it.item } },
    edited = edited,
    onSave = onSave,
    onReset = onReset,
    onDismiss = onDismiss,
)

/**
 * The same for items as the server has them ([title] says where the changes go). The album of a track is
 * the album's own name on the server, so it is changed there with the album ([albumEditable] false).
 */
@Composable
fun MetadataDialog(
    items: List<org.jellyfin.sdk.model.api.BaseItemDto>,
    edited: Boolean,
    onSave: (MetadataFields) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    hint: String? = null,
    albumEditable: Boolean = true,
) {
    val single = items.size == 1
    val music = items.all { it.mediaType == MediaType.AUDIO }

    fun <T> common(value: (org.jellyfin.sdk.model.api.BaseItemDto) -> T?): T? = items.map(value).distinct().singleOrNull()

    val fields = remember(items) {
        buildList {
            if (single) add(Field(FieldKind.NAME, common { it.name }.orEmpty()))
            if (music) {
                add(Field(FieldKind.ARTISTS, common { it.artists?.joinToString(", ") }.orEmpty(), differs = common { it.artists?.joinToString(", ") } == null && !single))
                if (albumEditable) add(Field(FieldKind.ALBUM, common { it.album }.orEmpty(), differs = common { it.album } == null && !single))
                add(Field(FieldKind.ALBUM_ARTIST, common { it.albumArtist }.orEmpty(), differs = common { it.albumArtist } == null && !single))
                if (single) {
                    add(Field(FieldKind.TRACK, common { it.indexNumber }?.toString().orEmpty()))
                    add(Field(FieldKind.DISC, common { it.parentIndexNumber }?.toString().orEmpty()))
                }
            }
            add(Field(FieldKind.YEAR, common { it.productionYear }?.toString().orEmpty(), differs = common { it.productionYear } == null && !single))
        }
    }
    val values = remember(items) { fields.associate { it.kind to mutableStateOf(it.initial) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(if (single) R.string.metadata_edit_title else R.string.metadata_edit_title_many, items.size)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = hint ?: stringResource(R.string.metadata_edit_hint),
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (field in fields) {
                    var value by values.getValue(field.kind)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = if (field.kind.numeric) it.filter(Char::isDigit).take(4) else it },
                        label = { Text(stringResource(field.kind.label)) },
                        placeholder = { if (field.differs) Text(stringResource(R.string.metadata_edit_differs)) },
                        singleLine = true,
                        keyboardOptions = if (field.kind.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        },
        buttons = {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (edited) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.metadata_edit_reset)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.metadata_edit_cancel)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    // Only what was changed; an emptied field is left as it was
                    fun changed(kind: FieldKind): String? {
                        val field = fields.firstOrNull { it.kind == kind } ?: return null
                        val value = values.getValue(kind).value.trim()
                        return value.takeIf { it.isNotEmpty() && it != field.initial }
                    }
                    onSave(
                        MetadataFields(
                            name = changed(FieldKind.NAME),
                            artists = changed(FieldKind.ARTISTS)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty),
                            album = changed(FieldKind.ALBUM),
                            albumArtist = changed(FieldKind.ALBUM_ARTIST),
                            indexNumber = changed(FieldKind.TRACK)?.toIntOrNull(),
                            parentIndexNumber = changed(FieldKind.DISC)?.toIntOrNull(),
                            productionYear = changed(FieldKind.YEAR)?.toIntOrNull(),
                        ),
                    )
                }) { Text(stringResource(R.string.metadata_edit_save)) }
            }
        },
    )
}

private enum class FieldKind(val label: Int, val numeric: Boolean = false) {
    NAME(R.string.metadata_field_name),
    ARTISTS(R.string.metadata_field_artists),
    ALBUM(R.string.metadata_field_album),
    ALBUM_ARTIST(R.string.metadata_field_album_artist),
    TRACK(R.string.metadata_field_track, numeric = true),
    DISC(R.string.metadata_field_disc, numeric = true),
    YEAR(R.string.metadata_field_year, numeric = true),
}

private data class Field(val kind: FieldKind, val initial: String, val differs: Boolean = false)
