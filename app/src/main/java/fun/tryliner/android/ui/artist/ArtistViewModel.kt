package `fun`.tryliner.android.ui.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.ArtistDetails
import `fun`.tryliner.android.core.model.SearchResult
import `fun`.tryliner.android.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ArtistUiState(
    val artist: ArtistDetails? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

class ArtistViewModel(
    private val artistId: String,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolvedId = if (artistId.startsWith("name:")) {
                        val queryName = artistId.removePrefix("name:").trim()
                        val searchRes = container.apiClient.search(query = queryName, type = "artist")
                        searchRes.items
                            .filterIsInstance<SearchResult.ArtistItem>()
                            .firstOrNull()?.id
                            ?: error("Artist \"$queryName\" not found")
                    } else {
                        artistId
                    }
                    container.apiClient.getArtist(resolvedId)
                }
            }.onSuccess { details ->
                container.seedLikedTrackIds(details.tracks)
                _state.value = ArtistUiState(
                    artist = details,
                    isLoading = false,
                    errorMessage = null,
                )
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to load artist",
                    )
                }
            }
        }
    }

    fun playAll(shuffle: Boolean = false) {
        val tracks = _state.value.artist?.tracks.orEmpty()
        if (tracks.isNotEmpty()) {
            container.playerController.playQueue(tracks, startIndex = 0, shuffle = shuffle)
        }
    }

    fun playTrackAt(index: Int) {
        val tracks = _state.value.artist?.tracks.orEmpty()
        if (index in tracks.indices) {
            container.playerController.playQueue(tracks, startIndex = index, shuffle = false)
        }
    }

    fun toggleLike(track: Track) {
        container.toggleLike(track)
    }

    class Factory(
        private val artistId: String,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ArtistViewModel(artistId, container) as T
        }
    }
}
