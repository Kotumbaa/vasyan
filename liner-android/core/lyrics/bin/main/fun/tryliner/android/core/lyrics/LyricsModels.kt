package `fun`.tryliner.android.core.lyrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LyricsFormat {
    @SerialName("plain")
    PLAIN,

    @SerialName("lrc")
    LRC,

    @SerialName("ttml")
    TTML,

    @SerialName("qrc")
    QRC,

    @SerialName("richsync")
    RICHSYNC;

    companion object {
        fun fromString(value: String?): LyricsFormat? = when (value?.trim()?.lowercase()) {
            "plain" -> PLAIN
            "lrc" -> LRC
            "ttml" -> TTML
            "qrc" -> QRC
            "richsync" -> RICHSYNC
            else -> null
        }
    }
}

@Serializable
enum class LyricsKind {
    @SerialName("plain")
    PLAIN,

    @SerialName("line_synced")
    LINE_SYNCED,

    @SerialName("word_synced")
    WORD_SYNCED,

    @SerialName("syllable_synced")
    SYLLABLE_SYNCED,

    @SerialName("instrumental")
    INSTRUMENTAL;

    companion object {
        fun fromString(value: String?): LyricsKind? = when (value?.trim()?.lowercase()) {
            "plain" -> PLAIN
            "line_synced" -> LINE_SYNCED
            "word_synced" -> WORD_SYNCED
            "syllable_synced" -> SYLLABLE_SYNCED
            "instrumental" -> INSTRUMENTAL
            else -> null
        }
    }
}

@Serializable
enum class LyricsSyncLevel {
    @SerialName("plain")
    PLAIN,

    @SerialName("line_level")
    LINE_LEVEL,

    @SerialName("word_level")
    WORD_LEVEL,

    @SerialName("syllable_level")
    SYLLABLE_LEVEL;
}

/**
 * A single timed word or syllable token inside a [LyricLine].
 */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isBackground: Boolean = false,
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)

    /**
     * Returns highlight progress in `[0f, 1f]` at [positionMs].
     */
    fun progressAt(positionMs: Long): Float {
        if (endMs <= startMs) {
            return if (positionMs >= startMs) 1f else 0f
        }
        if (positionMs <= startMs) return 0f
        if (positionMs >= endMs) return 1f
        return ((positionMs - startMs).toDouble() / (endMs - startMs).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun shifted(offsetMs: Long): LyricWord = copy(
        startMs = (startMs + offsetMs).coerceAtLeast(0L),
        endMs = (endMs + offsetMs).coerceAtLeast(0L),
    )
}

/**
 * A single line of lyrics, optionally carrying word-level timings in [words].
 */
data class LyricLine(
    val startMs: Long,
    val durationMs: Long = 0L,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val isInstrumental: Boolean = false,
    val isBackground: Boolean = false,
    val subtitle: String? = null,
) {
    val endMs: Long
        get() = startMs + durationMs.coerceAtLeast(0L)

    /**
     * Binary-searches [words] for the active word index at [positionMs],
     * or returns `-1` if [words] is empty or [positionMs] is before the first word.
     */
    fun findActiveWordIndex(positionMs: Long): Int {
        if (words.isEmpty() || positionMs < words.first().startMs) return -1
        var low = 0
        var high = words.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (words[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    fun shifted(offsetMs: Long): LyricLine = copy(
        startMs = (startMs + offsetMs).coerceAtLeast(0L),
        words = words.map { it.shifted(offsetMs) },
    )
}

/**
 * Parsed lyrics document with optional line/word synchronization and helper lookups.
 */
data class SyncedLyrics(
    val lines: List<LyricLine>,
    val kind: LyricsKind,
    val plainText: String? = null,
    val isInstrumental: Boolean = false,
    val provider: String? = null,
    val offsetMs: Long = 0L,
) {
    val isSynced: Boolean
        get() = kind != LyricsKind.PLAIN &&
            kind != LyricsKind.INSTRUMENTAL &&
            lines.any { it.startMs > 0L || it.durationMs > 0L || it.words.isNotEmpty() }

    /**
     * Binary-searches [lines] for the active line index at [positionMs].
     * Returns `-1` if lyrics are not synced, [lines] is empty, or [positionMs] precedes the first line.
     */
    fun findActiveLineIndex(positionMs: Long): Int {
        if (!isSynced || lines.isEmpty() || positionMs < 0L) return -1
        if (positionMs < lines.first().startMs) return -1

        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /**
     * Returns a copy with all line and word timestamps shifted by [deltaMs] (clamped to >= 0).
     */
    fun withOffset(deltaMs: Long): SyncedLyrics {
        if (deltaMs == 0L) return this
        return copy(
            lines = lines.map { it.shifted(deltaMs) },
            offsetMs = offsetMs + deltaMs,
        )
    }

    companion object {
        fun empty(isInstrumental: Boolean = false): SyncedLyrics = SyncedLyrics(
            lines = emptyList(),
            kind = if (isInstrumental) LyricsKind.INSTRUMENTAL else LyricsKind.PLAIN,
            plainText = null,
            isInstrumental = isInstrumental,
        )
    }
}
