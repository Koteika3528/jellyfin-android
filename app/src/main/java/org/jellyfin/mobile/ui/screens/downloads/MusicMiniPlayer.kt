package org.jellyfin.mobile.ui.screens.downloads

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadedMusicPlayer

private val BarColor = Color(0xFF242428)
private val Dim = Color(0x99FFFFFF)

/** Digits of the same width, so the time does not jitter while it counts. */
private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/**
 * The player when it is collapsed, a card over the bottom (web client and downloads): the cover, the track,
 * how far it played of how long it is, the buttons, and a thin progress line along its bottom edge. A new
 * track slides in, play and pause cross-fade, buttons sink under the finger.
 *
 * @param positionMs and [durationMs] are read twice a second while it is shown.
 */
@Composable
fun MusicMiniPlayer(
    state: DownloadedMusicPlayer.NowPlaying,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    positionMs: () -> Long = { 0L },
    durationMs: () -> Long = { 0L },
    /** Tapping the track opens the player in full. */
    onOpen: (() -> Unit)? = null,
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = positionMs()
            duration = durationMs()
            delay(REFRESH_MS)
        }
    }
    val target = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    // Linear over the refresh time, so the line moves evenly instead of in jumps
    val fraction by animateFloatAsState(target, tween(REFRESH_MS.toInt(), easing = LinearEasing), label = "progress")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BarColor),
    ) {
        Row(
            modifier = Modifier
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crossfade(targetState = state.artworkUri, animationSpec = tween(ANIMATION_MS), label = "cover") { artwork ->
                AsyncImage(
                    model = artwork,
                    placeholder = painterResource(R.drawable.ic_music_note_white_24dp),
                    fallback = painterResource(R.drawable.ic_music_note_white_24dp),
                    error = painterResource(R.drawable.ic_music_note_white_24dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF333338)),
                )
            }

            // The next track comes up from below, the previous one goes up
            AnimatedContent(
                targetState = state.title to (state.artist ?: state.subtitle),
                transitionSpec = {
                    (slideInVertically(tween(ANIMATION_MS)) { it / 2 } + fadeIn(tween(ANIMATION_MS))) togetherWith
                        (slideOutVertically(tween(ANIMATION_MS)) { -it / 2 } + fadeOut(tween(ANIMATION_MS)))
                },
                label = "track",
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            ) { (title, subtitle) ->
                Column {
                    Text(
                        text = title.orEmpty(),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(text = subtitle, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Where the heart was: how far it played, and how long it is
            if (duration > 0) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 2.dp)) {
                    Text(time(position), color = Color.White, fontSize = 11.sp, style = TabularNumbers)
                    Text(time(duration), color = Dim, fontSize = 11.sp, style = TabularNumbers)
                }
            }

            MiniButton(Icons.Filled.SkipPrevious, stringResource(R.string.music_previous), enabled = state.hasPrevious, onClick = onPrevious)
            MiniButton(
                icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = stringResource(if (state.isPlaying) R.string.music_pause else R.string.music_play),
                onClick = onTogglePlayPause,
            )
            MiniButton(Icons.Filled.SkipNext, stringResource(R.string.music_next), enabled = state.hasNext, onClick = onNext)
            MiniButton(Icons.Filled.Close, stringResource(R.string.music_stop), onClick = onStop, tint = Dim)
        }

        // The progress line along the bottom edge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(Color.White),
            )
        }
    }
}

/** A round icon button that sinks under the finger; a changed icon cross-fades. */
@Composable
private fun MiniButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, tween(PRESS_MS), label = "scale")
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = tween(ANIMATION_MS), label = "icon") { shown ->
            Icon(
                imageVector = shown,
                contentDescription = description,
                tint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun time(ms: Long) = DateUtils.formatElapsedTime(ms / MS_PER_SECOND)

private const val REFRESH_MS = 500L
private const val ANIMATION_MS = 220
private const val PRESS_MS = 90
private const val PRESSED_SCALE = 0.85f
private const val DISABLED_ALPHA = 0.35f
private const val MS_PER_SECOND = 1000L
