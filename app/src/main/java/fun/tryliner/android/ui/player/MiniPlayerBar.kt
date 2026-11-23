package `fun`.tryliner.android.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import `fun`.tryliner.android.ui.theme.LinerMotion
import kotlin.math.abs
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
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

private val MiniPlayerShape = LinerShapes.Floating
private val MiniPlayerBaseBg = Color(0xD914161C)

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
        (localTrack == null || activeRemote.isSlave || (!playbackState.isPlaying && activeRemote.isPlaying))

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
    val baseGlassColor by animateColorAsState(
        targetValue = lerp(
            MiniPlayerBaseBg,
            coverAmbient?.backdropColor ?: LinerColors.AmbientBlue,
            0.34f
        ).copy(alpha = 0.88f),
        animationSpec = LinerMotion.easeOutTween(450),
        label = "miniPlayerBg"
    )
    val progressFillColor by animateColorAsState(
        targetValue = lerp(
            Color.White.copy(alpha = 0.14f),
            (coverAmbient?.accentColor ?: Color.White).copy(alpha = 0.22f),
            0.45f
        ),
        animationSpec = LinerMotion.easeOutTween(450),
        label = "miniPlayerProgressFill"
    )

    val swipeOffsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val swipeThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }
    val rubberbandDimPx = with(LocalDensity.current) { 140.dp.toPx() }
    val dragAlpha = (1f - (abs(swipeOffsetX.value) / (swipeThresholdPx * 3.0f))).coerceIn(0.68f, 1f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .graphicsLayer {
                translationX = swipeOffsetX.value
                alpha = dragAlpha
            }
            .clip(MiniPlayerShape)
            .pointerInput(mainNext, mainPrevious) {
                var rawDrag = 0f
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        rawDrag = 0f
                        velocityTracker.resetTracking()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        rawDrag += dragAmount
                        val rubberbanded = LinerMotion.rubberband(rawDrag, rubberbandDimPx, 0.65f)
                        scope.launch { swipeOffsetX.snapTo(rubberbanded) }
                    },
                    onDragEnd = {
                        val vx = velocityTracker.calculateVelocity().x
                        val projected = rawDrag + LinerMotion.projectMomentum(vx, decelerationRate = 0.985f)
                        when {
                            projected <= -swipeThresholdPx -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                mainNext()
                            }
                            projected >= swipeThresholdPx -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                mainPrevious()
                            }
                        }
                        scope.launch {
                            swipeOffsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = LinerMotion.appleMomentumSpring(),
                                initialVelocity = vx * 0.45f
                            )
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            swipeOffsetX.animateTo(0f, LinerMotion.appleDefaultSpring())
                        }
                    }
                )
            }
            .clickable(onClick = onClick),
        shape = MiniPlayerShape,
        color = baseGlassColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawDesktopProgressFill(progress = progress, fillColor = progressFillColor)
        ) {
            if (activeRemote != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
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
                            tint = Color.White.copy(alpha = 0.70f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val statusLabel = if (activeRemote.isPlaying) "Playing on" else "Paused on"
                        Text(
                            text = "$statusLabel ${activeRemote.deviceName}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = onPlayOnPhone,
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "На телефон",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinerCoverArt(
                    url = coverUrl,
                    contentDescription = title,
                    size = 42.dp,
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
                        color = LinerColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Color.White.copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!showRemoteAsMain) {
                    val heartScale = remember { Animatable(1f) }
                    LaunchedEffect(isLiked) {
                        if (isLiked) {
                            heartScale.animateTo(1.22f, tween(90, easing = LinerMotion.EaseOut))
                            heartScale.animateTo(1f, LinerMotion.bounceSpring())
                        } else {
                            heartScale.snapTo(1f)
                        }
                    }
                    IconButton(
                        onClick = onToggleLike,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isLiked) R.drawable.ic_mgc_heart_fill else R.drawable.ic_mgc_heart_line
                            ),
                            contentDescription = stringResource(if (isLiked) R.string.cd_unlike else R.string.cd_like),
                            tint = if (isLiked) LinerColors.TextPrimary else Color.White.copy(alpha = 0.52f),
                            modifier = Modifier
                                .size(19.dp)
                                .graphicsLayer {
                                    scaleX = heartScale.value
                                    scaleY = heartScale.value
                                }
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
                            color = LinerColors.TextPrimary
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.ic_solar_pause else R.drawable.ic_solar_play
                            ),
                            contentDescription = stringResource(
                                if (isPlaying) R.string.cd_pause else R.string.cd_play
                            ),
                            tint = LinerColors.TextPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
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
