package org.jellyfin.mobile.ui.screens.music

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFF00A4DC)
private val Dim = Color(0x99FFFFFF)
private val PanelColor = Color(0xF21C1B1F)

/**
 * The app's music player in full: the cover blurred over the whole screen, the cover, title and artist, a seek
 * bar and the controls in Android's own style, with press animations; the queue is a side sheet. Short swipes
 * (or a quick flick) do it: down closes the player, left or up opens the queue, right or down closes it.
 *
 * @param downloadStatus the download of the current track, null when it is not downloaded.
 */
@Composable
fun NowPlayingScreen(
    player: DownloadedMusicPlayer,
    onClose: () -> Unit,
    downloadStatus: (String?) -> DownloadStatus? = { null },
    onDownload: (String) -> Unit = {},
) {
    val state = player.nowPlaying.collectAsState().value
    if (state == null) {
        // Nothing plays (stopped meanwhile), or the player is still connecting after a cold start
        LaunchedEffect(Unit) {
            delay(CONNECT_WAIT_MS)
            if (player.nowPlaying.value == null) onClose()
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
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
    val fling = with(density) { FLING_VELOCITY.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val panelWidthDp = minOf(maxWidth * PANEL_SHARE, PANEL_MAX_WIDTH)
        val panelWidth = with(density) { panelWidthDp.toPx() }
        // 0 - the queue is open, panelWidth - it is closed
        val panelOffset = remember(panelWidth) { Animatable(panelWidth) }
        val contentOffsetY = remember { Animatable(0f) }

        fun showQueue(show: Boolean) {
            scope.launch { panelOffset.animateTo(if (show) 0f else panelWidth, tween(ANIMATION_MS, easing = FastOutSlowInEasing)) }
        }

        Background(artwork = state.artworkUri)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(panelWidth) {
                    val velocity = VelocityTracker()
                    var vertical: Boolean? = null
                    var openAtStart = false
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(
                        onDragStart = {
                            velocity.resetTracking()
                            vertical = null
                            openAtStart = panelOffset.value < panelWidth / 2
                            totalX = 0f
                            totalY = 0f
                        },
                        onDragEnd = {
                            val speed = velocity.calculateVelocity()
                            scope.launch {
                                if (vertical == false || openAtStart) {
                                    // A fifth of the panel or a flick decides
                                    val flick = if (vertical == false) speed.x else speed.y
                                    val show = when {
                                        flick < -fling -> true
                                        flick > fling -> false
                                        openAtStart -> panelOffset.value < panelWidth * QUEUE_DECIDE
                                        else -> panelOffset.value < panelWidth * (1 - QUEUE_DECIDE)
                                    }
                                    panelOffset.animateTo(if (show) 0f else panelWidth, tween(ANIMATION_MS, easing = FastOutSlowInEasing))
                                } else if (vertical == true) {
                                    when {
                                        totalY < -closeDistance / 2 || speed.y < -fling -> showQueue(true)
                                        totalY > closeDistance || speed.y > fling && totalY > 0 -> onClose()
                                    }
                                }
                                contentOffsetY.animateTo(0f, tween(ANIMATION_MS, easing = FastOutSlowInEasing))
                            }
                        },
                    ) { change, amount ->
                        velocity.addPosition(change.uptimeMillis, change.position)
                        totalX += amount.x
                        totalY += amount.y
                        if (vertical == null && (abs(totalX) > SLOP || abs(totalY) > SLOP)) vertical = abs(totalY) > abs(totalX)
                        scope.launch {
                            when {
                                vertical == false -> panelOffset.snapTo((panelOffset.value + amount.x).coerceIn(0f, panelWidth))
                                vertical == true && openAtStart -> panelOffset.snapTo((panelOffset.value + amount.y).coerceIn(0f, panelWidth))
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
                    .offset { IntOffset(0, (contentOffsetY.value * DRAG_RESISTANCE).roundToInt()) }
                    .graphicsLayer { alpha = 1f - (contentOffsetY.value / (closeDistance * 4)).coerceIn(0f, 0.5f) },
            ) {
                val download = downloadStatus(state.mediaId)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PressButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Color.White) }
                    Text(
                        text = stringResource(R.string.music_now_playing),
                        color = Color(0xCCFFFFFF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    PressButton(onClick = { state.mediaId?.let(onDownload) }, enabled = download == null || download == DownloadStatus.ERROR) {
                        when (download) {
                            DownloadStatus.DOWNLOADED -> Icon(Icons.Filled.DownloadDone, contentDescription = stringResource(R.string.music_downloaded), tint = Accent)
                            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED ->
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            else -> Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.music_download), tint = Color.White)
                        }
                    }
                    ToggleButton(Icons.Filled.SwapVert, stringResource(R.string.music_reverse), state.reversed) { player.setReversed(!state.reversed) }
                    PressButton(onClick = { showQueue(true) }) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.music_queue), tint = Color.White)
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    // The cover shrinks a little while paused, as in Android's media players
                    val coverScale by animateFloatAsState(if (state.isPlaying) 1f else PAUSED_COVER_SCALE, tween(ANIMATION_MS * 2), label = "cover")
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
                            .graphicsLayer {
                                scaleX = coverScale
                                scaleY = coverScale
                            }
                            .shadow(20.dp, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1A1A1A)),
                    )
                }

                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    // Stop sits by the title, where Android's players keep their extra action
                    Row(modifier = Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.title.orEmpty(),
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.subtitle.orEmpty(),
                                color = Dim,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        PressButton(onClick = { player.stop() }, size = 44.dp) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.music_stop), tint = Dim, modifier = Modifier.size(24.dp))
                        }
                    }
                    SeekBar(position = position, duration = duration, onSeek = { target ->
                        player.seekTo(target)
                        position = target
                    })
                }

                Controls(player, state)
            }

            val openShare = 1f - panelOffset.value / panelWidth
            if (openShare > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = openShare }
                        .background(Color(0x99000000))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showQueue(false) },
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

/** A thin bar with rounded ends; the thumb grows under the finger. Tap or drag to seek. */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val enabled = duration > 0
    val fraction = if (dragging) dragFraction else if (enabled) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thumb by animateDpAsState(if (dragging) 9.dp else 6.dp, tween(ANIMATION_MS), label = "thumb")
    val track by animateDpAsState(if (dragging) 6.dp else 4.dp, tween(ANIMATION_MS), label = "track")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> onSeek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
            }
            .pointerInput(enabled, duration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        onSeek((dragFraction * duration).toLong())
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    change.consume()
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = size.height / 2
            val stroke = track.toPx()
            drawLine(Color(0x40FFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
            if (fraction > 0f) {
                drawLine(Accent, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            if (enabled) drawCircle(Color.White, radius = thumb.toPx(), center = Offset(size.width * fraction, y))
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(time(if (dragging) (dragFraction * duration).toLong() else position), color = Dim, fontSize = 12.sp)
        Text(if (enabled) "-" + time(duration - (if (dragging) (dragFraction * duration).toLong() else position)) else "", color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun Controls(player: DownloadedMusicPlayer, state: DownloadedMusicPlayer.NowPlaying) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleButton(Icons.Filled.Shuffle, stringResource(R.string.music_shuffle), state.shuffle) { player.setShuffle(!state.shuffle) }
        PressButton(onClick = { player.previous() }, size = 52.dp) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.music_previous), tint = Color.White, modifier = Modifier.size(32.dp))
        }
        PlayButton(playing = state.isPlaying) { player.togglePlayPause() }
        PressButton(onClick = { player.next() }, size = 52.dp, enabled = state.hasNext) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.music_next),
                tint = if (state.hasNext) Color.White else Dim,
                modifier = Modifier.size(32.dp),
            )
        }
        ToggleButton(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            description = stringResource(R.string.music_repeat),
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
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

/** Round while paused, a rounded square while playing (Android's media controls do the same). */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (playing) 24.dp else 36.dp, tween(ANIMATION_MS), label = "corner")
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, tween(PRESS_MS), label = "scale")
    Box(
        modifier = Modifier
            .size(72.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .background(Accent)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = playing, animationSpec = tween(ANIMATION_MS), label = "play") { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.music_pause else R.string.music_play),
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** A round button with a ripple that sinks a little under the finger. */
@Composable
private fun PressButton(
    onClick: () -> Unit,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, tween(PRESS_MS), label = "scale")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A mode (shuffle, reverse, repeat): accent and a dot under it when on. */
@Composable
private fun ToggleButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) Accent else Dim, tween(ANIMATION_MS), label = "tint")
    val dot by animateFloatAsState(if (active) 1f else 0f, tween(ANIMATION_MS), label = "dot")
    PressButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .size(4.dp)
                .graphicsLayer { alpha = dot }
                .clip(CircleShape)
                .background(Accent),
        )
    }
}

@Composable
private fun Background(artwork: Any?) {
    val context = LocalContext.current
    // Loaded tiny and stretched: soft at no cost; the real blur on top where the system has it
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
            .background(Brush.verticalGradient(0f to Color(0x66000000), 0.4f to Color(0x33000000), 1f to Color(0xE6000000))),
    )
}

/** The queue as a side sheet: rounded, covers with each track, the current one marked. */
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
        if (visible && state.index > 0) listState.animateScrollToItem((state.index - 1).coerceAtLeast(0))
    }
    val context = LocalContext.current
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
            .background(PanelColor)
            .systemBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.music_queue), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.music_queue_position, state.index + 1, state.count), color = Dim, fontSize = 13.sp)
            }
            PressButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = null, tint = Dim) }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            itemsIndexed(queue) { index, entry ->
                val current = index == state.index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (current) Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { player.playIndex(index) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val thumbnail = remember(entry.artworkUri) { ImageRequest.Builder(context).data(entry.artworkUri).size(THUMBNAIL_SIZE).build() }
                    AsyncImage(
                        model = thumbnail,
                        placeholder = painterResource(R.drawable.ic_music_note_white_24dp),
                        fallback = painterResource(R.drawable.ic_music_note_white_24dp),
                        error = painterResource(R.drawable.ic_music_note_white_24dp),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2E)),
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            text = entry.title.orEmpty(),
                            color = if (current) Accent else Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        entry.subtitle?.let { Text(it, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (current) Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun time(ms: Long): String = DateUtils.formatElapsedTime(ms.coerceAtLeast(0L) / MS_PER_SECOND)

private const val POSITION_REFRESH_MS = 500L
private const val CONNECT_WAIT_MS = 1_500L
private const val MS_PER_SECOND = 1000L
private const val ANIMATION_MS = 180
private const val PRESS_MS = 90
private const val PRESSED_SCALE = 0.88f
private const val PAUSED_COVER_SCALE = 0.92f
private const val SLOP = 16f
private const val DRAG_RESISTANCE = 0.85f

/** How far the queue must be pulled to stay where it goes: a fifth of it. */
private const val QUEUE_DECIDE = 0.2f
private const val PANEL_SHARE = 0.86f
private val PANEL_MAX_WIDTH = 340.dp
private val CLOSE_DISTANCE = 64.dp
private val FLING_VELOCITY = 600.dp
private const val BACKGROUND_SIZE = 32
private const val THUMBNAIL_SIZE = 96
private const val BACKGROUND_ALPHA = 0.5f
private val BACKGROUND_BLUR = 24.dp
