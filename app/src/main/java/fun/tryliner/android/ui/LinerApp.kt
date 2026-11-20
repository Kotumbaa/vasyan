package `fun`.tryliner.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
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

        AtmosphericBackdrop(
            modifier = Modifier.fillMaxSize(),
            coverUrl = activeCoverUrl
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    Column(
                        modifier = Modifier
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.32f to LinerColors.AmbientBase.copy(alpha = 0.85f),
                                    0.65f to LinerColors.AmbientBase,
                                    1.0f to LinerColors.AmbientBase
                                )
                            )
                            .navigationBarsPadding()
                    ) {
                        if (currentTrack != null || activeRemote != null) {
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

                        NavigationBar(
                            containerColor = Color(0xF20D0F14),
                            contentColor = LinerColors.TextPrimary,
                            tonalElevation = 0.dp
                        ) {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFFFFFFFF),
                                selectedTextColor = Color(0xFFFFFFFF),
                                unselectedIconColor = Color.White.copy(alpha = 0.45f),
                                unselectedTextColor = Color.White.copy(alpha = 0.45f),
                                indicatorColor = Color.Transparent
                            )

                            NavigationBarItem(
                                selected = currentRoute == LinerRoutes.HOME,
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
                                        imageVector = Icons.Rounded.Home,
                                        contentDescription = "Home"
                                    )
                                },
                                label = { Text("Home") },
                                colors = itemColors
                            )

                            NavigationBarItem(
                                selected = currentRoute == LinerRoutes.SEARCH,
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
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = "Search"
                                    )
                                },
                                label = { Text("Search") },
                                colors = itemColors
                            )

                            NavigationBarItem(
                                selected = currentRoute == LinerRoutes.LIBRARY ||
                                    currentRoute?.startsWith("playlist/") == true,
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
                                        imageVector = Icons.Rounded.LibraryMusic,
                                        contentDescription = "Library"
                                    )
                                },
                                label = { Text("Library") },
                                colors = itemColors
                            )
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = LinerRoutes.HOME,
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
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
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

            AnimatedVisibility(
                visible = isNowPlayingOpen && (currentTrack != null || activeRemote?.track != null),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                BackHandler(enabled = isNowPlayingOpen) {
                    isNowPlayingOpen = false
                }
                NowPlayingScreen(
                    playbackState = playbackState,
                    isLiked = isCurrentTrackLiked,
                    onCollapse = { isNowPlayingOpen = false },
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
