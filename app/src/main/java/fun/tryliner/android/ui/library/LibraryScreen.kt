package `fun`.tryliner.android.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import `fun`.tryliner.android.ui.theme.LinerMotion
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import `fun`.tryliner.android.ui.components.LocalAppHazeState
import `fun`.tryliner.android.ui.components.TrackListSkeleton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.R
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.UserPlaylist
import `fun`.tryliner.android.ui.components.LikedTracksArtwork
import `fun`.tryliner.android.ui.components.LinerChip
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.LinerPanel
import `fun`.tryliner.android.ui.components.TrackRow
import `fun`.tryliner.android.ui.components.formatTrackCountRu
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

private const val PLAYLIST_TITLE_MAX_LENGTH = 40

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenLikedTracks: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    bottomContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedTrackIds.collectAsStateWithLifecycle()
    val currentTrackIdFlow = remember(viewModel) {
        viewModel.playbackState.map { it.currentTrack?.id }.distinctUntilChanged()
    }
    val currentTrackId by currentTrackIdFlow.collectAsStateWithLifecycle(
        initialValue = viewModel.playbackState.value.currentTrack?.id
    )
    val reducedMotion = rememberReducedMotion()
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    val normalDuration = if (reducedMotion) 0 else LinerMotion.DURATION_NORMAL
    val filteredLikedTracks = state.filteredLikedTracks
    val likedTrackKeys = remember(filteredLikedTracks) {
        val counts = HashMap<String, Int>(filteredLikedTracks.size)
        filteredLikedTracks.map { track ->
            val occ = counts.getOrDefault(track.id, 0)
            counts[track.id] = occ + 1
            if (occ == 0) "liked_${track.id}" else "liked_${track.id}_dup$occ"
        }
    }
    val downloadedTracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val hasDownloads = downloadedTracks.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    val localFallbackHaze = remember { HazeState() }
    val headerHazeState = LocalAppHazeState.current ?: localFallbackHaze
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val topBarHeightDp = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() }
    } else {
        statusBarTop + 76.dp
    }
    val contentPhase = when {
        state.isLoading && state.playlists.isEmpty() && state.likedTracks.isEmpty() && !hasDownloads -> 0
        state.errorMessage != null && state.playlists.isEmpty() && state.likedTracks.isEmpty() && !hasDownloads -> 1
        else -> 2
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = headerHazeState)
        ) {
            Crossfade(
                targetState = contentPhase,
                animationSpec = LinerMotion.easeOutTween(normalDuration),
                label = "libraryContentPhase"
            ) { phase ->
                when (phase) {
                    0 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = topBarHeightDp + 12.dp)
                        ) {
                            TrackListSkeleton(count = 8)
                        }
                    }
                    1 -> {
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
                                    Spacer(modifier = Modifier.height(10.dp))
                                    TextButton(onClick = viewModel::refresh) {
                                        Text("Повторить", color = LinerColors.AccentPrimary)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val pullRefreshState = rememberPullToRefreshState()
                        val isRefreshing = state.isLoading && (state.playlists.isNotEmpty() || state.likedTracks.isNotEmpty())
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
                                        .padding(top = topBarHeightDp + 6.dp)
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = topBarHeightDp + 10.dp,
                        bottom = (bottomContentPadding.calculateBottomPadding() + 24.dp).coerceAtLeast(148.dp)
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Compact desktop-style "Liked Tracks" hero card
                    item(key = "liked_hero") {
                        LikedTracksHeroCard(
                            trackCount = state.likedTracksCount,
                            onOpen = onOpenLikedTracks,
                            onPlay = viewModel::playAllLiked,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // "Playlists" horizontal carousel + "+ New Playlist" pill
                    item(key = "playlists_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Плейлисты",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = LinerColors.TextPrimary
                                )
                                if (state.playlists.isNotEmpty()) {
                                    LinerChip(text = "${state.playlists.size}")
                                }
                            }
                            NewPlaylistPillButton(
                                onClick = viewModel::openCreatePlaylistDialog
                            )
                        }
                    }

                    if (state.playlists.isEmpty()) {
                        item(key = "playlists_empty") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(LinerShapes.Item)
                                    .clickable(onClick = viewModel::openCreatePlaylistDialog)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.42f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Плейлистов пока нет — нажмите, чтобы создать",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.50f)
                                )
                            }
                        }
                    } else {
                        item(key = "playlists_carousel") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(
                                    items = state.playlists,
                                    key = { it.id }
                                ) { playlist ->
                                    PlaylistCarouselCard(
                                        playlist = playlist,
                                        onClick = { onOpenPlaylist(playlist.id) }
                                    )
                                }
                            }
                        }
                    }

                    // "Recently Played" horizontal carousel from playback history
                    if (state.recentTracks.isNotEmpty()) {
                        item(key = "recent_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Недавние",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = LinerColors.TextPrimary
                                )
                                LinerChip(text = "${state.recentTracks.size}")
                            }
                        }

                        item(key = "recent_carousel") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = state.recentTracks,
                                    key = { index, track -> "recent_${track.id}_$index" }
                                ) { index, track ->
                                    RecentTrackCarouselCard(
                                        track = track,
                                        isPlayingCurrent = currentTrackId == track.id,
                                        onClick = { viewModel.playRecentTrackAt(index) }
                                    )
                                }
                            }
                        }
                    }

                    // Offline downloads
                    if (hasDownloads) {
                        item(key = "downloads_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.downloads_title),
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = LinerColors.TextPrimary
                                    )
                                    LinerChip(text = "${downloadedTracks.size}")
                                }
                                Row {
                                    IconButton(onClick = { viewModel.playDownloaded(0, shuffle = true) }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_solar_shuffle),
                                            contentDescription = stringResource(R.string.downloads_shuffle),
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.playDownloaded(0) }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_solar_play),
                                            contentDescription = stringResource(R.string.downloads_play),
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        val dlTotal = downloadedTracks.size
                        itemsIndexed(
                            items = downloadedTracks,
                            key = { index, track -> "downloaded_${track.id}_$index" }
                        ) { index, track ->
                            val panelShape = when {
                                dlTotal == 1 -> LinerShapes.Item
                                index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                index == dlTotal - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                else -> RoundedCornerShape(0.dp)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clip(panelShape)
                                    .background(LinerColors.FillSubtle)
                                    .padding(
                                        start = 4.dp,
                                        end = 4.dp,
                                        top = if (index == 0) 4.dp else 0.dp,
                                        bottom = if (index == dlTotal - 1) 4.dp else 0.dp
                                    )
                            ) {
                                TrackRow(
                                    track = track,
                                    isLiked = likedIds.contains(track.id),
                                    isPlayingCurrent = currentTrackId == track.id,
                                    onClick = { viewModel.playDownloaded(index) },
                                    onToggleLike = { viewModel.toggleLike(track) }
                                )
                            }
                        }
                    }

                    // Borderless "Liked Tracks" list with in-playlist search pill
                    if (state.likedTracks.isNotEmpty()) {
                        item(key = "liked_tracks_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Понравившиеся",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = LinerColors.TextPrimary
                                )
                                TextButton(onClick = onOpenLikedTracks) {
                                    Text(
                                        text = "Все",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = LinerColors.TextSecondary
                                    )
                                }
                            }
                        }

                        item(key = "liked_tracks_search") {
                            PlaylistSearchPill(
                                query = state.searchQuery,
                                matchCount = filteredLikedTracks.size,
                                totalCount = state.likedTracks.size,
                                onQueryChange = viewModel::onSearchQueryChange,
                                onClear = viewModel::clearSearchQuery,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                            )
                        }

                        if (filteredLikedTracks.isEmpty() && state.searchQuery.isNotBlank()) {
                            item(key = "liked_tracks_search_empty") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_mgc_search_line),
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.40f),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Ничего не найдено по «${state.searchQuery.trim()}»",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LinerColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TextButton(onClick = viewModel::clearSearchQuery) {
                                        Text("Очистить поиск", color = LinerColors.AccentPrimary)
                                    }
                                }
                            }
                        } else {
                            val likedTotal = filteredLikedTracks.size
                            itemsIndexed(
                                items = filteredLikedTracks,
                                key = { index, _ -> likedTrackKeys.getOrElse(index) { "liked_$index" } }
                            ) { index, track ->
                                val panelShape = when {
                                    likedTotal == 1 -> LinerShapes.Item
                                    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                    index == likedTotal - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                    else -> RoundedCornerShape(0.dp)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .clip(panelShape)
                                        .background(LinerColors.FillSubtle)
                                        .padding(
                                            start = 4.dp,
                                            end = 4.dp,
                                            top = if (index == 0) 4.dp else 0.dp,
                                            bottom = if (index == likedTotal - 1) 4.dp else 0.dp
                                        )
                                ) {
                                    TrackRow(
                                        track = track,
                                        isLiked = likedIds.contains(track.id),
                                        isPlayingCurrent = currentTrackId == track.id,
                                        onClick = { viewModel.playLikedTrack(track) },
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
        }

        // Frosted-glass translucent + backdrop blur top bar (lighter glass tint)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { topBarHeightPx = it.height }
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
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "vasyan",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color.White.copy(alpha = 0.50f)
                    )
                    Text(
                        text = "Медиатека",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = LinerColors.TextPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassCircleIconButton(
                        iconRes = R.drawable.ic_mgc_refresh_line,
                        contentDescription = "Обновить",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.refresh()
                        }
                    )
                    GlassCircleIconButton(
                        iconRes = R.drawable.ic_mgc_exit_line,
                        contentDescription = "Выйти",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.logout()
                        }
                    )
                }
            }
        }
    }

    if (state.isCreatePlaylistDialogOpen) {
        CreatePlaylistDialog(
            isCreating = state.isCreatingPlaylist,
            errorMessage = state.createPlaylistError,
            onDismiss = viewModel::dismissCreatePlaylistDialog,
            onCreate = viewModel::createPlaylist
        )
    }
}

@Composable
private fun GlassCircleIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = LinerColors.HeroGlass,
        modifier = Modifier
            .size(36.dp)
            .pressScale(interactionSource)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun NewPlaylistPillButton(
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = LinerShapes.HeroButton,
        color = LinerColors.HeroGlass,
        modifier = Modifier.pressScale(interactionSource)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mgc_add_line),
                contentDescription = null,
                tint = LinerColors.TextPrimary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Новый плейлист",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = LinerColors.TextPrimary
            )
        }
    }
}

@Composable
private fun PlaylistSearchPill(
    query: String,
    matchCount: Int,
    totalCount: Int,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                text = "Поиск в понравившихся",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.45f)
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_mgc_search_line),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(LinerMotion.easeOutTween(fastDuration)),
                exit = fadeOut(LinerMotion.easeOutTween(fastDuration))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "$matchCount/$totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = LinerColors.TextTertiary
                    )
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mgc_close_line),
                            contentDescription = stringResource(R.string.cd_clear),
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        },
        singleLine = true,
        shape = LinerShapes.Item,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
            focusedTextColor = LinerColors.TextPrimary,
            unfocusedTextColor = LinerColors.TextPrimary,
            cursorColor = Color.White
        ),
        modifier = modifier
    )
}

@Composable
private fun LikedTracksHeroCard(
    trackCount: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardInteraction = remember { MutableInteractionSource() }
    val playInteraction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        interactionSource = cardInteraction,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(cardInteraction, targetScale = 0.98f),
        shape = LinerShapes.BigArt,
        color = Color.White.copy(alpha = 0.07f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LikedTracksArtwork(
                size = 56.dp,
                shape = LinerShapes.HeroCover
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Понравившиеся",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (trackCount > 0) formatTrackCountRu(trackCount) else "Ваши любимые треки",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Surface(
                onClick = onPlay,
                interactionSource = playInteraction,
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFFFFFFFF),
                modifier = Modifier.pressScale(playInteraction)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Слушать понравившиеся",
                        tint = Color(0xFF0D0F14),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Слушать",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF0D0F14)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCarouselCard(
    playlist: UserPlaylist,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(132.dp)
            .pressScale(interactionSource, targetScale = 0.96f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        LinerCoverArt(
            url = playlist.coverUrls?.firstOrNull(),
            contentDescription = playlist.title,
            size = 132.dp,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = LinerColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = playlist.trackCount?.let { formatTrackCountRu(it) } ?: "Плейлист",
            style = MaterialTheme.typography.bodySmall,
            color = LinerColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecentTrackCarouselCard(
    track: Track,
    isPlayingCurrent: Boolean,
    onClick: () -> Unit
) {
    val coverShape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val reducedMotion = rememberReducedMotion()
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    Column(
        modifier = Modifier
            .width(124.dp)
            .pressScale(interactionSource, targetScale = 0.96f)
            .clip(coverShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            LinerCoverArt(
                url = track.cover?.url ?: track.cover?.fallbackUrl,
                contentDescription = track.title,
                size = 124.dp,
                shape = coverShape
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = isPlayingCurrent,
                enter = fadeIn(LinerMotion.easeOutTween(fastDuration)),
                exit = fadeOut(LinerMotion.easeOutTween(fastDuration))
            ) {
                Box(
                    modifier = Modifier
                        .size(124.dp)
                        .clip(coverShape)
                        .background(LinerColors.BgCanvas.copy(alpha = 0.52f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = LinerColors.TextPrimary,
                        modifier = Modifier.size(26.dp)
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
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = track.artistNames.ifBlank { "Неизвестный артист" },
            style = MaterialTheme.typography.bodySmall,
            color = LinerColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
    isCreating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String?) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val reducedMotion = rememberReducedMotion()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = LinerShapes.Panel,
            color = Color(0xFF14161C),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .animateContentSize(
                        animationSpec = if (reducedMotion) tween(0) else LinerMotion.appleDefaultSpring()
                    )
            ) {
                Text(
                    text = "Новый плейлист",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = LinerColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Создать свой плейлист",
                    style = MaterialTheme.typography.bodySmall,
                    color = LinerColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = Color.White.copy(alpha = 0.08f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                    disabledContainerColor = Color.White.copy(alpha = 0.05f),
                    focusedTextColor = LinerColors.TextPrimary,
                    unfocusedTextColor = LinerColors.TextPrimary,
                    disabledTextColor = LinerColors.TextSecondary,
                    cursorColor = Color.White
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { next ->
                        if (next.length <= PLAYLIST_TITLE_MAX_LENGTH) {
                            title = next
                        }
                    },
                    placeholder = {
                        Text(
                            text = "Название плейлиста",
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    },
                    trailingIcon = {
                        Text(
                            text = "${title.length}/$PLAYLIST_TITLE_MAX_LENGTH",
                            style = MaterialTheme.typography.labelSmall,
                            color = LinerColors.TextTertiary,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                    },
                    singleLine = true,
                    enabled = !isCreating,
                    shape = LinerShapes.Item,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { next ->
                        if (next.length <= 300) {
                            description = next
                        }
                    },
                    placeholder = {
                        Text(
                            text = "Описание (необязательно)",
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    },
                    minLines = 2,
                    maxLines = 3,
                    enabled = !isCreating,
                    shape = LinerShapes.Item,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.AccentWarning
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isCreating
                    ) {
                        Text(
                            text = "Отмена",
                            color = LinerColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onCreate(title, description) },
                        enabled = !isCreating && title.trim().isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LinerColors.BtnPrimaryBg,
                            contentColor = LinerColors.BtnPrimaryText,
                            disabledContainerColor = Color.White.copy(alpha = 0.16f),
                            disabledContentColor = Color.White.copy(alpha = 0.45f)
                        )
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                color = LinerColors.BtnPrimaryText,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = "Создать",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
    }
}
