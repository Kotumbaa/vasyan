package `fun`.tryliner.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.SearchResult
import `fun`.tryliner.android.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchUiState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(350L)
                .distinctUntilChanged()
                .collectLatest { rawQuery ->
                    val trimmed = rawQuery.trim()
                    if (trimmed.isEmpty()) {
                        _state.update {
                            it.copy(tracks = emptyList(), isLoading = false, errorMessage = null)
                        }
                        return@collectLatest
                    }
                    performSearch(trimmed)
                }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, errorMessage = null) }
        queryFlow.value = query
    }

    fun clearQuery() {
        onQueryChange("")
    }

    private suspend fun performSearch(query: String) {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        runCatching {
            withContext(Dispatchers.IO) {
                val linerItems = runCatching {
                    container.apiClient.search(query = query, type = "track").items
                }.getOrElse { emptyList() }

                val items = if (linerItems.isNotEmpty()) {
                    linerItems
                } else {
                    container.innertubeClient.search(query = query, type = "track")
                }

                items.filterIsInstance<SearchResult.TrackItem>().map { it.toTrack() }
            }
        }.onSuccess { tracks ->
            container.seedLikedTrackIds(tracks)
            _state.update {
                it.copy(
                    tracks = tracks,
                    isLoading = false,
                    errorMessage = null
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Search failed"
                )
            }
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

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(container) as T
        }
    }
}
