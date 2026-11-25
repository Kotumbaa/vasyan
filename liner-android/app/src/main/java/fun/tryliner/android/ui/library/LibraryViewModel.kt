package `fun`.tryliner.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.UserPlaylist
import `fun`.tryliner.android.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val playlists: List<UserPlaylist> = emptyList(),
    val likedTracks: List<Track> = emptyList(),
    val likedTracksCount: Int = 0,
    val recentTracks: List<Track> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isCreatePlaylistDialogOpen: Boolean = false,
    val isCreatingPlaylist: Boolean = false,
    val createPlaylistError: String? = null
) {
    val filteredLikedTracks: List<Track>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return likedTracks
            return likedTracks.filter { track ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artistNames.contains(query, ignoreCase = true) ||
                    (track.album?.title?.contains(query, ignoreCase = true) == true)
            }
        }
}

class LibraryViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    val likedTrackIds: StateFlow<Set<String>> = container.likedTrackIds
    val playbackState: StateFlow<PlaybackUiState> = container.playerController.state
    val downloadedTracks: StateFlow<List<Track>> = container.downloadManager.downloadedTracks

    private var refreshJob: Job? = null

    fun playDownloaded(index: Int, shuffle: Boolean = false) {
        val tracks = downloadedTracks.value
        if (tracks.isNotEmpty()) {
            container.playerController.playQueue(
                tracks,
                startIndex = if (shuffle) tracks.indices.random() else index.coerceIn(0, tracks.lastIndex),
                shuffle = shuffle
            )
        }
    }

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        refreshJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val playlistsDeferred = async { container.apiClient.listPlaylists() }
                    val likedDeferred = async {
                        val allLiked = mutableListOf<Track>()
                        var cursor: String? = null
                        var pages = 0
                        do {
                            val page = container.apiClient.listLikedTracks(limit = 100, cursor = cursor)
                            allLiked.addAll(page.items.map { it.track })
                            cursor = page.nextCursor
                            pages += 1
                        } while (!cursor.isNullOrBlank() && pages < 20)
                        allLiked.distinctBy { it.id }
                    }
                    val historyDeferred = async {
                        runCatching { container.apiClient.getHistory(limit = 25) }.getOrNull()
                    }
                    Triple(
                        playlistsDeferred.await(),
                        likedDeferred.await(),
                        historyDeferred.await()
                    )
                }
            }.onSuccess { (playlists, likedTracks, historyPage) ->
                val recentTracks = historyPage?.items
                    .orEmpty()
                    .map { it.track }
                    .distinctBy { it.id }

                container.seedLikedTrackIds(likedTracks, replace = false)

                _state.update {
                    it.copy(
                        playlists = playlists,
                        likedTracks = likedTracks,
                        likedTracksCount = likedTracks.size,
                        recentTracks = recentTracks,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Не удалось загрузить медиатеку"
                    )
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun clearSearchQuery() {
        _state.update { it.copy(searchQuery = "") }
    }

    fun playAllLiked() {
        val tracks = _state.value.likedTracks
        if (tracks.isNotEmpty()) {
            container.playerController.playQueue(tracks, startIndex = 0, shuffle = false)
        }
    }

    fun playLikedTrack(track: Track) {
        val tracks = _state.value.likedTracks
        val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (tracks.isNotEmpty()) {
            container.playerController.playQueue(tracks, startIndex = index, shuffle = false)
        }
    }

    fun playRecentTrackAt(index: Int) {
        val tracks = _state.value.recentTracks
        if (index in tracks.indices) {
            container.playerController.playQueue(tracks, startIndex = index, shuffle = false)
        }
    }

    fun toggleLike(track: Track) {
        container.toggleLike(track)
    }

    fun openCreatePlaylistDialog() {
        _state.update {
            it.copy(
                isCreatePlaylistDialogOpen = true,
                createPlaylistError = null
            )
        }
    }

    fun dismissCreatePlaylistDialog() {
        if (_state.value.isCreatingPlaylist) return
        _state.update {
            it.copy(
                isCreatePlaylistDialogOpen = false,
                createPlaylistError = null
            )
        }
    }

    fun createPlaylist(title: String, description: String?) {
        if (_state.value.isCreatingPlaylist) return
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            _state.update { it.copy(createPlaylistError = "Введите название плейлиста") }
            return
        }
        val trimmedDescription = description?.trim()?.takeIf { it.isNotEmpty() }

        _state.update { it.copy(isCreatingPlaylist = true, createPlaylistError = null) }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.apiClient.createPlaylist(
                        title = trimmedTitle,
                        description = trimmedDescription
                    )
                }
            }.onSuccess { created ->
                _state.update { current ->
                    current.copy(
                        playlists = listOf(created) + current.playlists.filterNot { it.id == created.id },
                        isCreatingPlaylist = false,
                        isCreatePlaylistDialogOpen = false,
                        createPlaylistError = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isCreatingPlaylist = false,
                        createPlaylistError = error.message ?: "Не удалось создать плейлист"
                    )
                }
            }
        }
    }

    fun logout() {
        container.apiClient.logout()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(container) as T
        }
    }
}
