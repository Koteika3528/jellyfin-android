package org.jellyfin.mobile.ui.screens.downloads

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadsViewModel
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.LaunchedEffect
import org.jellyfin.mobile.downloads.OfflineEntry
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.downloads.OfflineIndex
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = viewModel(),
    onBackPressed: () -> Unit = {},
    offlineReason: OfflineReason? = null,
    onRetryConnection: () -> Unit = {},
    startInStorage: Boolean = false,
    openCleanup: Boolean = false,
    onOpenNowPlaying: () -> Unit = {},
) {
    val downloads by viewModel.downloads.collectAsState()
    val waitingForWifi by viewModel.waitingForWifi.collectAsState()
    val storageLocation by viewModel.storageLocation.collectAsState()
    val storageLocationAccessible by viewModel.storageLocationAccessible.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val playStates by viewModel.playStates.collectAsState()
    val pendingFavorites by viewModel.pendingFavorites.collectAsState()
    val selection = remember { mutableStateSetOf<Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // Downloads whose details are being edited (on the phone)
    var metadataTargets by remember { mutableStateOf<List<DownloadEntity>?>(null) }

    // Browsing like the web client: libraries, then albums, artists, genres or series, then their downloads
    val locations = remember {
        mutableStateListOf<BrowseLocation>(BrowseLocation.Root).apply { if (startInStorage) add(BrowseLocation.Storage) }
    }
    val follows by viewModel.follows.collectAsState()
    val availableBytes by viewModel.availableBytes.collectAsState()
    var cleanup by remember { mutableStateOf<Pair<List<OfflineEntry>, Set<Long>>?>(null) }
    var cleanupOffered by rememberSaveable { mutableStateOf(!openCleanup) }
    val location = locations.last()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<List<DownloadEntity>?>(null) }

    val musicLibrary = stringResource(R.string.downloads_library_music)
    val moviesLibrary = stringResource(R.string.downloads_library_movies)
    val showsLibrary = stringResource(R.string.downloads_library_shows)
    val otherLibrary = stringResource(R.string.downloads_library_other)
    val index = remember(downloads) {
        OfflineIndex(downloads) { item ->
            when {
                item.mediaType == MediaType.AUDIO -> musicLibrary
                item.type == BaseItemKind.EPISODE -> showsLibrary
                item.type == BaseItemKind.MOVIE -> moviesLibrary
                else -> otherLibrary
            }
        }
    }

    val selectionMode = selection.isNotEmpty()

    LaunchedEffect(location) {
        if (location is BrowseLocation.Storage) viewModel.refreshAvailableBytes()
    }

    // Opened from "Choose…" of the watched offer: the deletion dialog with the watched downloads
    LaunchedEffect(index, playStates) {
        if (!cleanupOffered && index.entries.isNotEmpty()) {
            cleanupOffered = true
            val watched = index.entries.filter { !it.isAudio && viewModel.progressOf(it.download, playStates)?.played == true }
            if (watched.isNotEmpty()) cleanup = watched to watched.map { it.download.id }.toSet()
        }
    }

    cleanup?.let { (entries, preselected) ->
        CleanupDialog(
            entries = entries,
            preselected = preselected,
            onConfirm = { chosen ->
                viewModel.deleteDownloads(chosen)
                cleanup = null
            },
            onDismiss = { cleanup = null },
        )
    }

    fun goBack() {
        when {
            searchOpen -> {
                searchOpen = false
                searchQuery = ""
            }
            locations.size > 1 -> locations.removeAt(locations.lastIndex)
            else -> onBackPressed()
        }
    }

    BackHandler(enabled = !selectionMode && (searchOpen || locations.size > 1)) {
        goBack()
    }

    pendingRemoval?.let { toRemove ->
        DownloadRemoveDialog(
            downloads = toRemove,
            onConfirm = { keepLocalFiles ->
                toRemove.forEach { download -> viewModel.removeDownload(download, deleteFiles = !keepLocalFiles) }
                pendingRemoval = null
                if (locations.size > 1) locations.removeAt(locations.lastIndex)
            },
            onDismiss = { pendingRemoval = null },
        )
    }

    val storageLocationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.changeStorageLocation(uri)
    }

    BackHandler(enabled = selectionMode) {
        selection.clear()
    }

    metadataTargets?.let { targets ->
        MetadataDialog(
            downloads = targets,
            edited = viewModel.isEdited(targets),
            onSave = { changes ->
                viewModel.editMetadata(targets, changes)
                metadataTargets = null
                selection.clear()
            },
            onReset = {
                viewModel.resetMetadata(targets)
                metadataTargets = null
                selection.clear()
            },
            onDismiss = { metadataTargets = null },
        )
    }

    if (showDeleteConfirm) {
        val downloadsToRemove = remember(selection, downloads) {
            downloads
                .map { it.download }
                .filter { it.id in selection }
        }
        DownloadRemoveDialog(
            downloads = downloadsToRemove,
            onConfirm = { keepLocalFiles ->
                downloadsToRemove.forEach { download ->
                    viewModel.removeDownload(download, deleteFiles = !keepLocalFiles)
                }
                selection.clear()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = selectionMode,
                        transitionSpec = {
                            if (targetState) {
                                (slideInVertically { height -> height } + fadeIn()) togetherWith
                                    (slideOutVertically { height -> -height } + fadeOut())
                            } else {
                                (slideInVertically { height -> -height } + fadeIn()) togetherWith
                                    (slideOutVertically { height -> height } + fadeOut())
                            }
                        },
                        label = "TitleAnimation",
                    ) { isSelectionMode ->
                        if (isSelectionMode) {
                            Text(text = stringResource(R.string.selected_count, selection.size))
                        } else if (searchOpen) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(stringResource(R.string.downloads_search)) },
                                singleLine = true,
                                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = when (location) {
                                    is BrowseLocation.Root -> stringResource(R.string.downloads)
                                    is BrowseLocation.Library -> location.name
                                    is BrowseLocation.Group -> location.title
                                    is BrowseLocation.Storage -> stringResource(R.string.downloads_storage)
                                    is BrowseLocation.Active -> stringResource(R.string.downloads_active)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    Crossfade(
                        targetState = selectionMode,
                        label = "NavIconAnimation",
                    ) { isSelectionMode ->
                        if (isSelectionMode) {
                            IconButton(
                                onClick = { selection.clear() },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { goBack() },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                },
                actions = {
                    Row {
                        if (!selectionMode && !searchOpen) {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(imageVector = Icons.Outlined.Search, contentDescription = stringResource(R.string.downloads_search))
                            }
                            // An album: its details for all of its tracks
                            if (location is BrowseLocation.Group && location.kind == GroupKind.ALBUM) {
                                IconButton(onClick = {
                                    metadataTargets = downloads.map { it.download }
                                        .filter { it.item.mediaType == MediaType.AUDIO }
                                        .filter { (it.item.albumId?.toString() ?: "name:" + it.item.album.orEmpty()) == location.key }
                                        .ifEmpty { null }
                                }) {
                                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = stringResource(R.string.metadata_edit))
                                }
                            }
                            if (location !is BrowseLocation.Storage) {
                                IconButton(onClick = { locations.add(BrowseLocation.Storage) }) {
                                    Icon(imageVector = Icons.Outlined.Storage, contentDescription = stringResource(R.string.downloads_storage))
                                }
                            }
                        }
                        AnimatedVisibility(
                            visible = selectionMode,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Row {
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                    )
                                }

                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.MoreVert,
                                            contentDescription = null,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                    ) {
                                        if (selection.size < downloads.size) {
                                            DropdownMenuItem(
                                                onClick = {
                                                    selection.addAll(downloads.map { it.download.id })
                                                    showMenu = false
                                                },
                                            ) {
                                                Text(text = stringResource(R.string.select_all))
                                            }
                                        }

                                        if (selection.isNotEmpty()) {
                                            DropdownMenuItem(
                                                onClick = {
                                                    metadataTargets = downloads.map { it.download }.filter { it.id in selection }
                                                    showMenu = false
                                                },
                                            ) {
                                                Text(text = stringResource(R.string.metadata_edit))
                                            }
                                            DropdownMenuItem(
                                                onClick = {
                                                    selection.clear()
                                                    showMenu = false
                                                },
                                            ) {
                                                Text(text = stringResource(R.string.deselect_all))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                backgroundColor = Color.Transparent,
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = nowPlaying != null,
                enter = slideInVertically { height -> height } + fadeIn(),
                exit = slideOutVertically { height -> height } + fadeOut(),
            ) {
                nowPlaying?.let { state ->
                    val track = downloads.firstOrNull { it.download.itemId.toString() == state.mediaId }?.download
                    MusicMiniPlayer(
                        state = state,
                        favorite = track?.let { viewModel.isFavorite(it, pendingFavorites) },
                        onToggleFavorite = { track?.let { viewModel.toggleFavorite(it, pendingFavorites) } },
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onPrevious = { viewModel.playPrevious() },
                        onNext = { viewModel.playNext() },
                        onStop = { viewModel.stopMusic() },
                        onOpen = onOpenNowPlaying,
                    )
                }
            }
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (offlineReason != null) {
                    OfflineCard(
                        reason = offlineReason,
                        onRetry = onRetryConnection,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (waitingForWifi) {
                    WaitingForWifiCard(
                        onDownloadNow = { viewModel.allowMobileData(always = false) },
                        onAlways = { viewModel.allowMobileData(always = true) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (storageLocation == null) {
                    StorageLocationNotSetCard(
                        onFix = { storageLocationPicker.launch(null) },
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (!storageLocationAccessible) {
                    StorageLocationInaccessibleCard(
                        onFix = { storageLocationPicker.launch(null) },
                        modifier = Modifier.padding(16.dp),
                    )
                }

                val browseActions = BrowseActions(
                    navigate = { locations.add(it) },
                    open = { viewModel.openDownload(it) },
                    retry = { viewModel.download(it) },
                    playTracks = { tracks, start, shuffle -> viewModel.playTracks(tracks, start, shuffle) },
                    removeAll = { pendingRemoval = it },
                    toggleSelection = { download ->
                        if (selection.contains(download.id)) selection.remove(download.id)
                        else selection.add(download.id)
                    },
                    progress = { download -> viewModel.progressOf(download, playStates) },
                    openFromStart = { viewModel.openFromStart(it) },
                    isFavorite = { viewModel.isFavorite(it, pendingFavorites) },
                    toggleFavorite = { viewModel.toggleFavorite(it, pendingFavorites) },
                    isFollowed = { entry -> follows.any { it.seriesId == entry.item.seriesId?.toString() } },
                    setFollowed = { entry, followed -> viewModel.setFollowed(entry.download, followed) },
                    newEpisodesEnabled = viewModel.newEpisodesEnabled,
                    pauseAll = { viewModel.pauseDownloads() },
                    resumeAll = { viewModel.resumeDownloads() },
                    cancelAll = { viewModel.cancelAllDownloads() },
                )
                if (location is BrowseLocation.Storage && searchQuery.isBlank()) {
                    StorageView(
                        index = index,
                        actions = browseActions,
                        availableBytes = availableBytes,
                        limitBytes = viewModel.storageLimitBytes,
                        onCleanup = { entries, preselected -> cleanup = entries to preselected },
                        modifier = Modifier.weight(1f),
                    )
                } else if (downloads.isEmpty()) {
                    DownloadsEmpty(modifier = Modifier.weight(1f))
                } else {
                    DownloadsBrowser(
                        index = index,
                        location = location,
                        searchQuery = searchQuery,
                        actions = browseActions,
                        selection = selection,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

/** Why the downloads were opened instead of the web client. */
enum class OfflineReason { UNREACHABLE, WIFI_ONLY }

/** Shown when the downloads were opened because the server did not answer, or mobile data is not to be used. */
@Composable
fun OfflineCard(
    reason: OfflineReason,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) = Card(
    modifier = modifier.fillMaxWidth(),
    elevation = 4.dp,
    backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.15f),
) {
    Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(if (reason == OfflineReason.WIFI_ONLY) R.string.downloads_wifi_only_title else R.string.downloads_offline_title),
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(if (reason == OfflineReason.WIFI_ONLY) R.string.downloads_wifi_only_description else R.string.downloads_offline_description),
                style = MaterialTheme.typography.body2,
            )
        }
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.downloads_offline_retry))
        }
    }
}

/** Queued downloads wait for Wi-Fi: why nothing downloads, and how to download over mobile data. */
@Composable
fun WaitingForWifiCard(
    onDownloadNow: () -> Unit,
    onAlways: () -> Unit,
    modifier: Modifier = Modifier,
) = Card(
    modifier = modifier.fillMaxWidth(),
    elevation = 4.dp,
    backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.15f),
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.downloads_waiting_wifi_title),
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.downloads_waiting_wifi_description),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onAlways) {
                Text(text = stringResource(R.string.downloads_waiting_wifi_always))
            }
            TextButton(onClick = onDownloadNow) {
                Text(text = stringResource(R.string.downloads_waiting_wifi_now))
            }
        }
    }
}

@Composable
fun StorageLocationNotSetCard(
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) = Card(
    modifier = modifier.fillMaxWidth(),
    elevation = 4.dp,
    backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.2f),
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.download_location_not_set),
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.download_location_not_set_description),
            style = MaterialTheme.typography.body2,
        )
        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onFix,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = stringResource(R.string.select_folder))
        }
    }
}

@Composable
fun StorageLocationInaccessibleCard(
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) = Card(
    modifier = modifier.fillMaxWidth(),
    elevation = 4.dp,
    backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.2f),
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.download_location_inaccessible),
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.download_location_inaccessible_description),
            style = MaterialTheme.typography.body2,
        )
        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onFix,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = stringResource(R.string.select_folder))
        }
    }
}

@Composable
fun DownloadsEmpty(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
) = Box(
    modifier = modifier
        .fillMaxSize()
        .padding(contentPadding),
    contentAlignment = Alignment.Center,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.downloads_empty),
            style = MaterialTheme.typography.h6,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.downloads_empty_description),
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
        )
    }
}
