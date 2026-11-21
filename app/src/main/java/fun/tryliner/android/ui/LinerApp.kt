package `fun`.tryliner.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import `fun`.tryliner.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import `fun`.tryliner.android.AppContainer
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
import `fun`.tryliner.android.ui.playlist.LIKED_PLAYLIST_ID
import `fun`.tryliner.android.ui.playlist.PlaylistDetailScreen
import `fun`.tryliner.android.ui.playlist.PlaylistDetailViewModel
import `fun`.tryliner.android.ui.search.SearchScreen
import `fun`.tryliner.android.ui.search.SearchViewModel
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.rememberReducedMotion

object LinerRoutes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"

    fun playlistDetail(playlistId: String): String = "playlist/$playlistId"
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
        val likedIds by container.likedTrackIds.collectAsStateWithLifecycle()
        var isNowPlayingOpen by rememberSaveable { mutableStateOf(false) }

        val currentTrack = playbackState.currentTrack
        val isCurrentTrackLiked = currentTrack != null && likedIds.contains(currentTrack.id)
        val activeRemote = remoteState?.takeIf { remote ->
            remote.track != null && (remote.isPlaying || !playbackState.isPlaying)
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

        val openFactor = (1f - sheetProgress.value).coerceIn(0f, 1f)
        val bgScale = 1f - 0.055f * openFactor
        val bgCorner = (18f * openFactor).dp

        AtmosphericBackdrop(
            modifier = Modifier.fillMaxSize(),
            coverUrl = activeCoverUrl
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = bgScale
                        scaleY = bgScale
                        alpha = 1f - 0.25f * openFactor
                    }
                    .clip(RoundedCornerShape(bgCorner)),
                bottomBar = {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (currentTrack != null || activeRemote != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            0.0f to Color.Transparent,
                                            0.65f to Color(0xCC0A0A0C),
                                            1.0f to Color(0xFF0A0A0C)
                                        )
                                    )
                            ) {
                                MiniPlayerBar(
                                    playbackState = playbackState,
                                    isLiked = isCurrentTrackLiked,
                                    onToggleLike = { currentTrack?.let(container::toggleLike) },
                                    onTogglePlayPause = container.playerController::togglePlayPause,
                                    onClick = { isNowPlayingOpen = true },
                                    onPrevious = container.playerController::previous,
                                    onNext = container.playerController::next,
                                    remoteState = activeRemote,
                                    onRemoteTogglePlayPause = { container.lanConnectManager.sendCommand("toggle") },
                                    onRemotePrevious = { container.lanConnectManager.sendCommand("prev") },
                                    onRemoteNext = { container.lanConnectManager.sendCommand("next") },
                                    onPlayOnPhone = { container.lanConnectManager.handoffToPhone() }
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A0A0C))
                                .navigationBarsPadding()
                        ) {
                            HorizontalDivider(thickness = 0.5.dp, color = LinerColors.BorderAlpha14)
                            NavigationBar(
                                containerColor = Color(0xFF0A0A0C),
                                contentColor = LinerColors.TextPrimary,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0)
                            ) {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = LinerColors.TextPrimary,
                                selectedTextColor = LinerColors.TextPrimary,
                                unselectedIconColor = LinerColors.TextTertiary,
                                unselectedTextColor = LinerColors.TextTertiary,
                                indicatorColor = Color.Transparent
                            )

                            val isHome = currentRoute == LinerRoutes.HOME
                            NavigationBarItem(
                                selected = isHome,
                                onClick = {
                                    navController.navigate(LinerRoutes.HOME) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(
                                            if (isHome) R.drawable.ic_mgc_home_fill else R.drawable.ic_mgc_home_line
                                        ),
                                        contentDescription = "Home",
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text("Home") },
                                colors = itemColors
                            )

                            val isSearch = currentRoute == LinerRoutes.SEARCH
                            NavigationBarItem(
                                selected = isSearch,
                                onClick = {
                                    navController.navigate(LinerRoutes.SEARCH) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(
                                            if (isSearch) R.drawable.ic_mgc_search_fill else R.drawable.ic_mgc_search_line
                                        ),
                                        contentDescription = "Search",
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text("Search") },
                                colors = itemColors
                            )

                            val isLibrary = currentRoute == LinerRoutes.LIBRARY ||
                                currentRoute?.startsWith("playlist/") == true
                            NavigationBarItem(
                                selected = isLibrary,
                                onClick = {
                                    navController.navigate(LinerRoutes.LIBRARY) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(
                                            if (isLibrary) R.drawable.ic_mgc_library_fill else R.drawable.ic_mgc_library_line
                                        ),
                                        contentDescription = "Library",
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text("Library") },
                                colors = itemColors
                            )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = LinerRoutes.HOME,
                    enterTransition = {
                        fadeIn(tween(if (reducedMotion) 0 else LinerMotion.DURATION_FAST, easing = LinerMotion.EaseOut))
                    },
                    exitTransition = {
                        fadeOut(tween(if (reducedMotion) 0 else 110, easing = LinerMotion.EaseOut))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    composable(LinerRoutes.HOME) {
                        val homeViewModel: HomeViewModel = viewModel(
                            factory = HomeViewModel.Factory(container)
                        )
                        HomeScreen(
                            viewModel = homeViewModel,
                            bottomContentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
                        )
                    }

                    composable(LinerRoutes.LIBRARY) {
                        val libraryViewModel: LibraryViewModel = viewModel(
                            factory = LibraryViewModel.Factory(container)
                        )
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenLikedTracks = {
                                navController.navigate(LinerRoutes.playlistDetail(LIKED_PLAYLIST_ID))
                            },
                            onOpenPlaylist = { playlistId ->
                                navController.navigate(LinerRoutes.playlistDetail(playlistId))
                            },
                            bottomContentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
                        )
                    }

                    composable(LinerRoutes.SEARCH) {
                        val searchViewModel: SearchViewModel = viewModel(
                            factory = SearchViewModel.Factory(container)
                        )
                        SearchScreen(
                            viewModel = searchViewModel,
                            container = container,
                            bottomContentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
                        )
                    }

                    composable(
                        route = LinerRoutes.PLAYLIST_DETAIL,
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                        enterTransition = {
                            if (reducedMotion) {
                                fadeIn(tween(0))
                            } else {
                                slideInHorizontally(LinerMotion.easeOutTween(LinerMotion.DURATION_NORMAL)) { it / 8 } +
                                    fadeIn(LinerMotion.easeOutTween(180))
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
                            key = "playlist_$playlistId",
                            factory = PlaylistDetailViewModel.Factory(playlistId, container)
                        )
                        PlaylistDetailScreen(
                            viewModel = detailViewModel,
                            container = container,
                            onBack = { navController.popBackStack() },
                            bottomContentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
                        )
                    }
                }
            }

            if (hasPlayableTrack && (isNowPlayingOpen || sheetProgress.value < 0.998f)) {
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
                                    sheetProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = LinerMotion.appleDefaultSpring(),
                                        initialVelocity = (velocityYPx / screenHeightPx).coerceAtLeast(0f)
                                    )
                                    isNowPlayingOpen = false
                                }
                            } else {
                                scope.launch {
                                    sheetProgress.animateTo(
                                        targetValue = 0f,
                                        animationSpec = LinerMotion.appleMomentumSpring(),
                                        initialVelocity = velocityYPx / screenHeightPx
                                    )
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
                        onSeekTo = container.playerController::seekTo
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
