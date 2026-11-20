package `fun`.tryliner.android.ui.home

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.core.model.DailyMixItem
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.LinerGlassPillButton
import `fun`.tryliner.android.ui.components.LinerPanel
import `fun`.tryliner.android.ui.components.TrackRow
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    bottomContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedTrackIds.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    val currentPlayingTrackId = playbackState.currentTrack?.id

    Column(
        modifier = Modifier.fillMaxSize()
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
                text = "Home",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = LinerColors.TextPrimary
            )

            IconButton(
                onClick = viewModel::refresh,
                enabled = !state.isLoading
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = LinerColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        when {
            state.isLoading && state.isEmpty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = LinerColors.AccentPrimary)
                }
            }

            state.errorMessage != null && state.isEmpty -> {
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
                                Text("Try again")
                            }
                        }
                    }
                }
            }

            state.isEmpty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Nothing to show yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinerColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinerGlassPillButton(onClick = viewModel::refresh) {
                            Text("Refresh")
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = (bottomContentPadding.calculateBottomPadding() + 24.dp).coerceAtLeast(148.dp)
                    )
                ) {
                    if (state.dailyMixes.isNotEmpty()) {
                        item(key = "mixes_header") {
                            HomeSectionHeader(
                                title = "Your Mixes",
                                icon = Icons.Rounded.AutoAwesome
                            )
                        }
                        item(key = "mixes_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = state.dailyMixes,
                                    key = { index, mix -> "mix_${mix.id}_$index" }
                                ) { _, mix ->
                                    DailyMixCard(
                                        mix = mix,
                                        onClick = { viewModel.playDailyMix(mix) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(22.dp))
                        }
                    }

                    if (state.popularTracks.isNotEmpty()) {
                        item(key = "trending_header") {
                            HomeSectionHeader(
                                title = "Trending Now",
                                icon = Icons.Rounded.LocalFireDepartment
                            )
                        }
                        val trendingSlice = state.popularTracks.take(10)
                        itemsIndexed(
                            items = trendingSlice,
                            key = { index, track -> "trending_${track.id}_$index" }
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                isLiked = likedIds.contains(track.id),
                                isPlayingCurrent = currentPlayingTrackId == track.id,
                                onClick = { viewModel.playPopularTrackAt(index) },
                                onToggleLike = { viewModel.toggleLike(track) },
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                        }
                        item(key = "trending_spacer") {
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }

                    if (state.recentTracks.isNotEmpty()) {
                        item(key = "recent_header") {
                            HomeSectionHeader(
                                title = "Continue Listening",
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
                                title = "Most Played",
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
            tint = LinerColors.TextPrimary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(cardShape)
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            LinerCoverArt(
                url = track.cover?.url ?: track.cover?.fallbackUrl,
                contentDescription = track.title,
                size = 132.dp,
                shape = cardShape
            )
            if (isPlayingCurrent) {
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .clip(cardShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
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
            text = track.artistNames.ifBlank { "Unknown Artist" },
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

    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(cardShape)
            .clickable(onClick = onClick)
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
