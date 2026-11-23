package `fun`.tryliner.android.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

interface RemotePlaybackBridge {
    fun isControllingRemoteSlave(): Boolean
    fun onLocalPlaybackClaimed()
    fun playQueueOnSlave(
        tracks: List<Track>,
        startIndex: Int,
        shuffle: Boolean,
        initialPositionMs: Long
    )
    fun sendSlaveCommand(action: String, positionMs: Long? = null, volume: Float? = null)
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

data class PlaybackUiState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val lyrics: SyncedLyrics? = null,
    val isLyricsLoading: Boolean = false,
    val activeStreamSource: String? = null,
    val errorMessage: String? = null,
    val isWaveActive: Boolean = false,
    val waveVibe: String = "all",
    val waveSeedLabel: String = "",
    val isWaveLoading: Boolean = false,
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
    var onApplyLoudnessDb: ((Float?) -> Unit)? = null

    /** Set by AppContainer; when a track is downloaded, playback uses the local file. */
    var downloadManager: TrackDownloadManager? = null

    /** Set by LanConnectManager; routes playback/controls to active Slave when this device is Master. */
    var remoteBridge: RemotePlaybackBridge? = null

    private var mediaController: MediaController? = null
    private var resolveJob: Job? = null
    private var lyricsJob: Job? = null
    private var positionTickerJob: Job? = null
    private var waveJob: Job? = null
    private var waveRefillJob: Job? = null
    private val waveSeenIds = ArrayDeque<String>(160)
    private val streamCache = object : LinkedHashMap<String, CachedResolvedStream>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResolvedStream>?): Boolean {
            return size > MAX_STREAM_CACHE_ENTRIES
        }
    }
    private val streamCacheLock = Any()
    @Volatile
    private var preferRelayUntilMs: Long = 0L
    @Volatile
    private var lastRelayFallbackTrackId: String? = null

    private data class CachedResolvedStream(
        val url: String,
        val isHls: Boolean,
        val sourceLabel: String,
        val expiresAtMs: Long,
        val loudnessDb: Float? = null,
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

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (isRemoteSessionAttached()) return
            val controller = mediaController ?: return
            val effective = playWhenReady && controller.playbackState != Player.STATE_ENDED &&
                controller.playbackState != Player.STATE_IDLE
            _state.update { it.copy(isPlaying = effective) }
            if (effective) startPositionTicker() else stopPositionTicker()
        }

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL) {
                val controller = mediaController ?: return
                val effective = controller.isPlaying ||
                    (controller.playWhenReady && controller.playbackState == Player.STATE_READY)
                _state.update { it.copy(isPlaying = effective) }
                if (effective) startPositionTicker() else stopPositionTicker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isRemoteSessionAttached()) return
            val buffering = playbackState == Player.STATE_BUFFERING
            _state.update { it.copy(isBuffering = buffering) }

            if (playbackState == Player.STATE_READY) {
                val controller = mediaController
                if (controller != null) {
                    val playing = controller.isPlaying || controller.playWhenReady
                    _state.update { it.copy(isPlaying = playing) }
                    if (playing) startPositionTicker()
                }
                updatePositionFromController()
            } else if (playbackState == Player.STATE_ENDED) {
                val current = _state.value
                when (current.repeatMode) {
                    RepeatMode.ONE -> {
                        seekLocal(0L)
                        playLocal()
                    }
                    RepeatMode.ALL -> {
                        if (current.currentIndex < current.queue.lastIndex) {
                            next()
                        } else if (current.queue.isNotEmpty()) {
                            playIndex(0)
                        }
                    }
                    RepeatMode.OFF -> next()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("PlayerController", "ExoPlayer error (${error.errorCodeName}): ${error.message}", error)
            val current = _state.value
            val failedTrack = current.currentTrack
            if (current.activeStreamSource == "YT" &&
                failedTrack != null &&
                lastRelayFallbackTrackId != failedTrack.id
            ) {
                lastRelayFallbackTrackId = failedTrack.id
                synchronized(streamCacheLock) {
                    streamCache.remove(failedTrack.id)
                }
                preferRelayUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                Log.w("PlayerController", "Direct YT playback failed for ${failedTrack.id}, retrying via worker relay")
                playIndex(current.currentIndex, initialPositionMs = current.positionMs)
                return
            }
            _state.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = error.cause?.message ?: error.message ?: error.errorCodeName
                )
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

    private val controllerMutex = Mutex()

    private suspend fun ensureController(): MediaController {
        mediaController?.let { return it }
        return controllerMutex.withLock {
            mediaController?.let { return@withLock it }
            val token = SessionToken(
                context,
                ComponentName(context, LinerPlaybackService::class.java)
            )
            val controller = MediaController.Builder(context, token).buildAsync().await()
            controller.addListener(playerListener)
            mediaController = controller
            controller
        }
    }

    fun playQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        initialPositionMs: Long = 0L,
        forceLocal: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        if (!forceLocal && remoteBridge?.isControllingRemoteSlave() == true) {
            remoteBridge?.playQueueOnSlave(tracks, startIndex, shuffle, initialPositionMs)
            return
        }
        remoteBridge?.onLocalPlaybackClaimed()
        val ordered = if (shuffle) {
            val picked = tracks.getOrNull(startIndex)
            val rest = tracks.filterIndexed { index, _ -> index != startIndex }.shuffled()
            if (picked != null) listOf(picked) + rest else tracks.shuffled()
        } else {
            tracks
        }
        val targetIndex = if (shuffle) 0 else startIndex.coerceIn(0, ordered.lastIndex)

        waveJob?.cancel()
        waveRefillJob?.cancel()
        _state.update {
            it.copy(
                queue = ordered,
                currentIndex = targetIndex,
                shuffleEnabled = shuffle,
                errorMessage = null,
                isWaveActive = false,
                isWaveLoading = false
            )
        }
        playIndex(targetIndex, initialPositionMs = initialPositionMs)
    }

    fun startMyWave(vibe: String = _state.value.waveVibe) {
        val normalizedVibe = vibe.ifBlank { "all" }
        waveJob?.cancel()
        waveRefillJob?.cancel()
        remoteBridge?.onLocalPlaybackClaimed()
        _state.update {
            it.copy(
                waveVibe = normalizedVibe,
                isWaveLoading = true,
                errorMessage = null
            )
        }
        waveJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    apiClient.getMyWave(
                        vibe = normalizedVibe,
                        excludeIds = waveSeenIds.toList(),
                        limit = 20
                    )
                }
            }
            val resp = result.getOrNull()
            if (resp == null || resp.items.isEmpty()) {
                _state.update {
                    it.copy(
                        isWaveLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Не удалось запустить волну"
                    )
                }
                return@launch
            }
            recordWaveSeen(resp.items)
            _state.update {
                it.copy(
                    queue = resp.items,
                    currentIndex = 0,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.OFF,
                    isWaveActive = true,
                    waveVibe = resp.vibe,
                    waveSeedLabel = resp.seedLabel,
                    isWaveLoading = false,
                    errorMessage = null
                )
            }
            playIndex(0)
        }
    }

    fun setWaveVibe(vibe: String) {
        val normalized = vibe.ifBlank { "all" }
        if (_state.value.waveVibe == normalized && _state.value.isWaveActive) return
        _state.update { it.copy(waveVibe = normalized) }
        if (_state.value.isWaveActive) {
            startMyWave(normalized)
        }
    }

    private fun recordWaveSeen(tracks: List<Track>) {
        for (t in tracks) {
            if (!waveSeenIds.contains(t.id)) {
                waveSeenIds.addLast(t.id)
                while (waveSeenIds.size > 150) {
                    waveSeenIds.removeFirst()
                }
            }
        }
    }

    private fun maybeRefillWave(currentIndex: Int) {
        val cur = _state.value
        if (!cur.isWaveActive || waveRefillJob?.isActive == true) return
        if (cur.queue.isEmpty() || currentIndex < cur.queue.size - 3) return

        val vibe = cur.waveVibe
        val exclude = (waveSeenIds.toList() + cur.queue.map { it.id }).distinct().takeLast(150)
        waveRefillJob = scope.launch {
            val resp = withContext(Dispatchers.IO) {
                runCatching {
                    apiClient.getMyWave(vibe = vibe, excludeIds = exclude, limit = 16)
                }.getOrNull()
            } ?: return@launch

            if (resp.items.isEmpty() || !_state.value.isWaveActive) return@launch
            recordWaveSeen(resp.items)
            _state.update { latest ->
                if (!latest.isWaveActive) return@update latest
                val existingIds = latest.queue.mapTo(HashSet()) { it.id }
                val additions = resp.items.filter { it.id !in existingIds }
                if (additions.isEmpty()) latest else latest.copy(queue = latest.queue + additions)
            }
        }
    }

    fun playSingle(track: Track, initialPositionMs: Long = 0L, forceLocal: Boolean = false) {
        playQueue(
            listOf(track),
            startIndex = 0,
            shuffle = false,
            initialPositionMs = initialPositionMs,
            forceLocal = forceLocal
        )
    }

    fun playLocal() {
        val controller = mediaController ?: return
        remoteBridge?.onLocalPlaybackClaimed()
        onBeforeLocalPlayback?.invoke()
        _state.update { it.copy(isPlaying = true, errorMessage = null) }
        startPositionTicker()
        if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
            controller.prepare()
        }
        if (!controller.playWhenReady || !controller.isPlaying) {
            controller.play()
        }
    }

    fun pauseLocal() {
        val controller = mediaController ?: return
        if (isRemoteSessionAttached()) return
        _state.update { it.copy(isPlaying = false) }
        stopPositionTicker()
        if (controller.playWhenReady || controller.isPlaying) {
            controller.pause()
        }
    }

    fun setLocalVolume(volume: Float) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (volume.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun getLocalVolume(): Float {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 1f
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
    }

    fun play() {
        if (remoteBridge?.isControllingRemoteSlave() == true) {
            remoteBridge?.sendSlaveCommand("play")
            return
        }
        playLocal()
    }

    fun pause() {
        if (remoteBridge?.isControllingRemoteSlave() == true) {
            remoteBridge?.sendSlaveCommand("pause")
            return
        }
        pauseLocal()
    }

    fun togglePlayPause() {
        if (remoteBridge?.isControllingRemoteSlave() == true) {
            remoteBridge?.sendSlaveCommand("toggle")
            return
        }
        val controller = mediaController ?: return
        val currentlyPlaying = _state.value.isPlaying || (!isRemoteSessionAttached() && (controller.isPlaying || controller.playWhenReady))
        if (currentlyPlaying) {
            onBeforeLocalPlayback?.invoke()
            _state.update { it.copy(isPlaying = false) }
            stopPositionTicker()
            controller.pause()
        } else {
            remoteBridge?.onLocalPlaybackClaimed()
            onBeforeLocalPlayback?.invoke()
            _state.update { it.copy(isPlaying = true, errorMessage = null) }
            startPositionTicker()
            if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
                controller.prepare()
            }
            controller.play()
        }
    }

    fun seekLocal(positionMs: Long) {
        val controller = mediaController ?: return
        onBeforeLocalPlayback?.invoke()
        val clamped = positionMs.coerceAtLeast(0L)
        controller.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun seekTo(positionMs: Long) {
        if (remoteBridge?.isControllingRemoteSlave() == true) {
            remoteBridge?.sendSlaveCommand("seek", positionMs = positionMs.coerceAtLeast(0L))
            return
        }
        seekLocal(positionMs)
    }

    fun next() {
        if (remoteBridge?.isControllingRemoteSlave() == true) {
            remoteBridge?.sendSlaveCommand("next")
            return
        }
        val current = _state.value
        if (current.queue.isEmpty()) return
        val nextIndex = current.currentIndex + 1
        if (nextIndex <= current.queue.lastIndex) {
            playIndex(nextIndex)
        }
    }

    fun previous() {
        if (remoteBridge?.isControllingRemoteSlave() == true) {
            remoteBridge?.sendSlaveCommand("prev")
            return
        }
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

    fun cycleRepeatMode() {
        _state.update { current ->
            val nextMode = when (current.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            current.copy(repeatMode = nextMode)
        }
    }

    fun playNext(track: Track) {
        val current = _state.value
        if (current.queue.isEmpty() || current.currentIndex < 0) {
            playSingle(track)
            return
        }
        val insertIndex = (current.currentIndex + 1).coerceAtMost(current.queue.size)
        val updated = current.queue.toMutableList().apply {
            add(insertIndex, track)
        }
        _state.update { it.copy(queue = updated) }
    }

    fun playQueueIndex(index: Int) {
        val current = _state.value
        if (index in current.queue.indices) {
            playIndex(index)
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun playIndex(index: Int, initialPositionMs: Long = 0L) {
        val track = _state.value.queue.getOrNull(index) ?: return
        val wasRapidSkip = resolveJob?.isActive == true
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
        maybeRefillWave(index)

        resolveJob = scope.launch {
            if (wasRapidSkip) {
                delay(180L)
            }
            runCatching {
                val localUri = downloadManager?.localUriFor(track.id)
                val now = System.currentTimeMillis()
                val cached = synchronized(streamCacheLock) {
                    streamCache[track.id]?.takeIf { it.expiresAtMs - 60_000L > now }
                }

                val resolvedPlayable: CachedResolvedStream = when {
                    localUri != null -> CachedResolvedStream(
                        url = localUri.toString(),
                        isHls = false,
                        sourceLabel = "LOCAL",
                        expiresAtMs = Long.MAX_VALUE
                    )
                    cached != null -> cached
                    else -> withContext(Dispatchers.IO) {
                        resolveDirectOrRelay(track, now)
                    }.also { fresh ->
                        synchronized(streamCacheLock) {
                            streamCache[track.id] = fresh
                        }
                    }
                }
                _state.update { it.copy(activeStreamSource = resolvedPlayable.sourceLabel) }
                val playUri = Uri.parse(resolvedPlayable.url)
                val controller = ensureController()
                onBeforeLocalPlayback?.invoke()
                onApplyLoudnessDb?.invoke(resolvedPlayable.loudnessDb)
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
                    .apply {
                        if (resolvedPlayable.isHls) {
                            setMimeType(MimeTypes.APPLICATION_M3U8)
                        } else {
                            setCustomCacheKey("track_${track.id}")
                        }
                    }
                    .setUri(playUri)
                    .setMediaMetadata(metadata)
                    .build()

                controller.setMediaItem(mediaItem, clampedInitialPos)
                controller.prepare()
                controller.play()
                prefetchNextTrack(index)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.e("PlayerController", "Failed to resolve/play ${track.id}: ${error.message}", error)
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

    private fun prefetchNextTrack(currentIndex: Int) {
        val nextTrack = _state.value.queue.getOrNull(currentIndex + 1) ?: return
        if (downloadManager?.localUriFor(nextTrack.id) != null) return
        val now = System.currentTimeMillis()
        val alreadyCached = synchronized(streamCacheLock) {
            streamCache[nextTrack.id]?.takeIf { it.expiresAtMs - 60_000L > now } != null
        }
        if (alreadyCached) return
        scope.launch(Dispatchers.IO) {
            delay(1_000L)
            runCatching {
                val resolved = resolveDirectOrRelay(nextTrack, System.currentTimeMillis())
                synchronized(streamCacheLock) {
                    streamCache[nextTrack.id] = resolved
                }
            }
        }
    }

    private suspend fun resolveDirectOrRelay(track: Track, now: Long): CachedResolvedStream {
        Log.i("PlayerController", "Resolving stream for ${track.id} (${track.title})")
        if (track.id.startsWith("sc:", ignoreCase = true) ||
            track.id.startsWith("sp:", ignoreCase = true)
        ) {
            return resolveViaWorkerRelay(track, now)
        }
        if (now < preferRelayUntilMs) {
            return runCatching { resolveViaWorkerRelay(track, now) }
                .getOrElse { relayErr ->
                    Log.w("PlayerController", "Worker relay failed for ${track.id}, trying direct: ${relayErr.message}")
                    resolveDirectInnertube(track, now)
                }
        }
        return coroutineScope {
            val directDeferred = async {
                runCatching {
                    withTimeout(2_400L) {
                        resolveDirectInnertube(track, now)
                    }
                }
            }
            val firstTry = withTimeoutOrNull(600L) { directDeferred.await() }
            if (firstTry != null) {
                if (firstTry.isSuccess) {
                    return@coroutineScope firstTry.getOrThrow()
                }
                val directErr = firstTry.exceptionOrNull()
                if (directErr is CancellationException && directErr !is TimeoutCancellationException) {
                    throw directErr
                }
                Log.w("PlayerController", "Direct stream failed for ${track.id} (${directErr?.message}), falling back to worker relay")
                val msg = directErr?.message.orEmpty()
                if (directErr is TimeoutCancellationException ||
                    msg.contains("unreachable", ignoreCase = true) ||
                    msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true)
                ) {
                    preferRelayUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                }
                return@coroutineScope resolveViaWorkerRelay(track, System.currentTimeMillis())
            }

            // Direct resolution is taking >600ms: hedge with worker relay in parallel and take the first winner.
            Log.i("PlayerController", "Direct stream slow for ${track.id} (>600ms), racing worker relay")
            val relayDeferred = async {
                runCatching { resolveViaWorkerRelay(track, System.currentTimeMillis()) }
            }
            val winner: Pair<Boolean, Result<CachedResolvedStream>> = select {
                directDeferred.onAwait { res -> false to res }
                relayDeferred.onAwait { res -> true to res }
            }
            val (winnerIsRelay, winnerRes) = winner
            if (winnerRes.isSuccess) {
                if (winnerIsRelay) {
                    directDeferred.cancel()
                    preferRelayUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                } else {
                    relayDeferred.cancel()
                }
                return@coroutineScope winnerRes.getOrThrow()
            }

            val secondRes = if (winnerIsRelay) directDeferred.await() else relayDeferred.await()
            if (secondRes.isSuccess) {
                if (!winnerIsRelay) {
                    preferRelayUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                }
                return@coroutineScope secondRes.getOrThrow()
            }
            throw (secondRes.exceptionOrNull() ?: winnerRes.exceptionOrNull()
                ?: IllegalStateException("Failed to resolve stream"))
        }
    }

    private suspend fun resolveDirectInnertube(track: Track, now: Long): CachedResolvedStream {
        val fresh = innertubeClient.resolveAudioStream(track.id, verifyReachable = false)
        Log.i("PlayerController", "Resolved direct stream for ${track.id}: host=${Uri.parse(fresh.url).host}")
        val expMs = fresh.expiresAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: (now + 5L * 60L * 60L * 1000L)
        return CachedResolvedStream(
            url = fresh.url,
            isHls = false,
            sourceLabel = "YT",
            expiresAtMs = expMs,
            loudnessDb = fresh.loudnessDb
        )
    }

    private suspend fun resolveViaWorkerRelay(track: Track, now: Long): CachedResolvedStream {
        val resolved = apiClient.resolveStream(
            videoId = track.id,
            title = track.title,
            artist = track.artistNames,
        )
        Log.i("PlayerController", "Resolved relay stream for ${track.id}: source=${resolved.source} hls=${resolved.isHls}")
        return CachedResolvedStream(
            url = resolved.url,
            isHls = resolved.isHls,
            sourceLabel = if (resolved.source == "soundcloud") "SC" else "YT",
            expiresAtMs = now + 12L * 60L * 1000L
        )
    }

    private fun loadLyrics(track: Track) {
        lyricsJob?.cancel()
        _state.update { it.copy(lyrics = null, isLyricsLoading = true) }

        lyricsJob = scope.launch {
            var bestSynced: SyncedLyrics? = null
            try {
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
                                if (event.instrumental && bestSynced == null) {
                                    bestSynced = SyncedLyrics.empty(isInstrumental = true)
                                    _state.update { it.copy(lyrics = bestSynced) }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Fall back to searchLyrics below
            }

            ensureActive()

            if (bestSynced == null && track.title.isNotBlank()) {
                val searchResult = try {
                    withContext(Dispatchers.IO) {
                        apiClient.searchLyrics(
                            title = track.title,
                            artist = track.artistNames,
                            durationMs = track.durationMs.takeIf { it > 0L }
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                searchResult?.let { result ->
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

            ensureActive()
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

    private companion object {
        const val MAX_STREAM_CACHE_ENTRIES = 50
    }
}
