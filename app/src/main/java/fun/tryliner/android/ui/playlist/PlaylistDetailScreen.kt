package `fun`.tryliner.android.ui.playlist

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.R
import `fun`.tryliner.android.ui.components.LikedTracksArtwork
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.TrackRow
import `fun`.tryliner.android.ui.components.formatDurationMs
import `fun`.tryliner.android.ui.theme.LinerColors
import kotlin.math.abs

private val PlaylistBackdropTarget = Color(0xFF0D0F14)

private val PlaylistHeaderTints = listOf(
    Color(0xFF27304D),
    Color(0xFF3E2232),
    Color(0xFF1E3440),
    Color(0xFF302145),
    Color(0xFF3D2720)
)

@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    bottomContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedIds by container.likedTrackIds.collectAsStateWithLifecycle()
    val playbackState by container.playerController.state.collectAsStateWithLifecycle()

    val topTint = remember(state.isLikedCollection, state.title) {
        if (state.isLikedCollection) {
            Color(0xFF3F2D6B)
        } else {
            val idx = abs(state.title.hashCode()) % PlaylistHeaderTints.size
            PlaylistHeaderTints[idx]
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Soft desaturated ambient gradient at top fading into #0D0F14, transparent below
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to topTint.copy(alpha = 0.56f),
                        0.58f to PlaylistBackdropTarget.copy(alpha = 0.68f),
                        1.0f to Color.Transparent
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Minimal top bar with borderless back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.80f))
                    }
                }
                state.errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = LinerColors.FillSubtle
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
                                TextButton(onClick = viewModel::load) {
                                    Text("Retry", color = Color.White)
                                }
                            }
                        }
                    }
                }
                else -> {
                    val listBottomPadding = (bottomContentPadding.calculateBottomPadding() + 24.dp)
                        .coerceAtLeast(140.dp)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = listBottomPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item(key = "hero_header") {
                            EntityHeroHeader(
                                state = state,
                                onPlayAll = { viewModel.playAll(shuffle = false) },
                                onShuffle = { viewModel.playAll(shuffle = true) }
                            )
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
                                        text = "No tracks yet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LinerColors.TextTertiary
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = state.tracks,
                                key = { index, track -> "${index}_${track.id}" }
                            ) { index, track ->
                                val isLiked = likedIds.contains(track.id)
                                val isPlayingCurrent = playbackState.currentTrack?.id == track.id
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

@Composable
private fun EntityHeroHeader(
    state: PlaylistDetailUiState,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    val coverShape = RoundedCornerShape(14.dp)

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
                Text(
                    text = "${state.tracks.size} tracks$durationText",
                    style = MaterialTheme.typography.bodySmall,
                    color = LinerColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // White pill "Play all" button (no border/shadow) + borderless white@0.10f "Shuffle" button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onPlayAll,
                enabled = state.tracks.isNotEmpty(),
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
                    contentColor = PlaylistBackdropTarget,
                    disabledContainerColor = Color.White.copy(alpha = 0.25f),
                    disabledContentColor = PlaylistBackdropTarget.copy(alpha = 0.6f)
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                modifier = Modifier.height(42.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Play all",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Surface(
                onClick = onShuffle,
                enabled = state.tracks.isNotEmpty(),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.cd_shuffle),
                        tint = if (state.tracks.isNotEmpty()) {
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
