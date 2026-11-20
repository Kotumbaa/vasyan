package `fun`.tryliner.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

private const val PLAYLIST_TITLE_MAX_LENGTH = 40

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenLikedTracks: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    bottomContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedTrackIds.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentTrackId = playbackState.currentTrack?.id
    val filteredLikedTracks = state.filteredLikedTracks
    val downloadedTracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val hasDownloads = downloadedTracks.isNotEmpty()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top bar with "vasyan" / "Library" title + subtle glass icon buttons
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
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = LinerColors.TextPrimary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassCircleIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    onClick = viewModel::refresh
                )
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = "Sign out",
                    onClick = viewModel::logout
                )
            }
        }

        when {
            state.isLoading && state.playlists.isEmpty() && state.likedTracks.isEmpty() && !hasDownloads -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LinerColors.AccentPrimary)
                }
            }
            state.errorMessage != null && state.playlists.isEmpty() && state.likedTracks.isEmpty() && !hasDownloads -> {
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
                                Text("Retry", color = LinerColors.AccentPrimary)
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 6.dp,
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
                                    text = "Playlists",
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
                            Surface(
                                onClick = viewModel::openCreatePlaylistDialog,
                                shape = LinerShapes.Item,
                                color = Color.White.copy(alpha = 0.05f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LibraryMusic,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.50f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "No playlists yet — tap to create one",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.55f)
                                    )
                                }
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
                                    text = "Recently Played",
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
                                        text = "Скачанные",
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = LinerColors.TextPrimary
                                    )
                                    LinerChip(text = "${downloadedTracks.size}")
                                }
                                Row {
                                    IconButton(onClick = { viewModel.playDownloaded(0, shuffle = true) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Shuffle,
                                            contentDescription = "Перемешать",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.playDownloaded(0) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = "Слушать",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(
                            items = downloadedTracks,
                            key = { index, track -> "downloaded_${track.id}_$index" }
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                isLiked = likedIds.contains(track.id),
                                isPlayingCurrent = currentTrackId == track.id,
                                onClick = { viewModel.playDownloaded(index) },
                                onToggleLike = { viewModel.toggleLike(track) },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
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
                                    text = "Liked Tracks",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = LinerColors.TextPrimary
                                )
                                TextButton(onClick = onOpenLikedTracks) {
                                    Text(
                                        text = "See all",
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
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.40f),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No tracks matching \"${state.searchQuery.trim()}\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LinerColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TextButton(onClick = viewModel::clearSearchQuery) {
                                        Text("Clear search", color = LinerColors.AccentPrimary)
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = filteredLikedTracks,
                                key = { index, track -> "liked_${track.id}_$index" }
                            ) { _, track ->
                                TrackRow(
                                    track = track,
                                    isLiked = likedIds.contains(track.id),
                                    isPlayingCurrent = currentTrackId == track.id,
                                    onClick = { viewModel.playLikedTrack(track) },
                                    onToggleLike = { viewModel.toggleLike(track) },
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun NewPlaylistPillButton(
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = LinerColors.TextPrimary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "New Playlist",
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                text = "Search in playlist",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.45f)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
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
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = stringResource(R.string.cd_clear),
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(999.dp),
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
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
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
                    text = "Liked Tracks",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (trackCount > 0) "$trackCount tracks" else "Your favorite songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Surface(
                onClick = onPlay,
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFFFFFFFF)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play liked tracks",
                        tint = Color(0xFF0D0F14),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Play",
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
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
            text = playlist.trackCount?.let { "$it tracks" } ?: "Playlist",
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
    Column(
        modifier = Modifier
            .width(124.dp)
            .clip(coverShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            LinerCoverArt(
                url = track.cover?.url ?: track.cover?.fallbackUrl,
                contentDescription = track.title,
                size = 124.dp,
                shape = coverShape
            )
            if (isPlayingCurrent) {
                Box(
                    modifier = Modifier
                        .size(124.dp)
                        .clip(coverShape)
                        .background(Color.Black.copy(alpha = 0.52f)),
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
            text = track.artistNames.ifBlank { "Unknown Artist" },
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
            ) {
                Text(
                    text = "New Playlist",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = LinerColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Create a playlist from scratch",
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
                            text = "Playlist name",
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
                            text = "Description (optional)",
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
                            text = "Cancel",
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
                                text = "Create",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
    }
}
