package `fun`.tryliner.android.ui.artist

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.R
import `fun`.tryliner.android.core.model.ArtistDetails
import `fun`.tryliner.android.ui.components.LinerCoverArt
import `fun`.tryliner.android.ui.components.LocalAppHazeState
import `fun`.tryliner.android.ui.components.TrackRow
import `fun`.tryliner.android.ui.components.rememberCoverAmbient
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.LinerShapes
import `fun`.tryliner.android.ui.theme.pressScale

private val ArtistBackdropTarget = Color(0xFF0D0F14)

@Composable
fun ArtistScreen(
    viewModel: ArtistViewModel,
    container: AppContainer,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    bottomContentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val likedIds by container.likedTrackIds.collectAsStateWithLifecycle()
    val playbackState by container.playerController.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    val artist = state.artist
    val coverUrl = artist?.cover?.url ?: artist?.cover?.fallbackUrl
    val ambient = rememberCoverAmbient(coverUrl)
    val topTint = ambient?.backdropColor ?: LinerColors.AmbientBlue
    val appHazeState = LocalAppHazeState.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (appHazeState != null) Modifier.hazeSource(appHazeState) else Modifier)
            .background(ArtistBackdropTarget)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to topTint.copy(alpha = 0.72f),
                        0.56f to ArtistBackdropTarget.copy(alpha = 0.86f),
                        1.0f to ArtistBackdropTarget
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Floating glass Back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp)
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
            }

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.80f))
                    }
                }
                state.errorMessage != null || artist == null -> {
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
                                    text = state.errorMessage ?: "Failed to load artist",
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
                            top = 4.dp,
                            bottom = listBottomPadding
                        )
                    ) {
                        item(key = "artist_hero") {
                            ArtistHeroHeader(
                                artist = artist,
                                onPlayAll = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.playAll(shuffle = false)
                                },
                                onShuffle = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.playAll(shuffle = true)
                                }
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                        }

                        if (artist.tracks.isNotEmpty()) {
                            item(key = "tracks_header") {
                                Text(
                                    text = "Популярное",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.01).sp
                                    ),
                                    color = LinerColors.TextPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }

                            val topTracks = artist.tracks.take(10)
                            itemsIndexed(
                                items = topTracks,
                                key = { index, track -> "artist_track_${index}_${track.id}" }
                            ) { index, track ->
                                val isLiked = likedIds.contains(track.id)
                                val isPlayingCurrent = playbackState.currentTrack?.id == track.id
                                TrackRow(
                                    track = track,
                                    isLiked = isLiked,
                                    isPlayingCurrent = isPlayingCurrent,
                                    onClick = { viewModel.playTrackAt(index) },
                                    onToggleLike = { viewModel.toggleLike(track) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }

                            item(key = "tracks_spacer") {
                                Spacer(modifier = Modifier.height(22.dp))
                            }
                        }

                        if (artist.albums.isNotEmpty()) {
                            item(key = "albums_header") {
                                Text(
                                    text = "Альбомы",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.01).sp
                                    ),
                                    color = LinerColors.TextPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                            item(key = "albums_carousel") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = artist.albums,
                                        key = { it.id }
                                    ) { album ->
                                        ReleaseCard(
                                            title = album.title,
                                            subtitle = album.year?.toString() ?: "Album",
                                            coverUrl = album.cover?.url ?: album.cover?.fallbackUrl,
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onOpenAlbum(album.id)
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(22.dp))
                            }
                        }

                        if (artist.singles.isNotEmpty()) {
                            item(key = "singles_header") {
                                Text(
                                    text = "Синглы и EP",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.01).sp
                                    ),
                                    color = LinerColors.TextPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                            item(key = "singles_carousel") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = artist.singles,
                                        key = { it.id }
                                    ) { single ->
                                        val typeLabel = if (single.releaseType.equals("ep", ignoreCase = true)) {
                                            "EP"
                                        } else {
                                            "Single"
                                        }
                                        val subtitle = listOfNotNull(
                                            single.year?.toString(),
                                            typeLabel
                                        ).joinToString(" · ")
                                        ReleaseCard(
                                            title = single.title,
                                            subtitle = subtitle,
                                            coverUrl = single.cover?.url ?: single.cover?.fallbackUrl,
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onOpenAlbum(single.id)
                                            }
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

@Composable
private fun ArtistHeroHeader(
    artist: ArtistDetails,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    var bioExpanded by remember { mutableStateOf(false) }
    val playInteraction = remember { MutableInteractionSource() }
    val shuffleInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinerCoverArt(
                url = artist.cover?.url ?: artist.cover?.fallbackUrl,
                contentDescription = artist.name,
                size = 116.dp,
                shape = CircleShape,
                modifier = Modifier.shadow(18.dp, CircleShape, clip = false)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 24.sp,
                            lineHeight = 28.sp,
                            letterSpacing = (-0.02).sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = LinerColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (artist.verified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Verified",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (!artist.monthlyListeners.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = LinerShapes.HeroButton,
                        color = LinerColors.HeroGlass
                    ) {
                        Text(
                            text = artist.monthlyListeners.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = LinerColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (!artist.bio.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = artist.bio.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = LinerColors.TextSecondary,
                maxLines = if (bioExpanded) 12 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .animateContentSize(LinerMotion.appleDefaultSpring())
                    .clip(LinerShapes.Control)
                    .clickable { bioExpanded = !bioExpanded }
                    .padding(vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onPlayAll,
                enabled = artist.tracks.isNotEmpty(),
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
                enabled = artist.tracks.isNotEmpty(),
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
                        tint = if (artist.tracks.isNotEmpty()) {
                            LinerColors.TextPrimary
                        } else {
                            LinerColors.TextTertiary
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(140.dp)
            .pressScale(interactionSource, targetScale = 0.96f)
            .clip(LinerShapes.Item)
            .background(LinerColors.FillSubtle)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(8.dp)
    ) {
        LinerCoverArt(
            url = coverUrl,
            contentDescription = title,
            size = 124.dp,
            shape = LinerShapes.Control
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = LinerColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LinerColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
