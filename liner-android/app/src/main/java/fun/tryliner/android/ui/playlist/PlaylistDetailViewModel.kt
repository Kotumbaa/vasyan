package `fun`.tryliner.android.ui.playlist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.SearchResult
import `fun`.tryliner.android.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val LIKED_PLAYLIST_ID = "__liked__"
const val ALBUM_PLAYLIST_PREFIX = "album:"
const val CATALOG_PLAYLIST_PREFIX = "catalog:"
const val DAILY_MIX_PREFIX = "mix:"

data class ImportLikedState(
    val isImporting: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val imported: Int = 0,
    val finished: Boolean = false,
    val error: String? = null
)

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
            title = when {
                playlistId == LIKED_PLAYLIST_ID -> "Понравившиеся"
                playlistId.startsWith(ALBUM_PLAYLIST_PREFIX) -> "Альбом"
                playlistId.startsWith(DAILY_MIX_PREFIX) -> "Микс дня"
                else -> "Плейлист"
            },
            isLikedCollection = playlistId == LIKED_PLAYLIST_ID
        )
    )
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private val _importState = MutableStateFlow(ImportLikedState())
    val importState: StateFlow<ImportLikedState> = _importState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        loadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when {
                        playlistId == LIKED_PLAYLIST_ID -> {
                            val tracks = mutableListOf<Track>()
                            var cursor: String? = null
                            var pages = 0
                            do {
                                val page = container.apiClient.listLikedTracks(limit = 100, cursor = cursor)
                                tracks.addAll(page.items.map { it.track })
                                cursor = page.nextCursor
                                pages += 1
                            } while (!cursor.isNullOrBlank() && pages < 20)
                            val uniqueTracks = tracks.distinctBy { it.id }
                            uniqueTracks.forEach { container.markTrackLiked(it.id, true) }
                            PlaylistDetailUiState(
                                title = "Понравившиеся",
                                subtitle = "Любимые треки",
                                coverUrl = null,
                                isLikedCollection = true,
                                tracks = uniqueTracks,
                                totalDurationMs = uniqueTracks.sumOf { it.durationMs },
                                isLoading = false
                            )
                        }
                        playlistId.startsWith(ALBUM_PLAYLIST_PREFIX) -> {
                            val albumId = playlistId.removePrefix(ALBUM_PLAYLIST_PREFIX)
                            val album = container.apiClient.getAlbum(albumId)
                            container.seedLikedTrackIds(album.tracks)
                            val artistNames = album.artists.joinToString(", ") { it.name }
                            val subtitle = listOfNotNull(
                                artistNames.takeIf { it.isNotBlank() },
                                album.year?.toString()
                            ).joinToString(" · ").takeIf { it.isNotBlank() }
                            PlaylistDetailUiState(
                                title = album.title,
                                subtitle = subtitle,
                                coverUrl = album.cover?.url ?: album.cover?.fallbackUrl,
                                isLikedCollection = false,
                                tracks = album.tracks,
                                totalDurationMs = album.durationMs?.takeIf { it > 0L }
                                    ?: album.tracks.sumOf { it.durationMs },
                                isLoading = false
                            )
                        }
                        playlistId.startsWith(CATALOG_PLAYLIST_PREFIX) -> {
                            val catalogId = playlistId.removePrefix(CATALOG_PLAYLIST_PREFIX)
                            val playlist = container.apiClient.getCatalogPlaylist(catalogId)
                            container.seedLikedTrackIds(playlist.tracks)
                            val subtitle = playlist.description?.takeIf { it.isNotBlank() }
                                ?: playlist.author?.takeIf { it.isNotBlank() }
                            PlaylistDetailUiState(
                                title = playlist.title,
                                subtitle = subtitle,
                                coverUrl = playlist.cover?.url ?: playlist.cover?.fallbackUrl,
                                isLikedCollection = false,
                                tracks = playlist.tracks,
                                totalDurationMs = playlist.durationMs?.takeIf { it > 0L }
                                    ?: playlist.tracks.sumOf { it.durationMs },
                                isLoading = false
                            )
                        }
                        playlistId.startsWith(DAILY_MIX_PREFIX) -> {
                            val mixId = playlistId.removePrefix(DAILY_MIX_PREFIX)
                            val mix = container.getCachedDailyMix(mixId)
                                ?: container.apiClient.getDailyMixes().items
                                    .also { container.cacheDailyMixes(it) }
                                    .firstOrNull { it.id == mixId }
                                ?: error("Микс не найден")
                            container.seedLikedTrackIds(mix.tracks)
                            PlaylistDetailUiState(
                                title = mix.title,
                                subtitle = mix.description?.takeIf { it.isNotBlank() }
                                    ?: mix.clusterArtists.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                coverUrl = mix.cover?.url ?: mix.cover?.fallbackUrl
                                    ?: mix.tracks.firstOrNull()?.cover?.url,
                                isLikedCollection = false,
                                tracks = mix.tracks,
                                totalDurationMs = mix.tracks.sumOf { it.durationMs },
                                isLoading = false
                            )
                        }
                        else -> {
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
                }
            }.onSuccess { loaded ->
                _state.value = loaded
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Не удалось загрузить треки"
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

    fun resetImportState() {
        _importState.value = ImportLikedState()
    }

    fun importToLiked(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || _importState.value.isImporting) return

        viewModelScope.launch {
            _importState.value = ImportLikedState(isImporting = true)
            try {
                withContext(Dispatchers.IO) {
                    val alreadyLiked = container.likedTrackIds.value
                    val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }

                    // Check if single line is a Spotify or YouTube playlist URL
                    val singleSpotifyUrl = if (lines.size == 1 && isSpotifyUrl(lines[0])) lines[0] else null
                    val singlePlaylistId = if (lines.size == 1 && singleSpotifyUrl == null) extractPlaylistId(lines[0]) else null

                    if (singleSpotifyUrl != null) {
                        val spotify = container.apiClient.importSpotify(singleSpotifyUrl)
                        val spTracks = spotify.tracks
                        if (spTracks.isEmpty()) error("Плейлист Spotify пуст или закрыт")
                        _importState.update { it.copy(total = spTracks.size) }
                        var imported = 0
                        spTracks.forEachIndexed { idx, spTrack ->
                            val query = listOf(spTrack.artist, spTrack.title)
                                .filter { it.isNotBlank() }
                                .joinToString(" - ")
                            val track = resolveLineToTrack(query)
                            if (track != null && !alreadyLiked.contains(track.id)) {
                                runCatching {
                                    container.apiClient.likeTrack(track.id, trackSnapshot = track)
                                    container.markTrackLiked(track.id, true)
                                    imported += 1
                                }
                            }
                            _importState.update { it.copy(done = idx + 1, imported = imported) }
                        }
                    } else if (singlePlaylistId != null) {
                        val playlist = container.apiClient.getCatalogPlaylist(singlePlaylistId)
                        val tracks = playlist.tracks
                        if (tracks.isEmpty()) error("Плейлист пуст или недоступен")
                        _importState.update { it.copy(total = tracks.size) }
                        var imported = 0
                        tracks.forEachIndexed { idx, track ->
                            if (!alreadyLiked.contains(track.id)) {
                                runCatching {
                                    container.apiClient.likeTrack(track.id, trackSnapshot = track)
                                    container.markTrackLiked(track.id, true)
                                    imported += 1
                                }
                            }
                            _importState.update { it.copy(done = idx + 1, imported = imported) }
                        }
                    } else {
                        _importState.update { it.copy(total = lines.size) }
                        var imported = 0
                        lines.forEachIndexed { idx, line ->
                            val track = resolveLineToTrack(line)
                            if (track != null && !alreadyLiked.contains(track.id)) {
                                runCatching {
                                    container.apiClient.likeTrack(track.id, trackSnapshot = track)
                                    container.markTrackLiked(track.id, true)
                                    imported += 1
                                }
                            }
                            _importState.update { it.copy(done = idx + 1, imported = imported) }
                        }
                    }
                }
                _importState.update { it.copy(isImporting = false, finished = true) }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _importState.update {
                    it.copy(
                        isImporting = false,
                        error = e.message ?: "Ошибка импорта"
                    )
                }
            }
        }
    }

    private fun isSpotifyUrl(input: String): Boolean {
        return input.contains("open.spotify.com/", ignoreCase = true) ||
            input.contains("spotify.link/", ignoreCase = true)
    }

    private fun extractPlaylistId(input: String): String? {
        val uri = runCatching { Uri.parse(input) }.getOrNull()
        val listParam = uri?.getQueryParameter("list")?.trim()
        if (!listParam.isNullOrBlank()) return listParam
        if (Regex("^(PL|OLAK5uy_|RDCLAK|VL)[A-Za-z0-9_-]{8,}$").matches(input)) return input
        return null
    }

    private fun extractVideoId(input: String): String? {
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host.contains("youtu.be")) {
            return uri.pathSegments.firstOrNull()?.takeIf { it.length == 11 }
        }
        if (host.contains("youtube.com")) {
            return uri.getQueryParameter("v")?.takeIf { it.length == 11 }
        }
        return null
    }

    private suspend fun resolveLineToTrack(line: String): Track? {
        val videoId = extractVideoId(line)
        if (videoId != null) {
            return runCatching {
                val d = container.apiClient.getTrack(videoId)
                Track(
                    id = d.id,
                    title = d.title,
                    artists = d.artists,
                    album = d.album,
                    durationMs = d.durationMs,
                    cover = d.cover,
                    explicit = d.explicit,
                    audioVariant = d.audioVariant
                )
            }.getOrNull()
        }
        val items = runCatching {
            container.apiClient.search(query = line, type = "track").items
        }.getOrElse { emptyList() }.ifEmpty {
            runCatching { container.innertubeClient.search(query = line, type = "track") }
                .getOrElse { emptyList() }
        }
        return items.filterIsInstance<SearchResult.TrackItem>().firstOrNull()?.toTrack()
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
