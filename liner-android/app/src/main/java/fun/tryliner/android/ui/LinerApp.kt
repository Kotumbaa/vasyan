package `fun`.tryliner.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.ui.components.LocalAppHazeState
import `fun`.tryliner.android.ui.components.LocalTrackMenuOpener
import `fun`.tryliner.android.ui.components.TrackActionSheet
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import `fun`.tryliner.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import android.net.Uri
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.ui.artist.ArtistScreen
import `fun`.tryliner.android.ui.artist.ArtistViewModel
import `fun`.tryliner.android.ui.components.AtmosphericBackdrop
import `fun`.tryliner.android.ui.components.HumanCheckDialog
import `fun`.tryliner.android.ui.components.LocalTrackDownloads
import `fun`.tryliner.android.ui.home.HomeScreen
import `fun`.tryliner.android.ui.home.HomeViewModel
import `fun`.tryliner.android.ui.library.LibraryScreen
import `fun`.tryliner.android.ui.library.LibraryViewModel
import `fun`.tryliner.android.ui.login.LoginScreen
import `fun`.tryliner.android.ui.login.LoginViewModel
import `fun`.tryliner.android.ui.player.MiniPlayerBar
import `fun`.tryliner.android.ui.player.NowPlayingScreen
import `fun`.tryliner.android.ui.playlist.ALBUM_PLAYLIST_PREFIX
import `fun`.tryliner.android.ui.playlist.CATALOG_PLAYLIST_PREFIX
import `fun`.tryliner.android.ui.playlist.DAILY_MIX_PREFIX
import `fun`.tryliner.android.ui.playlist.LIKED_PLAYLIST_ID
import `fun`.tryliner.android.ui.playlist.PlaylistDetailScreen
import `fun`.tryliner.android.ui.playlist.PlaylistDetailViewModel
import `fun`.tryliner.android.ui.search.SearchScreen
import `fun`.tryliner.android.ui.search.SearchViewModel
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.LocalReducedMotion
import `fun`.tryliner.android.ui.theme.pressScale
import `fun`.tryliner.android.ui.theme.rememberReducedMotion

object LinerRoutes {
    const val TAB_HOME = "tab_home"
    const val TAB_SEARCH = "tab_search"
    const val TAB_LIBRARY = "tab_library"

    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"

    fun playlistRoutePattern(tabPrefix: String): String = "$tabPrefix/playlist/{playlistId}"
    fun artistRoutePattern(tabPrefix: String): String = "$tabPrefix/artist/{artistId}"

    fun playlistDetail(tabPrefix: String, playlistId: String): String =
        "$tabPrefix/playlist/${Uri.encode(playlistId)}"
    fun albumDetail(tabPrefix: String, albumId: String): String =
        playlistDetail(tabPrefix, "$ALBUM_PLAYLIST_PREFIX$albumId")
    fun catalogPlaylistDetail(tabPrefix: String, playlistId: String): String =
        playlistDetail(tabPrefix, "$CATALOG_PLAYLIST_PREFIX$playlistId")
    fun dailyMixDetail(tabPrefix: String, mixId: String): String =
        playlistDetail(tabPrefix, "$DAILY_MIX_PREFIX$mixId")
    fun artistDetail(tabPrefix: String, artistId: String): String =
        "$tabPrefix/artist/${Uri.encode(artistId)}"
}

@Composable
fun LinerApp(container: AppContainer) {
    CompositionLocalProvider(LocalTrackDownloads provides container.downloadManager) {
        LinerAppContent(container)
    }
}

@Composable
private fun LinerAppContent(container: AppContainer) {
    val session by container.sessionStorage.sessionFlow.collectAsStateWithLifecycle()
    val pendingHumanCheck by container.pendingHumanCheck.collectAsStateWithLifecycle()

    if (session == null) {
        val loginViewModel: LoginViewModel = viewModel(
            factory = LoginViewModel.Factory(container.apiClient)
        )
        LoginScreen(viewModel = loginViewModel)
    } else {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val reducedMotion = rememberReducedMotion()

        val playbackState by container.playerController.state.collectAsStateWithLifecycle()
        val remoteState by container.lanConnectManager.remoteState.collectAsStateWithLifecycle()
        val isLocalSlave by container.lanConnectManager.isLocalSlave.collectAsStateWithLifecycle()
        val likedIds by container.likedTrackIds.collectAsStateWithLifecycle()
        var isNowPlayingOpen by rememberSaveable { mutableStateOf(false) }
        var menuTrack by remember { mutableStateOf<Track?>(null) }
        val haptics = LocalHapticFeedback.current
        val bottomBarHazeState = remember { HazeState() }

        val currentTrack = playbackState.currentTrack
        val isCurrentTrackLiked = currentTrack != null && likedIds.contains(currentTrack.id)
        val activeRemote = remoteState?.takeIf { remote ->
            remote.track != null && !isLocalSlave && (remote.isSlave || remote.isPlaying)
        }

        val activeCoverUrl = currentTrack?.cover?.url
            ?: currentTrack?.cover?.fallbackUrl
            ?: activeRemote?.track?.coverUrl

        val hasPlayableTrack = currentTrack != null || activeRemote?.track != null
        val sheetProgress = remember { Animatable(1f) } // 0f = open, 1f = closed
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx().coerceAtLeast(1f) }

        LaunchedEffect(isNowPlayingOpen, hasPlayableTrack, reducedMotion) {
            val target = if (isNowPlayingOpen && hasPlayableTrack) 0f else 1f
            if (reducedMotion) {
                sheetProgress.snapTo(target)
            } else {
                sheetProgress.animateTo(
                    targetValue = target,
                    animationSpec = if (target == 0f) {
                        LinerMotion.appleMomentumSpring()
                    } else {
                        LinerMotion.appleDefaultSpring()
                    }
                )
            }
        }

        val isSheetMounted by remember {
            derivedStateOf { sheetProgress.value < 0.998f }
        }
        val trackMenuOpener = remember { { track: Track -> menuTrack = track } }
        val showMiniPlayer = currentTrack != null || activeRemote != null
        var retainedPlaybackState by remember { mutableStateOf(playbackState) }
        var retainedRemoteState by remember { mutableStateOf(activeRemote) }
        if (currentTrack != null) {
            retainedPlaybackState = playbackState
        }
        if (activeRemote != null) {
            retainedRemoteState = activeRemote
        }
        val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val settledBottomPadding = navBarBottomInset + if (showMiniPlayer) 132.dp else 56.dp
        val onRemoteToggle = remember(container) { { container.lanConnectManager.sendCommand("toggle") } }
        val onRemotePrev = remember(container) { { container.lanConnectManager.sendCommand("prev") } }
        val onRemoteNext = remember(container) { { container.lanConnectManager.sendCommand("next") } }
        val onPlayOnPhone = remember(container) { { container.lanConnectManager.handoffToPhone() } }

        CompositionLocalProvider(
            LocalTrackMenuOpener provides trackMenuOpener,
            LocalAppHazeState provides bottomBarHazeState,
            LocalReducedMotion provides reducedMotion
        ) {
        AtmosphericBackdrop(
            modifier = Modifier.fillMaxSize(),
            coverUrl = activeCoverUrl
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val openFactor = (1f - sheetProgress.value).coerceIn(0f, 1f)
                        val bgScale = 1f - 0.055f * openFactor
                        scaleX = bgScale
                        scaleY = bgScale
                        alpha = 1f - 0.25f * openFactor
                        shape = RoundedCornerShape((18f * openFactor).dp)
                        clip = openFactor > 0.002f
                    },
                bottomBar = {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AnimatedVisibility(
                            visible = showMiniPlayer,
                            enter = if (reducedMotion) {
                                fadeIn(tween(0))
                            } else {
                                slideInVertically(LinerMotion.appleDefaultSpring()) { it / 2 } +
                                    fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL))
                            },
                            exit = if (reducedMotion) {
                                fadeOut(tween(0))
                            } else {
                                slideOutVertically(LinerMotion.easeOutTween(LinerMotion.DURATION_FAST)) { it / 2 } +
                                    fadeOut(LinerMotion.easeOutTween(LinerMotion.DURATION_FAST))
                            }
                        ) {
                            val miniPlaybackState = if (currentTrack != null) playbackState else retainedPlaybackState
                            val miniRemoteState = activeRemote ?: if (currentTrack == null) retainedRemoteState else null
                            val miniTrack = miniPlaybackState.currentTrack
                            val miniLiked = miniTrack != null && likedIds.contains(miniTrack.id)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            ) {
                                MiniPlayerBar(
                                    playbackState = miniPlaybackState,
                                    isLiked = miniLiked,
                                    onToggleLike = { miniTrack?.let(container::toggleLike) },
                                    onTogglePlayPause = container.playerController::togglePlayPause,
                                    onClick = { isNowPlayingOpen = true },
                                    onPrevious = container.playerController::previous,
                                    onNext = container.playerController::next,
                                    remoteState = miniRemoteState,
                                    onRemoteTogglePlayPause = onRemoteToggle,
                                    onRemotePrevious = onRemotePrev,
                                    onRemoteNext = onRemoteNext,
                                    onPlayOnPhone = onPlayOnPhone
                                )
                            }
                        }

                        val currentDestination = navBackStackEntry?.destination
                        val isHome = currentDestination?.hierarchy?.any { it.route == LinerRoutes.TAB_HOME } == true
                        val isSearch = currentDestination?.hierarchy?.any { it.route == LinerRoutes.TAB_SEARCH } == true
                        val isLibrary = currentDestination?.hierarchy?.any { it.route == LinerRoutes.TAB_LIBRARY } == true

                        fun navigateToTab(tabRoute: String, rootRoute: String, isCurrentlySelected: Boolean) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isCurrentlySelected) {
                                if (currentRoute != rootRoute) {
                                    navController.popBackStack(rootRoute, inclusive = false)
                                }
                                return
                            }
                            navController.navigate(tabRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }

                        // Frosted-glass translucent + backdrop blur bottom navigation bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .hazeEffect(
                                    state = bottomBarHazeState,
                                    style = HazeStyle(
                                        backgroundColor = Color(0xFF0D0F14),
                                        tint = HazeTint(Color(0xFF0D0F14).copy(alpha = 0.56f)),
                                        blurRadius = 24.dp
                                    )
                                )
                                .background(
                                    Brush.verticalGradient(
                                        0.0f to Color.White.copy(alpha = 0.05f),
                                        0.04f to Color.Transparent,
                                        1.0f to Color(0x550A0C10)
                                    )
                                )
                                .navigationBarsPadding()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                LinerBottomNavItem(
                                    label = "Главная",
                                    iconRes = if (isHome) R.drawable.ic_mgc_home_fill else R.drawable.ic_mgc_home_line,
                                    selected = isHome,
                                    onClick = { navigateToTab(LinerRoutes.TAB_HOME, LinerRoutes.HOME, isHome) },
                                    modifier = Modifier.weight(1f)
                                )
                                LinerBottomNavItem(
                                    label = "Поиск",
                                    iconRes = if (isSearch) R.drawable.ic_mgc_search_fill else R.drawable.ic_mgc_search_line,
                                    selected = isSearch,
                                    onClick = { navigateToTab(LinerRoutes.TAB_SEARCH, LinerRoutes.SEARCH, isSearch) },
                                    modifier = Modifier.weight(1f)
                                )
                                LinerBottomNavItem(
                                    label = "Медиатека",
                                    iconRes = if (isLibrary) R.drawable.ic_mgc_library_fill else R.drawable.ic_mgc_library_line,
                                    selected = isLibrary,
                                    onClick = { navigateToTab(LinerRoutes.TAB_LIBRARY, LinerRoutes.LIBRARY, isLibrary) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            ) { _ ->
                NavHost(
                    navController = navController,
                    startDestination = LinerRoutes.TAB_HOME,
                    enterTransition = {
                        if (reducedMotion) {
                            fadeIn(tween(0))
                        } else {
                            val from = tabIndex(initialState.destination)
                            val to = tabIndex(targetState.destination)
                            if (from != null && to != null && from != to) {
                                val dir = if (to > from) 1 else -1
                                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { dir * (it / 10) } +
                                    fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL))
                            } else if (isDetailDestination(targetState.destination)) {
                                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { it / 8 } +
                                    fadeIn(LinerMotion.easeOutTween(180))
                            } else {
                                fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_FAST))
                            }
                        }
                    },
                    exitTransition = {
                        if (reducedMotion) {
                            fadeOut(tween(0))
                        } else {
                            val from = tabIndex(initialState.destination)
                            val to = tabIndex(targetState.destination)
                            if (from != null && to != null && from != to) {
                                val dir = if (to > from) 1 else -1
                                slideOutHorizontally(LinerMotion.easeOutTween(200)) { -dir * (it / 10) } +
                                    fadeOut(LinerMotion.easeOutTween(180))
                            } else if (isDetailDestination(targetState.destination)) {
                                slideOutHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { -it / 12 } +
                                    fadeOut(LinerMotion.easeOutTween(180))
                            } else {
                                fadeOut(LinerMotion.easeOutTween(LinerMotion.DURATION_FAST))
                            }
                        }
                    },
                    popEnterTransition = {
                        if (reducedMotion) {
                            fadeIn(tween(0))
                        } else {
                            val from = tabIndex(initialState.destination)
                            val to = tabIndex(targetState.destination)
                            if (from != null && to != null && from != to) {
                                val dir = if (to > from) 1 else -1
                                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { dir * (it / 10) } +
                                    fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL))
                            } else if (isDetailDestination(initialState.destination)) {
                                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { -it / 12 } +
                                    fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL))
                            } else {
                                fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_FAST))
                            }
                        }
                    },
                    popExitTransition = {
                        if (reducedMotion) {
                            fadeOut(tween(0))
                        } else {
                            val from = tabIndex(initialState.destination)
                            val to = tabIndex(targetState.destination)
                            if (from != null && to != null && from != to) {
                                val dir = if (to > from) 1 else -1
                                slideOutHorizontally(LinerMotion.easeOutTween(200)) { -dir * (it / 10) } +
                                    fadeOut(LinerMotion.easeOutTween(180))
                            } else if (isDetailDestination(initialState.destination)) {
                                slideOutHorizontally(LinerMotion.easeOutTween(200)) { it / 8 } +
                                    fadeOut(LinerMotion.easeOutTween(160))
                            } else {
                                fadeOut(LinerMotion.easeOutTween(LinerMotion.DURATION_FAST))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    navigation(
                        startDestination = LinerRoutes.HOME,
                        route = LinerRoutes.TAB_HOME
                    ) {
                        composable(LinerRoutes.HOME) {
                            val homeViewModel: HomeViewModel = viewModel(
                                factory = HomeViewModel.Factory(container)
                            )
                            HomeScreen(
                                viewModel = homeViewModel,
                                onOpenDailyMix = { mix ->
                                    navController.navigate(LinerRoutes.dailyMixDetail(LinerRoutes.TAB_HOME, mix.id))
                                },
                                bottomContentPadding = PaddingValues(bottom = settledBottomPadding)
                            )
                        }
                        addTabDetailDestinations(
                            tabPrefix = LinerRoutes.TAB_HOME,
                            navController = navController,
                            container = container,
                            reducedMotion = reducedMotion,
                            bottomPadding = settledBottomPadding
                        )
                    }

                    navigation(
                        startDestination = LinerRoutes.SEARCH,
                        route = LinerRoutes.TAB_SEARCH
                    ) {
                        composable(LinerRoutes.SEARCH) {
                            val searchViewModel: SearchViewModel = viewModel(
                                factory = SearchViewModel.Factory(container)
                            )
                            SearchScreen(
                                viewModel = searchViewModel,
                                container = container,
                                onOpenArtist = { artistId ->
                                    navController.navigate(LinerRoutes.artistDetail(LinerRoutes.TAB_SEARCH, artistId))
                                },
                                onOpenAlbum = { albumId ->
                                    navController.navigate(LinerRoutes.albumDetail(LinerRoutes.TAB_SEARCH, albumId))
                                },
                                onOpenCatalogPlaylist = { playlistId ->
                                    navController.navigate(LinerRoutes.catalogPlaylistDetail(LinerRoutes.TAB_SEARCH, playlistId))
                                },
                                bottomContentPadding = PaddingValues(bottom = settledBottomPadding)
                            )
                        }
                        addTabDetailDestinations(
                            tabPrefix = LinerRoutes.TAB_SEARCH,
                            navController = navController,
                            container = container,
                            reducedMotion = reducedMotion,
                            bottomPadding = settledBottomPadding
                        )
                    }

                    navigation(
                        startDestination = LinerRoutes.LIBRARY,
                        route = LinerRoutes.TAB_LIBRARY
                    ) {
                        composable(LinerRoutes.LIBRARY) {
                            val libraryViewModel: LibraryViewModel = viewModel(
                                factory = LibraryViewModel.Factory(container)
                            )
                            LibraryScreen(
                                viewModel = libraryViewModel,
                                onOpenLikedTracks = {
                                    navController.navigate(LinerRoutes.playlistDetail(LinerRoutes.TAB_LIBRARY, LIKED_PLAYLIST_ID))
                                },
                                onOpenPlaylist = { playlistId ->
                                    navController.navigate(LinerRoutes.playlistDetail(LinerRoutes.TAB_LIBRARY, playlistId))
                                },
                                bottomContentPadding = PaddingValues(bottom = settledBottomPadding)
                            )
                        }
                        addTabDetailDestinations(
                            tabPrefix = LinerRoutes.TAB_LIBRARY,
                            navController = navController,
                            container = container,
                            reducedMotion = reducedMotion,
                            bottomPadding = settledBottomPadding
                        )
                    }
                }
            }

            if (hasPlayableTrack && (isNowPlayingOpen || isSheetMounted)) {
                BackHandler(enabled = isNowPlayingOpen) {
                    isNowPlayingOpen = false
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = sheetProgress.value * screenHeightPx
                            alpha = if (reducedMotion) (1f - sheetProgress.value) else 1f
                        }
                ) {
                    NowPlayingScreen(
                        playbackState = playbackState,
                        isLiked = isCurrentTrackLiked,
                        onCollapse = { isNowPlayingOpen = false },
                        onDragDelta = { deltaPx ->
                            scope.launch {
                                val next = (sheetProgress.value + deltaPx / screenHeightPx).coerceIn(0f, 1f)
                                sheetProgress.snapTo(next)
                            }
                        },
                        onDragRelease = { velocityYPx ->
                            val projected = sheetProgress.value +
                                LinerMotion.projectMomentum(velocityYPx / screenHeightPx, 0.99f)
                            if (projected > 0.36f) {
                                scope.launch {
                                    if (reducedMotion) {
                                        sheetProgress.snapTo(1f)
                                    } else {
                                        sheetProgress.animateTo(
                                            targetValue = 1f,
                                            animationSpec = LinerMotion.appleDefaultSpring(),
                                            initialVelocity = (velocityYPx / screenHeightPx).coerceAtLeast(0f)
                                        )
                                    }
                                    isNowPlayingOpen = false
                                }
                            } else {
                                scope.launch {
                                    if (reducedMotion) {
                                        sheetProgress.snapTo(0f)
                                    } else {
                                        sheetProgress.animateTo(
                                            targetValue = 0f,
                                            animationSpec = LinerMotion.appleMomentumSpring(),
                                            initialVelocity = velocityYPx / screenHeightPx
                                        )
                                    }
                                }
                            }
                        },
                        onToggleLike = {
                            currentTrack?.let(container::toggleLike)
                        },
                        onTogglePlayPause = container.playerController::togglePlayPause,
                        onNext = container.playerController::next,
                        onPrevious = container.playerController::previous,
                        onToggleShuffle = container.playerController::toggleShuffle,
                        onSeekTo = container.playerController::seekTo,
                        onOpenArtist = { artistId ->
                            val activePrefix = currentTabPrefix(navBackStackEntry?.destination)
                            isNowPlayingOpen = false
                            navController.navigate(LinerRoutes.artistDetail(activePrefix, artistId))
                        },
                        onOpenAlbum = { albumId ->
                            val activePrefix = currentTabPrefix(navBackStackEntry?.destination)
                            isNowPlayingOpen = false
                            navController.navigate(LinerRoutes.albumDetail(activePrefix, albumId))
                        },
                        onCycleRepeat = container.playerController::cycleRepeatMode,
                        onPlayQueueIndex = container.playerController::playQueueIndex,
                        onOpenTrackMenu = { t -> menuTrack = t }
                    )
                }
            }

            menuTrack?.let { selectedTrack ->
                TrackActionSheet(
                    track = selectedTrack,
                    container = container,
                    onOpenArtist = { artistId ->
                        val activePrefix = currentTabPrefix(navBackStackEntry?.destination)
                        isNowPlayingOpen = false
                        navController.navigate(LinerRoutes.artistDetail(activePrefix, artistId))
                    },
                    onDismiss = { menuTrack = null }
                )
            }
        }
        }
    }

    pendingHumanCheck?.let { request ->
        HumanCheckDialog(
            request = request,
            baseUrl = container.apiBaseUrl,
            userAgent = container.userAgent,
            onSolved = container::submitHumanCheckResult,
            onCancel = container::cancelHumanCheck
        )
    }
}

@Composable
private fun LinerBottomNavItem(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val reducedMotion = rememberReducedMotion()
    val contentColor by animateColorAsState(
        targetValue = if (selected) LinerColors.TextPrimary else Color.White.copy(alpha = 0.45f),
        animationSpec = LinerMotion.easeOutTween(if (reducedMotion) 0 else LinerMotion.DURATION_FAST),
        label = "bottomNavColor"
    )
    Column(
        modifier = modifier
            .pressScale(interactionSource, targetScale = 0.94f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
            color = contentColor,
            maxLines = 1
        )
    }
}

private fun NavGraphBuilder.addTabDetailDestinations(
    tabPrefix: String,
    navController: NavHostController,
    container: AppContainer,
    reducedMotion: Boolean,
    bottomPadding: Dp
) {
    composable(
        route = LinerRoutes.playlistRoutePattern(tabPrefix),
        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        enterTransition = {
            if (reducedMotion) {
                fadeIn(tween(0))
            } else {
                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { it / 8 } +
                    fadeIn(LinerMotion.easeOutTween(180))
            }
        },
        exitTransition = {
            if (reducedMotion) {
                fadeOut(tween(0))
            } else {
                slideOutHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { -it / 12 } +
                    fadeOut(LinerMotion.easeOutTween(180))
            }
        },
        popEnterTransition = {
            if (reducedMotion) {
                fadeIn(tween(0))
            } else {
                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { -it / 12 } +
                    fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL))
            }
        },
        popExitTransition = {
            if (reducedMotion) {
                fadeOut(tween(0))
            } else {
                slideOutHorizontally(LinerMotion.easeOutTween(200)) { it / 8 } +
                    fadeOut(LinerMotion.easeOutTween(160))
            }
        }
    ) { backStackEntry ->
        val playlistId = backStackEntry.arguments?.getString("playlistId")
            ?: LIKED_PLAYLIST_ID
        val detailViewModel: PlaylistDetailViewModel = viewModel(
            key = "${tabPrefix}_playlist_$playlistId",
            factory = PlaylistDetailViewModel.Factory(playlistId, container)
        )
        PlaylistDetailScreen(
            viewModel = detailViewModel,
            container = container,
            onBack = { navController.popBackStack() },
            bottomContentPadding = PaddingValues(bottom = bottomPadding)
        )
    }

    composable(
        route = LinerRoutes.artistRoutePattern(tabPrefix),
        arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
        enterTransition = {
            if (reducedMotion) {
                fadeIn(tween(0))
            } else {
                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { it / 8 } +
                    fadeIn(LinerMotion.easeOutTween(180))
            }
        },
        exitTransition = {
            if (reducedMotion) {
                fadeOut(tween(0))
            } else {
                slideOutHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { -it / 12 } +
                    fadeOut(LinerMotion.easeOutTween(180))
            }
        },
        popEnterTransition = {
            if (reducedMotion) {
                fadeIn(tween(0))
            } else {
                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { -it / 12 } +
                    fadeIn(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL))
            }
        },
        popExitTransition = {
            if (reducedMotion) {
                fadeOut(tween(0))
            } else {
                slideOutHorizontally(LinerMotion.easeOutTween(200)) { it / 8 } +
                    fadeOut(LinerMotion.easeOutTween(160))
            }
        }
    ) { backStackEntry ->
        val artistId = backStackEntry.arguments?.getString("artistId").orEmpty()
        val artistViewModel: ArtistViewModel = viewModel(
            key = "${tabPrefix}_artist_$artistId",
            factory = ArtistViewModel.Factory(artistId, container)
        )
        ArtistScreen(
            viewModel = artistViewModel,
            container = container,
            onBack = { navController.popBackStack() },
            onOpenAlbum = { albumId ->
                navController.navigate(LinerRoutes.albumDetail(tabPrefix, albumId))
            },
            bottomContentPadding = PaddingValues(bottom = bottomPadding)
        )
    }
}

private fun isDetailDestination(dest: NavDestination?): Boolean {
    val route = dest?.route ?: return false
    return route.contains("/playlist/") || route.contains("/artist/")
}

private fun tabIndex(dest: NavDestination?): Int? = when {
    dest?.hierarchy?.any { it.route == LinerRoutes.TAB_HOME } == true -> 0
    dest?.hierarchy?.any { it.route == LinerRoutes.TAB_SEARCH } == true -> 1
    dest?.hierarchy?.any { it.route == LinerRoutes.TAB_LIBRARY } == true -> 2
    else -> null
}

private fun currentTabPrefix(dest: NavDestination?): String = when {
    dest?.hierarchy?.any { it.route == LinerRoutes.TAB_SEARCH } == true -> LinerRoutes.TAB_SEARCH
    dest?.hierarchy?.any { it.route == LinerRoutes.TAB_LIBRARY } == true -> LinerRoutes.TAB_LIBRARY
    else -> LinerRoutes.TAB_HOME
}
