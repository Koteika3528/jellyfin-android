package org.jellyfin.mobile.ui.screens.music

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFF00A4DC)
private val Dim = Color(0x99FFFFFF)

/**
 * The app's music player in full, styled as on Windows 10 Mobile: the cover blurred over the whole screen,
 * the cover, title and artist in the middle, the seek bar and controls below; the queue is a panel from the
 * right. Swipe down closes the player, swipe left or up opens the queue, swipe right or down closes it.
 */
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
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.positionMs()
            duration = player.durationMs()
            delay(POSITION_REFRESH_MS)
        }
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val closeDistance = with(density) { CLOSE_DISTANCE.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val panelWidthDp = minOf(maxWidth * PANEL_SHARE, PANEL_MAX_WIDTH)
        val panelWidth = with(density) { panelWidthDp.toPx() }
        // 0 - the queue is open, panelWidth - it is closed
        val panelOffset = remember(panelWidth) { Animatable(panelWidth) }
        val contentOffsetY = remember { Animatable(0f) }

        fun showQueue(show: Boolean) {
            scope.launch { panelOffset.animateTo(if (show) 0f else panelWidth, tween(ANIMATION_MS)) }
        }

        // Background: the cover, tiny and stretched (soft for free), blurred on top where the system can
        Background(artwork = state.artworkUri)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(panelWidth) {
                    var vertical: Boolean? = null
                    var openAtStart = false
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(
                        onDragStart = {
                            vertical = null
                            openAtStart = panelOffset.value < panelWidth / 2
                            totalX = 0f
                            totalY = 0f
                        },
                        onDragEnd = {
                            scope.launch {
                                if (vertical == false || openAtStart) {
                                    // The queue follows the finger; a third of its width decides
                                    val show = if (openAtStart) panelOffset.value < panelWidth / 3 else panelOffset.value < panelWidth * 2 / 3
                                    panelOffset.animateTo(if (show) 0f else panelWidth, tween(ANIMATION_MS))
                                } else if (vertical == true) {
                                    when {
                                        totalY < -closeDistance / 2 -> panelOffset.animateTo(0f, tween(ANIMATION_MS))
                                        totalY > closeDistance -> onClose()
                                    }
                                }
                                contentOffsetY.animateTo(0f, tween(ANIMATION_MS))
                            }
                        },
                    ) { change, amount ->
                        totalX += amount.x
                        totalY += amount.y
                        if (vertical == null && (abs(totalX) > SLOP || abs(totalY) > SLOP)) vertical = abs(totalY) > abs(totalX)
                        scope.launch {
                            when {
                                // Left opens, right closes the queue
                                vertical == false -> panelOffset.snapTo((panelOffset.value + amount.x).coerceIn(0f, panelWidth))
                                // Down closes the open queue
                                vertical == true && openAtStart -> panelOffset.snapTo((panelOffset.value + amount.y).coerceIn(0f, panelWidth))
                                // Down pulls the player away
                                vertical == true && totalY > 0 -> contentOffsetY.snapTo(totalY)
                                else -> Unit
                            }
                        }
                        change.consume()
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .offset { IntOffset(0, contentOffsetY.value.roundToInt()) }
                    .graphicsLayer { alpha = 1f - (contentOffsetY.value / (closeDistance * 3)).coerceIn(0f, 0.6f) },
            ) {
                Header(
                    reversed = state.reversed,
                    onClose = onClose,
                    onReverse = { player.setReversed(!state.reversed) },
                    onQueue = { showQueue(true) },
                )

                // Cover with a shadow
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = state.artworkUri,
                        placeholder = painterResource(R.drawable.ic_music_note_white_24dp),
                        fallback = painterResource(R.drawable.ic_music_note_white_24dp),
                        error = painterResource(R.drawable.ic_music_note_white_24dp),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .shadow(16.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1A1A))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp)),
                    )
                }

                // Title, artist, position
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = state.title.orEmpty(),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = state.subtitle.orEmpty(),
                        color = Dim,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                    )
                    val total = duration.coerceAtLeast(1L)
                    Slider(
                        value = if (seeking) seekValue else (position.toFloat() / total).coerceIn(0f, 1f),
                        onValueChange = {
                            seeking = true
                            seekValue = it
                        },
                        onValueChangeFinished = {
                            player.seekTo((seekValue * total).toLong())
                            position = (seekValue * total).toLong()
                            seeking = false
                        },
                        enabled = duration > 0,
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x40FFFFFF)),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(time(if (seeking) (seekValue * total).toLong() else position), color = Dim, fontSize = 12.sp)
                        Text(if (duration > 0) time(duration) else "", color = Dim, fontSize = 12.sp)
                    }
                }

                Controls(player, state)
            }

            // The queue: a darkened backdrop and the panel from the right
            val openShare = 1f - panelOffset.value / panelWidth
            if (openShare > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = openShare }
                        .background(Color(0xB3000000))
                        .clickable { showQueue(false) },
                )
                QueuePanel(
                    player = player,
                    state = state,
                    width = panelWidthDp,
                    visible = openShare > 0.5f,
                    onClose = { showQueue(false) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset { IntOffset(panelOffset.value.roundToInt(), 0) },
                )
            }
        }
    }
}

@Composable
private fun Header(reversed: Boolean, onClose: () -> Unit, onReverse: () -> Unit, onQueue: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Color.White) }
        Text(
            text = stringResource(R.string.music_now_playing).uppercase(),
            color = Color(0xCCFFFFFF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReverse) {
            Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.music_reverse), tint = if (reversed) Accent else Dim)
        }
        IconButton(onClick = onQueue) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.music_queue), tint = Color.White)
        }
    }
}

/** Shuffle, previous, play, stop, next, repeat - as on Windows 10 Mobile. */
@Composable
private fun Controls(player: DownloadedMusicPlayer, state: DownloadedMusicPlayer.NowPlaying) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(Icons.Filled.Shuffle, stringResource(R.string.music_shuffle), if (state.shuffle) Accent else Dim, 20) {
            player.setShuffle(!state.shuffle)
        }
        ControlButton(Icons.Filled.SkipPrevious, stringResource(R.string.music_previous), Color.White, 30) { player.previous() }
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable { player.togglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.music_pause else R.string.music_play),
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
        ControlButton(Icons.Filled.Stop, stringResource(R.string.music_stop), Dim, 22) { player.stop() }
        ControlButton(Icons.Filled.SkipNext, stringResource(R.string.music_next), if (state.hasNext) Color.White else Dim, 30) { player.next() }
        ControlButton(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            description = stringResource(R.string.music_repeat),
            tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) Accent else Dim,
            size = 20,
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
}

@Composable
private fun Background(artwork: Any?) {
    val context = LocalContext.current
    val request = remember(artwork) { ImageRequest.Builder(context).data(artwork).size(BACKGROUND_SIZE).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = BACKGROUND_ALPHA }
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(BACKGROUND_BLUR) else Modifier),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0x80000000),
                    0.3f to Color(0x40000000),
                    0.7f to Color(0xB3000000),
                    1f to Color(0xE6000000),
                ),
            ),
    )
}

@Composable
private fun QueuePanel(
    player: DownloadedMusicPlayer,
    state: DownloadedMusicPlayer.NowPlaying,
    width: Dp,
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val queue = remember(state.count, state.mediaId, state.reversed) { player.queue() }
    val listState = rememberLazyListState()
    LaunchedEffect(visible, state.index) {
        if (visible && state.index > 0) listState.scrollToItem((state.index - 1).coerceAtLeast(0))
    }
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(Color(0xD9111111))
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF262626)).padding(start = 20.dp, top = 14.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.music_queue).uppercase(),
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(stringResource(R.string.music_queue_hint), color = Color(0x66FFFFFF), fontSize = 10.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = null, tint = Dim) }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
            itemsIndexed(queue) { index, entry ->
                val current = index == state.index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (current) Color(0x3300A4DC) else Color(0x1AFFFFFF))
                        .border(1.dp, Color(0x15FFFFFF), RoundedCornerShape(2.dp))
                        .clickable { player.playIndex(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title.orEmpty(),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        entry.subtitle?.let {
                            Text(it, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    if (current) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Accent, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, tint: Color, size: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(size.dp))
    }
}

private fun time(ms: Long): String = DateUtils.formatElapsedTime(ms.coerceAtLeast(0L) / MS_PER_SECOND)

private const val POSITION_REFRESH_MS = 500L
private const val MS_PER_SECOND = 1000L
private const val ANIMATION_MS = 250
private const val SLOP = 24f
private const val PANEL_SHARE = 0.85f
private val PANEL_MAX_WIDTH = 320.dp
private val CLOSE_DISTANCE = 120.dp
private const val BACKGROUND_SIZE = 32
private const val BACKGROUND_ALPHA = 0.45f
private val BACKGROUND_BLUR = 24.dp
