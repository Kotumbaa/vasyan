package `fun`.tryliner.android.playback

import android.content.Context
import android.os.Build
import `fun`.tryliner.android.core.model.AuthSession
import `fun`.tryliner.android.core.model.Cover
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.TrackArtist
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Serializable
data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val coverUrl: String? = null,
    val durationMs: Long = 0L
)

data class RemotePlaybackState(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val track: RemoteTrack?,
    val volume: Float = 1f
)

@Serializable
private data class LanBeaconMessage(
    val type: String,
    val deviceId: String,
    val deviceName: String,
    val userId: String,
    val wsPort: Int
)

@Serializable
private data class LanHelloMessage(
    val type: String = "hello",
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val userId: String
)

@Serializable
private data class LanStateMessage(
    val type: String = "state",
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val track: RemoteTrack? = null,
    val volume: Float = 1f
)

@Serializable
private data class LanCommandMessage(
    val type: String = "command",
    val action: String,
    val positionMs: Long? = null,
    val track: RemoteTrack? = null,
    val volume: Float? = null
)

class LanConnectManager(
    context: Context,
    private val sessionFlow: StateFlow<AuthSession?>,
    private val playerController: PlayerController,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { generated ->
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        }
    }

    private val deviceName: String by lazy {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        when {
            model.isBlank() && manufacturer.isBlank() -> "Android"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isBlank() -> model
            else -> "$manufacturer $model"
        }
    }

    private val _remoteState = MutableStateFlow<RemotePlaybackState?>(null)
    val remoteState: StateFlow<RemotePlaybackState?> = _remoteState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val wsLock = Any()
    private var activeWebSocket: WebSocket? = null
    private var connectingPeerKey: String? = null
    private var currentUserId: String? = null

    private var sessionObserverJob: Job? = null
    private var discoveryJob: Job? = null
    private var stateSyncJob: Job? = null
    private var tickSyncJob: Job? = null

    fun start() {
        if (sessionObserverJob?.isActive == true) return
        sessionObserverJob = scope.launch {
            sessionFlow
                .map { it?.user?.id?.takeIf { id -> id.isNotBlank() } }
                .distinctUntilChanged()
                .collectLatest { userId ->
                    stopPeerSession()
                    currentUserId = userId
                    if (userId != null) {
                        startPeerSession(userId)
                    }
                }
        }
    }

    fun sendCommand(
        action: String,
        positionMs: Long? = null,
        track: RemoteTrack? = null,
        volume: Float? = null
    ) {
        val ws = synchronized(wsLock) { activeWebSocket } ?: return
        val payload = json.encodeToString(
            LanCommandMessage(
                action = action,
                positionMs = positionMs,
                track = track,
                volume = volume
            )
        )
        ws.send(payload)
    }

    fun setRemoteVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _remoteState.update { current ->
            current?.copy(volume = clamped)
        }
        sendCommand(action = "setVolume", volume = clamped)
    }

    fun handoffToPhone() {
        val remote = _remoteState.value ?: return
        val remoteTrack = remote.track ?: return
        sendCommand(action = "handoff")
        val track = Track(
            id = remoteTrack.id,
            title = remoteTrack.title,
            artists = if (remoteTrack.artist.isBlank()) {
                emptyList()
            } else {
                listOf(TrackArtist(id = "", name = remoteTrack.artist))
            },
            durationMs = remoteTrack.durationMs,
            cover = remoteTrack.coverUrl?.takeIf { it.isNotBlank() }?.let {
                Cover(url = it, width = 0, height = 0)
            }
        )
        playerController.playSingle(track, initialPositionMs = remote.positionMs)
    }

    fun handoffToRemote() {
        val state = playerController.state.value
        val currentTrack = state.currentTrack ?: return
        val remoteTrack = RemoteTrack(
            id = currentTrack.id,
            title = currentTrack.title,
            artist = currentTrack.artistNames,
            coverUrl = currentTrack.cover?.url,
            durationMs = currentTrack.durationMs
        )
        sendCommand(
            action = "handoff",
            positionMs = state.positionMs,
            track = remoteTrack
        )
        playerController.pause()
    }

    fun release() {
        sessionObserverJob?.cancel()
        sessionObserverJob = null
        stopPeerSession()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private fun startPeerSession(userId: String) {
        discoveryJob = scope.launch(Dispatchers.IO) {
            runDiscoveryLoop(userId)
        }
        stateSyncJob = scope.launch {
            observeLocalPlaybackState()
        }
        tickSyncJob = scope.launch {
            runPlayingTickLoop()
        }
    }

    private fun stopPeerSession() {
        discoveryJob?.cancel()
        discoveryJob = null
        stateSyncJob?.cancel()
        stateSyncJob = null
        tickSyncJob?.cancel()
        tickSyncJob = null
        closeActiveWebSocket(code = 1000, reason = "Session ended")
    }

    private suspend fun runDiscoveryLoop(userId: String) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = DISCOVERY_SOCKET_TIMEOUT_MS
            }
            val discoverBytes = DISCOVER_PAYLOAD.toByteArray(Charsets.UTF_8)
            val buffer = ByteArray(4096)

            while (scope.isActive && currentUserId == userId) {
                val hasActiveConnection = synchronized(wsLock) {
                    activeWebSocket != null || connectingPeerKey != null
                }
                if (hasActiveConnection) {
                    delay(CONNECTED_IDLE_POLL_MS)
                    continue
                }

                sendDiscoverBroadcasts(socket, discoverBytes)

                val listenDeadline = System.currentTimeMillis() + DISCOVERY_LISTEN_WINDOW_MS
                while (scope.isActive && System.currentTimeMillis() < listenDeadline) {
                    val stillDisconnected = synchronized(wsLock) {
                        activeWebSocket == null && connectingPeerKey == null
                    }
                    if (!stillDisconnected) break

                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val beacon = runCatching { json.decodeFromString<LanBeaconMessage>(raw) }.getOrNull()
                        if (beacon != null &&
                            beacon.type == "liner-connect-beacon" &&
                            beacon.userId == userId &&
                            beacon.deviceId != deviceId &&
                            beacon.wsPort in 1..65535
                        ) {
                            val peerIp = packet.address?.hostAddress
                            if (!peerIp.isNullOrBlank()) {
                                connectWebSocket(peerIp = peerIp, wsPort = beacon.wsPort, userId = userId)
                                break
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Exception) {
                        break
                    }
                }

                delay(DISCOVERY_RETRY_DELAY_MS)
            }
        } catch (_: Exception) {
            // Ignore socket errors when network is unavailable
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun sendDiscoverBroadcasts(socket: DatagramSocket, payload: ByteArray) {
        val targets = linkedSetOf<InetAddress>()
        runCatching {
            targets.add(InetAddress.getByName("255.255.255.255"))
        }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    addr.broadcast?.let { targets.add(it) }
                }
            }
        }
        for (target in targets) {
            runCatching {
                val packet = DatagramPacket(payload, payload.size, target, DISCOVERY_PORT)
                socket.send(packet)
            }
        }
    }

    private fun connectWebSocket(peerIp: String, wsPort: Int, userId: String) {
        val peerKey = "$peerIp:$wsPort"
        synchronized(wsLock) {
            if (activeWebSocket != null || connectingPeerKey != null) return
            connectingPeerKey = peerKey
        }

        val request = Request.Builder()
            .url("ws://$peerIp:$wsPort")
            .build()

        okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(wsLock) {
                    if (currentUserId != userId) {
                        connectingPeerKey = null
                        webSocket.close(1000, "Logged out")
                        return
                    }
                    activeWebSocket = webSocket
                    connectingPeerKey = null
                }
                _isConnected.value = true

                val hello = json.encodeToString(
                    LanHelloMessage(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        platform = "android",
                        userId = userId
                    )
                )
                webSocket.send(hello)
                sendCurrentState(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(webSocket, text, userId)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                clearWebSocketIfMatches(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clearWebSocketIfMatches(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                clearWebSocketIfMatches(webSocket)
            }
        })
    }

    private fun handleIncomingMessage(webSocket: WebSocket, text: String, expectedUserId: String) {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val type = root["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "hello" -> {
                val hello = runCatching { json.decodeFromString<LanHelloMessage>(text) }.getOrNull() ?: return
                if (hello.userId != expectedUserId) {
                    webSocket.close(WS_CLOSE_USER_MISMATCH, "userId mismatch")
                    clearWebSocketIfMatches(webSocket)
                }
            }
            "state" -> {
                val stateMsg = runCatching { json.decodeFromString<LanStateMessage>(text) }.getOrNull() ?: return
                if (stateMsg.deviceId == deviceId) return
                _remoteState.value = RemotePlaybackState(
                    deviceId = stateMsg.deviceId,
                    deviceName = stateMsg.deviceName,
                    platform = stateMsg.platform,
                    isPlaying = stateMsg.isPlaying,
                    positionMs = stateMsg.positionMs,
                    durationMs = stateMsg.durationMs,
                    updatedAt = stateMsg.updatedAt,
                    track = stateMsg.track,
                    volume = stateMsg.volume.coerceIn(0f, 1f)
                )
                if (stateMsg.track != null) {
                    scope.launch(Dispatchers.Main.immediate) {
                        playerController.ensureSessionConnected()
                    }
                }
            }
            "command" -> {
                val cmd = runCatching { json.decodeFromString<LanCommandMessage>(text) }.getOrNull() ?: return
                scope.launch(Dispatchers.Main.immediate) {
                    when (cmd.action) {
                        "play" -> playerController.play()
                        "pause" -> playerController.pause()
                        "toggle" -> playerController.togglePlayPause()
                        "next" -> playerController.next()
                        "prev" -> playerController.previous()
                        "seek" -> cmd.positionMs?.let { playerController.seekTo(it) }
                        "handoff" -> playerController.pause()
                    }
                }
            }
        }
    }

    private suspend fun observeLocalPlaybackState() {
        var lastTrackId: String? = null
        var lastIsPlaying: Boolean? = null
        var lastDurationMs: Long = -1L
        var lastPositionMs: Long = -1L

        playerController.state.collectLatest { playback ->
            val trackId = playback.currentTrack?.id
            val isSignificantSeek = lastPositionMs >= 0L &&
                abs(playback.positionMs - lastPositionMs) > POSITION_JUMP_THRESHOLD_MS &&
                trackId == lastTrackId

            val changed = trackId != lastTrackId ||
                playback.isPlaying != lastIsPlaying ||
                playback.durationMs != lastDurationMs ||
                isSignificantSeek

            lastTrackId = trackId
            lastIsPlaying = playback.isPlaying
            lastDurationMs = playback.durationMs
            lastPositionMs = playback.positionMs

            if (changed) {
                val ws = synchronized(wsLock) { activeWebSocket }
                if (ws != null) {
                    sendState(ws, playback)
                }
            }
        }
    }

    private suspend fun runPlayingTickLoop() {
        while (scope.isActive) {
            delay(STATE_TICK_INTERVAL_MS)
            val ws = synchronized(wsLock) { activeWebSocket } ?: continue
            val playback = playerController.state.value
            if (playback.isPlaying && playback.currentTrack != null) {
                sendState(ws, playback)
            }
        }
    }

    private fun sendCurrentState(webSocket: WebSocket) {
        sendState(webSocket, playerController.state.value)
    }

    private fun sendState(webSocket: WebSocket, playback: PlaybackUiState) {
        val currentTrack = playback.currentTrack
        val remoteTrack = currentTrack?.let {
            RemoteTrack(
                id = it.id,
                title = it.title,
                artist = it.artistNames,
                coverUrl = it.cover?.url ?: it.cover?.fallbackUrl,
                durationMs = playback.durationMs.takeIf { d -> d > 0L } ?: it.durationMs
            )
        }
        val msg = LanStateMessage(
            deviceId = deviceId,
            deviceName = deviceName,
            platform = "android",
            isPlaying = playback.isPlaying,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            updatedAt = System.currentTimeMillis(),
            track = remoteTrack
        )
        webSocket.send(json.encodeToString(msg))
    }

    private fun clearWebSocketIfMatches(webSocket: WebSocket) {
        var cleared = false
        synchronized(wsLock) {
            if (activeWebSocket === webSocket) {
                activeWebSocket = null
                cleared = true
            }
            connectingPeerKey = null
        }
        if (cleared) {
            _isConnected.value = false
            _remoteState.value = null
        }
    }

    private fun closeActiveWebSocket(code: Int, reason: String) {
        val ws = synchronized(wsLock) {
            connectingPeerKey = null
            activeWebSocket.also { activeWebSocket = null }
        }
        ws?.close(code, reason)
        _isConnected.value = false
        _remoteState.value = null
    }

    private companion object {
        const val PREFS_NAME = "liner_lan_connect"
        const val KEY_DEVICE_ID = "device_id_v1"
        const val DISCOVERY_PORT = 45788
        const val DISCOVER_PAYLOAD = "LINER_DISCOVER"
        const val DISCOVERY_SOCKET_TIMEOUT_MS = 2000
        const val DISCOVERY_LISTEN_WINDOW_MS = 2500L
        const val DISCOVERY_RETRY_DELAY_MS = 1500L
        const val CONNECTED_IDLE_POLL_MS = 3000L
        const val STATE_TICK_INTERVAL_MS = 1000L
        const val POSITION_JUMP_THRESHOLD_MS = 1500L
        const val WS_CLOSE_USER_MISMATCH = 4001
    }
}
