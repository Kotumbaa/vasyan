package `fun`.tryliner.android.core.lyrics

import `fun`.tryliner.android.core.model.LyricsStreamEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SseEventParserTest {

    @Test
    fun `parses incremental chunked SSE frames across arbitrary boundaries`() {
        val parser = SseEventParser()

        val stream = buildString {
            append(": keepalive\r\n\r\n")
            append("event: message\r\n")
            append("id: 1\r\n")
            append("data: {\"type\":\"metadata\",\"track\":{\"id\":\"t1\",\"title\":\"Song\",\"artists\":[{\"id\":\"a1\",\"name\":\"Artist\"}],\"durationMs\":180000}}\r\n\r\n")
            append("data: {\"type\":\"provider\",\"provider\":\"musixmatch\",\"status\":\"found\",")
            append("\n")
            append("data: \"syncLevel\":\"word_level\",\"candidate\":{\"provider\":\"musixmatch\",\"contentKind\":\"word_synced\",\"syncLevel\":\"word_level\",\"quality\":{\"total\":95},\"lyrics\":{\"format\":\"lrc\",\"content\":\"[00:01.00]Hi\"}}}\n\n")
            append("data: {\"type\":\"done\",\"cached\":false,\"best\":{\"provider\":\"musixmatch\",\"quality\":95,\"syncLevel\":\"word_level\"}}")
        }

        // Feed in tiny 7-char chunks to stress-test \r\n, \n\n, and JSON splitting.
        val frames = mutableListOf<SseFrame>()
        var idx = 0
        while (idx < stream.length) {
            val end = (idx + 7).coerceAtMost(stream.length)
            frames.addAll(parser.feed(stream.substring(idx, end)))
            idx = end
        }
        // The final "done" frame has no trailing \n\n until flush().
        assertEquals(2, frames.size)
        frames.addAll(parser.flush())
        assertEquals(3, frames.size)

        assertEquals("message", frames[0].event)
        assertEquals("1", frames[0].id)

        val events = frames.mapNotNull { SseEventParser.decodeLyricsEvent(it) }
        assertEquals(3, events.size)

        val meta = events[0] as LyricsStreamEvent.Metadata
        assertEquals("t1", meta.track.id)
        assertEquals("Song", meta.track.title)
        assertEquals(180_000L, meta.track.durationMs)

        val found = events[1] as LyricsStreamEvent.ProviderFound
        assertEquals("musixmatch", found.provider)
        assertEquals("word_level", found.syncLevel)
        assertEquals("lrc", found.candidate.lyrics.format)
        assertEquals("[00:01.00]Hi", found.candidate.lyrics.content)

        val done = events[2] as LyricsStreamEvent.Done
        assertFalse(done.cached)
        assertEquals("musixmatch", done.best?.provider)
    }

    @Test
    fun `decodes provider not_found, error, and instrumental done events and ignores malformed JSON`() {
        val parser = SseEventParser()
        val payload = """
            data: {not valid json}

            data: {"type":"unknown_event"}

            data: {"type":"provider","provider":"lrclib","status":"not_found","instrumental":true,"durationMs":42}

            data: {"type":"provider","provider":"genius","status":"error","message":"Rate limited"}

            data: {"type":"done","cached":true,"status":"not_found","instrumental":true}

        """.trimIndent() + "\n"

        val events = parser.feedLyricsEvents(payload) + parser.flushLyricsEvents()
        assertEquals(3, events.size)

        val notFound = events[0] as LyricsStreamEvent.ProviderNotFound
        assertEquals("lrclib", notFound.provider)
        assertTrue(notFound.instrumental)
        assertEquals(42L, notFound.durationMs)

        val err = events[1] as LyricsStreamEvent.ProviderError
        assertEquals("genius", err.provider)
        assertEquals("Rate limited", err.message)

        val done = events[2] as LyricsStreamEvent.Done
        assertTrue(done.cached)
        assertEquals("not_found", done.status)
        assertTrue(done.instrumental)
    }
}
