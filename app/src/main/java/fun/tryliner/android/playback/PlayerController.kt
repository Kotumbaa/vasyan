package `fun`.tryliner.android.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import `fun`.tryliner.android.core.lyrics.LyricsFormat
import `fun`.tryliner.android.core.lyrics.LyricsParser
import `fun`.tryliner.android.core.lyrics.SyncedLyrics
import `fun`.tryliner.android.core.model.LyricsStreamEvent
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.network.InnertubeClient
import `fun`.tryliner.android.core.network.LinerApiClient
import `fun`.tryliner.android.core.network.ResolvedAudioStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackUiState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val lyrics: SyncedLyrics? = null,
    val isLyricsLoading: Boolean = false,
    val errorMessage: String? = null
)

class PlayerController(
    private val context: Context,
    private val apiClient: LinerApiClient,
    private val innertubeClient: InnertubeClient,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    var onBeforeLocalPlayback: (() -> Unit)? = null

    private var mediaController: MediaController? = null
    private var resolveJob: Job? = null
    private var lyricsJob: Job? = null
    private var positionTickerJob: Job? = null
    private val streamCache = ConcurrentHashMap<String, CachedResolvedStream>()

    private data class CachedResolvedStream(
        val stream: ResolvedAudioStream,
        val expiresAtMs: Long,
    )

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isRemoteSessionAttached()) return
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
                updatePositionFromController()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isRemoteSessionAttached()) return
            val buffering = playbackState == Player.STATE_BUFFERING
            _state.update { it.copy(isBuffering = buffering) }

            if (playbackState == Player.STATE_READY) {
                updatePositionFromController()
            } else if (playbackState == Player.STATE_ENDED) {
                next()
            }
        }
    }

    private fun isRemoteSessionAttached(): Boolean {
        return mediaController?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
    }

    fun ensureSessionConnected() {
        if (mediaController != null) return
        scope.launch {
            runCatching { ensureController() }
        }
    }

    private suspend fun ensureController(): MediaController {
        mediaController?.let { return it }
        val token = SessionToken(
            context,
            ComponentName(context, LinerPlaybackService::class.java)
        )
        val controller = MediaController.Builder(context, token).buildAsync().await()
        controller.addListener(playerListener)
        mediaController = controller
        return controller
    }

    fun playQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        initialPositionMs: Long = 0L
    ) {
        if (tracks.isEmpty()) return
        val ordered = if (shuffle) {
            val picked = tracks.getOrNull(startIndex)
            val rest = tracks.filterIndexed { index, _ -> index != startIndex }.shuffled()
            if (picked != null) listOf(picked) + rest else tracks.shuffled()
        } else {
            tracks
        }
        val targetIndex = if (shuffle) 0 else startIndex.coerceIn(0, ordered.lastIndex)

        _state.update {
            it.copy(
                queue = ordered,
                currentIndex = targetIndex,
                shuffleEnabled = shuffle,
                errorMessage = null
            )
        }
        playIndex(targetIndex, initialPositionMs = initialPositionMs)
    }

    fun playSingle(track: Track, initialPositionMs: Long = 0L) {
        playQueue(listOf(track), startIndex = 0, shuffle = false, initialPositionMs = initialPositionMs)
    }

    fun play() {
        val controller = mediaController ?: return
        onBeforeLocalPlayback?.invoke()
        if (!controller.isPlaying) {
            controller.play()
        }
    }

    fun pause() {
        val controller = mediaController ?: return
        if (isRemoteSessionAttached()) return
        if (controller.isPlaying) {
            controller.pause()
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        onBeforeLocalPlayback?.invoke()
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        onBeforeLocalPlayback?.invoke()
        val clamped = positionMs.coerceAtLeast(0L)
        controller.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun next() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        val nextIndex = current.currentIndex + 1
        if (nextIndex <= current.queue.lastIndex) {
            playIndex(nextIndex)
        }
    }

    fun previous() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        if (current.positionMs > 3000L) {
            seekTo(0L)
            return
        }
        val prevIndex = (current.currentIndex - 1).coerceAtLeast(0)
        playIndex(prevIndex)
    }

    fun toggleShuffle() {
        val current = _state.value
        val nextShuffle = !current.shuffleEnabled
        _state.update { it.copy(shuffleEnabled = nextShuffle) }
        if (nextShuffle && current.queue.size > 1) {
            val active = current.currentTrack
            val rest = current.queue.filter { it.id != active?.id }.shuffled()
            val reordered = if (active != null) listOf(active) + rest else current.queue.shuffled()
            _state.update { it.copy(queue = reordered, currentIndex = 0) }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun playIndex(index: Int, initialPositionMs: Long = 0L) {
        val track = _state.value.queue.getOrNull(index) ?: return
        resolveJob?.cancel()

        val clampedInitialPos = initialPositionMs.coerceAtLeast(0L)
        _state.update {
            it.copy(
                currentIndex = index,
                currentTrack = track,
                isBuffering = true,
                positionMs = clampedInitialPos,
                durationMs = track.durationMs,
                errorMessage = null
            )
        }

        loadLyrics(track)

        resolveJob = scope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val cached = streamCache[track.id]?.takeIf { it.expiresAtMs - 60_000L > now }?.stream
                val resolved = cached ?: withContext(Dispatchers.IO) {
                    innertubeClient.resolveAudioStream(track.id)
                }.also { fresh ->
                    val expMs = fresh.expiresAt
                        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        ?: (now + 5L * 60L * 60L * 1000L)
                    streamCache[track.id] = CachedResolvedStream(fresh, expMs)
                }
                val controller = ensureController()
                onBeforeLocalPlayback?.invoke()
                val metadata = MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistNames)
                    .setAlbumTitle(track.album?.title)
                    .apply {
                        track.cover?.url?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setMediaId(track.id)
                    .setCustomCacheKey("track_${track.id}")
                    .setUri(resolved.url)
                    .setMediaMetadata(metadata)
                    .build()

                controller.setMediaItem(mediaItem, clampedInitialPos)
                controller.prepare()
                controller.play()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = error.message ?: "Failed to load audio stream"
                    )
                }
            }
        }
    }

    private fun loadLyrics(track: Track) {
        lyricsJob?.cancel()
        _state.update { it.copy(lyrics = null, isLyricsLoading = true) }

        lyricsJob = scope.launch {
            var bestSynced: SyncedLyrics? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    apiClient.streamLyrics(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artistNames.takeIf { it.isNotBlank() },
                        durationMs = track.durationMs.takeIf { it > 0L }
                    ).collect { event ->
                        when (event) {
                            is LyricsStreamEvent.ProviderFound -> {
                                val parsed = LyricsParser.parse(
                                    raw = event.candidate.lyrics.content,
                                    format = LyricsFormat.fromString(event.candidate.lyrics.format),
                                    songDurationMs = track.durationMs.takeIf { it > 0L },
                                    provider = event.provider
                                )
                                if (bestSynced == null || (!bestSynced!!.isSynced && parsed.isSynced)) {
                                    bestSynced = parsed
                                    _state.update { it.copy(lyrics = parsed) }
                                }
                            }
                            is LyricsStreamEvent.Done -> {
                                if (event.instrumental == true && bestSynced == null) {
                                    bestSynced = SyncedLyrics.empty(isInstrumental = true)
                                    _state.update { it.copy(lyrics = bestSynced) }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }

            if (bestSynced == null && track.title.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        apiClient.searchLyrics(
                            title = track.title,
                            artist = track.artistNames,
                            durationMs = track.durationMs.takeIf { it > 0L }
                        )
                    }
                }.getOrNull()?.let { result ->
                    val parsed = LyricsParser.parse(
                        raw = result.candidate.lyrics.content,
                        format = LyricsFormat.fromString(result.candidate.lyrics.format),
                        songDurationMs = track.durationMs.takeIf { it > 0L },
                        provider = result.provider ?: result.candidate.provider
                    )
                    bestSynced = parsed
                    _state.update { it.copy(lyrics = parsed) }
                }
            }

            _state.update { it.copy(isLyricsLoading = false) }
        }
    }

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope.launch {
            while (isActive) {
                updatePositionFromController()
                delay(200L)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun updatePositionFromController() {
        val controller = mediaController ?: return
        if (isRemoteSessionAttached()) return
        val pos = controller.currentPosition.coerceAtLeast(0L)
        val dur = controller.duration.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
        _state.update { it.copy(positionMs = pos, durationMs = dur) }
    }

    fun release() {
        stopPositionTicker()
        resolveJob?.cancel()
        lyricsJob?.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }
}
