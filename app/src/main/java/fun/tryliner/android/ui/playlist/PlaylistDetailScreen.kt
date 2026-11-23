package `fun`.tryliner.android.ui.playlist

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import `fun`.tryliner.android.ui.components.LinerShimmerBox
import `fun`.tryliner.android.ui.components.rememberShimmerAlphaState
import `fun`.tryliner.android.ui.theme.pressScale
import `fun`.tryliner.android.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import `fun`.tryliner.android.ui.components.LocalAppHazeState
import `fun`.tryliner.android.ui.components.TrackListSkeleton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.R
import `fun`.tryliner.android.playback.TrackDownloadState
import `fun`.tryliner.android.ui.components.LikedTracksArtwork
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.LocalTrackDownloads
import `fun`.tryliner.android.ui.components.TrackRow
import `fun`.tryliner.android.ui.components.formatDurationMs
import `fun`.tryliner.android.ui.components.formatTrackCountRu
import `fun`.tryliner.android.ui.components.rememberCoverAmbient
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.LinerShapes
import kotlin.math.abs

private val PlaylistBackdropTarget = Color(0xFF0D0F14)

private val PlaylistHeaderTints = listOf(
    Color(0xFF27304D),
    Color(0xFF3E2232),
    Color(0xFF1E3440),
    Color(0xFF302145),
    Color(0xFF3D2720)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    bottomContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val likedIds by container.likedTrackIds.collectAsStateWithLifecycle()
    val currentTrackIdFlow = remember(container) {
        container.playerController.state.map { it.currentTrack?.id }.distinctUntilChanged()
    }
    val currentPlayingTrackId by currentTrackIdFlow.collectAsStateWithLifecycle(
        initialValue = container.playerController.state.value.currentTrack?.id
    )
    val reducedMotion = rememberReducedMotion()
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    val normalDuration = if (reducedMotion) 0 else LinerMotion.DURATION_NORMAL
    val ambientDuration = if (reducedMotion) 0 else LinerMotion.DURATION_AMBIENT
    var showImportDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val showStickyTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 12
        }
    }
    val headerBlurAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 1f else 0f,
        animationSpec = LinerMotion.easeOutTween(fastDuration),
        label = "playlistHeaderBlurAlpha"
    )
    val haptics = LocalHapticFeedback.current
    val localFallbackHaze = remember { HazeState() }
    val appHazeState = LocalAppHazeState.current ?: localFallbackHaze
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerOverlayHeight = statusBarTop + 58.dp

    val coverAmbient = rememberCoverAmbient(state.coverUrl)
    val topTint = remember(state.isLikedCollection, state.title, coverAmbient?.backdropColor) {
        coverAmbient?.backdropColor ?: if (state.isLikedCollection) {
            Color(0xFF232735)
        } else {
            val idx = abs(state.title.hashCode()) % PlaylistHeaderTints.size
            PlaylistHeaderTints[idx]
        }
    }
    val animatedTopTintState = animateColorAsState(
        targetValue = topTint,
        animationSpec = LinerMotion.easeOutTween(ambientDuration),
        label = "playlistAmbientTopTint"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlaylistBackdropTarget)
    ) {
        // Scrollable content + ambient gradient wrapped in hazeSource so both top bar and bottom nav blur it
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = appHazeState)
        ) {
            // Full-bleed top ambient gradient covering status bar & camera cutout with playlist color
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .drawBehind {
                        val tint = animatedTopTintState.value
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to tint.copy(alpha = 0.74f),
                                0.55f to PlaylistBackdropTarget.copy(alpha = 0.86f),
                                1.0f to PlaylistBackdropTarget
                            )
                        )
                    }
            )

            val contentPhase = when {
                state.isLoading && state.tracks.isEmpty() -> 0
                state.errorMessage != null && state.tracks.isEmpty() -> 1
                else -> 2
            }
            Crossfade(
                targetState = contentPhase,
                animationSpec = LinerMotion.easeOutTween(normalDuration),
                label = "playlistContentPhase"
            ) { phase ->
                when (phase) {
                    0 -> {
                        val sharedAlpha = rememberShimmerAlphaState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = headerOverlayHeight + 8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LinerShimmerBox(
                                    modifier = Modifier.size(112.dp),
                                    shape = LinerShapes.HeroCover,
                                    shimmerAlpha = sharedAlpha
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LinerShimmerBox(
                                        modifier = Modifier
                                            .fillMaxWidth(0.72f)
                                            .height(22.dp),
                                        shimmerAlpha = sharedAlpha
                                    )
                                    LinerShimmerBox(
                                        modifier = Modifier
                                            .fillMaxWidth(0.48f)
                                            .height(14.dp),
                                        shimmerAlpha = sharedAlpha
                                    )
                                    LinerShimmerBox(
                                        modifier = Modifier
                                            .width(96.dp)
                                            .height(24.dp),
                                        shape = LinerShapes.HeroButton,
                                        shimmerAlpha = sharedAlpha
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LinerShimmerBox(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .height(40.dp),
                                    shape = CircleShape,
                                    shimmerAlpha = sharedAlpha
                                )
                                LinerShimmerBox(
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    shimmerAlpha = sharedAlpha
                                )
                                LinerShimmerBox(
                                    modifier = Modifier.size(40.dp),
                                    shape = LinerShapes.HeroButton,
                                    shimmerAlpha = sharedAlpha
                                )
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            TrackListSkeleton(count = 8)
                        }
                    }
                    1 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = state.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LinerColors.TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                TextButton(onClick = viewModel::load) {
                                    Text("Повторить", color = LinerColors.TextPrimary)
                                }
                            }
                        }
                    }
                    else -> {
                        val listBottomPadding = (bottomContentPadding.calculateBottomPadding() + 24.dp)
                            .coerceAtLeast(140.dp)
                        val pullToRefreshState = rememberPullToRefreshState()
                        val isRefreshing = state.isLoading && state.tracks.isNotEmpty()
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.load()
                            },
                            state = pullToRefreshState,
                            indicator = {
                                PullToRefreshDefaults.Indicator(
                                    state = pullToRefreshState,
                                    isRefreshing = isRefreshing,
                                    containerColor = LinerColors.SurfaceGlass,
                                    color = LinerColors.TextPrimary,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = headerOverlayHeight + 4.dp)
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = headerOverlayHeight + 4.dp,
                                    bottom = listBottomPadding
                                ),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                item(key = "hero_header") {
                                    Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                                        EntityHeroHeader(
                                            state = state,
                                            onPlayAll = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.playAll(shuffle = false)
                                            },
                                            onShuffle = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.playAll(shuffle = true)
                                            },
                                            onOpenImport = if (state.isLikedCollection) {
                                                {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    viewModel.resetImportState()
                                                    showImportDialog = true
                                                }
                                            } else {
                                                null
                                            }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))
                                }

                                if (state.tracks.isEmpty()) {
                                    item(key = "empty_tracks") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 40.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Пока нет треков",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = LinerColors.TextTertiary
                                            )
                                        }
                                    }
                                } else {
                                    val trackTotal = state.tracks.size
                                    itemsIndexed(
                                        items = state.tracks,
                                        key = { index, track -> "${index}_${track.id}" }
                                    ) { index, track ->
                                        val isLiked = likedIds.contains(track.id)
                                        val isPlayingCurrent = currentPlayingTrackId == track.id
                                        val panelShape = when {
                                            trackTotal == 1 -> LinerShapes.Item
                                            index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                            index == trackTotal - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                            else -> RoundedCornerShape(0.dp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(panelShape)
                                                .background(LinerColors.FillSubtle)
                                                .padding(
                                                    start = 4.dp,
                                                    end = 4.dp,
                                                    top = if (index == 0) 4.dp else 0.dp,
                                                    bottom = if (index == trackTotal - 1) 4.dp else 0.dp
                                                )
                                        ) {
                                            TrackRow(
                                                track = track,
                                                isLiked = isLiked,
                                                isPlayingCurrent = isPlayingCurrent,
                                                onClick = { viewModel.playTrackAt(index) },
                                                onToggleLike = { viewModel.toggleLike(track) }
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

        // Top bar overlay: frosted-glass translucent + backdrop blur when scrolled (matching bottom nav)
        val backInteraction = remember { MutableInteractionSource() }
        val stickyPlayInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            if (headerBlurAlpha > 0.001f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = headerBlurAlpha }
                        .hazeEffect(
                            state = appHazeState,
                            style = HazeStyle(
                                backgroundColor = Color(0xFF0D0F14),
                                tint = HazeTint(Color(0xFF0D0F14).copy(alpha = 0.56f)),
                                blurRadius = 24.dp
                            )
                        )
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color(0x550A0C10),
                                0.96f to Color.Transparent,
                                1.0f to Color.White.copy(alpha = 0.05f)
                            )
                        )
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBack()
                    },
                    interactionSource = backInteraction,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier
                        .size(38.dp)
                        .pressScale(backInteraction)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mgc_arrow_left_line),
                            contentDescription = stringResource(R.string.cd_back),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showStickyTitle,
                        enter = fadeIn(LinerMotion.easeOutTween(fastDuration)),
                        exit = fadeOut(LinerMotion.easeOutTween(fastDuration))
                    ) {
                        Text(
                            text = if (state.isLikedCollection) "Понравившиеся" else state.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = LinerColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showStickyTitle && state.tracks.isNotEmpty(),
                    enter = fadeIn(LinerMotion.easeOutTween(fastDuration)) +
                        scaleIn(LinerMotion.easeOutTween(fastDuration), initialScale = 0.92f),
                    exit = fadeOut(LinerMotion.easeOutTween(fastDuration)) +
                        scaleOut(LinerMotion.easeOutTween(fastDuration), targetScale = 0.92f)
                ) {
                    Surface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.playAll(shuffle = false)
                        },
                        interactionSource = stickyPlayInteraction,
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier
                            .size(36.dp)
                            .pressScale(stickyPlayInteraction)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Слушать",
                                tint = PlaylistBackdropTarget,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        if (showImportDialog) {
            ImportLikedDialog(
                importState = importState,
                onDismiss = {
                    if (!importState.isImporting) {
                        showImportDialog = false
                        viewModel.resetImportState()
                    }
                },
                onImport = { text -> viewModel.importToLiked(text) }
            )
        }
    }
}

@Composable
private fun EntityHeroHeader(
    state: PlaylistDetailUiState,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onOpenImport: (() -> Unit)? = null
) {
    val coverShape = LinerShapes.HeroCover

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        // Side-by-side 112.dp cover art on the left + Title & metadata on the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isLikedCollection) {
                LikedTracksArtwork(
                    size = 112.dp,
                    shape = coverShape,
                    modifier = Modifier.shadow(16.dp, coverShape, clip = false)
                )
            } else {
                LinerCoverArt(
                    url = state.coverUrl,
                    contentDescription = state.title,
                    size = 112.dp,
                    shape = coverShape,
                    modifier = Modifier.shadow(16.dp, coverShape, clip = false)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = LinerColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!state.subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.subtitle.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val durationText = if (state.totalDurationMs > 0L) {
                    " · ${formatDurationMs(state.totalDurationMs)}"
                } else {
                    ""
                }
                Surface(
                    shape = LinerShapes.HeroButton,
                    color = LinerColors.HeroGlass
                ) {
                    Text(
                        text = "${formatTrackCountRu(state.tracks.size)}$durationText",
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // White pill "Play all" button (no border/shadow) + borderless white@0.10f "Shuffle" button
        val playInteraction = remember { MutableInteractionSource() }
        val shuffleInteraction = remember { MutableInteractionSource() }
        val importInteraction = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onPlayAll,
                enabled = state.tracks.isNotEmpty(),
                interactionSource = playInteraction,
                shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                    disabledElevation = 0.dp
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = LinerColors.BtnPrimaryText,
                    disabledContainerColor = Color.White.copy(alpha = 0.25f),
                    disabledContentColor = LinerColors.BtnPrimaryText.copy(alpha = 0.6f)
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                modifier = Modifier
                    .height(40.dp)
                    .pressScale(playInteraction)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_solar_play),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Слушать",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Surface(
                onClick = onShuffle,
                enabled = state.tracks.isNotEmpty(),
                interactionSource = shuffleInteraction,
                shape = CircleShape,
                color = LinerColors.HeroGlass,
                modifier = Modifier
                    .size(40.dp)
                    .pressScale(shuffleInteraction)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_solar_shuffle),
                        contentDescription = stringResource(R.string.cd_shuffle),
                        tint = if (state.tracks.isNotEmpty()) {
                            LinerColors.TextPrimary
                        } else {
                            LinerColors.TextTertiary
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            PlaylistDownloadButton(tracks = state.tracks)

            if (onOpenImport != null) {
                Surface(
                    onClick = onOpenImport,
                    interactionSource = importInteraction,
                    shape = CircleShape,
                    color = LinerColors.HeroGlass,
                    modifier = Modifier
                        .height(40.dp)
                        .pressScale(importInteraction)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mgc_download_line),
                            contentDescription = "Импорт в понравившиеся",
                            tint = LinerColors.TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Импорт",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = LinerColors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportLikedDialog(
    importState: ImportLikedState,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val context = LocalContext.current
    var rawText by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                rawText = content
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = LinerShapes.Panel,
            color = LinerColors.SurfaceGlass,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Импорт в Понравившиеся",
                    style = MaterialTheme.typography.titleLarge,
                    color = LinerColors.TextPrimary
                )
                Text(
                    text = "Вставь ссылку на Spotify / YouTube плейлист или список треков (по одному на строку: Исполнитель - Трек).",
                    style = MaterialTheme.typography.bodySmall,
                    color = LinerColors.TextSecondary
                )

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    enabled = !importState.isImporting,
                    placeholder = {
                        Text(
                            text = "https://open.spotify.com/playlist/...\nhttps://music.youtube.com/playlist?list=...\nили: Артист - Трек",
                            color = LinerColors.TextTertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 4,
                    maxLines = 7,
                    shape = LinerShapes.Item,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = LinerColors.Fill,
                        unfocusedContainerColor = LinerColors.FillSubtle,
                        focusedTextColor = LinerColors.TextPrimary,
                        unfocusedTextColor = LinerColors.TextPrimary,
                        cursorColor = LinerColors.TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (importState.isImporting) {
                    val progress = if (importState.total > 0) {
                        importState.done.toFloat() / importState.total.toFloat()
                    } else {
                        0f
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (importState.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                            )
                            Text(
                                text = "Обработано ${importState.done} из ${importState.total} (добавлено: ${importState.imported})",
                                style = MaterialTheme.typography.bodySmall,
                                color = LinerColors.TextSecondary
                            )
                        } else {
                            LinearProgressIndicator(
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                            )
                            Text(
                                text = "Загружаю список треков…",
                                style = MaterialTheme.typography.bodySmall,
                                color = LinerColors.TextSecondary
                            )
                        }
                    }
                } else if (importState.finished) {
                    Text(
                        text = "Готово! Добавлено в Понравившиеся: ${importState.imported} из ${importState.total}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.TextPrimary
                    )
                } else if (!importState.error.isNullOrBlank()) {
                    Text(
                        text = importState.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.AccentWarning
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { filePicker.launch(arrayOf("text/plain", "text/csv", "*/*")) },
                        enabled = !importState.isImporting,
                        shape = CircleShape,
                        color = LinerColors.HeroGlass
                    ) {
                        Text(
                            text = "Из файла",
                            style = MaterialTheme.typography.labelMedium,
                            color = LinerColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !importState.isImporting
                        ) {
                            Text(
                                text = if (importState.finished) "Закрыть" else "Отмена",
                                color = LinerColors.TextSecondary
                            )
                        }
                        if (!importState.finished) {
                            Button(
                                onClick = { onImport(rawText) },
                                enabled = rawText.isNotBlank() && !importState.isImporting,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = LinerColors.BtnPrimaryText
                                )
                            ) {
                                Text("Импортировать", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDownloadButton(
    tracks: List<`fun`.tryliner.android.core.model.Track>,
) {
    val manager = LocalTrackDownloads.current ?: return
    val reducedMotion = rememberReducedMotion()
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    val interactionSource = remember { MutableInteractionSource() }
    val states by manager.states.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val downloadedCount = tracks.count { states[it.id] == TrackDownloadState.Downloaded }
    val anyDownloading = tracks.any { states[it.id] is TrackDownloadState.Downloading }
    val allDownloaded = tracks.isNotEmpty() && downloadedCount == tracks.size
    val stateKey = when {
        anyDownloading -> 1
        allDownloaded -> 2
        else -> 0
    }

    Surface(
        onClick = {
            when {
                anyDownloading -> tracks.forEach {
                    if (states[it.id] is TrackDownloadState.Downloading) manager.cancel(it.id)
                }
                allDownloaded -> confirmDeleteAll = true
                else -> manager.downloadAll(tracks)
            }
        },
        enabled = tracks.isNotEmpty(),
        interactionSource = interactionSource,
        shape = LinerShapes.HeroButton,
        color = LinerColors.HeroGlass,
        modifier = Modifier
            .size(40.dp)
            .pressScale(interactionSource)
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = stateKey,
                transitionSpec = {
                    fadeIn(LinerMotion.easeOutTween(fastDuration)) togetherWith
                        fadeOut(LinerMotion.easeOutTween(fastDuration))
                },
                label = "playlistDownloadState"
            ) { key ->
                Box(contentAlignment = Alignment.Center) {
                    when (key) {
                        1 -> {
                            val rawProgress = if (tracks.isNotEmpty()) downloadedCount.toFloat() / tracks.size else 0f
                            val progress by animateFloatAsState(
                                targetValue = rawProgress,
                                animationSpec = LinerMotion.easeOutTween(if (reducedMotion) 0 else 180),
                                label = "playlistDownloadProgress"
                            )
                            if (progress <= 0f) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = LinerColors.TextPrimary
                                )
                            } else {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = LinerColors.TextPrimary,
                                    trackColor = Color.White.copy(alpha = 0.15f)
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_mgc_close_line),
                                contentDescription = "Отменить загрузку плейлиста",
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(11.dp)
                            )
                        }
                        2 -> Icon(
                            painter = painterResource(R.drawable.ic_mgc_check_line),
                            contentDescription = "Плейлист скачан",
                            tint = LinerColors.AccentPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                        else -> Icon(
                            painter = painterResource(R.drawable.ic_mgc_download_line),
                            contentDescription = "Скачать весь плейлист",
                            tint = if (tracks.isNotEmpty()) {
                                LinerColors.TextPrimary
                            } else {
                                LinerColors.TextTertiary
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.delete_playlist_downloads_title)) },
            text = { Text(stringResource(R.string.delete_playlist_downloads_message, tracks.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    tracks.forEach { manager.delete(it.id) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
