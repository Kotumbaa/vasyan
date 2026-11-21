package `fun`.tryliner.android.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.tryliner.android.R
import `fun`.tryliner.android.playback.PlaybackUiState
import `fun`.tryliner.android.playback.RemotePlaybackState
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.rememberCoverAmbient
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

private val MiniPlayerShape = LinerShapes.Control
private val MiniPlayerBaseBg = Color(0xFF28282E)

@Composable
fun MiniPlayerBar(
    playbackState: PlaybackUiState,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    remoteState: RemotePlaybackState? = null,
    onRemoteTogglePlayPause: () -> Unit = {},
    onRemotePrevious: () -> Unit = {},
    onRemoteNext: () -> Unit = {},
    onPlayOnPhone: () -> Unit = {}
) {
    val activeRemote = remoteState?.takeIf { it.track != null }
    val localTrack = playbackState.currentTrack
    if (localTrack == null && activeRemote == null) return

    val showRemoteAsMain = activeRemote != null &&
        (localTrack == null || (!playbackState.isPlaying && activeRemote.isPlaying))

    val title: String
    val artist: String
    val coverUrl: String?
    val isPlaying: Boolean
    val isBuffering: Boolean
    val progress: Float
    val mainTogglePlayPause: () -> Unit
    val mainPrevious: () -> Unit
    val mainNext: () -> Unit

    if (showRemoteAsMain && activeRemote != null) {
        val remoteTrack = activeRemote.track!!
        title = remoteTrack.title
        artist = remoteTrack.artist.ifBlank { "Unknown Artist" }
        coverUrl = remoteTrack.coverUrl
        isPlaying = activeRemote.isPlaying
        isBuffering = false
        progress = if (activeRemote.durationMs > 0L) {
            (activeRemote.positionMs.toFloat() / activeRemote.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        mainTogglePlayPause = onRemoteTogglePlayPause
        mainPrevious = onRemotePrevious
        mainNext = onRemoteNext
    } else {
        val track = localTrack!!
        title = track.title
        artist = track.artistNames.ifBlank { "Unknown Artist" }
        coverUrl = track.cover?.url ?: track.cover?.fallbackUrl
        isPlaying = playbackState.isPlaying
        isBuffering = playbackState.isBuffering
        progress = if (playbackState.durationMs > 0L) {
            (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        mainTogglePlayPause = onTogglePlayPause
        mainPrevious = onPrevious
        mainNext = onNext
    }

    val coverAmbient = rememberCoverAmbient(coverUrl)
    val spotifyBarBg by animateColorAsState(
        targetValue = lerp(
            MiniPlayerBaseBg,
            coverAmbient?.backdropColor ?: LinerColors.AmbientBlue,
            0.82f
        ),
        animationSpec = tween(durationMillis = 450),
        label = "miniPlayerBg"
    )

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.8f),
        label = "miniPlayerSwipeOffset"
    )
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .graphicsLayer { translationX = animatedOffsetX }
            .clip(MiniPlayerShape)
            .pointerInput(mainNext, mainPrevious) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        dragOffsetX = (totalDrag * 0.45f).coerceIn(-80f, 80f)
                    },
                    onDragEnd = {
                        when {
                            totalDrag <= -swipeThresholdPx -> mainNext()
                            totalDrag >= swipeThresholdPx -> mainPrevious()
                        }
                        dragOffsetX = 0f
                    },
                    onDragCancel = { dragOffsetX = 0f }
                )
            }
            .clickable(onClick = onClick),
        shape = MiniPlayerShape,
        color = spotifyBarBg
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinerCoverArt(
                    url = coverUrl,
                    contentDescription = title,
                    size = 40.dp,
                    shape = LinerShapes.Tile
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeRemote != null) {
                            Icon(
                                imageVector = Icons.Rounded.Computer,
                                contentDescription = null,
                                tint = Color(0xFF1ED760),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = activeRemote.deviceName,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = Color(0xFF1ED760),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (activeRemote != null) {
                    IconButton(
                        onClick = onPlayOnPhone,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Smartphone,
                            contentDescription = "Play on Phone",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                if (!showRemoteAsMain) {
                    IconButton(
                        onClick = onToggleLike,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isLiked) R.drawable.ic_mgc_heart_fill else R.drawable.ic_mgc_heart_line
                            ),
                            contentDescription = stringResource(if (isLiked) R.string.cd_unlike else R.string.cd_like),
                            tint = if (isLiked) Color(0xFF1ED760) else Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = mainTogglePlayPause,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.ic_solar_pause else R.drawable.ic_solar_play
                            ),
                            contentDescription = stringResource(
                                if (isPlaying) R.string.cd_pause else R.string.cd_play
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Razor-thin Spotify progress line along bottom edge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(Color.White)
                )
            }
        }
    }
}

private fun Modifier.drawDesktopProgressFill(
    progress: Float,
    fillColor: Color
): Modifier = drawBehind {
    if (progress > 0f) {
        val fillWidth = (size.width * progress).coerceIn(0f, size.width)
        val featherWidth = 10.dp.toPx()
        val solidEnd = (fillWidth - featherWidth).coerceAtLeast(0f)

        if (solidEnd > 0f) {
            drawRect(
                color = fillColor,
                size = Size(width = solidEnd, height = size.height)
            )
        }

        val edgeStart = solidEnd
        val edgeEnd = (fillWidth + featherWidth * 0.4f).coerceAtMost(size.width)
        if (edgeEnd > edgeStart) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        fillColor,
                        Color.Transparent
                    ),
                    startX = edgeStart,
                    endX = edgeEnd
                ),
                topLeft = Offset(edgeStart, 0f),
                size = Size(width = edgeEnd - edgeStart, height = size.height)
            )
        }
    }
}
