package `fun`.tryliner.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.SearchResult
import `fun`.tryliner.android.core.model.Track
import kotlinx.coroutines.CancellationException
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

import kotlinx.coroutines.flow.combine

enum class SearchCatalogSource(val label: String, val apiParam: String?) {
    SPOTIFY("Spotify", "spotify"),
    YOUTUBE("YouTube", null)
}

data class SearchUiState(
    val query: String = "",
    val source: SearchCatalogSource = SearchCatalogSource.SPOTIFY,
    val tracks: List<Track> = emptyList(),
    val artists: List<SearchResult.ArtistItem> = emptyList(),
    val albums: List<SearchResult.AlbumItem> = emptyList(),
    val playlists: List<SearchResult.PlaylistItem> = emptyList(),
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
    private val sourceFlow = MutableStateFlow(SearchCatalogSource.SPOTIFY)

    init {
        viewModelScope.launch {
            combine(
                queryFlow.debounce(350L),
                sourceFlow
            ) { rawQuery, source -> rawQuery.trim() to source }
                .distinctUntilChanged()
                .collectLatest { (trimmed, source) ->
                    if (trimmed.isEmpty()) {
                        _state.update {
                            it.copy(
                                tracks = emptyList(),
                                artists = emptyList(),
                                albums = emptyList(),
                                playlists = emptyList(),
                                isLoading = false,
                                errorMessage = null
                            )
                        }
                        return@collectLatest
                    }
                    performSearch(trimmed, source)
                }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, errorMessage = null) }
        queryFlow.value = query
    }

    fun onSourceChange(source: SearchCatalogSource) {
        if (_state.value.source == source) return
        _state.update { it.copy(source = source, errorMessage = null) }
        sourceFlow.value = source
    }

    fun clearQuery() {
        onQueryChange("")
    }

    private suspend fun performSearch(query: String, source: SearchCatalogSource) {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val items = withContext(Dispatchers.IO) {
                val linerItems = try {
                    container.apiClient.search(
                        query = query,
                        type = "all",
                        source = source.apiParam
                    ).items
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }

                if (linerItems.isNotEmpty() || source == SearchCatalogSource.SPOTIFY) {
                    linerItems
                } else {
                    container.innertubeClient.search(query = query, type = "track")
                }
            }
            val tracks = items.filterIsInstance<SearchResult.TrackItem>().map { it.toTrack() }
            val artists = items.filterIsInstance<SearchResult.ArtistItem>()
            val albums = items.filterIsInstance<SearchResult.AlbumItem>()
            val playlists = items.filterIsInstance<SearchResult.PlaylistItem>()
            container.seedLikedTrackIds(tracks)
            _state.update {
                it.copy(
                    tracks = tracks,
                    artists = artists,
                    albums = albums,
                    playlists = playlists,
                    isLoading = false,
                    errorMessage = null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Ошибка поиска"
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
