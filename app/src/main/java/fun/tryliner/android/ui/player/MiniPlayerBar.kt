package `fun`.tryliner.android.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `fun`.tryliner.android.R
import `fun`.tryliner.android.playback.PlaybackUiState
import `fun`.tryliner.android.playback.RemotePlaybackState
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.rememberCoverAmbient
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

private val MiniPlayerShape = LinerShapes.Floating
private val MiniPlayerUnplayedBg = LinerColors.MiniPlayerGlass

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
    val animatedFillTint by animateColorAsState(
        targetValue = (coverAmbient?.accentColor ?: Color.White).copy(alpha = 0.22f),
        animationSpec = tween(durationMillis = 450),
        label = "miniPlayerProgressTint"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(MiniPlayerShape)
            .clickable(onClick = onClick),
        shape = MiniPlayerShape,
        color = MiniPlayerUnplayedBg,
        border = BorderStroke(0.5.dp, LinerColors.BorderAlpha14)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawDesktopProgressFill(progress = progress, fillColor = animatedFillTint)
        ) {
            if (activeRemote != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Computer,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (activeRemote.isPlaying) {
                                "Playing on ${activeRemote.deviceName}"
                            } else {
                                "Paused on ${activeRemote.deviceName}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(onClick = onPlayOnPhone)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Smartphone,
                            contentDescription = null,
                            tint = LinerColors.TextPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Play on Phone",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = LinerColors.TextPrimary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinerCoverArt(
                    url = coverUrl,
                    contentDescription = title,
                    size = 42.dp,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = LinerColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!showRemoteAsMain) {
                    IconButton(
                        onClick = onToggleLike,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isLiked) R.drawable.ic_mgc_heart_fill else R.drawable.ic_mgc_heart_line
                            ),
                            contentDescription = stringResource(if (isLiked) R.string.cd_unlike else R.string.cd_like),
                            tint = if (isLiked) LinerColors.AccentRed else LinerColors.TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = mainPrevious,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_solar_skip_prev),
                        contentDescription = stringResource(R.string.cd_previous),
                        tint = LinerColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = mainTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LinerColors.AmbientBase
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.ic_solar_pause else R.drawable.ic_solar_play
                            ),
                            contentDescription = stringResource(
                                if (isPlaying) R.string.cd_pause else R.string.cd_play
                            ),
                            tint = LinerColors.AmbientBase,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                IconButton(
                    onClick = mainNext,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_solar_skip_next),
                        contentDescription = stringResource(R.string.cd_next),
                        tint = LinerColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
