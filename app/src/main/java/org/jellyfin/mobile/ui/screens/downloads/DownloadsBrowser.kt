package org.jellyfin.mobile.ui.screens.downloads

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import org.jellyfin.mobile.downloads.OfflinePlayback
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Tab
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.OfflineEntry
import org.jellyfin.mobile.downloads.OfflineGroup
import org.jellyfin.mobile.downloads.OfflineIndex
import java.util.Locale

/** Kinds of cards inside a library, like the tabs of the web client. */
enum class GroupKind { ALBUM, ARTIST, GENRE, SERIES }

/** Where the downloads screen is: the list of libraries, a library, or an album, artist, genre or series in it. */
sealed interface BrowseLocation {
    data object Root : BrowseLocation
    data class Library(val name: String) : BrowseLocation
    data class Group(val library: String, val kind: GroupKind, val key: String, val title: String) : BrowseLocation
}

/** What the browser can ask the screen to do. */
class BrowseActions(
    val navigate: (BrowseLocation) -> Unit,
    val open: (DownloadEntity) -> Unit,
    val retry: (DownloadEntity) -> Unit,
    val playTracks: (List<OfflineEntry>, Int, Boolean) -> Unit,
    val removeAll: (List<DownloadEntity>) -> Unit,
    val toggleSelection: (DownloadEntity) -> Unit,
    val progress: (DownloadEntity) -> OfflinePlayback.Progress? = { null },
    val openFromStart: (DownloadEntity) -> Unit = open,
    val isFavorite: (DownloadEntity) -> Boolean = { false },
    val toggleFavorite: (DownloadEntity) -> Unit = {},
)

@Composable
fun DownloadsBrowser(
    index: OfflineIndex,
    location: BrowseLocation,
    searchQuery: String,
    actions: BrowseActions,
    selection: Set<Long>,
    modifier: Modifier = Modifier,
) {
    when {
        searchQuery.isNotBlank() -> EntryList(index.search(searchQuery), actions, selection, modifier, showLibrary = true)
        location is BrowseLocation.Root -> RootView(index, actions, selection, modifier)
        location is BrowseLocation.Library -> LibraryView(index, location.name, actions, selection, modifier)
        location is BrowseLocation.Group -> GroupView(index, location, actions, selection, modifier)
    }
}

// ─────────────────────────── Libraries ───────────────────────────

@Composable
private fun RootView(index: OfflineIndex, actions: BrowseActions, selection: Set<Long>, modifier: Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (index.active.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.downloads_active)) }
            items(index.active, key = { "active-" + it.download.id }) { files ->
                DownloadItem(
                    downloadFiles = files,
                    onOpen = { actions.open(files.download) },
                    onDownload = { actions.retry(files.download) },
                    onToggleSelection = { actions.toggleSelection(files.download) },
                    isSelected = files.download.id in selection,
                    selectionMode = selection.isNotEmpty(),
                )
            }
            item { SectionHeader(stringResource(R.string.downloads_libraries)) }
        }
        items(index.libraries, key = { "library-$it" }) { library ->
            val entries = remember(index, library) { index.inLibrary(library) }
            LibraryRow(
                name = library,
                image = entries.firstNotNullOfOrNull { it.seriesImage ?: it.image },
                count = entries.size,
                size = entries.sumOf { it.size },
                onClick = { actions.navigate(BrowseLocation.Library(library)) },
            )
        }
    }
}

@Composable
private fun LibraryRow(name: String, image: Uri?, count: Int, size: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(image, Modifier.size(64.dp))
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = name, style = MaterialTheme.typography.h6, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = pluralStringResource(R.plurals.downloads_items_count, count, count) +
                    " · " + Formatter.formatShortFileSize(context, size),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

// ─────────────────────────── One library ───────────────────────────

private enum class LibraryTab(val title: Int) {
    ALBUMS(R.string.downloads_tab_albums),
    ARTISTS(R.string.downloads_tab_artists),
    GENRES(R.string.downloads_tab_genres),
    TRACKS(R.string.downloads_tab_tracks),
    SERIES(R.string.downloads_tab_series),
    MOVIES(R.string.downloads_tab_movies),
}

@Composable
private fun LibraryView(index: OfflineIndex, library: String, actions: BrowseActions, selection: Set<Long>, modifier: Modifier) {
    val entries = remember(index, library) { index.inLibrary(library) }
    val hasMusic = entries.any { it.isAudio }
    val hasSeries = entries.any { it.isEpisode }
    val hasMovies = entries.any { !it.isAudio && !it.isEpisode }
    val tabs = buildList {
        if (hasMusic) addAll(listOf(LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.GENRES, LibraryTab.TRACKS))
        if (hasSeries) add(LibraryTab.SERIES)
        if (hasMovies) add(LibraryTab.MOVIES)
        if (!hasMusic && (hasSeries || hasMovies)) add(LibraryTab.GENRES)
    }
    var selected by rememberSaveable(library) { mutableIntStateOf(0) }
    val tab = tabs.getOrNull(selected) ?: tabs.firstOrNull() ?: return

    val unknownAlbum = stringResource(R.string.downloads_unknown_album)
    val unknownArtist = stringResource(R.string.downloads_unknown_artist)
    val noGenre = stringResource(R.string.downloads_no_genre)

    Column(modifier = modifier.fillMaxSize()) {
        if (tabs.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(tab),
                backgroundColor = MaterialTheme.colors.background,
                edgePadding = 8.dp,
            ) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = t == tab, onClick = { selected = i }, text = { Text(stringResource(t.title)) })
                }
            }
        }

        when (tab) {
            LibraryTab.ALBUMS -> GroupGrid(
                groups = remember(entries) { index.albums(entries, unknownAlbum) },
                square = true,
                onOpen = { actions.navigate(BrowseLocation.Group(library, GroupKind.ALBUM, it.key, it.title)) },
            )
            LibraryTab.ARTISTS -> GroupGrid(
                groups = remember(entries) { index.artists(entries, unknownArtist) },
                square = true,
                onOpen = { actions.navigate(BrowseLocation.Group(library, GroupKind.ARTIST, it.key, it.title)) },
            )
            LibraryTab.GENRES -> GroupGrid(
                groups = remember(entries) { index.genres(entries, noGenre) },
                square = hasMusic,
                onOpen = { actions.navigate(BrowseLocation.Group(library, GroupKind.GENRE, it.key, it.title)) },
            )
            LibraryTab.TRACKS -> {
                val tracks = remember(entries) { index.tracks(entries) }
                EntryList(tracks, actions, selection, Modifier.weight(1f), playAs = tracks)
            }
            LibraryTab.SERIES -> GroupGrid(
                groups = remember(entries) { index.series(entries) },
                square = false,
                onOpen = { actions.navigate(BrowseLocation.Group(library, GroupKind.SERIES, it.key, it.title)) },
            )
            LibraryTab.MOVIES -> PosterGrid(
                cards = remember(entries) { index.movies(entries) }.map { entry ->
                    val progress = actions.progress(entry.download)
                    Card(
                        id = entry.download.id.toString(),
                        title = entry.item.name.orEmpty(),
                        subtitle = entry.item.productionYear?.toString(),
                        image = entry.image,
                        progress = progress?.takeIf { !it.played }?.percent,
                        played = progress?.played == true,
                        onLongClick = { actions.openFromStart(entry.download) },
                    ) {
                        actions.open(entry.download)
                    }
                },
                square = false,
            )
        }
    }
}

private class Card(
    val id: String,
    val title: String,
    val subtitle: String?,
    val image: Uri?,
    val progress: Float? = null,
    val played: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

@Composable
private fun GroupGrid(groups: List<OfflineGroup>, square: Boolean, onOpen: (OfflineGroup) -> Unit) {
    PosterGrid(
        cards = groups.map { group ->
            Card(group.key, group.title, group.subtitle ?: pluralStringResource(R.plurals.downloads_items_count, group.entries.size, group.entries.size), group.image) {
                onOpen(group)
            }
        },
        square = square,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterGrid(cards: List<Card>, square: Boolean) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (square) 140.dp else 120.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(cards, key = { it.id }) { card ->
            Column(modifier = Modifier.combinedClickable(onClick = card.onClick, onLongClick = card.onLongClick)) {
                Box {
                    Cover(
                        image = card.image,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (square) 1f else 2f / 3f),
                    )
                    WatchedMarks(
                        progress = card.progress,
                        played = card.played,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.subtitle2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                card.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Album, artist, genre, series ───────────────────────────

@Composable
private fun GroupView(index: OfflineIndex, location: BrowseLocation.Group, actions: BrowseActions, selection: Set<Long>, modifier: Modifier) {
    val unknownAlbum = stringResource(R.string.downloads_unknown_album)
    val unknownArtist = stringResource(R.string.downloads_unknown_artist)
    val noGenre = stringResource(R.string.downloads_no_genre)
    val group = remember(index, location) {
        val entries = index.inLibrary(location.library)
        when (location.kind) {
            GroupKind.ALBUM -> index.albums(entries, unknownAlbum)
            GroupKind.ARTIST -> index.artists(entries, unknownArtist)
            GroupKind.GENRE -> index.genres(entries, noGenre)
            GroupKind.SERIES -> index.series(entries)
        }.firstOrNull { it.key == location.key }
    }
    if (group == null) {
        DownloadsEmpty(modifier = modifier)
        return
    }

    val entries = group.entries
    val music = entries.all { it.isAudio }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            val next = if (location.kind == GroupKind.SERIES) nextEpisode(entries, actions) else null
            GroupHeader(
                group = group,
                square = music,
                showPlay = music,
                onPlay = { shuffle -> actions.playTracks(entries, 0, shuffle) },
                onRemove = { actions.removeAll(entries.map { it.download }) },
                watch = next?.let { (entry, resume) ->
                    val label = episodeLabel(entry)
                    (if (resume) stringResource(R.string.downloads_continue, label) else stringResource(R.string.downloads_watch, label)) to {
                        actions.open(entry.download)
                    }
                },
            )
        }

        if (location.kind == GroupKind.SERIES) {
            entries.groupBy { it.item.parentIndexNumber ?: -1 }.forEach { (season, episodes) ->
                item(key = "season-$season") {
                    SectionHeader(
                        episodes.first().item.seasonName ?: when (season) {
                            0 -> stringResource(R.string.downloads_specials)
                            -1 -> stringResource(R.string.downloads_tab_other)
                            else -> stringResource(R.string.downloads_season, season)
                        },
                    )
                }
                items(episodes, key = { it.download.id }) { entry ->
                    EntryRow(entry, actions, selection, numbered = true, onClick = { actions.open(entry.download) })
                }
            }
        } else {
            // Artist and genre pages list tracks by album, an album lists them by disc and number
            val showAlbum = location.kind != GroupKind.ALBUM
            items(entries, key = { it.download.id }) { entry ->
                EntryRow(
                    entry = entry,
                    actions = actions,
                    selection = selection,
                    numbered = !showAlbum,
                    showAlbum = showAlbum,
                    onClick = {
                        if (music) actions.playTracks(entries, entries.indexOf(entry), false) else actions.open(entry.download)
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: OfflineGroup,
    square: Boolean,
    showPlay: Boolean,
    onPlay: (Boolean) -> Unit,
    onRemove: () -> Unit,
    watch: Pair<String, () -> Unit>? = null,
) {
    val context = LocalContext.current
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(group.image, Modifier.width(120.dp).aspectRatio(if (square) 1f else 2f / 3f))
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(text = group.title, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            group.subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
            }
            Text(
                text = pluralStringResource(R.plurals.downloads_items_count, group.entries.size, group.entries.size) +
                    " · " + Formatter.formatShortFileSize(context, group.entries.sumOf { it.size }),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                watch?.let { (label, onWatch) ->
                    Button(onClick = onWatch) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (showPlay) {
                    Button(onClick = { onPlay(false) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.downloads_play_all))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onPlay(true) }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = stringResource(R.string.downloads_shuffle))
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.downloads_remove_group))
                }
            }
        }
    }
}

// ─────────────────────────── Rows ───────────────────────────

@Composable
private fun EntryList(
    entries: List<OfflineEntry>,
    actions: BrowseActions,
    selection: Set<Long>,
    modifier: Modifier,
    showLibrary: Boolean = false,
    playAs: List<OfflineEntry>? = null,
) {
    if (entries.isEmpty()) {
        DownloadsEmpty(modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(entries, key = { it.download.id }) { entry ->
            EntryRow(
                entry = entry,
                actions = actions,
                selection = selection,
                showAlbum = true,
                showLibrary = showLibrary,
                onClick = {
                    val queue = playAs ?: entries.filter { it.isAudio }
                    if (entry.isAudio) actions.playTracks(queue, queue.indexOf(entry).coerceAtLeast(0), false) else actions.open(entry.download)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: OfflineEntry,
    actions: BrowseActions,
    selection: Set<Long>,
    onClick: () -> Unit,
    numbered: Boolean = false,
    showAlbum: Boolean = false,
    showLibrary: Boolean = false,
) {
    val item = entry.item
    val selected = entry.download.id in selection
    val selectionMode = selection.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) actions.toggleSelection(entry.download) else onClick() },
                onLongClick = { actions.toggleSelection(entry.download) },
            )
            .background(if (selected) MaterialTheme.colors.primary.copy(alpha = 0.15f) else MaterialTheme.colors.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        }
        if (numbered && entry.isAudio) {
            Text(
                text = item.indexNumber?.toString().orEmpty(),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.width(32.dp),
            )
        } else {
            Cover(entry.image, Modifier.size(width = if (entry.isAudio) 48.dp else 80.dp, height = 48.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            val title = if (entry.isEpisode && item.indexNumber != null) "${item.indexNumber}. ${item.name.orEmpty()}" else item.name.orEmpty()
            Text(text = title, style = MaterialTheme.typography.body1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                if (showAlbum && entry.isAudio) {
                    entry.artist?.let(::add)
                    item.album?.let(::add)
                }
                if (showAlbum && entry.isEpisode) item.seriesName?.let(::add)
                if (showLibrary) add(entry.library)
            }.filter { it.isNotBlank() }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.isAudio) {
                val progress = actions.progress(entry.download)
                val percent = progress?.percent
                if (progress?.played != true && percent != null) {
                    LinearProgressIndicator(
                        progress = percent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp),
                    )
                }
            }
        }
        if (!entry.isAudio && actions.progress(entry.download)?.played == true) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.downloads_played),
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.padding(start = 8.dp).size(20.dp),
            )
        }
        if (entry.isAudio) {
            val favorite = actions.isFavorite(entry.download)
            IconButton(onClick = { actions.toggleFavorite(entry.download) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(R.string.downloads_favorite),
                    tint = if (favorite) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        item.runTimeTicks?.let { ticks ->
            Text(
                text = formatDuration(ticks / 10_000_000),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** A thin progress line at the bottom of a cover, or a check when finished. */
@Composable
private fun WatchedMarks(progress: Float?, played: Boolean, modifier: Modifier) {
    Box(modifier = modifier) {
        if (played) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.downloads_played),
                tint = MaterialTheme.colors.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colors.background, RoundedCornerShape(50))
                    .size(22.dp),
            )
        } else if (progress != null) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

/** The episode to continue with: one left midway, or the one after the last finished, or the first. */
private fun nextEpisode(episodes: List<OfflineEntry>, actions: BrowseActions): Pair<OfflineEntry, Boolean>? {
    if (episodes.isEmpty()) return null
    val progress = episodes.map { actions.progress(it.download) }
    val lastPlayed = progress.indexOfLast { it?.played == true }
    val candidates = episodes.indices.drop(lastPlayed + 1).ifEmpty { episodes.indices.toList() }
    val index = candidates.firstOrNull { progress[it]?.played != true } ?: candidates.first()
    val resume = progress[index]?.let { !it.played && it.positionTicks > 0 } == true
    return episodes[index] to resume
}

private fun episodeLabel(entry: OfflineEntry): String {
    val season = entry.item.parentIndexNumber
    val episode = entry.item.indexNumber
    return when {
        season != null && episode != null -> "S$season:E$episode"
        episode != null -> "E$episode"
        else -> entry.item.name.orEmpty()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.subtitle1,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Cover(image: Uri?, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = image,
            placeholder = painterResource(R.drawable.ic_local_movies_white_64),
            fallback = painterResource(R.drawable.ic_local_movies_white_64),
            error = painterResource(R.drawable.ic_local_movies_white_64),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
