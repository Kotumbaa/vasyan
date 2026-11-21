package `fun`.tryliner.android.ui.player

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.pressScale
import `fun`.tryliner.android.ui.theme.rememberReducedMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.tryliner.android.LinerApplication
import `fun`.tryliner.android.R
import `fun`.tryliner.android.core.lyrics.LyricLine
import `fun`.tryliner.android.core.lyrics.SyncedLyrics
import `fun`.tryliner.android.core.model.Cover
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.TrackArtist
import `fun`.tryliner.android.playback.PlaybackUiState
import `fun`.tryliner.android.playback.RemotePlaybackState
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.formatDurationMs
import `fun`.tryliner.android.ui.components.rememberCoverAmbient
import `fun`.tryliner.android.ui.theme.LinerColors

private val BackdropBase = Color(0xFF0D0F14)

@Composable
fun NowPlayingScreen(
    playbackState: PlaybackUiState,
    isLiked: Boolean,
    onCollapse: () -> Unit,
    onToggleLike: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onDragDelta: ((Float) -> Unit)? = null,
    onDragRelease: ((Float) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as? LinerApplication
    val lanConnectManager = app?.container?.lanConnectManager
    val remoteState: RemotePlaybackState? = if (lanConnectManager != null) {
        lanConnectManager.remoteState.collectAsState().value
    } else {
        null
    }
    val lanConnected: Boolean = if (lanConnectManager != null) {
        lanConnectManager.isConnected.collectAsState().value
    } else {
        false
    }

    val isRemoteMode = (playbackState.currentTrack == null && remoteState?.track != null) ||
        (!playbackState.isPlaying && !playbackState.isBuffering && remoteState?.isPlaying == true && remoteState.track != null)

    val track: Track? = if (isRemoteMode) {
        remoteState?.track?.let { rt ->
            Track(
                id = rt.id,
                title = rt.title,
                artists = if (rt.artist.isBlank()) emptyList() else listOf(TrackArtist(id = "", name = rt.artist)),
                durationMs = rt.durationMs,
                cover = rt.coverUrl?.takeIf { it.isNotBlank() }?.let { Cover(url = it, width = 0, height = 0) }
            )
        }
    } else {
        playbackState.currentTrack
    }

    val effectiveIsPlaying = if (isRemoteMode) (remoteState?.isPlaying == true) else playbackState.isPlaying
    val effectiveIsBuffering = if (isRemoteMode) false else playbackState.isBuffering
    val rawDurationMs = if (isRemoteMode) {
        (remoteState?.durationMs ?: 0L).coerceAtLeast(track?.durationMs ?: 0L)
    } else {
        playbackState.durationMs
    }
    var showLyrics by remember { mutableStateOf(false) }
    var scrubbingPositionMs by remember { mutableStateOf<Long?>(null) }

    val durationMs = rawDurationMs.coerceAtLeast(1L)
    val basePositionMs = if (isRemoteMode) (remoteState?.positionMs ?: 0L) else playbackState.positionMs
    val displayedPositionMs = scrubbingPositionMs ?: basePositionMs

    val effectiveTogglePlayPause: () -> Unit = if (isRemoteMode) {
        { lanConnectManager?.sendCommand("toggle") }
    } else {
        onTogglePlayPause
    }
    val effectiveNext: () -> Unit = if (isRemoteMode) {
        { lanConnectManager?.sendCommand("next") }
    } else {
        onNext
    }
    val effectivePrevious: () -> Unit = if (isRemoteMode) {
        { lanConnectManager?.sendCommand("prev") }
    } else {
        onPrevious
    }
    val effectiveSeekTo: (Long) -> Unit = if (isRemoteMode) {
        { ms -> lanConnectManager?.sendCommand("seek", positionMs = ms) }
    } else {
        onSeekTo
    }

    val coverUrl = track?.cover?.url ?: track?.cover?.fallbackUrl
    val coverAmbient = rememberCoverAmbient(coverUrl)
    val targetTopColor = coverAmbient?.backdropColor ?: LinerColors.AmbientBlue
    val targetMidColor = coverAmbient?.backdropColor?.copy(alpha = 0.72f) ?: LinerColors.AmbientBlue.copy(alpha = 0.72f)
    val reducedMotion = rememberReducedMotion()
    val topColor by animateColorAsState(
        targetValue = targetTopColor,
        animationSpec = LinerMotion.easeOutTween(if (reducedMotion) 0 else LinerMotion.DURATION_AMBIENT),
        label = "nowPlayingTopColor"
    )
    val midColor by animateColorAsState(
        targetValue = targetMidColor,
        animationSpec = LinerMotion.easeOutTween(if (reducedMotion) 0 else LinerMotion.DURATION_AMBIENT),
        label = "nowPlayingMidColor"
    )

    val haptics = LocalHapticFeedback.current
    val dismissThresholdPx = with(LocalDensity.current) { 110.dp.toPx() }
    var localDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackdropBase)
            .pointerInput(showLyrics, onCollapse, onDragDelta, onDragRelease) {
                if (!showLyrics) {
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            localDragY = 0f
                            velocityTracker.resetTracking()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            localDragY = (localDragY + dragAmount).coerceAtLeast(0f)
                            if (localDragY > 0f || dragAmount > 0f) {
                                change.consume()
                                onDragDelta?.invoke(dragAmount)
                            }
                        },
                        onDragEnd = {
                            val vy = velocityTracker.calculateVelocity().y
                            if (onDragRelease != null) {
                                if (localDragY > 12f || vy >= 650f) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onDragRelease.invoke(vy)
                            } else if (localDragY >= dismissThresholdPx || vy >= 650f) {
                                onCollapse()
                            }
                            localDragY = 0f
                        },
                        onDragCancel = {
                            onDragRelease?.invoke(0f)
                            localDragY = 0f
                        }
                    )
                }
            }
    ) {
        // Blurred cover-art ambient layer behind the gradient (matching desktop FullscreenPlayer)
        val blurredBitmap = coverAmbient?.blurredBitmap
        if (blurredBitmap != null) {
            Crossfade(
                targetState = blurredBitmap,
                animationSpec = LinerMotion.easeOutTween(if (reducedMotion) 0 else 600),
                label = "nowPlayingCoverBlur"
            ) { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.38f)
                )
            }
        }

        // Full-screen ambient gradient matching desktop Kawarp / PageCoverBackdrop depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to topColor.copy(alpha = 0.86f),
                        0.40f to midColor.copy(alpha = 0.70f),
                        0.76f to BackdropBase.copy(alpha = 0.82f),
                        1.0f to BackdropBase.copy(alpha = 0.94f)
                    )
                )
        )

        // Full-screen subtle radial aura
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            topColor.copy(alpha = 0.32f),
                            midColor.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        radius = 1400f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            // Spotify-style top bar: Chevron down | Centered source/album | Download
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(onCollapse, onDragDelta, onDragRelease) {
                        val velocityTracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = {
                                localDragY = 0f
                                velocityTracker.resetTracking()
                            },
                            onVerticalDrag = { change, dragAmount ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                localDragY = (localDragY + dragAmount).coerceAtLeast(0f)
                                if (localDragY > 0f || dragAmount > 0f) {
                                    change.consume()
                                    onDragDelta?.invoke(dragAmount)
                                }
                            },
                            onDragEnd = {
                                val vy = velocityTracker.calculateVelocity().y
                                if (onDragRelease != null) {
                                    if (localDragY > 12f || vy >= 650f) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    onDragRelease.invoke(vy)
                                } else if (localDragY >= dismissThresholdPx || vy >= 650f) {
                                    onCollapse()
                                }
                                localDragY = 0f
                            },
                            onDragCancel = {
                                onDragRelease?.invoke(0f)
                                localDragY = 0f
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mgc_down_line),
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(
                            if (isRemoteMode) R.string.now_playing_remote_header else R.string.now_playing_header
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            letterSpacing = 0.8.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1
                    )
                    Text(
                        text = if (isRemoteMode) {
                            remoteState?.deviceName ?: "Desktop"
                        } else {
                            track?.album?.title?.takeIf { it.isNotBlank() } ?: "vasyan"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (track != null) {
                    `fun`.tryliner.android.ui.components.DownloadButton(
                        track = track,
                        iconSize = 22.dp,
                        buttonSize = 40.dp
                    )
                } else {
                    Spacer(modifier = Modifier.size(40.dp))
                }
            }

            // Compact LAN Connect indicator / handoff pill when a desktop peer is active
            if (lanConnectManager != null && (lanConnected || remoteState != null)) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LanConnectPill(
                        deviceName = remoteState?.deviceName ?: "Desktop",
                        actionLabel = if (isRemoteMode) "Play on Phone" else if (track != null) "Play on PC" else null,
                        onAction = {
                            if (isRemoteMode) {
                                lanConnectManager.handoffToPhone()
                            } else {
                                lanConnectManager.handoffToRemote()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main center area: Artwork or Borderless Lyrics with vertical fade mask
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = {
                        if (reducedMotion) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            (fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) +
                                scaleIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL), initialScale = 0.96f)) togetherWith
                                (fadeOut(LinerMotion.easeOutTween(160)) +
                                    scaleOut(LinerMotion.easeOutTween(160), targetScale = 0.98f))
                        }
                    },
                    label = "now_playing_center"
                ) { lyricsVisible ->
                    if (lyricsVisible) {
                        NowPlayingLyricsView(
                            lyrics = playbackState.lyrics,
                            positionMs = displayedPositionMs,
                            isLoading = playbackState.isLyricsLoading,
                            onSeekTo = onSeekTo,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val coverShape = RoundedCornerShape(10.dp)
                            LinerCoverArt(
                                url = track?.cover?.url ?: track?.cover?.fallbackUrl,
                                contentDescription = track?.title,
                                size = 340.dp,
                                shape = coverShape,
                                modifier = Modifier
                                    .fillMaxWidth(0.96f)
                                    .aspectRatio(1f)
                                    .shadow(
                                        elevation = 24.dp,
                                        shape = coverShape,
                                        clip = false,
                                        ambientColor = Color.Black.copy(alpha = 0.55f),
                                        spotColor = Color.Black.copy(alpha = 0.65f)
                                    )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Track metadata + monochrome white heart button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track?.artistNames?.ifBlank { "Unknown Artist" } ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (track != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    val heartScale = remember { Animatable(1f) }
                    LaunchedEffect(isLiked) {
                        if (isLiked && !reducedMotion) {
                            heartScale.animateTo(1.22f, tween(90, easing = LinerMotion.EaseOut))
                            heartScale.animateTo(1f, LinerMotion.bounceSpring())
                        } else {
                            heartScale.snapTo(1f)
                        }
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleLike()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isLiked) R.drawable.ic_mgc_heart_fill else R.drawable.ic_mgc_heart_line
                            ),
                            contentDescription = stringResource(
                                if (isLiked) R.string.cd_unlike else R.string.cd_like
                            ),
                            tint = if (isLiked) LinerColors.AccentRed else Color.White.copy(alpha = 0.75f),
                            modifier = Modifier
                                .size(26.dp)
                                .graphicsLayer {
                                    scaleX = heartScale.value
                                    scaleY = heartScale.value
                                }
                        )
                    }
                }
            }

            if (!playbackState.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = playbackState.errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Minimalist 4.dp scrubber (no thumb bar, no end dot)
            val progressFraction = (displayedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            MinimalScrubber(
                progress = progressFraction,
                durationMs = durationMs,
                onScrub = { scrubbingPositionMs = it },
                onScrubEnd = { finalMs ->
                    effectiveSeekTo(finalMs)
                    scrubbingPositionMs = null
                },
                onScrubCancel = { scrubbingPositionMs = null }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDurationMs(displayedPositionMs),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    color = Color.White.copy(alpha = 0.55f)
                )
                Text(
                    text = formatDurationMs(rawDurationMs),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    color = Color.White.copy(alpha = 0.55f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Spotify 5-slot transport controls: Shuffle | Prev | Big Play | Next | Lyrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.size(44.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_solar_shuffle),
                            contentDescription = stringResource(R.string.cd_shuffle),
                            tint = if (playbackState.shuffleEnabled) {
                                Color(0xFF1ED760)
                            } else {
                                Color.White.copy(alpha = 0.65f)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                        if (playbackState.shuffleEnabled) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1ED760))
                            )
                        }
                    }
                }

                IconButton(
                    onClick = effectivePrevious,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_solar_skip_prev),
                        contentDescription = stringResource(R.string.cd_previous),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                val playInteraction = remember { MutableInteractionSource() }
                FilledIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        effectiveTogglePlayPause()
                    },
                    interactionSource = playInteraction,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = BackdropBase
                    ),
                    modifier = Modifier
                        .size(64.dp)
                        .pressScale(playInteraction, targetScale = 0.94f)
                ) {
                    if (effectiveIsBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = BackdropBase
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (effectiveIsPlaying) R.drawable.ic_solar_pause else R.drawable.ic_solar_play
                            ),
                            contentDescription = stringResource(
                                if (effectiveIsPlaying) R.string.cd_pause else R.string.cd_play
                            ),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                IconButton(
                    onClick = effectiveNext,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_solar_skip_next),
                        contentDescription = stringResource(R.string.cd_next),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = { showLyrics = !showLyrics },
                    modifier = Modifier.size(44.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = stringResource(R.string.cd_lyrics),
                            tint = if (showLyrics) Color(0xFF1ED760) else Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(22.dp)
                        )
                        if (showLyrics) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1ED760))
                            )
                        }
                    }
                }
            }

            if (!showLyrics) {
                Spacer(modifier = Modifier.height(14.dp))
                val previewCardBg = lerp(Color(0xFF1E1E24), topColor, 0.72f)
                val lyr = playbackState.lyrics
                val activeIdx = remember(lyr, displayedPositionMs) {
                    if (lyr != null && lyr.lines.isNotEmpty()) {
                        lyr.findActiveLineIndex(displayedPositionMs).coerceAtLeast(0)
                    } else {
                        -1
                    }
                }
                val loadingText = stringResource(R.string.lyrics_loading)
                val tapText = stringResource(R.string.lyrics_preview_tap)
                val previewPair: Pair<String, String?> = remember(lyr, activeIdx, playbackState.isLyricsLoading, loadingText, tapText) {
                    when {
                        lyr != null && lyr.lines.isNotEmpty() && activeIdx >= 0 -> {
                            val current = lyr.lines.getOrNull(activeIdx)?.text.orEmpty()
                            val next = lyr.lines.getOrNull(activeIdx + 1)?.text
                            current to next
                        }
                        !lyr?.plainText.isNullOrBlank() -> {
                            val plain = lyr?.plainText?.lines()?.filter { it.isNotBlank() }.orEmpty()
                            (plain.firstOrNull() ?: "") to plain.getOrNull(1)
                        }
                        playbackState.isLyricsLoading -> loadingText to null
                        else -> tapText to null
                    }
                }
                val desktopEase = LinerMotion.EaseOut
                Surface(
                    onClick = { showLyrics = true },
                    shape = RoundedCornerShape(14.dp),
                    color = previewCardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.lyrics_preview_title),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AnimatedContent(
                            targetState = Triple(activeIdx, previewPair.first, previewPair.second),
                            transitionSpec = {
                                val enter = slideInVertically(
                                    animationSpec = tween(durationMillis = 360, easing = desktopEase),
                                    initialOffsetY = { fullHeight -> (fullHeight * 0.45f).toInt() }
                                ) + fadeIn(
                                    animationSpec = tween(durationMillis = 320, easing = desktopEase)
                                )
                                val exit = slideOutVertically(
                                    animationSpec = tween(durationMillis = 260, easing = desktopEase),
                                    targetOffsetY = { fullHeight -> -(fullHeight * 0.45f).toInt() }
                                ) + fadeOut(
                                    animationSpec = tween(durationMillis = 220, easing = desktopEase)
                                )
                                enter togetherWith exit
                            },
                            label = "lyrics_preview_slide"
                        ) { (_, currentLine, nextLine) ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = currentLine,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = 21.sp
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!nextLine.isNullOrBlank()) {
                                    Text(
                                        text = nextLine,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 19.sp
                                        ),
                                        color = Color.White.copy(alpha = 0.55f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun MinimalScrubber(
    progress: Float,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onScrubCancel: () -> Unit
) {
    val pillShape = RoundedCornerShape(999.dp)
    var isDragging by remember { mutableStateOf(false) }
    val trackHeightDp by animateDpAsState(
        targetValue = if (isDragging) 5.dp else 3.dp,
        animationSpec = LinerMotion.easeOutTween(120),
        label = "scrubberTrackHeight"
    )
    val thumbRadiusDp by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 5.5.dp,
        animationSpec = LinerMotion.easeOutTween(120),
        label = "scrubberThumbRadius"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onScrubEnd((fraction * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                var lastScrubMs = 0L
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val fraction = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        lastScrubMs = (fraction * durationMs).toLong()
                        onScrub(lastScrubMs)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        lastScrubMs = (fraction * durationMs).toLong()
                        onScrub(lastScrubMs)
                    },
                    onDragEnd = {
                        isDragging = false
                        onScrubEnd(lastScrubMs)
                    },
                    onDragCancel = {
                        isDragging = false
                        onScrubCancel()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
        ) {
            val fraction = progress.coerceIn(0f, 1f)
            val centerY = size.height / 2f
            val trackHeight = trackHeightDp.toPx()
            val thumbRadius = thumbRadiusDp.toPx()
            val usableWidth = (size.width - thumbRadius * 2).coerceAtLeast(1f)
            val thumbX = thumbRadius + usableWidth * fraction

            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = androidx.compose.ui.geometry.Offset(thumbRadius, centerY),
                end = androidx.compose.ui.geometry.Offset(size.width - thumbRadius, centerY),
                strokeWidth = trackHeight,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = androidx.compose.ui.geometry.Offset(thumbRadius, centerY),
                end = androidx.compose.ui.geometry.Offset(thumbX, centerY),
                strokeWidth = trackHeight,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = androidx.compose.ui.geometry.Offset(thumbX, centerY)
            )
        }
    }
}

@Composable
private fun NowPlayingLyricsView(
    lyrics: SyncedLyrics?,
    positionMs: Long,
    isLoading: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val fadeMaskModifier = modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.12f to Color.Black,
                    0.88f to Color.Black,
                    1.0f to Color.Transparent
                ),
                blendMode = BlendMode.DstIn
            )
        }

    when {
        isLoading && lyrics == null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.72f))
            }
        }
        lyrics == null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics available",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
        }
        lyrics.isInstrumental -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Instrumental",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }
        lyrics.isSynced && lyrics.lines.isNotEmpty() -> {
            val activeIndex = lyrics.findActiveLineIndex(positionMs)
            val listState = rememberLazyListState()

            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0) {
                    val target = (activeIndex - 2).coerceAtLeast(0)
                    listState.animateScrollToItem(target)
                }
            }

            LazyColumn(
                state = listState,
                modifier = fadeMaskModifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 44.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(
                    items = lyrics.lines,
                    key = { index, line -> "${index}_${line.startMs}" }
                ) { index, line ->
                    NowPlayingLyricLineRow(
                        line = line,
                        isActive = index == activeIndex,
                        onClick = { onSeekTo(line.startMs) }
                    )
                }
            }
        }
        !lyrics.plainText.isNullOrBlank() -> {
            Column(
                modifier = fadeMaskModifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 36.dp)
            ) {
                Text(
                    text = lyrics.plainText.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 20.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
        }
        else -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics available",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun NowPlayingLyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val textColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.28f),
        animationSpec = tween(durationMillis = 200),
        label = "lyricLineColor"
    )

    val displayText = if (line.isInstrumental && line.text.isBlank()) {
        "• • •"
    } else {
        line.text
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 22.sp,
                lineHeight = 30.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = textColor
        )
        if (!line.subtitle.isNullOrBlank()) {
            Text(
                text = line.subtitle.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun LanConnectPill(
    deviceName: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp,
                end = if (actionLabel != null) 6.dp else 12.dp,
                top = 5.dp,
                bottom = 5.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Computer,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (actionLabel != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onAction)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
