package `fun`.tryliner.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.R
import `fun`.tryliner.android.core.model.DailyMixItem
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.WaveVibe
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.LinerGlassPillButton
import `fun`.tryliner.android.ui.components.LinerPanel
import `fun`.tryliner.android.ui.components.TrackRow
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.LinerShapes
import `fun`.tryliner.android.ui.theme.pressScale
import `fun`.tryliner.android.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private enum class HomeContentPhase {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDailyMix: (DailyMixItem) -> Unit = { viewModel.playDailyMix(it) },
    bottomContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedTrackIds.collectAsStateWithLifecycle()
    val currentPlayingTrackId by remember(viewModel) {
        viewModel.playbackState.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.playbackState.value.currentTrack?.id)
    val waveUiState by remember(viewModel) {
        viewModel.playbackState.map {
            Triple(it.isWaveActive, it.isWaveLoading, Pair(it.isPlaying, it.waveVibe))
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = Triple(
            viewModel.playbackState.value.isWaveActive,
            viewModel.playbackState.value.isWaveLoading,
            Pair(viewModel.playbackState.value.isPlaying, viewModel.playbackState.value.waveVibe)
        )
    )
    val (isWaveActive, isWaveLoading, wavePlayAndVibe) = waveUiState
    val (isWavePlaying, activeWaveVibeId) = wavePlayAndVibe
    var selectedWaveVibe by remember(activeWaveVibeId) {
        mutableStateOf(WaveVibe.fromId(activeWaveVibeId))
    }
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val localFallbackHaze = remember { HazeState() }
    val headerHazeState = LocalAppHazeState.current ?: localFallbackHaze
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerOverlayHeight = statusBarTop + 62.dp
    val reducedMotion = rememberReducedMotion()
    val pullRefreshState = rememberPullToRefreshState()
    val contentPhase = when {
        state.isLoading && state.isEmpty -> HomeContentPhase.LOADING
        state.errorMessage != null && state.isEmpty -> HomeContentPhase.ERROR
        state.isEmpty -> HomeContentPhase.EMPTY
        else -> HomeContentPhase.CONTENT
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = headerHazeState)
        ) {
            Crossfade(
                targetState = contentPhase,
                animationSpec = LinerMotion.easeOutTween(if (reducedMotion) 0 else LinerMotion.DURATION_NORMAL),
                label = "homeContentPhase"
            ) { phase ->
                when (phase) {
                    HomeContentPhase.LOADING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = headerOverlayHeight + 8.dp)
                        ) {
                            TrackListSkeleton(count = 8)
                        }
                    }

                    HomeContentPhase.ERROR -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LinerPanel(
                                shape = LinerShapes.Panel,
                                containerColor = Color.White.copy(alpha = 0.07f),
                                borderColor = Color.Transparent
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = state.errorMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LinerColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    LinerGlassPillButton(onClick = viewModel::refresh) {
                                        Text("Попробовать снова")
                                    }
                                }
                            }
                        }
                    }

                    HomeContentPhase.EMPTY -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Пока здесь пусто",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LinerColors.TextSecondary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                LinerGlassPillButton(onClick = viewModel::refresh) {
                                    Text("Обновить")
                                }
                            }
                        }
                    }

                    HomeContentPhase.CONTENT -> {
                        val isRefreshing = state.isLoading && !state.isEmpty
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.refresh()
                            },
                            state = pullRefreshState,
                            indicator = {
                                PullToRefreshDefaults.Indicator(
                                    state = pullRefreshState,
                                    isRefreshing = isRefreshing,
                                    containerColor = LinerColors.BgElevated,
                                    color = LinerColors.TextPrimary,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = headerOverlayHeight + 6.dp)
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = headerOverlayHeight + 6.dp,
                                bottom = (bottomContentPadding.calculateBottomPadding() + 24.dp).coerceAtLeast(148.dp)
                            )
                        ) {
                    // My Wave hero card ("Моя волна")
                    item(key = "my_wave_banner") {
                        MyWaveBanner(
                            selectedVibe = selectedWaveVibe,
                            isActive = isWaveActive,
                            isPlaying = isWaveActive && isWavePlaying,
                            isLoading = isWaveLoading,
                            onPlayToggle = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.startOrToggleMyWave(selectedWaveVibe)
                            },
                            onSelectVibe = { vibe ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedWaveVibe = vibe
                                viewModel.selectWaveVibe(vibe)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                    }

                    // Compact 2-column quick-access grid (Spotify / Desktop style, zero vertical bloat)
                    val quickTracks = if (state.recentTracks.isNotEmpty()) {
                        state.recentTracks.take(6)
                    } else {
                        state.popularTracks.take(6)
                    }
                    val quickIsRecent = state.recentTracks.isNotEmpty()
                    if (quickTracks.isNotEmpty()) {
                        item(key = "quick_grid") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                quickTracks.chunked(2).forEachIndexed { rowIdx, pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        pair.forEachIndexed { colIdx, track ->
                                            val index = rowIdx * 2 + colIdx
                                            QuickAccessTile(
                                                track = track,
                                                isPlayingCurrent = currentPlayingTrackId == track.id,
                                                onClick = {
                                                    if (quickIsRecent) {
                                                        viewModel.playRecentTrackAt(index)
                                                    } else {
                                                        viewModel.playPopularTrackAt(index)
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (pair.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }

                    if (state.dailyMixes.isNotEmpty()) {
                        item(key = "mixes_header") {
                            HomeSectionHeader(
                                title = "Твои миксы",
                                icon = Icons.Rounded.AutoAwesome
                            )
                        }
                        item(key = "mixes_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = state.dailyMixes,
                                    key = { index, mix -> "mix_${mix.id}_$index" }
                                ) { _, mix ->
                                    DailyMixCard(
                                        mix = mix,
                                        onClick = { onOpenDailyMix(mix) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }

                    // Desktop ListeningNowSection style: horizontal columns of 4 tracks inside bg-fill-subtle panels
                    if (state.popularTracks.isNotEmpty()) {
                        item(key = "trending_header") {
                            HomeSectionHeader(
                                title = "В тренде",
                                icon = Icons.Rounded.LocalFireDepartment
                            )
                        }
                        item(key = "trending_columns") {
                            val columns = state.popularTracks.take(12).chunked(4)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = columns,
                                    key = { colIdx, _ -> "trending_col_$colIdx" }
                                ) { colIdx, colTracks ->
                                    Column(
                                        modifier = Modifier
                                            .width(304.dp)
                                            .clip(LinerShapes.Item)
                                            .background(LinerColors.FillSubtle)
                                            .padding(vertical = 4.dp)
                                    ) {
                                        colTracks.forEachIndexed { rowIdx, track ->
                                            val globalIdx = colIdx * 4 + rowIdx
                                            TrackRow(
                                                track = track,
                                                isLiked = likedIds.contains(track.id),
                                                isPlayingCurrent = currentPlayingTrackId == track.id,
                                                onClick = { viewModel.playPopularTrackAt(globalIdx) },
                                                onToggleLike = { viewModel.toggleLike(track) }
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }

                    if (state.recentTracks.isNotEmpty()) {
                        item(key = "recent_header") {
                            HomeSectionHeader(
                                title = "Недавние",
                                icon = Icons.Rounded.History
                            )
                        }
                        item(key = "recent_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = state.recentTracks,
                                    key = { index, track -> "recent_${track.id}_$index" }
                                ) { index, track ->
                                    SquareTrackCard(
                                        track = track,
                                        isPlayingCurrent = currentPlayingTrackId == track.id,
                                        onClick = { viewModel.playRecentTrackAt(index) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(22.dp))
                        }
                    }

                    if (state.mostPlayedTracks.isNotEmpty()) {
                        item(key = "most_played_header") {
                            HomeSectionHeader(
                                title = "Чаще всего слушаете",
                                icon = Icons.Rounded.EmojiEvents
                            )
                        }
                        item(key = "most_played_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = state.mostPlayedTracks,
                                    key = { index, track -> "most_played_${track.id}_$index" }
                                ) { index, track ->
                                    SquareTrackCard(
                                        track = track,
                                        isPlayingCurrent = currentPlayingTrackId == track.id,
                                        onClick = { viewModel.playMostPlayedTrackAt(index) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                        }
                    }
                }
            }
        }
        }

        // Frosted-glass translucent + backdrop blur top bar (lighter glass tint)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .hazeEffect(
                    state = headerHazeState,
                    style = HazeStyle(
                        backgroundColor = Color(0xFF14171F),
                        tint = HazeTint(Color(0xFF14171F).copy(alpha = 0.28f)),
                        blurRadius = 24.dp
                    )
                )
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.04f),
                        0.96f to Color.Transparent,
                        1.0f to Color.White.copy(alpha = 0.05f)
                    )
                )
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "vasyan",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = LinerColors.TextPrimary
                )

                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.refresh()
                    },
                    enabled = !state.isLoading
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mgc_refresh_line),
                        contentDescription = "Обновить",
                        tint = LinerColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAccessTile(
    track: Track,
    isPlayingCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val reducedMotion = rememberReducedMotion()
    val bgColor by animateColorAsState(
        targetValue = if (isPlayingCurrent) LinerColors.FillHover else LinerColors.Fill,
        animationSpec = LinerMotion.easeOutTween(if (reducedMotion) 0 else LinerMotion.DURATION_FAST),
        label = "quickTileBg"
    )
    Row(
        modifier = modifier
            .height(52.dp)
            .pressScale(interactionSource, targetScale = 0.97f)
            .clip(LinerShapes.Control)
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinerCoverArt(
            url = track.cover?.url ?: track.cover?.fallbackUrl,
            contentDescription = track.title,
            size = 52.dp,
            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
        )
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = LinerColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 9.dp)
        )
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LinerColors.TextTertiary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            ),
            color = LinerColors.TextPrimary
        )
    }
}

@Composable
private fun SquareTrackCard(
    track: Track,
    isPlayingCurrent: Boolean,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val reducedMotion = rememberReducedMotion()
    Column(
        modifier = Modifier
            .width(132.dp)
            .pressScale(interactionSource, targetScale = 0.96f)
            .clip(cardShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            LinerCoverArt(
                url = track.cover?.url ?: track.cover?.fallbackUrl,
                contentDescription = track.title,
                size = 132.dp,
                shape = cardShape
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = isPlayingCurrent,
                enter = fadeIn(LinerMotion.easeOutTween(if (reducedMotion) 0 else LinerMotion.DURATION_FAST)),
                exit = fadeOut(LinerMotion.easeOutTween(if (reducedMotion) 0 else LinerMotion.DURATION_FAST))
            ) {
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .clip(cardShape)
                        .background(LinerColors.AmbientBase.copy(alpha = 0.52f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = LinerColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isPlayingCurrent) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = LinerColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artistNames.ifBlank { "Неизвестный артист" },
            style = MaterialTheme.typography.bodySmall,
            color = LinerColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DailyMixCard(
    mix: DailyMixItem,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(12.dp)
    val coverUrl = mix.cover?.url
        ?: mix.cover?.fallbackUrl
        ?: mix.tracks.firstOrNull()?.cover?.url
        ?: mix.tracks.firstOrNull()?.cover?.fallbackUrl
    val subtitle = mix.clusterArtists
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?: mix.description

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(132.dp)
            .pressScale(interactionSource, targetScale = 0.96f)
            .clip(cardShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        LinerCoverArt(
            url = coverUrl,
            contentDescription = mix.title,
            size = 132.dp,
            shape = cardShape
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = mix.title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = LinerColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LinerColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MyWaveBanner(
    selectedVibe: WaveVibe,
    isActive: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayToggle: () -> Unit,
    onSelectVibe: (WaveVibe) -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    val playInteraction = remember { MutableInteractionSource() }

    LinerPanel(
        modifier = modifier.fillMaxWidth(),
        shape = LinerShapes.BigArt,
        containerColor = LinerColors.FillSubtle,
        borderColor = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isActive) 0.12f else 0.06f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(LinerShapes.Item)
                                .background(
                                    if (isActive) Color.White else Color.White.copy(alpha = 0.12f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Waves,
                                contentDescription = null,
                                tint = if (isActive) LinerColors.BtnPrimaryText else LinerColors.TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Моя волна",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = LinerColors.TextPrimary
                            )
                            Text(
                                text = if (isActive) {
                                    "Настроение: ${selectedVibe.label}"
                                } else {
                                    "Бесконечный поток под твой вкус"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = LinerColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Surface(
                        onClick = onPlayToggle,
                        enabled = !isLoading,
                        interactionSource = playInteraction,
                        shape = CircleShape,
                        color = LinerColors.BtnPrimaryBg,
                        modifier = Modifier
                            .height(40.dp)
                            .pressScale(playInteraction, targetScale = 0.95f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = LinerColors.BtnPrimaryText
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = LinerColors.BtnPrimaryText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = if (isPlaying) "Пауза" else "Слушать",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = LinerColors.BtnPrimaryText
                            )
                        }
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(
                        items = WaveVibe.entries,
                        key = { _, v -> v.id }
                    ) { _, vibe ->
                        val isSelected = selectedVibe == vibe
                        val chipInteraction = remember(vibe) { MutableInteractionSource() }
                        val chipBg by animateColorAsState(
                            targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.08f),
                            animationSpec = LinerMotion.easeOutTween(fastDuration),
                            label = "waveVibeChipBg"
                        )
                        val chipText by animateColorAsState(
                            targetValue = if (isSelected) LinerColors.BtnPrimaryText else LinerColors.TextSecondary,
                            animationSpec = LinerMotion.easeOutTween(fastDuration),
                            label = "waveVibeChipText"
                        )
                        Surface(
                            onClick = { onSelectVibe(vibe) },
                            interactionSource = chipInteraction,
                            shape = LinerShapes.Control,
                            color = chipBg,
                            modifier = Modifier.pressScale(chipInteraction, targetScale = 0.95f)
                        ) {
                            Text(
                                text = vibe.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = chipText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
