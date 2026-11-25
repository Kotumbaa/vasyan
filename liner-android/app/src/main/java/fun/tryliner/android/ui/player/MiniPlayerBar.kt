package `fun`.tryliner.android.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.util.VelocityTracker
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.pressScale
import `fun`.tryliner.android.ui.theme.rememberReducedMotion
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
        artist = remoteTrack.artist.ifBlank { "Неизвестный артист" }
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
        artist = track.artistNames.ifBlank { "Неизвестный артист" }
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

    val reducedMotion = rememberReducedMotion()
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    val normalDuration = if (reducedMotion) 0 else LinerMotion.DURATION_NORMAL
    val ambientDuration = if (reducedMotion) 0 else LinerMotion.DURATION_AMBIENT
    val coverAmbient = rememberCoverAmbient(coverUrl)
    val baseGlassColor by animateColorAsState(
        targetValue = lerp(
            MiniPlayerBaseBg,
            coverAmbient?.backdropColor ?: LinerColors.AmbientBlue,
            0.34f
        ).copy(alpha = 0.88f),
        animationSpec = LinerMotion.easeOutTween(ambientDuration),
        label = "miniPlayerBg"
    )
    val progressFillColor by animateColorAsState(
        targetValue = lerp(
            Color.White.copy(alpha = 0.14f),
            (coverAmbient?.accentColor ?: Color.White).copy(alpha = 0.22f),
            0.45f
        ),
        animationSpec = LinerMotion.easeOutTween(ambientDuration),
        label = "miniPlayerProgressFill"
    )
    val animatedProgressState = animateFloatAsState(
        targetValue = progress,
        animationSpec = LinerMotion.easeOutTween(normalDuration),
        label = "miniPlayerProgress"
    )

    val swipeOffsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val swipeThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }
    val rubberbandDimPx = with(LocalDensity.current) { 140.dp.toPx() }
    val currentMainNext by rememberUpdatedState(mainNext)
    val currentMainPrevious by rememberUpdatedState(mainPrevious)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .graphicsLayer {
                val offset = swipeOffsetX.value
                translationX = offset
                alpha = (1f - (abs(offset) / (swipeThresholdPx * 3.0f))).coerceIn(0.68f, 1f)
            }
            .clip(MiniPlayerShape)
            .pointerInput(Unit) {
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
                                currentMainNext()
                            }
                            projected >= swipeThresholdPx -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentMainPrevious()
                            }
                        }
                        scope.launch {
                            if (reducedMotion) {
                                swipeOffsetX.snapTo(0f)
                            } else {
                                swipeOffsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = LinerMotion.appleMomentumSpring(),
                                    initialVelocity = vx * 0.45f
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            if (reducedMotion) {
                                swipeOffsetX.snapTo(0f)
                            } else {
                                swipeOffsetX.animateTo(0f, LinerMotion.appleDefaultSpring())
                            }
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
                .drawDesktopProgressFill(
                    progressProvider = { animatedProgressState.value },
                    fillColor = progressFillColor
                )
        ) {
            AnimatedVisibility(
                visible = activeRemote != null,
                enter = expandVertically(LinerMotion.easeOutTween(fastDuration)) +
                    fadeIn(LinerMotion.easeOutTween(fastDuration)),
                exit = shrinkVertically(LinerMotion.easeOutTween(fastDuration)) +
                    fadeOut(LinerMotion.easeOutTween(fastDuration))
            ) {
                val remote = activeRemote
                if (remote != null) {
                    val handoffInteraction = remember { MutableInteractionSource() }
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
                            val statusLabel = if (remote.isPlaying) "Играет на" else "На паузе:"
                            Text(
                                text = "$statusLabel ${remote.deviceName}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            onClick = onPlayOnPhone,
                            interactionSource = handoffInteraction,
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.pressScale(handoffInteraction, targetScale = 0.95f)
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

                AnimatedContent(
                    targetState = Pair(title, artist),
                    transitionSpec = {
                        (fadeIn(LinerMotion.easeOutTween(normalDuration)) +
                            slideInVertically(LinerMotion.easeOutTween(normalDuration)) { it / 5 }) togetherWith
                            (fadeOut(LinerMotion.easeOutTween(fastDuration)) +
                                slideOutVertically(LinerMotion.easeOutTween(fastDuration)) { -it / 5 }) using
                            SizeTransform(clip = false)
                    },
                    modifier = Modifier.weight(1f),
                    label = "miniPlayerMetadata"
                ) { (displayTitle, displayArtist) ->
                    Column {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = LinerColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = displayArtist,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Color.White.copy(alpha = 0.58f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !showRemoteAsMain,
                    enter = fadeIn(LinerMotion.easeOutTween(fastDuration)),
                    exit = fadeOut(LinerMotion.easeOutTween(fastDuration))
                ) {
                    val heartScale = remember(localTrack?.id) { Animatable(1f) }
                    var previousLiked by remember(localTrack?.id) { mutableStateOf(isLiked) }
                    val heartTint by animateColorAsState(
                        targetValue = if (isLiked) LinerColors.TextPrimary else Color.White.copy(alpha = 0.52f),
                        animationSpec = LinerMotion.easeOutTween(fastDuration),
                        label = "miniPlayerHeartTint"
                    )
                    val likeInteraction = remember { MutableInteractionSource() }
                    LaunchedEffect(localTrack?.id, isLiked, reducedMotion) {
                        if (isLiked && !previousLiked && !reducedMotion) {
                            heartScale.animateTo(1.22f, tween(90, easing = LinerMotion.EaseOut))
                            heartScale.animateTo(1f, LinerMotion.bounceSpring())
                        } else if (!isLiked || reducedMotion) {
                            heartScale.snapTo(1f)
                        }
                        previousLiked = isLiked
                    }
                    IconButton(
                        onClick = onToggleLike,
                        interactionSource = likeInteraction,
                        modifier = Modifier
                            .size(36.dp)
                            .pressScale(likeInteraction, targetScale = 0.92f)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isLiked) R.drawable.ic_mgc_heart_fill else R.drawable.ic_mgc_heart_line
                            ),
                            contentDescription = stringResource(if (isLiked) R.string.cd_unlike else R.string.cd_like),
                            tint = heartTint,
                            modifier = Modifier
                                .size(19.dp)
                                .graphicsLayer {
                                    scaleX = heartScale.value
                                    scaleY = heartScale.value
                                }
                        )
                    }
                }

                val playInteraction = remember { MutableInteractionSource() }
                val playStateKey = when {
                    isBuffering -> 0
                    isPlaying -> 1
                    else -> 2
                }
                IconButton(
                    onClick = mainTogglePlayPause,
                    interactionSource = playInteraction,
                    modifier = Modifier
                        .size(40.dp)
                        .pressScale(playInteraction, targetScale = 0.92f)
                ) {
                    AnimatedContent(
                        targetState = playStateKey,
                        transitionSpec = {
                            (fadeIn(LinerMotion.easeOutTween(fastDuration)) +
                                scaleIn(LinerMotion.easeOutTween(fastDuration), initialScale = 0.86f)) togetherWith
                                (fadeOut(LinerMotion.easeOutTween(fastDuration)) +
                                    scaleOut(LinerMotion.easeOutTween(fastDuration), targetScale = 0.86f))
                        },
                        label = "miniPlayerPlayPauseIcon"
                    ) { stateKey ->
                        when (stateKey) {
                            0 -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = LinerColors.TextPrimary
                            )
                            1 -> Icon(
                                painter = painterResource(R.drawable.ic_solar_pause),
                                contentDescription = stringResource(R.string.cd_pause),
                                tint = LinerColors.TextPrimary,
                                modifier = Modifier.size(21.dp)
                            )
                            else -> Icon(
                                painter = painterResource(R.drawable.ic_solar_play),
                                contentDescription = stringResource(R.string.cd_play),
                                tint = LinerColors.TextPrimary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.drawDesktopProgressFill(
    progressProvider: () -> Float,
    fillColor: Color
): Modifier = drawBehind {
    val progress = progressProvider()
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
