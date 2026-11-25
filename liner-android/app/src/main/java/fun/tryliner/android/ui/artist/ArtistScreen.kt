package `fun`.tryliner.android.ui.artist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import `fun`.tryliner.android.ui.components.LinerShimmerBox
import `fun`.tryliner.android.ui.components.TrackListSkeleton
import `fun`.tryliner.android.ui.components.rememberShimmerAlphaState
import `fun`.tryliner.android.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
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
    val haptics = LocalHapticFeedback.current

    val artist = state.artist
    val coverUrl = artist?.cover?.url ?: artist?.cover?.fallbackUrl
    val ambient = rememberCoverAmbient(coverUrl)
    val topTint = ambient?.backdropColor ?: LinerColors.AmbientBlue
    val animatedTopTintState = animateColorAsState(
        targetValue = topTint,
        animationSpec = LinerMotion.easeOutTween(ambientDuration),
        label = "artistAmbientTopTint"
    )
    val localFallbackHaze = remember { HazeState() }
    val appHazeState = LocalAppHazeState.current ?: localFallbackHaze
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
        label = "artistHeaderBlurAlpha"
    )
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerOverlayHeight = statusBarTop + 58.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArtistBackdropTarget)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(appHazeState)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .drawBehind {
                        val tint = animatedTopTintState.value
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to tint.copy(alpha = 0.72f),
                                0.56f to ArtistBackdropTarget.copy(alpha = 0.86f),
                                1.0f to ArtistBackdropTarget
                            )
                        )
                    }
            )

            val contentPhase = when {
                state.isLoading -> 0
                state.errorMessage != null || artist == null -> 1
                else -> 2
            }
            Crossfade(
                targetState = contentPhase,
                animationSpec = LinerMotion.easeOutTween(normalDuration),
                label = "artistContentPhase"
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
                                    modifier = Modifier.size(116.dp),
                                    shape = CircleShape,
                                    shimmerAlpha = sharedAlpha
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LinerShimmerBox(
                                        modifier = Modifier
                                            .fillMaxWidth(0.68f)
                                            .height(24.dp),
                                        shimmerAlpha = sharedAlpha
                                    )
                                    LinerShimmerBox(
                                        modifier = Modifier
                                            .width(110.dp)
                                            .height(22.dp),
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
                            }
                            Spacer(modifier = Modifier.height(22.dp))
                            TrackListSkeleton(count = 6)
                        }
                    }
                    1 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = LinerShapes.Panel,
                                color = LinerColors.FillSubtle
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = state.errorMessage ?: "Не удалось загрузить артиста",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LinerColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    TextButton(onClick = viewModel::load) {
                                        Text("Повторить", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val loadedArtist = artist ?: return@Crossfade
                        val listBottomPadding = (bottomContentPadding.calculateBottomPadding() + 24.dp)
                            .coerceAtLeast(140.dp)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = headerOverlayHeight + 4.dp,
                                bottom = listBottomPadding
                            )
                        ) {
                            item(key = "artist_hero") {
                                ArtistHeroHeader(
                                    artist = loadedArtist,
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

                            if (loadedArtist.tracks.isNotEmpty()) {
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

                                val topTracks = loadedArtist.tracks.take(10)
                                itemsIndexed(
                                    items = topTracks,
                                    key = { index, track -> "artist_track_${index}_${track.id}" }
                                ) { index, track ->
                                    val isLiked = likedIds.contains(track.id)
                                    val isPlayingCurrent = currentPlayingTrackId == track.id
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

                            if (loadedArtist.albums.isNotEmpty()) {
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
                                            items = loadedArtist.albums,
                                            key = { it.id }
                                        ) { album ->
                                            ReleaseCard(
                                                title = album.title,
                                                subtitle = album.year?.toString() ?: "Альбом",
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

                            if (loadedArtist.singles.isNotEmpty()) {
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
                                            items = loadedArtist.singles,
                                            key = { it.id }
                                        ) { single ->
                                            val typeLabel = if (single.releaseType.equals("ep", ignoreCase = true)) {
                                                "EP"
                                            } else {
                                                "Сингл"
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

        // Top bar overlay: frosted-glass translucent + backdrop blur when scrolled
        val backInteraction = remember { MutableInteractionSource() }
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
                        visible = showStickyTitle && artist != null,
                        enter = fadeIn(LinerMotion.easeOutTween(fastDuration)),
                        exit = fadeOut(LinerMotion.easeOutTween(fastDuration))
                    ) {
                        Text(
                            text = artist?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = LinerColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
    val reducedMotion = rememberReducedMotion()
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
                            contentDescription = "Подтверждённый артист",
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
                    .animateContentSize(if (reducedMotion) tween(0) else LinerMotion.appleDefaultSpring())
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
