package `fun`.tryliner.android.core.network

import `fun`.tryliner.android.core.model.Cover
import `fun`.tryliner.android.core.model.RadioResponse
import `fun`.tryliner.android.core.model.RadioTrack
import `fun`.tryliner.android.core.model.SearchResult
import `fun`.tryliner.android.core.model.TrackAlbum
import `fun`.tryliner.android.core.model.TrackArtist
import `fun`.tryliner.android.core.model.TrackDetails
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

data class InnertubeProfile(
    val clientName: String,
    val clientVersion: String,
    val clientIdHeader: String,
    val origin: String,
    val userAgent: String? = null,
    val includeUserAgentInContext: Boolean = false,
    val extraContext: Map<String, JsonPrimitive> = emptyMap(),
)

data class ResolvedAudioStream(
    val url: String,
    val mimeType: String,
    val codec: String,
    val bitrate: Int,
    val itag: Int,
    val expiresAt: String? = null,
    val contentLength: Long? = null,
)

class InnertubeException(
    override val message: String,
    val status: Int,
) : Exception(message)

/**
 * Direct YouTube Music Innertube client for search, track metadata, radio queue,
 * and direct audio stream URL resolution (without requiring a JS player).
 */
class InnertubeClient(
    private val httpClient: OkHttpClient = defaultOkHttpClient(),
    private val baseUrlOverride: String? = null,
    private val json: Json = LinerApiClient.defaultJson(),
) {
    @Volatile
    private var lastWorkingProfile: InnertubeProfile? = null

    @Volatile
    private var visitorData: String? = null

    @Volatile
    private var visitorDataExpiresAtMs: Long = 0L

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
        private val VISITOR_DATA_REGEX = Regex("""Cg[s-v][A-Za-z0-9_-]{20,}(?:%3D|=){0,2}""")
        private const val VISITOR_SESSION_TTL_MS = 5L * 60L * 60L * 1000L
        private val ARTWORK_HOST_REGEX =
            Regex("(?:[a-zA-Z0-9_-]+\\.googleusercontent\\.com|yt3\\.ggpht\\.com)", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("^\\d{4}$")

        private val SEARCH_PARAMS = mapOf(
            "track" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
            "video" to "EgWKAQIQAWoKEAkQBRAKEAMQBA%3D%3D",
            "artist" to "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D",
            "album" to "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D",
            "playlist" to "EgWKAQIoAWoKEAkQBRAKEAMQBA%3D%3D",
        )

        val WEB_REMIX_PROFILE = InnertubeProfile(
            clientName = "WEB_REMIX",
            clientVersion = "1.20260911.08.00",
            clientIdHeader = "67",
            origin = "https://music.youtube.com",
        )

        val ANDROID_VR_PROFILE = InnertubeProfile(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientIdHeader = "28",
            origin = "https://www.youtube.com",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            includeUserAgentInContext = true,
            extraContext = mapOf(
                "osName" to JsonPrimitive("Android"),
                "osVersion" to JsonPrimitive("12L"),
                "deviceMake" to JsonPrimitive("Oculus"),
                "deviceModel" to JsonPrimitive("Quest 3"),
                "androidSdkVersion" to JsonPrimitive(32),
            ),
        )

        val VISIONOS_PROFILE = InnertubeProfile(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientIdHeader = "101",
            origin = "https://www.youtube.com",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            includeUserAgentInContext = true,
            extraContext = mapOf(
                "osName" to JsonPrimitive("visionOS"),
                "osVersion" to JsonPrimitive("26.5.23O471"),
                "deviceMake" to JsonPrimitive("Apple"),
                "deviceModel" to JsonPrimitive("RealityDevice17,1"),
            ),
        )

        val IOS_PROFILE = InnertubeProfile(
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientIdHeader = "5",
            origin = "https://www.youtube.com",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            includeUserAgentInContext = true,
            extraContext = mapOf(
                "osName" to JsonPrimitive("iPhone"),
                "osVersion" to JsonPrimitive("18.3.2.22D82"),
                "deviceMake" to JsonPrimitive("Apple"),
                "deviceModel" to JsonPrimitive("iPhone16,2"),
            ),
        )

        val PLAYBACK_PROFILES: List<InnertubeProfile> = listOf(
            VISIONOS_PROFILE,
            ANDROID_VR_PROFILE,
            IOS_PROFILE,
        )

        fun toVideoId(trackId: String): String? {
            val clean = trackId.removePrefix("yt:")
            return clean.takeIf { VIDEO_ID_REGEX.matches(it) }
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    // ---- Search ----

    suspend fun search(query: String, type: String = "all"): List<SearchResult> {
        val params = if (type != "all") SEARCH_PARAMS[type] else null
        val payload = buildJsonObject {
            put("query", query)
            if (params != null) put("params", params)
        }
        val root = postInnertube("search", payload, WEB_REMIX_PROFILE)
        val results = mapSearchResponse(root)
        return if (type == "all") results else results.filter {
            when (type) {
                "track" -> it is SearchResult.TrackItem
                "artist" -> it is SearchResult.ArtistItem
                "album" -> it is SearchResult.AlbumItem
                "playlist" -> it is SearchResult.PlaylistItem
                else -> true
            }
        }
    }

    // ---- Track & Radio ----

    suspend fun getTrack(trackId: String): TrackDetails {
        val videoId = toVideoId(trackId) ?: trackId.removePrefix("yt:")
        val nextBody = buildJsonObject { put("videoId", videoId) }
        val fromNext = runCatching {
            val root = postInnertube("next", nextBody, WEB_REMIX_PROFILE)
            val items = getQueueItems(root)
            val selected = items.firstOrNull {
                it.obj("playlistPanelVideoRenderer")?.bool("selected") == true
            } ?: items.firstOrNull {
                it.obj("playlistPanelVideoRenderer")?.str("videoId") == videoId
            }
            selected?.let(::mapTrackDetailsFromPanel)
        }.getOrNull()
        if (fromNext != null) return fromNext

        val searched = runCatching { search(videoId, "track") }.getOrDefault(emptyList())
        val match = searched.filterIsInstance<SearchResult.TrackItem>().firstOrNull { it.id == videoId }
        if (match != null) {
            return TrackDetails(
                id = match.id,
                title = match.title,
                artists = match.artists,
                album = match.album,
                durationMs = match.durationMs,
                cover = match.cover,
                explicit = match.explicit,
                audioVariant = match.audioVariant,
                viewer = match.viewer,
            )
        }
        throw InnertubeException("Track was not found.", 404)
    }

    suspend fun getRadio(trackId: String, k: Int = 25): RadioResponse {
        val videoId = toVideoId(trackId) ?: trackId.removePrefix("yt:")
        var queue = getQueueItems(
            postInnertube(
                "next",
                buildJsonObject {
                    put("videoId", videoId)
                    put("playlistId", "RDAMVM$videoId")
                },
                WEB_REMIX_PROFILE,
            ),
        )
        if (queue.isEmpty()) {
            queue = getQueueItems(
                postInnertube(
                    "next",
                    buildJsonObject { put("videoId", videoId) },
                    WEB_REMIX_PROFILE,
                ),
            )
        }
        val items = queue.mapNotNull(::mapRadioTrackFromPanel).take(k)
        return RadioResponse(items = items)
    }

    // ---- Direct Audio Stream Resolution ----

    suspend fun resolveAudioStream(
        trackId: String,
        preferredCodec: String = "opus",
        verifyReachable: Boolean = true,
    ): ResolvedAudioStream {
        val videoId = toVideoId(trackId)
            ?: throw InnertubeException("Track $trackId is not a YouTube video.", 400)

        ensureVisitorData(forceRefresh = false)

        val failures = mutableListOf<String>()
        var lastRefusedAll = false

        // Up to 2 passes: if pass 1 fails across all profiles, refresh authentic visitorData
        // from sw.js_data (matching desktop visitorSession.ts) and retry once.
        for (pass in 0..1) {
            if (pass == 1) {
                lastWorkingProfile = null
                ensureVisitorData(forceRefresh = true)
            }
            val profiles = orderedProfiles()
            var refusedCount = 0

            for (profile in profiles) {
                try {
                    val stream = resolveWithProfile(videoId, profile, preferredCodec, verifyReachable)
                    lastWorkingProfile = profile
                    return stream
                } catch (err: InnertubeException) {
                    if (err.status == 403) refusedCount++
                    failures.add("${profile.clientName}: ${err.message}")
                    if (err.status == 0) {
                        // Stream host (e.g. googlevideo.com) is unreachable/blocked on this network;
                        // fail fast so caller can fall back to relay without burning 6x retries.
                        throw InnertubeException("Direct audio resolution failed (${failures.joinToString("; ")}).", 502)
                    }
                } catch (err: Exception) {
                    if (err is CancellationException) throw err
                    failures.add("${profile.clientName}: ${err.message ?: err::class.simpleName}")
                }
            }
            lastRefusedAll = profiles.isNotEmpty() && refusedCount == profiles.size
        }

        val status = if (lastRefusedAll) 403 else 502
        throw InnertubeException("Direct audio resolution failed (${failures.joinToString("; ")}).", status)
    }

    /**
     * Port of desktop `visitorSession.ts`: fetches authentic `visitorData` from
     * `https://www.youtube.com/sw.js_data` so YouTube doesn't flag anonymous requests as bots.
     */
    private suspend fun ensureVisitorData(forceRefresh: Boolean) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && !visitorData.isNullOrBlank() && visitorDataExpiresAtMs > now) {
            return
        }
        if (forceRefresh) {
            visitorData = null
            visitorDataExpiresAtMs = 0L
        }
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                .get()
                .build()
            val quickClient = httpClient.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            quickClient.newCall(req).await().use { res ->
                if (!res.isSuccessful) return
                val text = res.body?.string().orEmpty()
                val parsed = VISITOR_DATA_REGEX.find(text)?.value
                if (!parsed.isNullOrBlank()) {
                    visitorData = parsed
                    visitorDataExpiresAtMs = System.currentTimeMillis() + VISITOR_SESSION_TTL_MS
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // visitorData is optional; do not block playback if YouTube is unreachable.
        }
    }

    private suspend fun resolveWithProfile(
        videoId: String,
        profile: InnertubeProfile,
        preferredCodec: String,
        verifyReachable: Boolean,
    ): ResolvedAudioStream {
        val body = buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        var response = postInnertube("player", body, profile)
        if (requiresBotCheck(response)) {
            ensureVisitorData(forceRefresh = true)
            response = postInnertube("player", body, profile)
        }
        val playabilityErr = playabilityError(response)
        if (playabilityErr != null) {
            throw InnertubeException(playabilityErr, 403)
        }

        val formats = extractAudioFormats(response).filter { it.url.isNotBlank() }
        val best = selectBestAudioFormat(formats, preferredCodec)
            ?: throw InnertubeException("${profile.clientName} returned no direct audio formats.", 502)

        val resolved = toResolvedStream(best)
        if (verifyReachable) {
            assertStreamReachable(resolved.url, resolved.contentLength)
        }
        return resolved
    }

    private suspend fun assertStreamReachable(url: String, contentLength: Long?) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val offset = if (contentLength != null && contentLength > 1_052_672L) 1_052_672L else 0L
        val probeUrl = parsed.newBuilder()
            .setQueryParameter("range", "$offset-$offset")
            .build()
        val req = Request.Builder().url(probeUrl).get().build()
        val probeClient = httpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val res = try {
            probeClient.newCall(req).await()
        } catch (e: IOException) {
            throw InnertubeException("stream url unreachable: ${e.message}", 0)
        }
        res.use {
            if (!it.isSuccessful) {
                throw InnertubeException("stream url rejected with ${it.code}", it.code)
            }
        }
    }

    private fun orderedProfiles(): List<InnertubeProfile> {
        val last = lastWorkingProfile ?: return PLAYBACK_PROFILES
        if (last !in PLAYBACK_PROFILES) return PLAYBACK_PROFILES
        return listOf(last) + PLAYBACK_PROFILES.filter { it != last }
    }

    // ---- HTTP Transport ----

    private suspend fun postInnertube(
        endpoint: String,
        body: JsonObject,
        profile: InnertubeProfile,
    ): JsonObject {
        val base = (baseUrlOverride ?: profile.origin).trimEnd('/')
        val url = "$base/youtubei/v1/$endpoint?prettyPrint=false"

        suspend fun send(): okhttp3.Response {
            val payload = buildJsonObject {
                for ((k, v) in body) {
                    put(k, v)
                }
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", profile.clientName)
                        put("clientVersion", profile.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        visitorData?.let { put("visitorData", it) }
                        if (profile.includeUserAgentInContext && profile.userAgent != null) {
                            put("userAgent", profile.userAgent)
                        }
                        for ((k, v) in profile.extraContext) {
                            put(k, v)
                        }
                    }
                    putJsonObject("user") {
                        put("lockedSafetyMode", false)
                    }
                }
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-youtube-client-name", profile.clientIdHeader)
                .header("x-youtube-client-version", profile.clientVersion)
                .header("x-origin", profile.origin)

            visitorData?.let { reqBuilder.header("x-goog-visitor-id", it) }
            profile.userAgent?.let { reqBuilder.header("User-Agent", it) }

            return try {
                httpClient.newCall(reqBuilder.build()).await()
            } catch (e: IOException) {
                throw InnertubeException("YouTube Music is unreachable: ${e.message}", 0)
            }
        }

        var response = send()
        if (response.code == 401 || response.code == 403) {
            response.close()
            visitorData = null
            response = send()
        }

        return response.use { res ->
            if (!res.isSuccessful) {
                throw InnertubeException("YouTube Music $endpoint failed with HTTP ${res.code}.", res.code)
            }
            val raw = res.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
                throw InnertubeException("YouTube Music $endpoint returned invalid JSON.", 502)
            }
            obj.obj("responseContext")?.str("visitorData")?.let { visitorData = it }
            obj
        }
    }

    // ---- Audio Format Helpers ----

    private data class AudioFormat(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val contentLength: Long?,
    )

    private fun extractAudioFormats(playerResponse: JsonObject): List<AudioFormat> {
        val adaptive = playerResponse.obj("streamingData")?.arr("adaptiveFormats") ?: return emptyList()
        return adaptive.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val itag = obj.int("itag") ?: return@mapNotNull null
            val mimeType = obj.str("mimeType") ?: return@mapNotNull null
            val url = obj.str("url") ?: return@mapNotNull null
            if (!mimeType.startsWith("audio/")) return@mapNotNull null
            val bitrate = obj.int("bitrate") ?: obj.int("averageBitrate") ?: 0
            val contentLength = obj.long("contentLength")?.takeIf { it > 0 }
            AudioFormat(itag, url, mimeType, bitrate, contentLength)
        }
    }

    private fun selectBestAudioFormat(formats: List<AudioFormat>, preferredCodec: String): AudioFormat? {
        if (formats.isEmpty()) return null
        val preferredOrder = if (preferredCodec.equals("aac", ignoreCase = true)) {
            listOf(140, 139, 251, 250, 249)
        } else {
            listOf(251, 140, 250, 249, 139)
        }
        for (itag in preferredOrder) {
            formats.firstOrNull { it.itag == itag }?.let { return it }
        }
        return formats.maxByOrNull { it.bitrate }
    }

    private fun toResolvedStream(format: AudioFormat): ResolvedAudioStream {
        val isOpus = format.mimeType.contains("opus", ignoreCase = true) ||
            format.mimeType.contains("webm", ignoreCase = true)
        val expiresAt = format.url.toHttpUrlOrNull()
            ?.queryParameter("expire")
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { Instant.ofEpochSecond(it).toString() }
        return ResolvedAudioStream(
            url = format.url,
            mimeType = format.mimeType.substringBefore(';').trim().ifBlank { "audio/webm" },
            codec = if (isOpus) "opus" else "aac",
            bitrate = if (format.bitrate > 0) format.bitrate else 160_000,
            itag = format.itag,
            expiresAt = expiresAt,
            contentLength = format.contentLength,
        )
    }

    private fun requiresBotCheck(playerResponse: JsonObject): Boolean =
        playerResponse.obj("playabilityStatus")?.str("status") == "LOGIN_REQUIRED"

    private fun playabilityError(playerResponse: JsonObject): String? {
        val statusObj = playerResponse.obj("playabilityStatus") ?: return null
        val status = statusObj.str("status")
        if (status == "OK") return null
        return statusObj.str("reason")?.takeIf { it.isNotBlank() }
            ?: "Playability status $status"
    }

    // ---- Search / Queue Mappers ----

    private fun mapSearchResponse(response: JsonObject): List<SearchResult> {
        val tabs = response.obj("contents")?.obj("tabbedSearchResultsRenderer")?.arr("tabs") ?: return emptyList()
        val tabContent = (tabs.firstOrNull() as? JsonObject)?.obj("tabRenderer")?.obj("content") ?: return emptyList()
        val sections = tabContent.obj("sectionListRenderer")?.arr("contents") ?: return emptyList()

        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        for (sectionElem in sections) {
            val section = sectionElem as? JsonObject ?: continue
            val shelfContents = section.obj("musicShelfRenderer")?.arr("contents")
                ?: section.obj("itemSectionRenderer")?.arr("contents")
                ?: continue
            for (itemElem in shelfContents) {
                val item = itemElem as? JsonObject ?: continue
                val mapped = mapSearchListItem(item) ?: continue
                val key = "${mapped::class.simpleName}:${mapped.id}"
                if (seen.add(key)) results.add(mapped)
            }
        }
        return results
    }

    private fun mapSearchListItem(raw: JsonObject): SearchResult? {
        val renderer = raw.obj("musicResponsiveListItemRenderer") ?: return null
        val titleCol = flexColumnText(renderer, 0)
        val title = getText(titleCol) ?: return null
        val videoId = resolveVideoId(renderer, getRuns(titleCol).firstOrNull())
        if (videoId != null) {
            val allRuns = flexRuns(renderer) + fixedRuns(renderer)
            val artists = runsToArtists(allRuns).ifEmpty {
                listOf(TrackArtist(id = "unknown", name = "Unknown Artist"))
            }
            return SearchResult.TrackItem(
                id = videoId,
                title = title,
                artists = artists,
                album = runsToAlbum(allRuns),
                durationMs = durationFromRuns(allRuns),
                cover = coverFromThumbnail(renderer["thumbnail"]),
                explicit = hasExplicitBadge(renderer),
            )
        }

        val browseEndpoint = getBrowseEndpoint(renderer) ?: return null
        val browseId = browseEndpoint.str("browseId") ?: return null
        val pageType = getPageType(browseEndpoint)
        val metadataRuns = getRuns(flexColumnText(renderer, 1))
        val cover = coverFromThumbnail(renderer["thumbnail"])

        return when (pageType) {
            "MUSIC_PAGE_TYPE_ARTIST" -> SearchResult.ArtistItem(
                id = browseId,
                name = title,
                cover = cover,
            )
            "MUSIC_PAGE_TYPE_ALBUM" -> SearchResult.AlbumItem(
                id = browseId,
                title = title,
                artists = runsToArtists(metadataRuns),
                year = metadataRuns.firstNotNullOfOrNull { run ->
                    run.str("text")?.takeIf { YEAR_REGEX.matches(it) }?.toIntOrNull()
                },
                cover = cover,
            )
            "MUSIC_PAGE_TYPE_PLAYLIST" -> SearchResult.PlaylistItem(
                id = browseId,
                title = title,
                author = metadataRuns.firstNotNullOfOrNull { it.str("text")?.trim()?.takeIf(String::isNotEmpty) },
                cover = cover,
            )
            else -> null
        }
    }

    private fun getQueueItems(response: JsonObject): List<JsonObject> {
        val contents = response.obj("contents") ?: return emptyList()
        val singleColumn = contents.obj("singleColumnMusicWatchNextResultsRenderer")
        val watchNext = singleColumn?.obj("tabbedRenderer")?.obj("watchNextTabbedResultsRenderer")
        for (tabElem in watchNext?.arr("tabs").orEmpty()) {
            val tab = tabElem as? JsonObject ?: continue
            val queue = tab.obj("tabRenderer")?.obj("content")?.obj("musicQueueRenderer")
            val panel = queue?.obj("content")?.obj("playlistPanelRenderer")?.arr("contents")
            if (panel != null && panel.isNotEmpty()) {
                return panel.mapNotNull { it as? JsonObject }
            }
        }
        val direct = singleColumn?.obj("playlistPanelRenderer")?.arr("contents")
        if (direct != null && direct.isNotEmpty()) {
            return direct.mapNotNull { it as? JsonObject }
        }
        return emptyList()
    }

    private fun mapTrackDetailsFromPanel(raw: JsonObject): TrackDetails? {
        val renderer = raw.obj("playlistPanelVideoRenderer") ?: return null
        val id = renderer.str("videoId") ?: return null
        val title = getText(renderer["title"]) ?: return null
        val byline = renderer.obj("longBylineText") ?: renderer.obj("shortBylineText") ?: renderer.obj("bylineText")
        val runs = getRuns(byline)
        val artists = runsToArtists(runs).ifEmpty {
            listOf(TrackArtist(id = "unknown", name = getText(byline) ?: "Unknown Artist"))
        }
        val year = runs.firstNotNullOfOrNull { r -> r.str("text")?.takeIf { YEAR_REGEX.matches(it) } }
        return TrackDetails(
            id = id,
            title = title,
            artists = artists,
            album = runsToAlbum(runs),
            durationMs = parseDurationMs(getText(renderer["lengthText"]).orEmpty()) ?: 0L,
            cover = coverFromThumbnail(renderer["thumbnail"]),
            releaseDate = year,
            explicit = hasExplicitBadge(renderer),
        )
    }

    private fun mapRadioTrackFromPanel(raw: JsonObject): RadioTrack? {
        val renderer = raw.obj("playlistPanelVideoRenderer") ?: raw
        val id = renderer.str("videoId") ?: return null
        val title = getText(renderer["title"]) ?: return null
        val byline = renderer.obj("longBylineText") ?: renderer.obj("shortBylineText") ?: renderer.obj("bylineText")
        val runs = getRuns(byline)
        val artists = runsToArtists(runs).ifEmpty {
            listOf(TrackArtist(id = "", name = getText(byline)?.substringBefore("•")?.trim() ?: "Unknown Artist"))
        }
        val durationText = getText(renderer["lengthText"]) ?: getText(renderer["duration"]) ?: ""
        return RadioTrack(
            id = id,
            title = title,
            artists = artists,
            album = runsToAlbum(runs),
            durationMs = parseDurationMs(durationText) ?: 0L,
            cover = coverFromThumbnail(renderer["thumbnail"]),
            explicit = hasExplicitBadge(renderer),
        )
    }

    // ---- Low-level JSON Helpers ----

    private fun flexColumnText(renderer: JsonObject, index: Int): JsonObject? {
        val col = renderer.arr("flexColumns").getOrNull(index) as? JsonObject ?: return null
        return col.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
    }

    private fun flexRuns(renderer: JsonObject): List<JsonObject> =
        renderer.arr("flexColumns").flatMapIndexed { index, _ -> getRuns(flexColumnText(renderer, index)) }

    private fun fixedRuns(renderer: JsonObject): List<JsonObject> =
        renderer.arr("fixedColumns").flatMap { elem ->
            val col = elem as? JsonObject ?: return@flatMap emptyList()
            getRuns(col.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text"))
        }

    private fun resolveVideoId(renderer: JsonObject, titleRun: JsonObject?): String? {
        renderer.obj("playlistItemData")?.str("videoId")?.let { return it }
        val playBtn = renderer.obj("overlay")
            ?.obj("musicItemThumbnailOverlayRenderer")
            ?.obj("content")
            ?.obj("musicPlayButtonRenderer")
        return watchVideoId(titleRun?.get("navigationEndpoint"))
            ?: watchVideoId(playBtn?.get("playNavigationEndpoint"))
            ?: watchVideoId(renderer["onTap"])
            ?: watchVideoId(renderer["navigationEndpoint"])
    }

    private fun watchVideoId(value: JsonElement?): String? =
        (value as? JsonObject)?.obj("watchEndpoint")?.str("videoId")

    private fun getRuns(value: JsonElement?): List<JsonObject> =
        (value as? JsonObject)?.arr("runs")?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun getText(value: JsonElement?): String? {
        val obj = value as? JsonObject ?: return null
        obj.str("simpleText")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val joined = getRuns(obj).mapNotNull { it.str("text") }.joinToString("").trim()
        return joined.takeIf { it.isNotEmpty() }
    }

    private fun getBrowseEndpoint(value: JsonObject): JsonObject? =
        value.obj("navigationEndpoint")?.obj("browseEndpoint")

    private fun getPageType(endpoint: JsonObject?): String? =
        endpoint?.obj("browseEndpointContextSupportedConfigs")
            ?.obj("browseEndpointContextMusicConfig")
            ?.str("pageType")

    private fun runsToArtists(runs: List<JsonObject>): List<TrackArtist> =
        runs.mapNotNull { run ->
            val endpoint = getBrowseEndpoint(run) ?: return@mapNotNull null
            val pageType = getPageType(endpoint)
            if (pageType != "MUSIC_PAGE_TYPE_ARTIST" && pageType != "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                return@mapNotNull null
            }
            val id = endpoint.str("browseId") ?: return@mapNotNull null
            val name = run.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TrackArtist(id = id, name = name)
        }

    private fun runsToAlbum(runs: List<JsonObject>): TrackAlbum? {
        val albumRun = runs.firstOrNull { getPageType(getBrowseEndpoint(it)) == "MUSIC_PAGE_TYPE_ALBUM" }
            ?: return null
        val id = getBrowseEndpoint(albumRun)?.str("browseId") ?: return null
        val title = albumRun.str("text") ?: return null
        return TrackAlbum(id = id, title = title)
    }

    private fun coverFromThumbnail(thumbnail: JsonElement?): Cover? {
        val obj = thumbnail as? JsonObject ?: return null
        val inner = obj.obj("musicThumbnailRenderer")?.obj("thumbnail") ?: obj
        val entries = inner.arr("thumbnails").mapNotNull { it as? JsonObject }.mapNotNull { entry ->
            val url = entry.str("url") ?: return@mapNotNull null
            val width = entry.int("width") ?: 0
            val height = entry.int("height") ?: 0
            Triple(url, width, height)
        }
        val best = entries.maxByOrNull { maxOf(it.second, it.third) } ?: return null
        val url = if (ARTWORK_HOST_REGEX.containsMatchIn(best.first)) {
            toSquareArtworkUrl(best.first, 512)
        } else {
            best.first
        }
        return Cover(url = url, width = if (best.second > 0) best.second else 512, height = if (best.third > 0) best.third else 512)
    }

    private fun toSquareArtworkUrl(url: String, size: Int): String {
        val suffix = "=w$size-h$size-l90-rj"
        val whRegex = Regex("=w\\d+-h\\d+(?:-[a-zA-Z0-9_-]+)*")
        val sRegex = Regex("=s\\d+(?:-[a-zA-Z0-9_-]+)*")
        return when {
            whRegex.containsMatchIn(url) -> whRegex.replace(url, suffix)
            sRegex.containsMatchIn(url) -> sRegex.replace(url, suffix)
            else -> "$url$suffix"
        }
    }

    private fun hasExplicitBadge(element: JsonElement?): Boolean = when (element) {
        is JsonArray -> element.any { hasExplicitBadge(it) }
        is JsonObject -> {
            val iconType = element.obj("musicInlineBadgeRenderer")?.obj("icon")?.str("iconType")
            iconType == "MUSIC_EXPLICIT_BADGE" || element.values.any { hasExplicitBadge(it) }
        }
        else -> false
    }

    private fun parseDurationMs(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size !in 2..3 || parts.any { !it.matches(Regex("^\\d{1,3}$")) }) return null
        val nums = parts.map { it.toLong() }
        val seconds = nums.last()
        val minutes = nums[nums.size - 2]
        val hours = if (parts.size == 3) nums[0] else 0L
        if (seconds > 59 || (parts.size == 3 && minutes > 59)) return null
        val total = hours * 3600L + minutes * 60L + seconds
        return if (total > 0) total * 1000L else null
    }

    private fun durationFromRuns(runs: List<JsonObject>): Long =
        runs.firstNotNullOfOrNull { r -> r.str("text")?.let(::parseDurationMs) } ?: 0L

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
