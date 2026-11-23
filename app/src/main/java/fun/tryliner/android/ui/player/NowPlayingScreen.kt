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
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import `fun`.tryliner.android.playback.PlaybackUiState
import `fun`.tryliner.android.playback.RemotePlaybackState
import `fun`.tryliner.android.playback.RepeatMode
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.formatDurationMs
import `fun`.tryliner.android.ui.components.rememberCoverAmbient
import `fun`.tryliner.android.ui.theme.LinerColors

private val BackdropBase = Color(0xFF0D0F14)

@OptIn(ExperimentalMaterial3Api::class)
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
    onOpenArtist: (String) -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onPlayQueueIndex: (Int) -> Unit = {},
    onOpenTrackMenu: (Track) -> Unit = {},
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

    val isLocalSlave = if (lanConnectManager != null) {
        lanConnectManager.isLocalSlave.collectAsState().value
    } else {
        false
    }

    val isRemoteMode = remoteState?.track != null && !isLocalSlave &&
        (remoteState.isSlave || playbackState.currentTrack == null || (!playbackState.isPlaying && !playbackState.isBuffering && remoteState.isPlaying))

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
    var showQueueSheet by remember { mutableStateOf(false) }
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
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val coverShape = RoundedCornerShape(12.dp)
                            val coverScale by animateFloatAsState(
                                targetValue = if (effectiveIsPlaying || reducedMotion) 1f else 0.93f,
                                animationSpec = LinerMotion.appleDefaultSpring(),
                                label = "coverPlayPauseScale"
                            )
                            var swipeOffsetX by remember { mutableFloatStateOf(0f) }
                            val animatedSwipeX by animateFloatAsState(
                                targetValue = swipeOffsetX,
                                animationSpec = LinerMotion.appleDefaultSpring(),
                                label = "coverSwipeX"
                            )
                            LinerCoverArt(
                                url = track?.cover?.url ?: track?.cover?.fallbackUrl,
                                contentDescription = track?.title,
                                size = 320.dp,
                                shape = coverShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        scaleX = coverScale
                                        scaleY = coverScale
                                        translationX = animatedSwipeX * 0.45f
                                    }
                                    .pointerInput(track?.id) {
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { change, dragAmount ->
                                                change.consume()
                                                swipeOffsetX = (swipeOffsetX + dragAmount).coerceIn(-220f, 220f)
                                            },
                                            onDragEnd = {
                                                if (swipeOffsetX <= -90f) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    effectiveNext()
                                                } else if (swipeOffsetX >= 90f) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    effectivePrevious()
                                                }
                                                swipeOffsetX = 0f
                                            },
                                            onDragCancel = { swipeOffsetX = 0f }
                                        )
                                    }
                                    .shadow(
                                        elevation = if (effectiveIsPlaying) 28.dp else 14.dp,
                                        shape = coverShape,
                                        clip = false,
                                        ambientColor = Color.Black.copy(alpha = 0.55f),
                                        spotColor = Color.Black.copy(alpha = 0.65f)
                                    )
                            )

                            // Spotify-style left-aligned live lyrics preview under cover art
                            // with the same scroll-up & color transition as fullscreen lyrics
                            val syncedLyrics = playbackState.lyrics
                            if (syncedLyrics != null && syncedLyrics.isSynced && syncedLyrics.lines.isNotEmpty()) {
                                val activeIndex = syncedLyrics.findActiveLineIndex(displayedPositionMs)
                                val previewListState = rememberLazyListState()

                                LaunchedEffect(activeIndex) {
                                    if (activeIndex >= 0) {
                                        previewListState.animateScrollToItem(activeIndex)
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                LazyColumn(
                                    state = previewListState,
                                    userScrollEnabled = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showLyrics = true
                                        }
                                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    0.0f to Color.Transparent,
                                                    0.10f to Color.Black,
                                                    0.72f to Color.Black,
                                                    1.0f to Color.Transparent
                                                ),
                                                blendMode = BlendMode.DstIn
                                            )
                                        },
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(
                                        items = syncedLyrics.lines,
                                        key = { index, line -> "preview_${index}_${line.startMs}" }
                                    ) { index, line ->
                                        val isActive = index == activeIndex
                                        val lineColor by animateColorAsState(
                                            targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.28f),
                                            animationSpec = tween(durationMillis = if (reducedMotion) 0 else 200),
                                            label = "previewLyricColor"
                                        )
                                        val lineText = if (line.isInstrumental && line.text.isBlank()) {
                                            "• • •"
                                        } else {
                                            line.text
                                        }
                                        Text(
                                            text = lineText,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 16.sp,
                                                lineHeight = 22.sp,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold
                                            ),
                                            color = lineColor,
                                            textAlign = TextAlign.Start,
                                            softWrap = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
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
                        text = track?.title ?: "Ничего не играет",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 21.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White,
                        softWrap = true
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val primaryArtist = track?.artists?.firstOrNull()
                    val artistDisplayText = track?.artistNames?.ifBlank { "Неизвестный артист" } ?: ""
                    val artistTargetId = when {
                        primaryArtist != null && primaryArtist.id.isNotBlank() && primaryArtist.id != "unknown" ->
                            primaryArtist.id
                        primaryArtist != null && primaryArtist.name.isNotBlank() ->
                            "name:${primaryArtist.name}"
                        artistDisplayText.isNotBlank() && artistDisplayText != "Неизвестный артист" ->
                            "name:${artistDisplayText.split(",").first().trim()}"
                        else -> null
                    }
                    Text(
                        text = artistDisplayText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        color = Color.White.copy(alpha = 0.72f),
                        softWrap = true,
                        modifier = if (artistTargetId != null) {
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onOpenArtist(artistTargetId)
                                }
                                .padding(vertical = 2.dp)
                        } else {
                            Modifier
                        }
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
                            tint = if (isLiked) LinerColors.TextPrimary else Color.White.copy(alpha = 0.55f),
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
                val rawErr = playbackState.errorMessage.orEmpty()
                val friendlyErr = when {
                    rawErr.contains("Unable to resolve host", ignoreCase = true) ||
                        rawErr.contains("No address", ignoreCase = true) ||
                        rawErr.contains("timed out", ignoreCase = true) ||
                        rawErr.contains("unreachable", ignoreCase = true) ->
                        "Нет сети — нажмите ▶ для повтора"
                    rawErr.contains("HTTP 5", ignoreCase = true) ->
                        "Сервер временно недоступен — нажмите ▶"
                    else -> "Ошибка воспроизведения — нажмите ▶"
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = friendlyErr,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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

            // Balanced 5-slot monochrome transport controls: Shuffle | Prev | Play 56dp | Next | Lyrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleShuffle()
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_solar_shuffle),
                            contentDescription = stringResource(R.string.cd_shuffle),
                            tint = if (playbackState.shuffleEnabled) {
                                LinerColors.TextPrimary
                            } else {
                                Color.White.copy(alpha = 0.50f)
                            },
                            modifier = Modifier.size(21.dp)
                        )
                        if (playbackState.shuffleEnabled) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(LinerColors.TextPrimary)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        effectivePrevious()
                    },
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_solar_skip_prev),
                        contentDescription = stringResource(R.string.cd_previous),
                        tint = LinerColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
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
                        .size(56.dp)
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
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        effectiveNext()
                    },
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_solar_skip_next),
                        contentDescription = stringResource(R.string.cd_next),
                        tint = LinerColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showLyrics = !showLyrics
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = stringResource(R.string.cd_lyrics),
                            tint = if (showLyrics) LinerColors.TextPrimary else Color.White.copy(alpha = 0.50f),
                            modifier = Modifier.size(21.dp)
                        )
                        if (showLyrics) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(LinerColors.TextPrimary)
                            )
                        }
                    }
                }
            }

            if (isRemoteMode && lanConnectManager != null && remoteState != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.60f),
                        modifier = Modifier.size(17.dp)
                    )
                    MinimalScrubber(
                        progress = remoteState.volume.coerceIn(0f, 1f),
                        durationMs = 1000L,
                        onScrub = { v -> lanConnectManager.setRemoteVolume(v / 1000f) },
                        onScrubEnd = { v -> lanConnectManager.setRemoteVolume(v / 1000f) },
                        onScrubCancel = {},
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Secondary bottom actions: Repeat | Queue | Track Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCycleRepeat()
                    },
                    modifier = Modifier.size(42.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (playbackState.repeatMode == RepeatMode.ONE) {
                                Icons.Rounded.RepeatOne
                            } else {
                                Icons.Rounded.Repeat
                            },
                            contentDescription = "Повтор",
                            tint = if (playbackState.repeatMode != RepeatMode.OFF) {
                                LinerColors.TextPrimary
                            } else {
                                Color.White.copy(alpha = 0.46f)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        if (playbackState.repeatMode != RepeatMode.OFF) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(3.5.dp)
                                    .clip(CircleShape)
                                    .background(LinerColors.TextPrimary)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showQueueSheet = true
                    },
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "Очередь",
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        track?.let(onOpenTrackMenu)
                    },
                    enabled = track != null,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = "Действия",
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }

        if (showQueueSheet) {
            val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                sheetState = queueSheetState,
                containerColor = Color(0xFF13161D),
                contentColor = Color.White,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, bottom = 6.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "Очередь (${playbackState.queue.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = LinerColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                    if (playbackState.queue.isEmpty()) {
                        Text(
                            text = "Очередь пуста",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinerColors.TextTertiary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                        ) {
                            itemsIndexed(playbackState.queue) { idx, qTrack ->
                                val isCurrent = idx == playbackState.currentIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isCurrent) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onPlayQueueIndex(idx)
                                            showQueueSheet = false
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    LinerCoverArt(
                                        url = qTrack.cover?.url ?: qTrack.cover?.fallbackUrl,
                                        contentDescription = qTrack.title,
                                        size = 42.dp,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = qTrack.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                                            ),
                                            color = LinerColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = qTrack.artistNames.ifBlank { "Неизвестный артист" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = LinerColors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(999.dp)
    var isDragging by remember { mutableStateOf(false) }
    val trackHeightDp by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 4.dp,
        animationSpec = LinerMotion.easeOutTween(120),
        label = "scrubberTrackHeight"
    )
    Box(
        modifier = modifier
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp)
                .clip(pillShape)
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(trackHeightDp)
                    .clip(pillShape)
                    .background(Color.White)
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
                    text = "Текст песни недоступен",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
        }
        lyrics.isInstrumental -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Инструментал",
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
                    text = "Текст песни недоступен",
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
