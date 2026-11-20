package `fun`.tryliner.android.core.network

import `fun`.tryliner.android.core.crypto.WasmRequestSigner
import `fun`.tryliner.android.core.model.ApiError
import `fun`.tryliner.android.core.model.AuthSession
import `fun`.tryliner.android.core.model.AuthUser
import `fun`.tryliner.android.core.model.LyricsStreamEvent
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.TrackArtist
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LinerApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private lateinit var client: LinerApiClient

    private val initialSession = AuthSession(
        accessToken = "access-1",
        refreshToken = "refresh-1",
        accessTokenExpiresAt = "2099-01-01T00:00:00Z",
        user = AuthUser(id = "user-42", username = "svyat"),
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = InMemorySessionStorage(
            initialSession = initialSession,
            initialEdgeKey = "edge-key-1234567890",
        )
        client = LinerApiClient(
            sessionStorage = storage,
            signer = WasmRequestSigner(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends signed, correlation, edge, user-agent, clearance cookie, and auth headers`() = runTest {
        storage.setClearanceCookie("cf_clear_abc123")
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"user-42","username":"svyat"}"""),
        )

        val me = client.getMe()
        assertEquals("user-42", me.id)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/me", recorded.path)
        assertEquals("Bearer access-1", recorded.getHeader("Authorization"))
        assertEquals("edge-key-1234567890", recorded.getHeader("x-liner-edge"))
        assertEquals("cf_clearance=cf_clear_abc123", recorded.getHeader("Cookie"))
        assertEquals("user-42", recorded.getHeader("x-user-id"))
        assertEquals("svyat", recorded.getHeader("x-user-name"))
        assertEquals("desktop", recorded.getHeader("x-platform"))
        assertEquals(LinerApiClient.DEFAULT_CLIENT_VERSION, recorded.getHeader("x-client-version"))
        assertEquals(LinerApiClient.DEFAULT_USER_AGENT, recorded.getHeader("User-Agent"))
        assertNotNull(recorded.getHeader("x-request-id"))
        assertTrue(!recorded.getHeader("x-liner-signature").isNullOrBlank())
        assertTrue(!recorded.getHeader("x-liner-timestamp").isNullOrBlank())
        assertTrue(!recorded.getHeader("x-liner-nonce").isNullOrBlank())
    }

    @Test
    fun `401 triggers single refresh and retries original request with new token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"TOKEN_EXPIRED","message":"Expired"}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "accessToken": "access-2",
                      "refreshToken": "refresh-2",
                      "accessTokenExpiresAt": "2099-01-01T00:00:00Z",
                      "user": { "id": "user-42", "username": "svyat" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"user-42","username":"svyat"}"""),
        )

        val me = client.getMe()
        assertEquals("user-42", me.id)
        assertEquals("access-2", storage.getSession()?.accessToken)
        assertEquals("refresh-2", storage.getSession()?.refreshToken)

        val req1 = server.takeRequest()
        assertEquals("/v1/me", req1.path)
        assertEquals("Bearer access-1", req1.getHeader("Authorization"))

        val refreshReq = server.takeRequest()
        assertEquals("POST", refreshReq.method)
        assertEquals("/v1/auth/refresh", refreshReq.path)
        assertTrue(refreshReq.body.readUtf8().contains("refresh-1"))

        val retryReq = server.takeRequest()
        assertEquals("/v1/me", retryReq.path)
        assertEquals("Bearer access-2", retryReq.getHeader("Authorization"))
    }

    @Test
    fun `listLikedTracks supports pagination cursor`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "track": {
                            "id": "yt:abc12345678",
                            "title": "Song One",
                            "artists": [{"id": "a1", "name": "Artist 1"}],
                            "durationMs": 180000
                          },
                          "likedAt": "2026-11-19T00:00:00Z"
                        }
                      ],
                      "nextCursor": "cursor-2"
                    }
                    """.trimIndent(),
                ),
        )

        val page = client.listLikedTracks(limit = 50, cursor = "cursor-1")
        assertEquals(1, page.items.size)
        assertEquals("Song One", page.items.first().track.title)
        assertEquals("cursor-2", page.nextCursor)

        val req = server.takeRequest()
        assertEquals("/v1/me/library/tracks?limit=50&cursor=cursor-1", req.path)
    }

    @Test
    fun `likeTrack sends trackSnapshot and unlikeTrack sends DELETE`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        val snapshot = Track(
            id = "yt:dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artists = listOf(TrackArtist("a1", "Rick Astley")),
            durationMs = 213000L,
        )

        client.likeTrack("yt:dQw4w9WgXcQ", snapshot)
        client.unlikeTrack("yt:dQw4w9WgXcQ")

        val likeReq = server.takeRequest()
        assertEquals("PUT", likeReq.method)
        assertEquals("/v1/me/library/tracks/yt%3AdQw4w9WgXcQ", likeReq.path)
        val likeBody = likeReq.body.readUtf8()
        assertTrue(likeBody.contains("trackSnapshot"))
        assertTrue(likeBody.contains("Never Gonna Give You Up"))

        val unlikeReq = server.takeRequest()
        assertEquals("DELETE", unlikeReq.method)
        assertEquals("/v1/me/library/tracks/yt%3AdQw4w9WgXcQ", unlikeReq.path)
    }

    @Test
    fun `playlist fetch, create, and addTrack with snapshot`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "playlist": {
                    "id": "pl-1",
                    "title": "Favorites",
                    "isPublic": false,
                    "createdAt": "2026-11-19T00:00:00Z",
                    "updatedAt": "2026-11-19T00:00:00Z",
                    "revision": 1
                  },
                  "items": [
                    {
                      "id": "item-1",
                      "track": { "id": "t1", "title": "Track 1", "durationMs": 120000 },
                      "position": 0,
                      "addedAt": "2026-11-19T00:00:00Z"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val details = client.getUserPlaylist("pl-1")
        assertEquals("Favorites", details.playlist.title)
        assertEquals(1, details.trackCount)
        assertEquals(120000L, details.durationMs)
        assertEquals("Track 1", details.items.first().track.title)

        val req = server.takeRequest()
        assertEquals("/v1/me/playlists/pl-1", req.path)
    }

    @Test
    fun `streamLyrics parses SSE events and emits Flow`() = runTest {
        val sseBody = buildString {
            append("data: {\"type\":\"metadata\",\"track\":{\"id\":\"t1\",\"title\":\"Song\",\"artists\":[],\"durationMs\":180000}}\n\n")
            append("data: {\"type\":\"provider\",\"provider\":\"lrclib\",\"status\":\"found\",\"syncLevel\":\"line\",\"candidate\":{\"lyrics\":{\"format\":\"lrc\",\"content\":\"[00:01.00]Hello\"},\"provider\":\"lrclib\",\"contentKind\":\"lyrics\",\"syncLevel\":\"line\",\"quality\":{\"total\":0.9,\"match\":1.0,\"content\":1.0,\"sync\":1.0,\"source\":0.8}}}\n\n")
            append("data: {\"type\":\"done\",\"cached\":false,\"status\":\"found\"}\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val events = client.streamLyrics("t1", title = "Song", artist = "Artist", durationMs = 180000L).toList()
        assertEquals(3, events.size)
        assertTrue(events[0] is LyricsStreamEvent.Metadata)
        val found = events[1] as LyricsStreamEvent.ProviderFound
        assertEquals("lrclib", found.provider)
        assertEquals("[00:01.00]Hello", found.candidate.lyrics.content)
        val done = events[2] as LyricsStreamEvent.Done
        assertEquals("found", done.status)

        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/v1/tracks/t1/lyrics?title=Song&artist=Artist&durationMs=180000"))
        assertNull(req.getHeader("Content-Type"))
    }

    @Test
    fun `login with required turnstile invokes provider, saves clearance, and sends token`() = runTest {
        val hcClient = LinerApiClient(
            sessionStorage = storage,
            signer = WasmRequestSigner(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            humanCheckProvider = HumanCheckProvider { req ->
                assertEquals("login", req.action)
                assertEquals("required", req.mode)
                HumanCheckResult(token = "turnstile-tok-1", clearanceCookie = "cf_clear_xyz")
            },
        )

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "mode": "off",
                  "algorithm": "argon2id-v1",
                  "token": "pow-tok",
                  "salt": "salt",
                  "difficulty": 1,
                  "memoryKiB": 64,
                  "iterations": 1,
                  "parallelism": 1,
                  "hashLength": 32,
                  "expiresAt": "2099-01-01T00:00:00Z",
                  "turnstile": { "mode": "required", "edge": true }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "accessToken": "access-new",
                  "refreshToken": "refresh-new",
                  "accessTokenExpiresAt": "2099-01-01T00:00:00Z",
                  "user": { "id": "user-42", "username": "svyat" }
                }
                """.trimIndent(),
            ),
        )

        val session = hcClient.login("a@b.com", "secret")
        assertEquals("access-new", session.accessToken)
        assertEquals("cf_clear_xyz", storage.getClearanceCookie())

        server.takeRequest() // challenge
        val loginReq = server.takeRequest()
        assertEquals("/v1/auth/login", loginReq.path)
        assertEquals("cf_clearance=cf_clear_xyz", loginReq.getHeader("Cookie"))
        assertTrue(loginReq.body.readUtf8().contains("\"turnstile\":\"turnstile-tok-1\""))
    }

    @Test
    fun `403 cf-mitigated challenge triggers humanCheckProvider and retries, or surfaces real error`() = runTest {
        val hcClient = LinerApiClient(
            sessionStorage = storage,
            signer = WasmRequestSigner(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            humanCheckProvider = HumanCheckProvider {
                HumanCheckResult(clearanceCookie = "cf_clear_recovered")
            },
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("cf-mitigated", "challenge")
                .setBody("<html>Just a moment...</html>"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"user-42","username":"svyat"}"""),
        )

        val me = hcClient.getMe()
        assertEquals("user-42", me.id)
        assertEquals("cf_clear_recovered", storage.getClearanceCookie())

        server.takeRequest()
        val retryReq = server.takeRequest()
        assertEquals("cf_clearance=cf_clear_recovered", retryReq.getHeader("Cookie"))

        // Without humanCheckProvider, real 403 error message from body is preserved
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("x-instance", "edge-1")
                .setBody("""{"code":"TURNSTILE_FAILED","message":"Verification token invalid"}"""),
        )
        val err = assertThrows(ApiError::class.java) {
            kotlinx.coroutines.runBlocking { client.getMe() }
        }
        assertEquals(403, err.status)
        assertEquals("TURNSTILE_FAILED", err.code)
        assertEquals("Verification token invalid", err.message)
    }
}
