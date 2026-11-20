package `fun`.tryliner.android.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val LIKED_PLAYLIST_ID = "__liked__"

data class PlaylistDetailUiState(
    val title: String = "",
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val isLikedCollection: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val totalDurationMs: Long = 0L,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val container: AppContainer
) : ViewModel() {

    private val _state = MutableStateFlow(
        PlaylistDetailUiState(
            title = if (playlistId == LIKED_PLAYLIST_ID) "Liked Tracks" else "Playlist",
            isLikedCollection = playlistId == LIKED_PLAYLIST_ID
        )
    )
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (playlistId == LIKED_PLAYLIST_ID) {
                        val page = container.apiClient.listLikedTracks(limit = 100)
                        val tracks = page.items.map { it.track }
                        tracks.forEach { container.markTrackLiked(it.id, true) }
                        PlaylistDetailUiState(
                            title = "Liked Tracks",
                            subtitle = "Your favorite songs",
                            coverUrl = null,
                            isLikedCollection = true,
                            tracks = tracks,
                            totalDurationMs = tracks.sumOf { it.durationMs },
                            isLoading = false
                        )
                    } else {
                        val details = container.apiClient.getUserPlaylist(playlistId)
                        val tracks = details.items.sortedBy { it.position }.map { it.track }
                        container.seedLikedTrackIds(tracks)
                        PlaylistDetailUiState(
                            title = details.playlist.title,
                            subtitle = details.playlist.description,
                            coverUrl = details.playlist.coverUrls?.firstOrNull(),
                            isLikedCollection = false,
                            tracks = tracks,
                            totalDurationMs = details.durationMs.takeIf { it > 0L }
                                ?: tracks.sumOf { it.durationMs },
                            isLoading = false
                        )
                    }
                }
            }.onSuccess { loaded ->
                _state.value = loaded
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to load tracks"
                    )
                }
            }
        }
    }

    fun playAll(shuffle: Boolean = false) {
        val tracks = _state.value.tracks
        if (tracks.isNotEmpty()) {
            container.playerController.playQueue(tracks, startIndex = 0, shuffle = shuffle)
        }
    }

    fun playTrackAt(index: Int) {
        val tracks = _state.value.tracks
        if (index in tracks.indices) {
            container.playerController.playQueue(tracks, startIndex = index, shuffle = false)
        }
    }

    fun toggleLike(track: Track) {
        container.toggleLike(track)
    }

    class Factory(
        private val playlistId: String,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistDetailViewModel(playlistId, container) as T
        }
    }
}
