package `fun`.tryliner.android.core.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LyricsParserTest {

    @Test
    fun `parses standard LRC with metadata and sorted timestamps`() {
        val lrc = """
            [ti:Test Song]
            [ar:Test Artist]
            [al:Test Album]
            [00:12.34]First line
            [00:05:25]Intro line
            [01:05.500]Third line
        """.trimIndent()

        val parsed = LyricsParser.parse(lrc, LyricsFormat.LRC, songDurationMs = 90_000L)

        assertEquals(LyricsKind.LINE_SYNCED, parsed.kind)
        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)

        assertEquals(5_250L, parsed.lines[0].startMs)
        assertEquals("Intro line", parsed.lines[0].text)
        assertEquals(7_090L, parsed.lines[0].durationMs)

        assertEquals(12_340L, parsed.lines[1].startMs)
        assertEquals("First line", parsed.lines[1].text)
        assertEquals(53_160L, parsed.lines[1].durationMs)

        assertEquals(65_500L, parsed.lines[2].startMs)
        assertEquals("Third line", parsed.lines[2].text)
        assertEquals(24_500L, parsed.lines[2].durationMs)
    }

    @Test
    fun `expands multi-timestamp LRC lines in chronological order`() {
        val lrc = """
            [00:10.00][01:10.00]Chorus line
            [00:30.00]Verse line
        """.trimIndent()

        val parsed = LyricsParser.parse(lrc)

        assertEquals(3, parsed.lines.size)
        assertEquals(listOf(10_000L, 30_000L, 70_000L), parsed.lines.map { it.startMs })
        assertEquals(listOf("Chorus line", "Verse line", "Chorus line"), parsed.lines.map { it.text })
    }

    @Test
    fun `parses enhanced LRC word-synced timestamps`() {
        val elrc = """
            [00:12.00]<00:12.00>Hello <00:12.50>world <00:13.20>today
            [00:15.00]<00:15.00>Next <00:15.40>line
        """.trimIndent()

        val parsed = LyricsParser.parse(elrc, songDurationMs = 20_000L)

        assertEquals(LyricsKind.WORD_SYNCED, parsed.kind)
        assertEquals(2, parsed.lines.size)

        val first = parsed.lines[0]
        assertEquals(12_000L, first.startMs)
        assertEquals("Hello world today", first.text)
        assertEquals(3, first.words.size)
        assertEquals(LyricWord(12_000L, 12_500L, "Hello "), first.words[0])
        assertEquals(LyricWord(12_500L, 13_200L, "world "), first.words[1])
        assertEquals(LyricWord(13_200L, 15_000L, "today"), first.words[2])

        assertEquals(0f, first.words[0].progressAt(11_999L))
        assertEquals(0.5f, first.words[0].progressAt(12_250L), 0.001f)
        assertEquals(1f, first.words[0].progressAt(12_500L))
    }

    @Test
    fun `parses Musixmatch richsync JSON and merges whitespace tokens`() {
        val richsync = """
            [
              {
                "ts": 1.5,
                "te": 3.5,
                "l": [
                  { "c": "Hello", "o": 0.0 },
                  { "c": " ", "o": 0.4 },
                  { "c": "world", "o": 0.5 }
                ]
              }
            ]
        """.trimIndent()

        val parsed = LyricsParser.parse(richsync, LyricsFormat.RICHSYNC)

        assertEquals(LyricsKind.WORD_SYNCED, parsed.kind)
        assertEquals(1, parsed.lines.size)
        val line = parsed.lines[0]
        assertEquals(1_500L, line.startMs)
        assertEquals(2_000L, line.durationMs)
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words.size)
        assertEquals(LyricWord(1_500L, 2_000L, "Hello "), line.words[0])
        assertEquals(LyricWord(2_000L, 3_500L, "world"), line.words[1])
    }

    @Test
    fun `parses Musixmatch subtitles JSON`() {
        val mxm = """
            [
              { "text": "Line one", "time": { "total": 2.25 } },
              { "text": "   ", "time": { "total": 3.0 } },
              { "text": "Line two", "time": { "total": 5.5 } }
            ]
        """.trimIndent()

        val parsed = LyricsParser.parse(mxm)

        assertEquals(LyricsKind.LINE_SYNCED, parsed.kind)
        assertEquals(2, parsed.lines.size)
        assertEquals(2_250L, parsed.lines[0].startMs)
        assertEquals("Line one", parsed.lines[0].text)
        assertEquals(3_250L, parsed.lines[0].durationMs)
        assertEquals(5_500L, parsed.lines[1].startMs)
    }

    @Test
    fun `parses untimed and synced TTML`() {
        val untimedTtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xml:lang="en">
              <head><metadata><translations><p>Translated line</p></translations></metadata></head>
              <body>
                <div>
                  <p>Parallelograms</p>
                  <p></p>
                  <p>Rhomboids, <span>cylinders</span> &amp; spheres</p>
                  <p>Line one<br/>Line two</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val untimed = LyricsParser.parse(untimedTtml, LyricsFormat.TTML)
        assertEquals(LyricsKind.PLAIN, untimed.kind)
        assertFalse(untimed.isSynced)
        assertEquals(
            listOf("Parallelograms", "Rhomboids, cylinders & spheres", "Line one", "Line two"),
            untimed.lines.map { it.text },
        )

        val syncedTtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:01.000" end="00:03.500">First line</p>
                  <p begin="00:04.000" end="00:06.000">Second line</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val synced = LyricsParser.parse(syncedTtml, LyricsFormat.TTML)
        assertEquals(LyricsKind.LINE_SYNCED, synced.kind)
        assertTrue(synced.isSynced)
        assertEquals(1_000L, synced.lines[0].startMs)
        assertEquals(2_500L, synced.lines[0].durationMs)
        assertEquals(4_000L, synced.lines[1].startMs)
        assertEquals(2_000L, synced.lines[1].durationMs)
    }

    @Test
    fun `handles malformed LRC lines and empty input gracefully`() {
        val empty = LyricsParser.parse("   \n  ")
        assertTrue(empty.lines.isEmpty())
        assertNull(empty.plainText)
        assertFalse(empty.isSynced)

        val mixedLrc = """
            [99:99.abc]Bad timestamp
            [00:99.00]Invalid seconds
            [00:12.34Unclosed bracket
            [00:15.00]Valid line
            Random untimed noise
        """.trimIndent()

        val parsed = LyricsParser.parse(mixedLrc, LyricsFormat.LRC)
        assertEquals(LyricsKind.LINE_SYNCED, parsed.kind)
        assertEquals(1, parsed.lines.size)
        assertEquals(15_000L, parsed.lines[0].startMs)
        assertEquals("Valid line", parsed.lines[0].text)

        val allMalformed = """
            [bad:tag]Not a real timestamp
            Just plain text
        """.trimIndent()
        val fallback = LyricsParser.parse(allMalformed)
        assertEquals(LyricsKind.PLAIN, fallback.kind)
        assertFalse(fallback.isSynced)
        assertEquals(2, fallback.lines.size)
    }

    @Test
    fun `applies offset and clamps negative timestamps to zero`() {
        val parsed = LyricsParser.parse("[00:01.00]Early\n[00:05.00]Later")
        val shiftedBack = parsed.withOffset(-2_000L)

        assertEquals(-2_000L, shiftedBack.offsetMs)
        assertEquals(0L, shiftedBack.lines[0].startMs)
        assertEquals(3_000L, shiftedBack.lines[1].startMs)
    }
}
