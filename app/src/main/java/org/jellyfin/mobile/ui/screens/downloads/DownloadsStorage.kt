package org.jellyfin.mobile.ui.screens.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TriStateCheckbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.OfflineEntry
import org.jellyfin.mobile.downloads.OfflineIndex

/** What the downloads take: in all, against the free space and the limit, per library; and what can go. */
@Composable
fun StorageView(
    index: OfflineIndex,
    actions: BrowseActions,
    availableBytes: Long?,
    limitBytes: Long,
    onCleanup: (List<OfflineEntry>, Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val total = index.entries.sumOf { it.size }
    val watched = index.entries.filter { !it.isAudio && actions.progress(it.download)?.played == true }
    val largest = remember(index) {
        val entries = index.entries
        (index.series(entries) + index.albums(entries, "")).map { it.title to it.entries } +
            index.movies(entries).map { it.item.name.orEmpty() to listOf(it) }
    }.sortedByDescending { (_, entries) -> entries.sumOf { it.size } }.take(LARGEST_COUNT)

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.downloads_storage_used, size(total)), style = MaterialTheme.typography.h6)
                    availableBytes?.let {
                        Text(stringResource(R.string.downloads_storage_free, size(it)), style = MaterialTheme.typography.body2)
                    }
                    if (limitBytes > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = (total.toFloat() / limitBytes).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = if (total > limitBytes) MaterialTheme.colors.error else MaterialTheme.colors.primary,
                        )
                        Text(
                            text = if (total > limitBytes) stringResource(R.string.downloads_storage_over_limit) else stringResource(R.string.downloads_storage_limit, size(limitBytes)),
                            style = MaterialTheme.typography.caption,
                            color = if (total > limitBytes) MaterialTheme.colors.error else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (watched.isEmpty()) stringResource(R.string.downloads_storage_watched_none)
                        else stringResource(R.string.downloads_storage_watched, size(watched.sumOf { it.size })),
                        style = MaterialTheme.typography.subtitle1,
                        modifier = Modifier.weight(1f),
                    )
                    if (watched.isNotEmpty()) {
                        Button(onClick = { onCleanup(watched, watched.map { it.download.id }.toSet()) }) {
                            Text(stringResource(R.string.downloads_storage_free_up))
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = { onCleanup(index.entries, emptySet()) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.downloads_storage_choose))
            }
        }

        item { Text(stringResource(R.string.downloads_libraries), style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold) }
        items(index.libraries) { library ->
            val bytes = index.inLibrary(library).sumOf { it.size }
            SizeRow(library, size(bytes), if (total > 0) bytes.toFloat() / total else 0f) {
                actions.navigate(BrowseLocation.Library(library))
            }
        }

        if (largest.isNotEmpty()) {
            item { Text(stringResource(R.string.downloads_storage_largest), style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold) }
            items(largest) { (title, entries) ->
                val bytes = entries.sumOf { it.size }
                SizeRow(title, size(bytes), if (total > 0) bytes.toFloat() / total else 0f) {
                    onCleanup(entries, emptySet())
                }
            }
        }
    }
}

@Composable
private fun SizeRow(title: String, size: String, share: Float, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Row {
            Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(size, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
        }
        LinearProgressIndicator(progress = share.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp))
    }
}

/**
 * Choosing downloads to delete, like Google Files: shows and albums are groups with one checkbox for
 * all of them (and their own for each episode or track), movies and the rest follow one by one.
 */
@Composable
fun CleanupDialog(
    entries: List<OfflineEntry>,
    preselected: Set<Long>,
    onConfirm: (List<DownloadEntity>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val selected = remember(entries) { mutableStateSetOf<Long>().apply { addAll(preselected) } }
    val expanded = remember(entries) { mutableStateSetOf<String>() }
    val otherTitle = stringResource(R.string.downloads_cleanup_other)
    val groups = remember(entries, otherTitle) {
        val (grouped, single) = entries.partition { it.isEpisode || it.isAudio }
        grouped.groupBy { entry ->
            if (entry.isEpisode) "series:" + (entry.item.seriesId ?: entry.item.seriesName) else "album:" + (entry.item.albumId ?: entry.item.album)
        }.map { (key, items) ->
            val first = items.first()
            Triple(key, (if (first.isEpisode) first.item.seriesName else first.item.album) ?: first.item.name.orEmpty(), items)
        }.sortedBy { it.second.lowercase() } + single.map { Triple("item:" + it.download.id, it.item.name.orEmpty(), listOf(it)) }
    }
    val selectedEntries = entries.filter { it.download.id in selected }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Text(
                    text = stringResource(R.string.downloads_cleanup_title),
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(16.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    var otherHeaderShown = false
                    for ((key, title, items) in groups) {
                        if (items.size == 1 && key.startsWith("item:")) {
                            if (!otherHeaderShown) {
                                otherHeaderShown = true
                                item(key = "other-header") {
                                    Text(otherTitle, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                                }
                            }
                            val entry = items.first()
                            item(key = key) {
                                CleanupRow(entry, entry.download.id in selected, indent = false) { checked ->
                                    if (checked) selected.add(entry.download.id) else selected.remove(entry.download.id)
                                }
                            }
                            continue
                        }

                        val ids = items.map { it.download.id }
                        val checkedCount = ids.count { it in selected }
                        item(key = key) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { if (key in expanded) expanded.remove(key) else expanded.add(key) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TriStateCheckbox(
                                    state = when (checkedCount) {
                                        0 -> ToggleableState.Off
                                        ids.size -> ToggleableState.On
                                        else -> ToggleableState.Indeterminate
                                    },
                                    onClick = { if (checkedCount == ids.size) selected.removeAll(ids.toSet()) else selected.addAll(ids) },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "$checkedCount / ${ids.size} · " + Formatter.formatShortFileSize(context, items.sumOf { it.size }),
                                        style = MaterialTheme.typography.caption,
                                    )
                                }
                                Icon(if (key in expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            }
                        }
                        if (key in expanded) {
                            items(items, key = { "$key-${it.download.id}" }) { entry ->
                                CleanupRow(entry, entry.download.id in selected, indent = true) { checked ->
                                    if (checked) selected.add(entry.download.id) else selected.remove(entry.download.id)
                                }
                            }
                        }
                    }
                }
                Divider()
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.downloads_cleanup_selected, Formatter.formatShortFileSize(context, selectedEntries.sumOf { it.size })),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.downloads_offer_later)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(selectedEntries.map { it.download }) }, enabled = selectedEntries.isNotEmpty()) {
                        Text(stringResource(R.string.downloads_offer_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupRow(entry: OfflineEntry, checked: Boolean, indent: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val item = entry.item
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = if (indent) 40.dp else 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        val number = if (entry.isEpisode) listOfNotNull(item.parentIndexNumber?.let { "S$it" }, item.indexNumber?.let { "E$it" }).joinToString(":") else ""
        Text(
            text = listOf(number, item.name.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(Formatter.formatShortFileSize(context, entry.size), style = MaterialTheme.typography.caption)
    }
}

private const val LARGEST_COUNT = 10
