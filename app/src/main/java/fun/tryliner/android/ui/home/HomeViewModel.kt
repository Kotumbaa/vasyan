package `fun`.tryliner.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.DailyMixItem
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val dailyMixes: List<DailyMixItem> = emptyList(),
    val popularTracks: List<Track> = emptyList(),
    val recentTracks: List<Track> = emptyList(),
    val mostPlayedTracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
) {
    val isEmpty: Boolean
        get() = dailyMixes.isEmpty() &&
            popularTracks.isEmpty() &&
            recentTracks.isEmpty() &&
            mostPlayedTracks.isEmpty()
}

class HomeViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val likedTrackIds: StateFlow<Set<String>> = container.likedTrackIds
    val playbackState: StateFlow<PlaybackUiState> = container.playerController.state

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val mixesDeferred = async { runCatching { container.apiClient.getDailyMixes() } }
                val popular7dDeferred = async {
                    runCatching { container.apiClient.getPopularTracks(window = "7d", limit = 20) }
                }
                val historyDeferred = async {
                    runCatching { container.apiClient.getHistory(limit = 20) }
                }
                val popularAllDeferred = async {
                    runCatching { container.apiClient.getPopularTracks(window = "all", limit = 20) }
                }

                HomeFetchResult(
                    mixes = mixesDeferred.await(),
                    popular7d = popular7dDeferred.await(),
                    history = historyDeferred.await(),
                    popularAll = popularAllDeferred.await()
                )
            }

            val mixes = result.mixes.getOrNull()?.items.orEmpty()
            val popularTracks = result.popular7d.getOrNull()?.items
                .orEmpty()
                .map { item ->
                    if (item.track.viewer == null && item.viewer != null) {
                        item.track.copy(viewer = item.viewer)
                    } else {
                        item.track
                    }
                }
            val recentTracks = result.history.getOrNull()?.items
                .orEmpty()
                .map { it.track }
                .distinctBy { it.id }
            val mostPlayedTracks = result.popularAll.getOrNull()?.items
                .orEmpty()
                .map { item ->
                    if (item.track.viewer == null && item.viewer != null) {
                        item.track.copy(viewer = item.viewer)
                    } else {
                        item.track
                    }
                }

            val allTracks = buildList {
                addAll(popularTracks)
                addAll(recentTracks)
                addAll(mostPlayedTracks)
                mixes.forEach { addAll(it.tracks) }
            }
            if (allTracks.isNotEmpty()) {
                container.seedLikedTrackIds(allTracks, replace = false)
            }

            val allFailed = result.mixes.isFailure &&
                result.popular7d.isFailure &&
                result.history.isFailure &&
                result.popularAll.isFailure

            val firstError = result.popular7d.exceptionOrNull()
                ?: result.history.exceptionOrNull()
                ?: result.mixes.exceptionOrNull()
                ?: result.popularAll.exceptionOrNull()

            _state.update {
                it.copy(
                    dailyMixes = mixes,
                    popularTracks = popularTracks,
                    recentTracks = recentTracks,
                    mostPlayedTracks = mostPlayedTracks,
                    isLoading = false,
                    errorMessage = if (allFailed) {
                        firstError?.message ?: "Failed to load home feed"
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun playPopularTrackAt(index: Int) {
        val tracks = _state.value.popularTracks
        if (index in tracks.indices) {
            container.playerController.playQueue(tracks, startIndex = index, shuffle = false)
        }
    }

    fun playRecentTrackAt(index: Int) {
        val tracks = _state.value.recentTracks
        if (index in tracks.indices) {
            container.playerController.playQueue(tracks, startIndex = index, shuffle = false)
        }
    }

    fun playMostPlayedTrackAt(index: Int) {
        val tracks = _state.value.mostPlayedTracks
        if (index in tracks.indices) {
            container.playerController.playQueue(tracks, startIndex = index, shuffle = false)
        }
    }

    fun playDailyMix(mix: DailyMixItem) {
        if (mix.tracks.isNotEmpty()) {
            container.playerController.playQueue(mix.tracks, startIndex = 0, shuffle = false)
        }
    }

    fun toggleLike(track: Track) {
        container.toggleLike(track)
    }

    private data class HomeFetchResult(
        val mixes: Result<`fun`.tryliner.android.core.model.DailyMixesResponse>,
        val popular7d: Result<`fun`.tryliner.android.core.model.PopularTracksResponse>,
        val history: Result<`fun`.tryliner.android.core.model.PlaybackHistoryResponse>,
        val popularAll: Result<`fun`.tryliner.android.core.model.PopularTracksResponse>
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(container) as T
        }
    }
}
