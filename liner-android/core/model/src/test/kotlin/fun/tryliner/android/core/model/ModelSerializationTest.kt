package `fun`.tryliner.android.core.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes Track with Cover and viewer`() {
        val raw = """
            {
              "id": "trk_1",
              "title": "Midnight City",
              "artists": [{"id": "art_1", "name": "M83"}],
              "album": {"id": "alb_1", "title": "Hurry Up, We're Dreaming"},
              "durationMs": 243000,
              "cover": {"url": "https://cdn.example/c.jpg", "width": 640, "height": 640},
              "explicit": false,
              "type": "track",
              "viewer": {"liked": true}
            }
        """.trimIndent()

        val track = json.decodeFromString<Track>(raw)
        assertEquals("trk_1", track.id)
        assertEquals("Midnight City", track.title)
        assertEquals("M83", track.artistNames)
        assertEquals(243000L, track.durationMs)
        assertTrue(track.isLiked)
        assertEquals(640, track.cover?.width)
    }

    @Test
    fun `deserializes polymorphic SearchResponse`() {
        val raw = """
            {
              "items": [
                {"type": "track", "id": "t1", "title": "Song", "artists": [{"id": "a1", "name": "Artist"}], "durationMs": 180000},
                {"type": "artist", "id": "a1", "name": "Artist"},
                {"type": "album", "id": "al1", "title": "Album", "artists": [{"id": "a1", "name": "Artist"}], "year": 2024},
                {"type": "playlist", "id": "p1", "title": "Playlist", "author": "Liner"}
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<SearchResponse>(raw)
        assertEquals(4, response.items.size)
        val trackItem = assertInstanceOf(SearchResult.TrackItem::class.java, response.items[0])
        assertEquals("Song", trackItem.toTrack().title)
        assertInstanceOf(SearchResult.ArtistItem::class.java, response.items[1])
        val albumItem = assertInstanceOf(SearchResult.AlbumItem::class.java, response.items[2])
        assertEquals(2024, albumItem.year)
        val playlistItem = assertInstanceOf(SearchResult.PlaylistItem::class.java, response.items[3])
        assertEquals("Liner", playlistItem.author)
    }

    @Test
    fun `deserializes AuthSession and LikedTracksPage`() {
        val sessionRaw = """
            {
              "accessToken": "acc_123",
              "refreshToken": "ref_456",
              "accessTokenExpiresAt": "2026-12-01T00:00:00Z",
              "user": {
                "id": "usr_1",
                "username": "svyat",
                "displayName": "Svyat"
              }
            }
        """.trimIndent()
        val session = json.decodeFromString<AuthSession>(sessionRaw)
        assertEquals("acc_123", session.accessToken)
        assertEquals("usr_1", session.user.id)

        val likedRaw = """
            {
              "items": [
                {
                  "likedAt": "2026-11-19T10:00:00Z",
                  "track": {"id": "t1", "title": "Track 1", "artists": [], "durationMs": 1000}
                }
              ],
              "nextCursor": null
            }
        """.trimIndent()
        val page = json.decodeFromString<LikedTracksPage>(likedRaw)
        assertEquals(1, page.items.size)
        assertNull(page.nextCursor)
        assertFalse(page.items[0].track.isLiked)
    }

    @Test
    fun `deserializes LyricsStreamEvent variants`() {
        val metadata = json.decodeFromString<LyricsStreamEvent>(
            """{"type":"metadata","track":{"id":"t1","title":"Song","artists":[{"id":"a1","name":"A"}],"durationMs":120000}}""",
        )
        assertInstanceOf(LyricsStreamEvent.Metadata::class.java, metadata)

        val found = json.decodeFromString<LyricsStreamEvent>(
            """
            {
              "type": "provider",
              "provider": "lrclib",
              "status": "found",
              "syncLevel": "line_level",
              "candidate": {
                "lyrics": {"format": "lrc", "content": "[00:01.00] Hello"},
                "provider": "lrclib",
                "contentKind": "line_synced",
                "syncLevel": "line_level",
                "quality": {"total": 90, "match": 95, "content": 85, "sync": 90, "source": 80, "reasons": []}
              }
            }
            """.trimIndent(),
        )
        assertInstanceOf(LyricsStreamEvent.ProviderFound::class.java, found)

        val notFound = json.decodeFromString<LyricsStreamEvent>(
            """{"type":"provider","provider":"genius","status":"not_found","instrumental":true}""",
        )
        val nf = assertInstanceOf(LyricsStreamEvent.ProviderNotFound::class.java, notFound)
        assertEquals(true, nf.instrumental)

        val error = json.decodeFromString<LyricsStreamEvent>(
            """{"type":"provider","provider":"musixmatch","status":"error","message":"timeout"}""",
        )
        assertInstanceOf(LyricsStreamEvent.ProviderError::class.java, error)

        val done = json.decodeFromString<LyricsStreamEvent>(
            """{"type":"done","cached":true,"best":{"provider":"lrclib","quality":90,"syncLevel":"line_level"}}""",
        )
        val d = assertInstanceOf(LyricsStreamEvent.Done::class.java, done)
        assertTrue(d.cached)
    }

    @Test
    fun `deserializes PowChallenge and creates ApiError`() {
        val challenge = json.decodeFromString<PowChallenge>(
            """
            {
              "mode": "required",
              "algorithm": "argon2id-v1",
              "token": "tok_abc",
              "salt": "AQIDBA==",
              "difficulty": 8,
              "memoryKiB": 1024,
              "iterations": 1,
              "parallelism": 1,
              "hashLength": 32,
              "expiresAt": "2026-11-19T23:00:00Z"
            }
            """.trimIndent(),
        )
        assertEquals("argon2id-v1", challenge.algorithm)
        assertEquals(8, challenge.difficulty)

        val err = ApiError(status = 401, message = "Unauthorized", code = "TOKEN_EXPIRED", requestId = "req-1")
        assertEquals(401, err.status)
        assertEquals("TOKEN_EXPIRED", err.code)
        assertEquals("req-1", err.requestId)
    }
}
