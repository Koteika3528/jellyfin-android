package org.jellyfin.mobile.ui.screens.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.TriStateCheckbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadMode
import org.jellyfin.mobile.downloads.DownloadOptions
import org.jellyfin.mobile.downloads.DownloadOptionsViewModel
import org.jellyfin.mobile.downloads.DownloadOptionsViewModel.Resolution
import java.util.Locale

/** Which episodes and how to download them, opened when the web client asks to download videos. */
@Composable
fun DownloadOptionsScreen(
    viewModel: DownloadOptionsViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Scaffold(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = viewModel.title ?: stringResource(R.string.download_options_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            if (!viewModel.loading && viewModel.error == null) {
                Surface(elevation = 8.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.download_options_count, viewModel.selected.size),
                                style = MaterialTheme.typography.body2,
                            )
                            viewModel.availableBytes?.let { free ->
                                Text(
                                    text = stringResource(R.string.download_options_free, size(free)),
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                        val estimate = viewModel.estimatedSize()
                        Button(onClick = onStart, enabled = viewModel.selected.isNotEmpty()) {
                            Text(
                                if (estimate != null) {
                                    stringResource(R.string.download_options_start_size, size(estimate))
                                } else {
                                    stringResource(R.string.download_options_start)
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            viewModel.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            viewModel.error != null -> Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.download_options_error, viewModel.error.orEmpty()))
            }
            else -> OptionsList(viewModel, Modifier.padding(padding), ::size)
        }
    }
}

@Composable
private fun OptionsList(viewModel: DownloadOptionsViewModel, modifier: Modifier, size: (Long) -> String) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (viewModel.insecureConnection) {
            item(key = "insecure") {
                Text(
                    text = stringResource(R.string.download_options_insecure),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
        }
        if (viewModel.canChoose) {
            item(key = "what") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Header(stringResource(R.string.download_options_what), Modifier.weight(1f))
                    TextButton(onClick = viewModel::selectUnwatched) { Text(stringResource(R.string.download_options_unwatched)) }
                    TextButton(onClick = viewModel::selectAll) { Text(stringResource(R.string.download_options_all)) }
                }
            }
            for (season in viewModel.seasons) {
                val chosen = season.items.count { it.id in viewModel.selected }
                val open = season.key in viewModel.expanded
                // Movies without seasons are listed as they are
                if (viewModel.seasons.size > 1 || season.title != null) {
                    item(key = "season-${season.key}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleExpanded(season) }.padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(
                                state = when (chosen) {
                                    0 -> ToggleableState.Off
                                    season.items.size -> ToggleableState.On
                                    else -> ToggleableState.Indeterminate
                                },
                                onClick = { viewModel.toggle(season) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(seasonTitle(season.title), style = MaterialTheme.typography.subtitle1)
                                val bytes = season.items.filter { it.id in viewModel.selected }.sumOf { viewModel.sizeOf(it) ?: 0L }
                                Text(
                                    text = stringResource(R.string.download_options_season_count, chosen, season.items.size) +
                                        if (bytes > 0) " · " + size(bytes) else "",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                    }
                }
                if (open || (viewModel.seasons.size == 1 && season.title == null)) {
                    items(season.items, key = { "item-${it.id}" }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.toggle(item) }.padding(start = 24.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = item.id in viewModel.selected, onCheckedChange = { viewModel.toggle(item) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = listOfNotNull(item.indexNumber?.toString(), item.name).joinToString(". "),
                                    style = MaterialTheme.typography.body1,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val details = listOfNotNull(
                                    viewModel.sizeOf(item)?.let(size),
                                    stringResource(R.string.download_options_watched).takeIf { item.userData?.played == true },
                                )
                                if (details.isNotEmpty()) {
                                    Text(
                                        text = details.joinToString(" · "),
                                        style = MaterialTheme.typography.caption,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { Divider(modifier = Modifier.padding(top = 8.dp)) }
        }

        item(key = "how") { Header(stringResource(R.string.download_options_how)) }
        item(key = "modes") {
            Column {
                ModeRow(viewModel, DownloadMode.ORIGINAL, R.string.download_mode_original, R.string.download_mode_original_description, size)
                ModeRow(viewModel, DownloadMode.REMUX, R.string.download_mode_remux, R.string.download_mode_remux_description, size)
                ModeRow(viewModel, DownloadMode.TRANSCODE, R.string.download_mode_transcode, R.string.download_mode_transcode_description, size)
                if (!viewModel.canConvert) {
                    Text(
                        text = stringResource(R.string.download_mode_unavailable),
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        if (viewModel.mode != DownloadMode.ORIGINAL) {
            val groups = viewModel.trackGroups
            // Several seasons: tracks are chosen for each of them, they differ often
            val bySeason = groups.size > 1
            if (groups.any { it.audio.isNotEmpty() }) {
                item(key = "audio") { Header(stringResource(R.string.download_options_audio)) }
                for (group in groups) {
                    if (group.audio.isEmpty()) continue
                    if (bySeason) item(key = "audio-season-${group.season.key}") { SubHeader(seasonTitle(group.season.title)) }
                    items(group.audio, key = { "audio-${group.season.key}-${it.key}" }) { track ->
                        TrackRow(track, checked = viewModel.isAudioKept(group.season, track)) { viewModel.toggleAudio(group.season, track) }
                    }
                    if (group.audio.none { viewModel.isAudioKept(group.season, it) } || group.audio.any { it.count < it.total }) {
                        item(key = "audio-hint-${group.season.key}") { Hint(stringResource(R.string.download_options_no_audio)) }
                    }
                }
            }
            if (groups.any { it.subtitles.isNotEmpty() }) {
                item(key = "subtitles") { Header(stringResource(R.string.download_options_subtitles)) }
                for (group in groups) {
                    if (group.subtitles.isEmpty()) continue
                    if (bySeason) item(key = "subtitle-season-${group.season.key}") { SubHeader(seasonTitle(group.season.title)) }
                    items(group.subtitles, key = { "subtitle-${group.season.key}-${it.key}" }) { track ->
                        TrackRow(track, checked = viewModel.isSubtitleKept(group.season, track)) { viewModel.toggleSubtitle(group.season, track) }
                    }
                }
            }
        }

        if (viewModel.mode == DownloadMode.TRANSCODE) {
            item(key = "video") { Header(stringResource(R.string.download_options_video)) }
            item(key = "codecs") {
                Column {
                    Choice(viewModel.videoCodec == DownloadOptions.H264, stringResource(R.string.download_codec_h264), stringResource(R.string.download_codec_h264_description)) {
                        viewModel.videoCodec = DownloadOptions.H264
                    }
                    Choice(viewModel.videoCodec == DownloadOptions.HEVC, stringResource(R.string.download_codec_hevc), stringResource(R.string.download_codec_hevc_description)) {
                        viewModel.videoCodec = DownloadOptions.HEVC
                    }
                }
            }
            item(key = "resolution") { Header(stringResource(R.string.download_options_resolution)) }
            item(key = "resolutions") {
                // Wraps to a second line on narrow screens instead of running off the edge
                FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    for (resolution in Resolution.entries) {
                        Row(
                            modifier = Modifier.clickable { viewModel.resolution = resolution }.padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = viewModel.resolution == resolution, onClick = { viewModel.resolution = resolution })
                            Text(
                                text = resolution.height?.let { "${it}p" } ?: stringResource(R.string.download_resolution_source),
                                style = MaterialTheme.typography.body2,
                            )
                        }
                    }
                }
            }
            item(key = "bitrate") { Header(stringResource(R.string.download_options_bitrate)) }
            item(key = "bitrate-modes") {
                Column {
                    Choice(
                        selected = !viewModel.manualBitrate,
                        title = stringResource(R.string.download_bitrate_auto, megabits(viewModel.recommendedLimit)),
                        description = stringResource(R.string.download_bitrate_auto_description),
                    ) { viewModel.manualBitrate = false }
                    Choice(
                        selected = viewModel.manualBitrate,
                        title = stringResource(R.string.download_bitrate_manual),
                        description = stringResource(R.string.download_bitrate_manual_description),
                    ) { viewModel.manualBitrate = true }
                    if (viewModel.manualBitrate) {
                        OutlinedTextField(
                            value = viewModel.manualMbits,
                            onValueChange = { value -> viewModel.manualMbits = value.filter { it.isDigit() || it == '.' || it == ',' }.take(MAX_BITRATE_DIGITS) },
                            label = { Text(stringResource(R.string.download_bitrate_field)) },
                            singleLine = true,
                            isError = viewModel.manualMbits.isNotEmpty() && viewModel.manualLimit == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    viewModel.averageBitrate?.let { average ->
                        Hint(stringResource(R.string.download_bitrate_average, megabits(average)))
                    }
                }
            }
            item(key = "sound") { Header(stringResource(R.string.download_options_sound)) }
            item(key = "sound-codecs") {
                Column {
                    Choice(viewModel.audioCodec == DownloadOptions.AAC, stringResource(R.string.download_audio_aac), null) {
                        viewModel.audioCodec = DownloadOptions.AAC
                    }
                    Choice(viewModel.audioCodec == DownloadOptions.OPUS, stringResource(R.string.download_audio_opus), stringResource(R.string.download_audio_opus_description)) {
                        viewModel.audioCodec = DownloadOptions.OPUS
                    }
                    Choice(viewModel.audioCodec == DownloadOptions.COPY, stringResource(R.string.download_audio_copy), null) {
                        viewModel.audioCodec = DownloadOptions.COPY
                    }
                    if (viewModel.audioCodec != DownloadOptions.COPY && viewModel.hasSurround) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.keepChannels = !viewModel.keepChannels }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = viewModel.keepChannels, onCheckedChange = { viewModel.keepChannels = it })
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(stringResource(R.string.download_audio_keep_channels), style = MaterialTheme.typography.body1)
                                Text(
                                    text = stringResource(R.string.download_audio_keep_channels_description),
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun seasonTitle(title: String?): String = when {
    title == null -> stringResource(R.string.download_options_videos)
    title.toIntOrNull() != null -> stringResource(R.string.download_options_season, title)
    else -> title
}

@Composable
private fun ModeRow(viewModel: DownloadOptionsViewModel, mode: DownloadMode, title: Int, description: Int, size: (Long) -> String) {
    val enabled = mode == DownloadMode.ORIGINAL || viewModel.canConvert
    val estimate = if (enabled) viewModel.estimatedSize(mode) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { viewModel.mode = mode }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = viewModel.mode == mode, onClick = { viewModel.mode = mode }, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(title), style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Medium)
                if (estimate != null) {
                    Text(
                        text = (if (mode == DownloadMode.ORIGINAL) "" else "≈ ") + size(estimate),
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.7f else 0.4f),
            )
        }
    }
}

@Composable
private fun TrackRow(track: DownloadOptionsViewModel.Track, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(track.label, style = MaterialTheme.typography.body1, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Episodes with the track of the chosen ones: those without keep their default track
        if (track.total > 1) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.download_options_track_count, track.count, track.total),
                style = MaterialTheme.typography.body2,
                color = if (track.count < track.total) MaterialTheme.colors.error else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, description: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.body1)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.subtitle1,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/** 3000000 → "3", 1500000 → "1.5" (in the device's format). */
private fun megabits(bitsPerSecond: Int): String {
    val value = bitsPerSecond.toDouble() / DownloadOptionsViewModel.MBIT
    return if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.getDefault(), "%.1f", value)
}

private const val MAX_BITRATE_DIGITS = 5

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
