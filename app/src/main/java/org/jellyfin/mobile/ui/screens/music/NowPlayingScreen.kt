package org.jellyfin.mobile.ui.screens.music

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer

/** The app's music player in full: artwork, position, controls, repeat and shuffle, the queue. */
@Composable
fun NowPlayingScreen(player: DownloadedMusicPlayer, onClose: () -> Unit) {
    val state = player.nowPlaying.collectAsState().value
    if (state == null) {
        // Stopped meanwhile
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.positionMs()
            duration = player.durationMs()
            delay(POSITION_REFRESH_MS)
        }
    }

    val queue = remember(state.count, state.mediaId, state.reversed) { player.queue() }
    val listState = rememberLazyListState()
    LaunchedEffect(state.index) { if (state.index > 0) listState.animateScrollToItem((state.index - 1).coerceAtLeast(0)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .systemBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.music_queue_position, state.index + 1, state.count),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { player.stop() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.music_stop)) }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            item(key = "header") {
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    AsyncImage(
                        model = state.artworkUri,
                        placeholder = painterResource(R.drawable.ic_music_note_white_24dp),
                        fallback = painterResource(R.drawable.ic_music_note_white_24dp),
                        error = painterResource(R.drawable.ic_music_note_white_24dp),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.title.orEmpty(),
                        style = MaterialTheme.typography.h6,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val total = duration.coerceAtLeast(1L)
                    Slider(
                        value = if (dragging) dragValue else (position.toFloat() / total).coerceIn(0f, 1f),
                        onValueChange = {
                            dragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            player.seekTo((dragValue * total).toLong())
                            position = (dragValue * total).toLong()
                            dragging = false
                        },
                        enabled = duration > 0,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colors.primary, activeTrackColor = MaterialTheme.colors.primary),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(time(if (dragging) (dragValue * total).toLong() else position), style = MaterialTheme.typography.caption)
                        Text(if (duration > 0) time(duration) else "", style = MaterialTheme.typography.caption)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { player.previous() }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.music_previous), modifier = Modifier.size(36.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colors.primary)
                                .clickable { player.togglePlayPause() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(if (state.isPlaying) R.string.music_pause else R.string.music_play),
                                tint = MaterialTheme.colors.onPrimary,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        IconButton(onClick = { player.next() }, enabled = state.hasNext) {
                            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.music_next), modifier = Modifier.size(36.dp))
                        }
                    }

                    // Modes: shuffle or reverse order (one excludes the other), each with repeat all / one
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModeButton(
                            icon = Icons.Filled.Shuffle,
                            active = state.shuffle,
                            description = stringResource(R.string.music_shuffle),
                        ) { player.setShuffle(!state.shuffle) }
                        ModeButton(
                            icon = Icons.Filled.SwapVert,
                            active = state.reversed,
                            description = stringResource(R.string.music_reverse),
                        ) { player.setReversed(!state.reversed) }
                        ModeButton(
                            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            active = state.repeatMode != Player.REPEAT_MODE_OFF,
                            description = stringResource(R.string.music_repeat),
                        ) {
                            // Off -> all -> one -> off
                            player.setRepeatMode(
                                when (state.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                },
                            )
                        }
                    }
                    if (queue.size > 1) {
                        Text(
                            text = stringResource(R.string.music_queue),
                            style = MaterialTheme.typography.subtitle1,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            if (queue.size > 1) {
                itemsIndexed(queue) { index, entry ->
                    val current = index == state.index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { player.playIndex(index) }
                            .background(if (current) MaterialTheme.colors.primary.copy(alpha = 0.15f) else MaterialTheme.colors.background)
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.caption,
                            color = if (current) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title.orEmpty(),
                                style = MaterialTheme.typography.body1,
                                color = if (current) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            entry.subtitle?.let {
                                Text(it, style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, active: Boolean, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colors.primary else LocalContentColor.current.copy(alpha = 0.6f),
        )
    }
}

private fun time(ms: Long): String = DateUtils.formatElapsedTime(ms.coerceAtLeast(0L) / MS_PER_SECOND)

private const val POSITION_REFRESH_MS = 500L
private const val MS_PER_SECOND = 1000L
