package `fun`.tryliner.android.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.R
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.LinerPanel
import `fun`.tryliner.android.ui.components.TrackRow
import `fun`.tryliner.android.ui.components.formatTrackCountRu
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.LinerShapes
import `fun`.tryliner.android.ui.theme.pressScale
import `fun`.tryliner.android.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private enum class SearchContentPhase {
    LOADING,
    ERROR,
    BLANK,
    EMPTY,
    RESULTS
}

private enum class SearchFilter(val label: String) {
    ALL("Все"),
    TRACKS("Треки"),
    ARTISTS("Артисты"),
    ALBUMS("Альбомы"),
    PLAYLISTS("Плейлисты")
}

private data class DerivedArtistResult(
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val firstTrackIndex: Int
)

private data class DerivedAlbumResult(
    val title: String,
    val artistName: String,
    val coverUrl: String?,
    val trackCount: Int,
    val firstTrackIndex: Int
)

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    container: AppContainer,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenCatalogPlaylist: (String) -> Unit = {},
    bottomContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedIds by container.likedTrackIds.collectAsStateWithLifecycle()
    val currentPlayingTrackId by remember(container) {
        container.playerController.state.map { it.currentTrack?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = container.playerController.state.value.currentTrack?.id)
    var selectedFilter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    val reducedMotion = rememberReducedMotion()
    val trackKeys = remember(state.tracks) {
        val counts = HashMap<String, Int>(state.tracks.size)
        state.tracks.map { track ->
            val occ = counts.getOrDefault(track.id, 0)
            counts[track.id] = occ + 1
            if (occ == 0) "track_${track.id}" else "track_${track.id}_dup$occ"
        }
    }

    val derivedArtists = remember(state.tracks) {
        state.tracks
            .withIndex()
            .filter { it.value.artists.isNotEmpty() }
            .groupBy { it.value.artists.first().name }
            .map { (name, group) ->
                val first = group.first()
                DerivedArtistResult(
                    name = name,
                    coverUrl = first.value.cover?.url ?: first.value.cover?.fallbackUrl,
                    trackCount = group.size,
                    firstTrackIndex = first.index
                )
            }
    }

    val derivedAlbums = remember(state.tracks) {
        state.tracks
            .withIndex()
            .filter { !it.value.album?.title.isNullOrBlank() }
            .groupBy { it.value.album!!.title }
            .map { (title, group) ->
                val first = group.first()
                DerivedAlbumResult(
                    title = title,
                    artistName = first.value.artistNames.ifBlank { "Неизвестный артист" },
                    coverUrl = first.value.cover?.url ?: first.value.cover?.fallbackUrl,
                    trackCount = group.size,
                    firstTrackIndex = first.index
                )
            }
    }

    val localFallbackHaze = remember { HazeState() }
    val headerHazeState = LocalAppHazeState.current ?: localFallbackHaze
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val topBarHeightDp = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() }
    } else {
        statusBarTop + 178.dp
    }

    val contentPhase = when {
        state.isLoading -> SearchContentPhase.LOADING
        state.errorMessage != null -> SearchContentPhase.ERROR
        state.query.isBlank() -> SearchContentPhase.BLANK
        state.tracks.isEmpty() &&
            state.artists.isEmpty() &&
            state.albums.isEmpty() &&
            state.playlists.isEmpty() -> SearchContentPhase.EMPTY
        else -> SearchContentPhase.RESULTS
    }
    val fastDuration = if (reducedMotion) 0 else LinerMotion.DURATION_FAST
    val normalDuration = if (reducedMotion) 0 else LinerMotion.DURATION_NORMAL

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = headerHazeState)
                .imePadding()
        ) {
            Crossfade(
                targetState = contentPhase,
                animationSpec = LinerMotion.easeOutTween(normalDuration),
                label = "searchContentPhase"
            ) { phase ->
                when (phase) {
                    SearchContentPhase.LOADING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = topBarHeightDp + 10.dp)
                        ) {
                            TrackListSkeleton(count = 7)
                        }
                    }
                    SearchContentPhase.ERROR -> {
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
                                Text(
                                    text = state.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LinerColors.TextSecondary,
                                    modifier = Modifier.padding(20.dp)
                                )
                            }
                        }
                    }
                    SearchContentPhase.BLANK -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_mgc_search_line),
                                            contentDescription = null,
                                            tint = LinerColors.TextTertiary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Найдите треки, артистов или альбомы",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LinerColors.TextTertiary
                                )
                            }
                        }
                    }
                    SearchContentPhase.EMPTY -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Ничего не найдено",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LinerColors.TextTertiary
                            )
                        }
                    }
                    SearchContentPhase.RESULTS -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = topBarHeightDp + 6.dp,
                                bottom = (bottomContentPadding.calculateBottomPadding() + 24.dp).coerceAtLeast(148.dp)
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            when (selectedFilter) {
                                SearchFilter.ALL -> {
                                    if (state.artists.isNotEmpty()) {
                                        item(key = "all_artists_header") {
                                            Text(
                                                text = "Артисты",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = LinerColors.TextSecondary,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            )
                                        }
                                        items(state.artists.take(3), key = { "all_artist_${it.id}" }) { artist ->
                                            EntityResultRow(
                                                title = artist.name,
                                                subtitle = "Артист",
                                                coverUrl = artist.cover?.url ?: artist.cover?.fallbackUrl,
                                                isCircularCover = true,
                                                onClick = { onOpenArtist(artist.id) }
                                            )
                                        }
                                    }
                                    if (state.albums.isNotEmpty()) {
                                        item(key = "all_albums_header") {
                                            Text(
                                                text = "Альбомы",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = LinerColors.TextSecondary,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            )
                                        }
                                        items(state.albums.take(3), key = { "all_album_${it.id}" }) { album ->
                                            val artistText = album.artists.joinToString(", ") { it.name }.ifBlank { "Альбом" }
                                            EntityResultRow(
                                                title = album.title,
                                                subtitle = listOfNotNull(artistText, album.year?.toString()).joinToString(" · "),
                                                coverUrl = album.cover?.url ?: album.cover?.fallbackUrl,
                                                isCircularCover = false,
                                                onClick = { onOpenAlbum(album.id) }
                                            )
                                        }
                                    }
                                    if (state.tracks.isNotEmpty()) {
                                        item(key = "tracks_header") {
                                            Text(
                                                text = "Треки",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = LinerColors.TextSecondary,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            )
                                        }
                                        itemsIndexed(
                                            items = state.tracks,
                                            key = { index, _ -> trackKeys.getOrElse(index) { "track_$index" } }
                                        ) { index, track ->
                                            TrackRow(
                                                track = track,
                                                isLiked = likedIds.contains(track.id),
                                                isPlayingCurrent = currentPlayingTrackId == track.id,
                                                onClick = { viewModel.playTrackAt(index) },
                                                onToggleLike = { viewModel.toggleLike(track) },
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }
                                SearchFilter.TRACKS -> {
                                    item(key = "tracks_header") {
                                        Text(
                                            text = "Треки",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = LinerColors.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                        )
                                    }
                                    itemsIndexed(
                                        items = state.tracks,
                                        key = { index, _ -> trackKeys.getOrElse(index) { "track_$index" } }
                                    ) { index, track ->
                                        TrackRow(
                                            track = track,
                                            isLiked = likedIds.contains(track.id),
                                            isPlayingCurrent = currentPlayingTrackId == track.id,
                                            onClick = { viewModel.playTrackAt(index) },
                                            onToggleLike = { viewModel.toggleLike(track) },
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                        SearchFilter.ARTISTS -> {
                            if (state.artists.isNotEmpty()) {
                                items(state.artists, key = { "artist_${it.id}" }) { artist ->
                                    EntityResultRow(
                                        title = artist.name,
                                        subtitle = "Артист",
                                        coverUrl = artist.cover?.url ?: artist.cover?.fallbackUrl,
                                        isCircularCover = true,
                                        onClick = { onOpenArtist(artist.id) }
                                    )
                                }
                            } else if (derivedArtists.isEmpty()) {
                                item(key = "artists_empty") {
                                    EmptyFilterState(
                                        icon = Icons.Rounded.Person,
                                        message = "Артисты не найдены"
                                    )
                                }
                            } else {
                                items(derivedArtists, key = { it.name }) { artist ->
                                    EntityResultRow(
                                        title = artist.name,
                                        subtitle = formatTrackCountRu(artist.trackCount),
                                        coverUrl = artist.coverUrl,
                                        isCircularCover = true,
                                        onClick = { viewModel.playTrackAt(artist.firstTrackIndex) }
                                    )
                                }
                            }
                        }
                        SearchFilter.ALBUMS -> {
                            if (state.albums.isNotEmpty()) {
                                items(state.albums, key = { "album_${it.id}" }) { album ->
                                    val artistText = album.artists.joinToString(", ") { it.name }.ifBlank { "Альбом" }
                                    EntityResultRow(
                                        title = album.title,
                                        subtitle = listOfNotNull(artistText, album.year?.toString()).joinToString(" · "),
                                        coverUrl = album.cover?.url ?: album.cover?.fallbackUrl,
                                        isCircularCover = false,
                                        onClick = { onOpenAlbum(album.id) }
                                    )
                                }
                            } else if (derivedAlbums.isEmpty()) {
                                item(key = "albums_empty") {
                                    EmptyFilterState(
                                        icon = Icons.Rounded.Album,
                                        message = "Альбомы не найдены"
                                    )
                                }
                            } else {
                                items(derivedAlbums, key = { "${it.title}_${it.artistName}" }) { album ->
                                    EntityResultRow(
                                        title = album.title,
                                        subtitle = album.artistName,
                                        coverUrl = album.coverUrl,
                                        isCircularCover = false,
                                        onClick = { viewModel.playTrackAt(album.firstTrackIndex) }
                                    )
                                }
                            }
                        }
                        SearchFilter.PLAYLISTS -> {
                            if (state.playlists.isNotEmpty()) {
                                items(state.playlists, key = { "playlist_${it.id}" }) { playlist ->
                                    EntityResultRow(
                                        title = playlist.title,
                                        subtitle = playlist.author ?: "Плейлист",
                                        coverUrl = playlist.cover?.url ?: playlist.cover?.fallbackUrl,
                                        isCircularCover = false,
                                        onClick = { onOpenCatalogPlaylist(playlist.id) }
                                    )
                                }
                            } else {
                                item(key = "playlists_empty") {
                                    EmptyFilterState(
                                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                        message = "Плейлисты по запросу «${state.query.trim()}» не найдены"
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

        // Frosted-glass translucent + backdrop blur top bar (lighter glass tint)
        Column(
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
                .padding(bottom = 10.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Поиск",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = LinerColors.TextPrimary
                )
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchCatalogSource.entries.forEach { src ->
                        val active = state.source == src
                        val srcInteraction = remember(src) { MutableInteractionSource() }
                        val pillBg by animateColorAsState(
                            targetValue = if (active) Color.White else Color.Transparent,
                            animationSpec = LinerMotion.easeOutTween(fastDuration),
                            label = "searchSourceBg"
                        )
                        val pillText by animateColorAsState(
                            targetValue = if (active) Color(0xFF0D0F14) else LinerColors.TextSecondary,
                            animationSpec = LinerMotion.easeOutTween(fastDuration),
                            label = "searchSourceText"
                        )
                        Box(
                            modifier = Modifier
                                .pressScale(srcInteraction, targetScale = 0.95f)
                                .clip(CircleShape)
                                .background(pillBg)
                                .clickable(
                                    interactionSource = srcInteraction,
                                    indication = null
                                ) { viewModel.onSourceChange(src) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = src.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = pillText
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = {
                    Text(
                        text = "Треки, артисты, альбомы",
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
                        visible = state.query.isNotEmpty(),
                        enter = fadeIn(LinerMotion.easeOutTween(fastDuration)) +
                            scaleIn(LinerMotion.easeOutTween(fastDuration), initialScale = 0.85f),
                        exit = fadeOut(LinerMotion.easeOutTween(fastDuration)) +
                            scaleOut(LinerMotion.easeOutTween(fastDuration), targetScale = 0.85f)
                    ) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mgc_close_line),
                                contentDescription = stringResource(R.string.cd_clear),
                                tint = Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.size(18.dp)
                            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(SearchFilter.entries) { filter ->
                    val isSelected = selectedFilter == filter
                    val filterInteraction = remember(filter) { MutableInteractionSource() }
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFFFFFFFF) else Color.White.copy(alpha = 0.08f),
                        animationSpec = LinerMotion.easeOutTween(fastDuration),
                        label = "searchFilterBg"
                    )
                    val chipText by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFF0D0F14) else Color.White.copy(alpha = 0.65f),
                        animationSpec = LinerMotion.easeOutTween(fastDuration),
                        label = "searchFilterText"
                    )
                    Surface(
                        onClick = { selectedFilter = filter },
                        interactionSource = filterInteraction,
                        shape = LinerShapes.Control,
                        color = chipBg,
                        modifier = Modifier.pressScale(filterInteraction, targetScale = 0.95f)
                    ) {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = chipText,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntityResultRow(
    title: String,
    subtitle: String,
    coverUrl: String?,
    isCircularCover: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .pressScale(interactionSource, targetScale = 0.98f)
            .clip(LinerShapes.Item)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinerCoverArt(
            url = coverUrl,
            contentDescription = title,
            size = 48.dp,
            shape = if (isCircularCover) CircleShape else RoundedCornerShape(8.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = LinerColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LinerColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = LinerColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyFilterState(
    icon: ImageVector,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LinerColors.TextTertiary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = LinerColors.TextSecondary
        )
    }
}
