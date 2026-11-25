package `fun`.tryliner.android.core.network

import `fun`.tryliner.android.core.crypto.PowTimeoutException
import `fun`.tryliner.android.core.crypto.ProofOfWorkSolver
import `fun`.tryliner.android.core.crypto.WasmRequestSigner
import `fun`.tryliner.android.core.lyrics.SseEventParser
import `fun`.tryliner.android.core.model.AlbumDetails
import `fun`.tryliner.android.core.model.ApiError
import `fun`.tryliner.android.core.model.ApiErrorBody
import `fun`.tryliner.android.core.model.ArtistDetails
import `fun`.tryliner.android.core.model.AuthSession
import `fun`.tryliner.android.core.model.AuthUser
import `fun`.tryliner.android.core.model.CatalogPlaylistDetails
import `fun`.tryliner.android.core.model.DailyMixesResponse
import `fun`.tryliner.android.core.model.MyWaveRequest
import `fun`.tryliner.android.core.model.MyWaveResponse
import `fun`.tryliner.android.core.model.LikedTracksPage
import `fun`.tryliner.android.core.model.LyricsSearchResult
import `fun`.tryliner.android.core.model.LyricsStreamEvent
import `fun`.tryliner.android.core.model.PlaybackHistoryResponse
import `fun`.tryliner.android.core.model.PopularTracksResponse
import `fun`.tryliner.android.core.model.PowChallenge
import `fun`.tryliner.android.core.model.PowSolution
import `fun`.tryliner.android.core.model.RecordPlaybackEventRequest
import `fun`.tryliner.android.core.model.SearchResponse
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.TrackDetails
import `fun`.tryliner.android.core.model.UserPlaylist
import `fun`.tryliner.android.core.model.UserPlaylistDetails
import `fun`.tryliner.android.core.model.UserPlaylistItem
import `fun`.tryliner.android.core.model.UsernameAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class SpotifyImportTrack(
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L,
)

@Serializable
data class SpotifyImportResponse(
    val title: String = "",
    val tracks: List<SpotifyImportTrack> = emptyList(),
)

@Serializable
data class EdgeKeyResponse(
    val key: String,
    val rotatesAt: String = "",
)

@Serializable
data class ResolvedRelayStream(
    val url: String,
    val isHls: Boolean = false,
    val source: String = "relay",
)

@Serializable
private data class ChallengeRequest(
    val purpose: String,
    val email: String,
)

@Serializable
private data class LoginRequest(
    val email: String,
    val password: String,
    val pow: PowSolution? = null,
    val turnstile: String? = null,
)

@Serializable
private data class RegisterRequest(
    val email: String,
    val password: String,
    val username: String,
    val displayName: String,
    val pow: PowSolution? = null,
    val turnstile: String? = null,
)

@Serializable
private data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
private data class TrackSnapshotPayload(
    val track: Track,
    val trackSnapshot: Track,
)

@Serializable
private data class CreatePlaylistRequest(
    val title: String,
    val description: String? = null,
    val isPublic: Boolean = false,
)

@Serializable
private data class PlaylistsResponse(
    val items: List<UserPlaylist> = emptyList(),
)

@Serializable
private data class RawUserPlaylistResponse(
    val playlist: UserPlaylist,
    val items: List<UserPlaylistItem> = emptyList(),
    val trackCount: Int? = null,
    val durationMs: Long? = null,
    val partial: Boolean = false,
)

@Serializable
private data class InstrumentalLyricsBody(
    val instrumental: Boolean = false,
)

@Serializable
private data class RawImportedSession(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: String? = null,
    val user: AuthUser? = null,
    val expiresAt: Long? = null,
)

/**
 * HTTP client for the Liner backend API (`https://api.tryliner.fun`).
 *
 * Handles WASM HMAC request signing (`x-liner-signature`, `x-liner-timestamp`, `x-liner-nonce`),
 * edge-key header (`x-liner-edge`), correlation headers, server clock synchronization,
 * PoW login/register challenges, atomic 401 token refresh & retry, and SSE lyrics streaming.
 */
class LinerApiClient(
    private val sessionStorage: SessionStorage,
    private val signer: WasmRequestSigner = WasmRequestSigner(),
    baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultOkHttpClient(),
    private val clientVersion: String = DEFAULT_CLIENT_VERSION,
    private val platform: String = DEFAULT_PLATFORM,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val humanCheckProvider: HumanCheckProvider? = null,
    private val json: Json = defaultJson(),
) {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    private val refreshMutex = Mutex()
    private val edgeMutex = Mutex()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.tryliner.fun"
        const val DEFAULT_CLIENT_VERSION = "1.0.4-desktop"
        const val DEFAULT_PLATFORM = "desktop"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DEAD_REFRESH_CODES = setOf("INVALID_REFRESH_TOKEN", "REFRESH_SESSION_EXPIRED")
        private val RETRYABLE_POW_CODES = setOf("POW_EXPIRED", "POW_REUSED")
        private const val PROACTIVE_REFRESH_WINDOW_MS = 120_000L

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ---- Auth & Edge ----

    suspend fun fetchEdgeKey(): EdgeKeyResponse {
        val result: EdgeKeyResponse = request("GET", "/v1/edge/key", edgeRetry = false)
        sessionStorage.setEdgeKey(result.key)
        return result
    }

    suspend fun login(email: String, password: String): AuthSession {
        val session = withAuthProof("login", email) { proof ->
            val body = json.encodeToString(
                LoginRequest(
                    email = email,
                    password = password,
                    pow = proof.pow,
                    turnstile = proof.turnstile,
                ),
            )
            request<AuthSession>("POST", "/v1/auth/login", body = body, allowRefresh = false)
        }
        sessionStorage.setSession(session)
        return session
    }

    suspend fun register(
        email: String,
        password: String,
        username: String,
        displayName: String,
    ): AuthSession {
        val session = withAuthProof("register", email) { proof ->
            val body = json.encodeToString(
                RegisterRequest(
                    email = email,
                    password = password,
                    username = username.trim().lowercase(),
                    displayName = displayName.trim(),
                    pow = proof.pow,
                    turnstile = proof.turnstile,
                ),
            )
            request<AuthSession>("POST", "/v1/auth/register", body = body, allowRefresh = false)
        }
        sessionStorage.setSession(session)
        return session
    }

    suspend fun checkUsername(username: String): UsernameAvailability {
        val encoded = urlEncode(username.trim().lowercase())
        return request("GET", "/v1/auth/username-available?username=$encoded", allowRefresh = false)
    }

    suspend fun getMe(): AuthUser = request("GET", "/v1/me")

    suspend fun importSession(rawInput: String): AuthSession {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "Session token or JSON is empty." }

        val parsed = runCatching {
            json.decodeFromString<RawImportedSession>(trimmed)
        }.getOrNull()

        val accessToken = parsed?.accessToken?.takeIf { it.isNotBlank() }
            ?: trimmed.takeIf { it.startsWith("eyJ") && !it.contains(" ") }
            ?: error("Could not find accessToken. Paste the JSON from window.linerElectron.authSession.get().")

        val userId = parsed?.user?.id?.takeIf { it.isNotBlank() }
            ?: extractSubFromJwt(accessToken)
            ?: "me"

        val initial = AuthSession(
            accessToken = accessToken,
            refreshToken = parsed?.refreshToken.orEmpty(),
            accessTokenExpiresAt = parsed?.accessTokenExpiresAt.orEmpty(),
            user = parsed?.user ?: AuthUser(id = userId),
            expiresAt = parsed?.expiresAt,
        )
        sessionStorage.setSession(initial)

        // Best-effort fetch edge key and full user profile using the imported session
        runCatching { fetchEdgeKey() }
        val resolvedUser = runCatching { getMe() }.getOrNull()
        val finalSession = if (resolvedUser != null) {
            initial.copy(user = resolvedUser).also { sessionStorage.setSession(it) }
        } else {
            initial
        }
        return finalSession
    }

    fun logout() {
        sessionStorage.setSession(null)
        sessionStorage.setEdgeKey(null)
    }

    // ---- Library & Playlists ----

    suspend fun listLikedTracks(limit: Int = 100, cursor: String? = null): LikedTracksPage {
        val query = buildString {
            append("limit=").append(limit)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(urlEncode(cursor))
            }
        }
        return request("GET", "/v1/me/library/tracks?$query")
    }

    suspend fun likeTrack(id: String, trackSnapshot: Track? = null) {
        val body = trackSnapshot?.let {
            json.encodeToString(TrackSnapshotPayload(track = it, trackSnapshot = it))
        }
        requestUnit("PUT", "/v1/me/library/tracks/${urlEncode(id)}", body = body)
    }

    suspend fun unlikeTrack(id: String) {
        requestUnit("DELETE", "/v1/me/library/tracks/${urlEncode(id)}")
    }

    suspend fun listPlaylists(): List<UserPlaylist> {
        val response: PlaylistsResponse = request("GET", "/v1/me/playlists")
        return response.items
    }

    suspend fun getUserPlaylist(id: String): UserPlaylistDetails {
        val raw: RawUserPlaylistResponse = request("GET", "/v1/me/playlists/${urlEncode(id)}")
        val computedDuration = raw.durationMs ?: raw.items.sumOf { it.track.durationMs }
        return UserPlaylistDetails(
            partial = raw.partial,
            trackCount = raw.trackCount ?: raw.items.size,
            durationMs = computedDuration,
            playlist = raw.playlist,
            items = raw.items,
        )
    }

    suspend fun createPlaylist(
        title: String,
        description: String? = null,
        isPublic: Boolean = false,
    ): UserPlaylist {
        val body = json.encodeToString(
            CreatePlaylistRequest(title = title, description = description, isPublic = isPublic),
        )
        return request("POST", "/v1/me/playlists", body = body)
    }

    suspend fun addPlaylistTrack(
        playlistId: String,
        trackId: String,
        trackSnapshot: Track? = null,
    ): UserPlaylistItem {
        val body = trackSnapshot?.let {
            json.encodeToString(TrackSnapshotPayload(track = it, trackSnapshot = it))
        }
        return request(
            "POST",
            "/v1/me/playlists/${urlEncode(playlistId)}/tracks/${urlEncode(trackId)}",
            body = body,
        )
    }

    suspend fun removePlaylistItem(playlistId: String, itemId: String) {
        requestUnit(
            "DELETE",
            "/v1/me/playlists/${urlEncode(playlistId)}/items/${urlEncode(itemId)}",
        )
    }

    suspend fun deletePlaylist(id: String) {
        requestUnit("DELETE", "/v1/me/playlists/${urlEncode(id)}")
    }

    // ---- Catalog & Search ----

    suspend fun search(
        query: String,
        type: String = "all",
        source: String? = null,
    ): SearchResponse {
        val sourceParam = if (!source.isNullOrBlank()) "&source=${urlEncode(source)}" else ""
        val path = "/v1/catalog/search?q=${urlEncode(query)}&type=${urlEncode(type)}$sourceParam"
        return request("GET", path)
    }

    suspend fun getTrack(id: String): TrackDetails =
        request("GET", "/v1/catalog/tracks/${urlEncode(id)}")

    suspend fun getArtist(id: String): ArtistDetails =
        request("GET", "/v1/catalog/artists/${urlEncode(id)}")

    suspend fun getAlbum(id: String): AlbumDetails =
        request("GET", "/v1/catalog/albums/${urlEncode(id)}")

    suspend fun getCatalogPlaylist(id: String): CatalogPlaylistDetails =
        request("GET", "/v1/catalog/playlists/${urlEncode(id)}")

    suspend fun importSpotify(url: String): SpotifyImportResponse =
        request("GET", "/v1/catalog/import/spotify?url=${urlEncode(url)}")

    suspend fun resolveStream(
        videoId: String,
        title: String? = null,
        artist: String? = null,
    ): ResolvedRelayStream {
        val query = buildString {
            append("videoId=").append(urlEncode(videoId))
            if (!title.isNullOrBlank()) {
                append("&title=").append(urlEncode(title))
            }
            if (!artist.isNullOrBlank()) {
                append("&artist=").append(urlEncode(artist))
            }
        }
        return request("GET", "/v1/stream/resolve?$query")
    }

    // ---- Lyrics ----

    suspend fun searchLyrics(
        title: String,
        artist: String,
        durationMs: Long? = null,
    ): LyricsSearchResult? {
        val query = buildString {
            append("title=").append(urlEncode(title.trim()))
            append("&artist=").append(urlEncode(artist.trim()))
            if (durationMs != null && durationMs > 0) {
                append("&durationMs=").append(durationMs)
            }
        }
        return try {
            request("GET", "/v1/me/lyrics/search?$query")
        } catch (err: ApiError) {
            if (err.status == 404 && err.code == "LYRICS_NOT_FOUND") null else throw err
        }
    }

    fun streamLyrics(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        durationMs: Long? = null,
    ): Flow<LyricsStreamEvent> = flow {
        val hintQuery = if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            buildString {
                append("?title=").append(urlEncode(title.trim()))
                append("&artist=").append(urlEncode(artist.trim()))
                if (durationMs != null && durationMs > 0) {
                    append("&durationMs=").append(durationMs)
                }
            }
        } else {
            ""
        }
        val path = "/v1/tracks/${urlEncode(trackId)}/lyrics$hintQuery"
        val requestId = UUID.randomUUID().toString()
        val extraHeaders = mapOf(
            "Accept" to "application/json, text/event-stream",
            "x-session-id" to trackId,
        )

        val response = executeWithRecovery(
            method = "GET",
            path = path,
            body = null,
            allowRefresh = true,
            edgeRetry = true,
            requestId = requestId,
            extraHeaders = extraHeaders,
        )

        response.use { res ->
            syncDateHeader(res)
            if (!res.isSuccessful) {
                throw toApiError(res, requestId)
            }

            val contentType = res.header("Content-Type").orEmpty()
            val body = res.body ?: throw ApiError(502, "Empty lyrics response", requestId = requestId)

            if (contentType.contains("application/json", ignoreCase = true)) {
                val rawJson = body.string()
                val parsed = runCatching {
                    json.decodeFromString<InstrumentalLyricsBody>(rawJson)
                }.getOrNull()
                emit(
                    LyricsStreamEvent.Done(
                        cached = true,
                        status = "not_found",
                        instrumental = parsed?.instrumental == true,
                    ),
                )
                return@flow
            }

            val parser = SseEventParser()
            body.charStream().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    for (frame in parser.feed(String(buffer, 0, count))) {
                        SseEventParser.decodeLyricsEvent(frame, json)?.let { emit(it) }
                    }
                }
            }
            for (frame in parser.flush()) {
                SseEventParser.decodeLyricsEvent(frame, json)?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---- Playback, History, Explore, Recommendations ----

    suspend fun recordPlaybackEvent(payload: RecordPlaybackEventRequest) {
        val body = json.encodeToString(payload)
        requestUnit("POST", "/v1/me/playback/events", body = body)
    }

    suspend fun getHistory(cursor: String? = null, limit: Int = 30): PlaybackHistoryResponse {
        val query = buildString {
            append("limit=").append(limit)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(urlEncode(cursor))
            }
        }
        return request("GET", "/v1/me/history?$query")
    }

    suspend fun getPopularTracks(window: String = "7d", limit: Int = 50): PopularTracksResponse =
        request("GET", "/v1/explore/popular?window=${urlEncode(window)}&limit=$limit")

    suspend fun getDailyMixes(): DailyMixesResponse =
        request("GET", "/v1/me/recommendations/daily-mixes")

    suspend fun getMyWave(
        vibe: String = "all",
        excludeIds: List<String> = emptyList(),
        limit: Int = 20,
    ): MyWaveResponse =
        request(
            method = "POST",
            path = "/v1/me/recommendations/wave",
            body = json.encodeToString(
                MyWaveRequest.serializer(),
                MyWaveRequest(vibe = vibe, excludeIds = excludeIds, limit = limit),
            ),
        )

    // ---- Internal Request Execution ----

    private data class AuthProof(
        val pow: PowSolution? = null,
        val turnstile: String? = null,
    )

    private suspend fun withAuthProof(
        purpose: String,
        email: String,
        send: suspend (AuthProof) -> AuthSession,
    ): AuthSession {
        val firstProof = fetchAuthProof(purpose, email)
        return try {
            send(firstProof)
        } catch (err: ApiError) {
            if (err.code != null && err.code in RETRYABLE_POW_CODES) {
                val retryProof = fetchAuthProof(purpose, email)
                send(retryProof)
            } else {
                throw err
            }
        } catch (_: PowTimeoutException) {
            val retryProof = fetchAuthProof(purpose, email)
            send(retryProof)
        }
    }

    private suspend fun fetchAuthProof(purpose: String, email: String): AuthProof {
        val challenge: PowChallenge = try {
            val body = json.encodeToString(ChallengeRequest(purpose = purpose, email = email))
            request("POST", "/v1/auth/challenge", body = body, allowRefresh = false)
        } catch (err: ApiError) {
            if (err.status == 404) return AuthProof()
            throw err
        }

        val pow = if (challenge.mode != "off" && challenge.algorithm == ProofOfWorkSolver.SUPPORTED_POW_ALGORITHM) {
            withContext(Dispatchers.Default) {
                ProofOfWorkSolver.solve(challenge)
            }
        } else {
            null
        }

        val turnstileConfig = challenge.turnstile
        val requestedMode = turnstileConfig?.mode
        val mode = if (requestedMode == "optional" || requestedMode == "required") requestedMode else "off"
        val needsEdge = turnstileConfig?.edge == true

        var turnstileToken: String? = null
        if (mode != "off" || needsEdge) {
            val effectiveMode = if (mode == "off") "optional" else mode
            val result = humanCheckProvider?.requestHumanCheck(
                HumanCheckRequest(action = purpose, mode = effectiveMode),
            )
            result?.clearanceCookie?.takeIf { it.isNotBlank() }?.let {
                sessionStorage.setClearanceCookie(it)
            }
            turnstileToken = result?.token?.takeIf { it.isNotBlank() }
            val hasClearance = !result?.clearanceCookie.isNullOrBlank()
            if (effectiveMode == "required" && turnstileToken == null && !hasClearance) {
                throw HumanCheckCancelledException()
            }
        }

        return AuthProof(pow = pow, turnstile = turnstileToken)
    }

    private suspend inline fun <reified T> request(
        method: String,
        path: String,
        body: String? = null,
        allowRefresh: Boolean = true,
        edgeRetry: Boolean = true,
    ): T {
        val rawBody = executeAndUnwrap(method, path, body, allowRefresh, edgeRetry)
        return json.decodeFromString(rawBody)
    }

    private suspend fun requestUnit(
        method: String,
        path: String,
        body: String? = null,
        allowRefresh: Boolean = true,
        edgeRetry: Boolean = true,
    ) {
        executeAndUnwrap(method, path, body, allowRefresh, edgeRetry)
    }

    private suspend fun executeAndUnwrap(
        method: String,
        path: String,
        body: String?,
        allowRefresh: Boolean,
        edgeRetry: Boolean,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val response = executeWithRecovery(
            method = method,
            path = path,
            body = body,
            allowRefresh = allowRefresh,
            edgeRetry = edgeRetry,
            requestId = requestId,
        )

        return response.use { res ->
            syncDateHeader(res)
            if (!res.isSuccessful) {
                val cfMitigated = res.code == 403 && res.header("cf-mitigated") == "challenge"
                throw toApiError(res, requestId, cfMitigated = cfMitigated)
            }
            if (res.code == 204) "" else res.body?.string().orEmpty()
        }
    }

    private suspend fun executeWithRecovery(
        method: String,
        path: String,
        body: String?,
        allowRefresh: Boolean,
        edgeRetry: Boolean,
        requestId: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val isAuthPath = path.startsWith("/v1/auth/")
        var accessToken = if (allowRefresh && !isAuthPath) getValidAccessToken() else null
        var response = executeRaw(method, path, body, accessToken, requestId, extraHeaders)

        if (response.code == 401 && allowRefresh && !isAuthPath) {
            response.close()
            val nextToken = refreshAccessTokenIfNeeded(failedToken = accessToken)
            if (nextToken != null) {
                accessToken = nextToken
                response = executeRaw(method, path, body, accessToken, requestId, extraHeaders)
            } else {
                throw ApiError(401, "Unauthorized", requestId = requestId)
            }
        }

        if (edgeRetry && response.code == 403) {
            val cfMitigated = response.header("cf-mitigated") == "challenge"
            val hasInstance = response.header("x-instance") != null
            if (cfMitigated || !hasInstance) {
                val snapshotError = response.use { res ->
                    syncDateHeader(res)
                    toApiError(res, requestId, cfMitigated = cfMitigated)
                }

                val recovered = if (cfMitigated) {
                    requestHumanCheckAndSaveCookie("login", "required")
                } else {
                    refreshEdgeKeySafe() || requestHumanCheckAndSaveCookie("login", "required")
                }

                if (recovered) {
                    response = executeRaw(method, path, body, accessToken, requestId, extraHeaders)
                    if (response.code == 401 && allowRefresh && !isAuthPath) {
                        response.close()
                        val nextToken = refreshAccessTokenIfNeeded(failedToken = accessToken)
                        if (nextToken != null) {
                            accessToken = nextToken
                            response = executeRaw(method, path, body, accessToken, requestId, extraHeaders)
                        } else {
                            throw ApiError(401, "Unauthorized", requestId = requestId)
                        }
                    }
                } else {
                    throw snapshotError
                }
            }
        }

        return response
    }

    private suspend fun requestHumanCheckAndSaveCookie(action: String, mode: String): Boolean {
        val provider = humanCheckProvider ?: return false
        val result = provider.requestHumanCheck(HumanCheckRequest(action = action, mode = mode))
            ?: return false
        result.clearanceCookie?.takeIf { it.isNotBlank() }?.let {
            sessionStorage.setClearanceCookie(it)
            return true
        }
        return !result.token.isNullOrBlank()
    }

    private suspend fun executeRaw(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        requestId: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val cleanPath = path.substringBefore('?').trim()
        val signed = signer.signRequest(method = method, path = cleanPath, body = body)
        val currentSession = sessionStorage.getSession()

        val builder = Request.Builder()
            .url("$normalizedBaseUrl$path")

        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") {
                ByteArray(0).toRequestBody(null)
            } else {
                null
            }
        builder.method(method, requestBody)

        if (body != null) {
            builder.header("Content-Type", "application/json")
        }
        if (!accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }

        builder.header("User-Agent", userAgent)
        builder.header("x-request-id", requestId)
        builder.header("x-client-version", clientVersion)
        builder.header("x-platform", platform)

        val clearanceCookie = sessionStorage.getClearanceCookie()
        if (!clearanceCookie.isNullOrBlank()) {
            builder.header("Cookie", "cf_clearance=$clearanceCookie")
        }

        val userId = currentSession?.user?.id ?: accessToken?.let(::extractSubFromJwt)
        if (!userId.isNullOrBlank()) {
            builder.header("x-user-id", userId)
        }
        val username = currentSession?.user?.username
        if (!username.isNullOrBlank()) {
            builder.header("x-user-name", username)
        }

        val edgeKey = sessionStorage.getEdgeKey()
        if (!edgeKey.isNullOrBlank()) {
            builder.header("x-liner-edge", edgeKey)
        }

        for ((k, v) in signed.toHeaderMap()) {
            builder.header(k, v)
        }
        for ((k, v) in extraHeaders) {
            builder.header(k, v)
        }

        return httpClient.newCall(builder.build()).await()
    }

    private suspend fun getValidAccessToken(): String? {
        val current = sessionStorage.getSession() ?: return null
        val expiresAtMs = parseExpiryMs(current)
        if (expiresAtMs != null && expiresAtMs - System.currentTimeMillis() < PROACTIVE_REFRESH_WINDOW_MS) {
            val refreshed = refreshAccessTokenIfNeeded(failedToken = current.accessToken)
            if (refreshed != null) return refreshed
            if (expiresAtMs > System.currentTimeMillis()) return current.accessToken
            return null
        }
        return current.accessToken
    }

    private suspend fun refreshAccessTokenIfNeeded(failedToken: String?): String? =
        refreshMutex.withLock {
            val current = sessionStorage.getSession() ?: return null
            if (failedToken != null && current.accessToken != failedToken) {
                return current.accessToken
            }
            val refreshToken = current.refreshToken
            if (refreshToken.isBlank()) return null

            val body = json.encodeToString(RefreshRequest(refreshToken = refreshToken))
            val res = try {
                executeRaw(
                    method = "POST",
                    path = "/v1/auth/refresh",
                    body = body,
                    accessToken = null,
                    requestId = UUID.randomUUID().toString(),
                )
            } catch (_: IOException) {
                return null
            }

            return res.use { response ->
                syncDateHeader(response)
                if (response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    val next = runCatching { json.decodeFromString<AuthSession>(raw) }.getOrNull()
                        ?: return@use null
                    val merged = current.copy(
                        accessToken = next.accessToken,
                        refreshToken = next.refreshToken,
                        accessTokenExpiresAt = next.accessTokenExpiresAt,
                        user = next.user,
                        expiresAt = next.expiresAt ?: current.expiresAt,
                    )
                    sessionStorage.setSession(merged)
                    merged.accessToken
                } else {
                    if (response.code == 401 || response.code == 403) {
                        val errBody = runCatching {
                            json.decodeFromString<ApiErrorBody>(response.body?.string().orEmpty())
                        }.getOrNull()
                        if (errBody?.code in DEAD_REFRESH_CODES) {
                            sessionStorage.setSession(null)
                        }
                    }
                    null
                }
            }
        }

    private suspend fun refreshEdgeKeySafe(): Boolean = edgeMutex.withLock {
        if (sessionStorage.getSession() == null) return false
        return runCatching { fetchEdgeKey() }.isSuccess
    }

    private fun syncDateHeader(response: Response) {
        val dateHeader = response.header("date") ?: return
        runCatching { signer.syncServerTime(dateHeader) }
    }

    private fun toApiError(
        response: Response,
        fallbackRequestId: String,
        cfMitigated: Boolean = false,
    ): ApiError {
        val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString<ApiErrorBody>(raw) }.getOrNull()
        val reqId = response.header("x-request-id")
            ?: parsed?.reqId
            ?: parsed?.requestId
            ?: fallbackRequestId
        val fallbackBodySnippet = raw.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") }?.take(200)
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: parsed?.error?.takeIf { it.isNotBlank() }
            ?: if (cfMitigated) {
                "Cloudflare security challenge required (HTTP 403 cf-mitigated)"
            } else {
                fallbackBodySnippet ?: response.message.ifBlank { "HTTP ${response.code}" }
            }
        return ApiError(
            status = response.code,
            message = message,
            code = parsed?.code ?: if (cfMitigated) "CF_CHALLENGE" else null,
            requestId = reqId,
        )
    }

    private fun parseExpiryMs(session: AuthSession): Long? {
        session.expiresAt?.let { exp ->
            if (exp > 0) return if (exp < 10_000_000_000L) exp * 1000L else exp
        }
        val iso = session.accessTokenExpiresAt
        if (iso.isBlank()) return null
        iso.toLongOrNull()?.let { num ->
            return if (num < 10_000_000_000L) num * 1000L else num
        }
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    private fun extractSubFromJwt(token: String): String? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            val subRegex = Regex(""""sub"\s*:\s*"([^"]+)"""")
            subRegex.find(decoded)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) {
                response.close()
                return
            }
            cont.resume(response)
        }
    })
}
