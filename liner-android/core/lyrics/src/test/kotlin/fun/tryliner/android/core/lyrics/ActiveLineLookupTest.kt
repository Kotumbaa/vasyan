package `fun`.tryliner.android.core.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ActiveLineLookupTest {

    private val sampleLyrics = SyncedLyrics(
        kind = LyricsKind.WORD_SYNCED,
        lines = listOf(
            LyricLine(
                startMs = 1_000L,
                durationMs = 2_000L,
                text = "First line",
                words = listOf(
                    LyricWord(1_000L, 1_500L, "First "),
                    LyricWord(1_500L, 3_000L, "line"),
                ),
            ),
            LyricLine(startMs = 3_000L, durationMs = 2_000L, text = "Second line"),
            LyricLine(startMs = 3_000L, durationMs = 1_000L, text = "Duplicate timestamp"),
            LyricLine(startMs = 10_000L, durationMs = 4_000L, text = "Last line"),
        ),
    )

    @Test
    fun `returns -1 when empty, plain, negative, or before first line`() {
        assertEquals(-1, SyncedLyrics.empty().findActiveLineIndex(1_000L))
        assertEquals(-1, sampleLyrics.findActiveLineIndex(-5L))
        assertEquals(-1, sampleLyrics.findActiveLineIndex(999L))

        val plain = LyricsParser.parse("Line one\nLine two", LyricsFormat.PLAIN)
        assertEquals(-1, plain.findActiveLineIndex(0L))
        assertEquals(-1, plain.findActiveLineIndex(5_000L))
    }

    @Test
    fun `finds active line via binary search at boundaries and midpoints`() {
        assertEquals(0, sampleLyrics.findActiveLineIndex(1_000L))
        assertEquals(0, sampleLyrics.findActiveLineIndex(2_999L))
        assertEquals(2, sampleLyrics.findActiveLineIndex(3_000L))
        assertEquals(2, sampleLyrics.findActiveLineIndex(9_999L))
        assertEquals(3, sampleLyrics.findActiveLineIndex(10_000L))
        assertEquals(3, sampleLyrics.findActiveLineIndex(99_999L))
    }

    @Test
    fun `finds active word via binary search`() {
        val line = sampleLyrics.lines[0]
        assertEquals(-1, line.findActiveWordIndex(999L))
        assertEquals(0, line.findActiveWordIndex(1_000L))
        assertEquals(0, line.findActiveWordIndex(1_499L))
        assertEquals(1, line.findActiveWordIndex(1_500L))
        assertEquals(1, line.findActiveWordIndex(5_000L))
    }
}
