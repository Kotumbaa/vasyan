package `fun`.tryliner.android

import android.content.Context
import android.webkit.WebSettings
import `fun`.tryliner.android.core.crypto.WasmRequestSigner
import `fun`.tryliner.android.core.model.DailyMixItem
import `fun`.tryliner.android.core.model.Track
import java.util.concurrent.ConcurrentHashMap
import `fun`.tryliner.android.core.network.HumanCheckProvider
import `fun`.tryliner.android.core.network.HumanCheckRequest
import `fun`.tryliner.android.core.network.HumanCheckResult
import `fun`.tryliner.android.core.network.InnertubeClient
import `fun`.tryliner.android.core.network.LinerApiClient
import `fun`.tryliner.android.data.EncryptedSessionStorage
import `fun`.tryliner.android.playback.LanConnectManager
import `fun`.tryliner.android.playback.PlayerController
import `fun`.tryliner.android.playback.TrackDownloadManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val userAgent: String = runCatching {
        WebSettings.getDefaultUserAgent(appContext)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: LinerApiClient.DEFAULT_USER_AGENT

    private val _pendingHumanCheck = MutableStateFlow<HumanCheckRequest?>(null)
    val pendingHumanCheck: StateFlow<HumanCheckRequest?> = _pendingHumanCheck.asStateFlow()
    private var pendingHumanCheckDeferred: CompletableDeferred<HumanCheckResult?>? = null
    private val humanCheckLock = Any()

    val apiBaseUrl: String = BuildConfig.LINER_API_BASE_URL
    val sessionStorage = EncryptedSessionStorage(appContext)
    val signer = WasmRequestSigner()
    val apiClient = LinerApiClient(
        sessionStorage = sessionStorage,
        signer = signer,
        baseUrl = apiBaseUrl,
        userAgent = userAgent,
        humanCheckProvider = HumanCheckProvider { request ->
            requestHumanCheck(request)
        }
    )
    val innertubeClient = InnertubeClient()

    val downloadManager = TrackDownloadManager(
        context = appContext,
        innertubeClient = innertubeClient,
        scope = appScope
    )

    val playerController = PlayerController(
        context = appContext,
        apiClient = apiClient,
        innertubeClient = innertubeClient,
        scope = appScope
    ).also { it.downloadManager = downloadManager }

    val lanConnectManager = LanConnectManager(
        context = appContext,
        sessionFlow = sessionStorage.sessionFlow,
        playerController = playerController,
        scope = appScope
    ).apply {
        start()
    }

    private val _likedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTrackIds: StateFlow<Set<String>> = _likedTrackIds.asStateFlow()

    private val cachedDailyMixes = ConcurrentHashMap<String, DailyMixItem>()

    fun cacheDailyMixes(mixes: List<DailyMixItem>) {
        mixes.forEach { cachedDailyMixes[it.id] = it }
    }

    fun getCachedDailyMix(id: String): DailyMixItem? = cachedDailyMixes[id]

    private suspend fun requestHumanCheck(request: HumanCheckRequest): HumanCheckResult? {
        val deferred = CompletableDeferred<HumanCheckResult?>()
        synchronized(humanCheckLock) {
            pendingHumanCheckDeferred?.complete(null)
            pendingHumanCheckDeferred = deferred
            _pendingHumanCheck.value = request
        }
        return deferred.await()
    }

    fun submitHumanCheckResult(token: String?, clearanceCookie: String?) {
        val result = if (token.isNullOrBlank() && clearanceCookie.isNullOrBlank()) {
            null
        } else {
            HumanCheckResult(token = token, clearanceCookie = clearanceCookie)
        }
        synchronized(humanCheckLock) {
            pendingHumanCheckDeferred?.complete(result)
            pendingHumanCheckDeferred = null
            _pendingHumanCheck.value = null
        }
    }

    fun cancelHumanCheck() {
        synchronized(humanCheckLock) {
            pendingHumanCheckDeferred?.complete(null)
            pendingHumanCheckDeferred = null
            _pendingHumanCheck.value = null
        }
    }

    fun seedLikedTrackIds(tracks: List<Track>, replace: Boolean = false) {
        val liked = tracks.filter { it.isLiked }.mapTo(mutableSetOf()) { it.id }
        _likedTrackIds.update { current ->
            if (replace) liked else current + liked
        }
    }

    fun markTrackLiked(trackId: String, liked: Boolean) {
        _likedTrackIds.update { current ->
            if (liked) current + trackId else current - trackId
        }
    }

    fun toggleLike(track: Track) {
        val currentlyLiked = _likedTrackIds.value.contains(track.id)
        val targetLiked = !currentlyLiked
        markTrackLiked(track.id, targetLiked)

        appScope.launch(Dispatchers.IO) {
            runCatching {
                if (targetLiked) {
                    apiClient.likeTrack(track.id, trackSnapshot = track)
                } else {
                    apiClient.unlikeTrack(track.id)
                }
            }.onFailure {
                // Revert optimistic update on failure
                markTrackLiked(track.id, currentlyLiked)
            }
        }
    }
}
