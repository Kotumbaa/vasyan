package `fun`.tryliner.android.core.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

object LyricsParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Matches [mm:ss], [mm:ss.xx], [mm:ss.xxx], [mm:ss:xx], [hh:mm:ss.xxx]
    private val LRC_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[:.](\d{1,3}))?(?::(\d{2})(?:[:.](\d{1,3}))?)?]""")
    private val LRC_LEADING_TAG_REGEX = Regex("""\[([^\]]+)]""")
    private val LRC_METADATA_REGEX = Regex("""^\[(ti|ar|al|au|by|length|offset|re|ve|tool|#):(.*)]$""", RegexOption.IGNORE_CASE)
    private val ELRC_WORD_TAG_REGEX = Regex("""<(\d{1,3}):(\d{2})(?:[:.](\d{1,3}))?(?::(\d{2})(?:[:.](\d{1,3}))?)?>""")

    private val TTML_ROOT_REGEX = Regex("""<tt\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TTML_BODY_REGEX = Regex("""<body\b[^>]*>([\s\S]*?)</body>""", RegexOption.IGNORE_CASE)
    private val TTML_PARAGRAPH_REGEX = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    private val TTML_SPAN_REGEX = Regex("""<span\b([^>]*)>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
    private val TTML_ATTR_REGEX = Regex("""(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*'([^']*)'""")
    private val XML_ENTITY_REGEX = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
    )

    fun parse(
        raw: String,
        format: LyricsFormat? = null,
        songDurationMs: Long? = null,
        provider: String? = null,
    ): SyncedLyrics {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return SyncedLyrics.empty().copy(provider = provider)
        }

        if (format == LyricsFormat.PLAIN) {
            return parsePlain(trimmed, provider)
        }

        tryParseRichsync(trimmed, provider)?.let { return it }
        tryParseMxmSubtitles(trimmed, songDurationMs, provider)?.let { return it }

        if (format == LyricsFormat.TTML || TTML_ROOT_REGEX.containsMatchIn(trimmed)) {
            tryParseTtml(trimmed, songDurationMs, provider)?.let { return it }
        }

        tryParseLrc(trimmed, songDurationMs, provider)?.let { return it }

        return parsePlain(trimmed, provider)
    }

    private fun parsePlain(trimmed: String, provider: String?): SyncedLyrics {
        val lines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(startMs = 0L, durationMs = 0L, text = it) }
            .toList()
        return SyncedLyrics(
            lines = lines,
            kind = LyricsKind.PLAIN,
            plainText = lines.joinToString("\n") { it.text }.ifEmpty { null },
            provider = provider,
        )
    }

    private fun tryParseRichsync(trimmed: String, provider: String?): SyncedLyrics? {
        if (!trimmed.startsWith("[")) return null
        val array = runCatching { json.parseToJsonElement(trimmed).jsonArray }.getOrNull() ?: return null
        if (array.isEmpty()) return null
        val firstObj = array.firstOrNull() as? JsonObject ?: return null
        if (firstObj["ts"]?.jsonPrimitive?.doubleOrNull == null) return null

        val parsedLines = mutableListOf<LyricLine>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val ts = obj["ts"]?.jsonPrimitive?.doubleOrNull ?: continue
            val te = obj["te"]?.jsonPrimitive?.doubleOrNull ?: ts
            val startMs = (ts * 1000.0).roundToLong().coerceAtLeast(0L)
            val endMs = (te * 1000.0).roundToLong().coerceAtLeast(startMs)
            val durationMs = (endMs - startMs).coerceAtLeast(0L)

            val rawWords = (obj["l"] as? JsonArray).orEmpty().mapNotNull { item ->
                val wObj = item as? JsonObject ?: return@mapNotNull null
                val c = wObj["c"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val o = wObj["o"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                c to o
            }

            // Merge standalone whitespace tokens into the preceding word.
            val mergedWords = mutableListOf<Pair<String, Double>>()
            for ((tokenText, offsetSec) in rawWords) {
                if (tokenText.trim().isEmpty()) {
                    if (mergedWords.isNotEmpty()) {
                        val last = mergedWords.last()
                        mergedWords[mergedWords.lastIndex] = (last.first + tokenText) to last.second
                    }
                } else {
                    mergedWords.add(tokenText to offsetSec)
                }
            }

            val words = mergedWords.mapIndexed { index, (wordText, offsetSec) ->
                val rawOffsetMs = (offsetSec * 1000.0).roundToLong()
                val wordStart = if (rawOffsetMs >= startMs) rawOffsetMs else startMs + rawOffsetMs
                val nextWordStart = if (index + 1 < mergedWords.size) {
                    val nextRaw = (mergedWords[index + 1].second * 1000.0).roundToLong()
                    if (nextRaw >= startMs) nextRaw else startMs + nextRaw
                } else {
                    endMs
                }
                LyricWord(
                    startMs = wordStart.coerceAtLeast(0L),
                    endMs = nextWordStart.coerceAtLeast(wordStart.coerceAtLeast(0L)),
                    text = wordText,
                )
            }

            val lineText = mergedWords.joinToString("") { it.first }
            parsedLines.add(
                LyricLine(
                    startMs = startMs,
                    durationMs = durationMs,
                    text = lineText,
                    words = words,
                )
            )
        }

        if (parsedLines.isEmpty()) return null
        val sorted = parsedLines.sortedBy { it.startMs }
        val hasWords = sorted.any { it.words.isNotEmpty() }
        return SyncedLyrics(
            lines = sorted,
            kind = if (hasWords) LyricsKind.WORD_SYNCED else LyricsKind.LINE_SYNCED,
            plainText = sorted.joinToString("\n") { it.text }.ifBlank { null },
            provider = provider,
        )
    }

    private fun tryParseMxmSubtitles(
        trimmed: String,
        songDurationMs: Long?,
        provider: String?,
    ): SyncedLyrics? {
        if (!trimmed.startsWith("[")) return null
        val array = runCatching { json.parseToJsonElement(trimmed).jsonArray }.getOrNull() ?: return null
        if (array.isEmpty()) return null
        val firstObj = array.firstOrNull() as? JsonObject ?: return null
        val firstTime = firstObj["time"] as? JsonObject ?: return null
        if (firstTime["total"]?.jsonPrimitive?.doubleOrNull == null) return null

        val rawLines = mutableListOf<LyricLine>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) continue
            val totalSec = (obj["time"] as? JsonObject)
                ?.get("total")
                ?.jsonPrimitive
                ?.doubleOrNull ?: 0.0
            val startMs = (totalSec * 1000.0).roundToLong().coerceAtLeast(0L)
            rawLines.add(LyricLine(startMs = startMs, durationMs = 0L, text = text))
        }

        if (rawLines.isEmpty()) return null
        val withDurations = fillLineDurations(rawLines.sortedBy { it.startMs }, songDurationMs)
        return SyncedLyrics(
            lines = withDurations,
            kind = LyricsKind.LINE_SYNCED,
            plainText = withDurations.joinToString("\n") { it.text }.ifBlank { null },
            provider = provider,
        )
    }

    private fun tryParseTtml(
        trimmed: String,
        songDurationMs: Long?,
        provider: String?,
    ): SyncedLyrics? {
        if (!TTML_ROOT_REGEX.containsMatchIn(trimmed)) return null
        val body = TTML_BODY_REGEX.find(trimmed)?.groupValues?.get(1) ?: trimmed

        val lines = mutableListOf<LyricLine>()
        for (pMatch in TTML_PARAGRAPH_REGEX.findAll(body)) {
            val attrs = parseXmlAttributes(pMatch.groupValues[1])
            val innerHtml = pMatch.groupValues[2]

            val beginMs = attrs["begin"]?.let(::parseTtmlTimeMs)
            val endMs = attrs["end"]?.let(::parseTtmlTimeMs)
            val durMs = attrs["dur"]?.let(::parseTtmlTimeMs)

            val spanWords = mutableListOf<LyricWord>()
            val spanMatches = TTML_SPAN_REGEX.findAll(innerHtml).toList()
            if (spanMatches.isNotEmpty() && spanMatches.any {
                    val sa = parseXmlAttributes(it.groupValues[1])
                    sa.containsKey("begin")
                }
            ) {
                for (span in spanMatches) {
                    val sa = parseXmlAttributes(span.groupValues[1])
                    val wBegin = sa["begin"]?.let(::parseTtmlTimeMs) ?: continue
                    val wEnd = sa["end"]?.let(::parseTtmlTimeMs)
                        ?: sa["dur"]?.let(::parseTtmlTimeMs)?.let { wBegin + it }
                        ?: wBegin
                    val wText = cleanXmlText(span.groupValues[2])
                    if (wText.isNotEmpty()) {
                        spanWords.add(
                            LyricWord(
                                startMs = wBegin,
                                endMs = wEnd.coerceAtLeast(wBegin),
                                text = wText,
                            )
                        )
                    }
                }
            }

            if (beginMs != null) {
                val lineText = cleanXmlText(
                    innerHtml.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
                )
                if (lineText.isEmpty()) continue
                val computedDuration = when {
                    endMs != null -> (endMs - beginMs).coerceAtLeast(0L)
                    durMs != null -> durMs.coerceAtLeast(0L)
                    else -> 0L
                }
                lines.add(
                    LyricLine(
                        startMs = beginMs,
                        durationMs = computedDuration,
                        text = lineText,
                        words = spanWords,
                    )
                )
            } else {
                val normalized = innerHtml
                    .replace(Regex("""\s+"""), " ")
                    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("""<[^>]*>"""), "")
                for (subLine in decodeXmlEntities(normalized).split('\n')) {
                    val text = subLine.trim()
                    if (text.isNotEmpty()) {
                        lines.add(LyricLine(startMs = 0L, durationMs = 0L, text = text))
                    }
                }
            }
        }

        if (lines.isEmpty()) return null
        val isTimed = lines.any { it.startMs > 0L || it.durationMs > 0L }
        val sorted = if (isTimed) fillLineDurations(lines.sortedBy { it.startMs }, songDurationMs) else lines
        val hasWords = sorted.any { it.words.isNotEmpty() }
        val kind = when {
            hasWords -> LyricsKind.WORD_SYNCED
            isTimed -> LyricsKind.LINE_SYNCED
            else -> LyricsKind.PLAIN
        }
        return SyncedLyrics(
            lines = sorted,
            kind = kind,
            plainText = sorted.joinToString("\n") { it.text }.ifBlank { null },
            provider = provider,
        )
    }

    private fun tryParseLrc(
        trimmed: String,
        songDurationMs: Long?,
        provider: String?,
    ): SyncedLyrics? {
        val rawLines = trimmed.lineSequence().toList()
        val parsedLines = mutableListOf<LyricLine>()
        var lrcOffsetMs = 0L

        for (rawLine in rawLines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val metaMatch = LRC_METADATA_REGEX.matchEntire(line)
            if (metaMatch != null) {
                if (metaMatch.groupValues[1].equals("offset", ignoreCase = true)) {
                    lrcOffsetMs = metaMatch.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                continue
            }

            // Collect all leading [mm:ss.xx] tags on this line.
            val timestamps = mutableListOf<Long>()
            var cursor = 0
            while (cursor < line.length && line[cursor] == '[') {
                val tagMatch = LRC_LEADING_TAG_REGEX.matchAt(line, cursor) ?: break
                val ts = parseLrcTimestamp(tagMatch.value)
                if (ts != null) {
                    timestamps.add(ts)
                    cursor = tagMatch.range.last + 1
                } else {
                    break
                }
            }

            if (timestamps.isEmpty()) {
                // Malformed or untimed line inside an LRC file: skip without failing.
                continue
            }

            val body = line.substring(cursor).trim()
            for (ts in timestamps) {
                val adjustedStart = (ts + lrcOffsetMs).coerceAtLeast(0L)
                val wordMatches = ELRC_WORD_TAG_REGEX.findAll(body).toList()
                if (wordMatches.isNotEmpty()) {
                    val words = parseEnhancedWords(body, wordMatches, lrcOffsetMs)
                    val plainLineText = words.joinToString("") { it.text }.trim()
                    if (plainLineText.isEmpty() && words.isEmpty()) continue
                    parsedLines.add(
                        LyricLine(
                            startMs = adjustedStart,
                            durationMs = 0L,
                            text = plainLineText,
                            words = words,
                        )
                    )
                } else {
                    parsedLines.add(
                        LyricLine(
                            startMs = adjustedStart,
                            durationMs = 0L,
                            text = body,
                        )
                    )
                }
            }
        }

        if (parsedLines.isEmpty()) return null

        val sorted = fillLineDurations(parsedLines.sortedBy { it.startMs }, songDurationMs)
        val hasWords = sorted.any { it.words.isNotEmpty() }
        return SyncedLyrics(
            lines = sorted,
            kind = if (hasWords) LyricsKind.WORD_SYNCED else LyricsKind.LINE_SYNCED,
            plainText = sorted.mapNotNull { it.text.takeIf(String::isNotBlank) }
                .joinToString("\n")
                .ifBlank { null },
            provider = provider,
        )
    }

    private fun parseEnhancedWords(
        body: String,
        wordMatches: List<MatchResult>,
        lrcOffsetMs: Long,
    ): List<LyricWord> {
        val rawSegments = mutableListOf<Pair<Long, String>>()
        for (i in wordMatches.indices) {
            val match = wordMatches[i]
            val wordStart = parseTimestampGroups(match)?.let { (it + lrcOffsetMs).coerceAtLeast(0L) } ?: continue
            val textStart = match.range.last + 1
            val textEnd = if (i + 1 < wordMatches.size) wordMatches[i + 1].range.first else body.length
            val segmentText = body.substring(textStart, textEnd)
            if (segmentText.isNotEmpty()) {
                rawSegments.add(wordStart to segmentText)
            }
        }

        if (rawSegments.isEmpty()) return emptyList()

        return rawSegments.mapIndexed { index, (startMs, text) ->
            val endMs = if (index + 1 < rawSegments.size) {
                rawSegments[index + 1].first.coerceAtLeast(startMs)
            } else {
                startMs
            }
            LyricWord(startMs = startMs, endMs = endMs, text = text)
        }
    }

    private fun fillLineDurations(
        sorted: List<LyricLine>,
        songDurationMs: Long?,
    ): List<LyricLine> {
        if (sorted.isEmpty()) return sorted
        return sorted.mapIndexed { index, line ->
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            val duration = when {
                line.durationMs > 0L -> line.durationMs
                nextStart != null -> (nextStart - line.startMs).coerceAtLeast(0L)
                songDurationMs != null -> (songDurationMs - line.startMs).coerceAtLeast(0L)
                else -> 0L
            }
            val lineEnd = line.startMs + duration
            val updatedWords = if (line.words.isNotEmpty()) {
                line.words.mapIndexed { wi, w ->
                    if (wi == line.words.lastIndex && w.endMs <= w.startMs && lineEnd > w.startMs) {
                        w.copy(endMs = lineEnd)
                    } else {
                        w
                    }
                }
            } else {
                line.words
            }
            line.copy(durationMs = duration, words = updatedWords)
        }
    }

    private fun parseLrcTimestamp(tag: String): Long? {
        val match = LRC_TAG_REGEX.matchEntire(tag) ?: return null
        return parseTimestampGroups(match)
    }

    private fun parseTimestampGroups(match: MatchResult): Long? {
        val g1 = match.groupValues[1].toLongOrNull() ?: return null
        val g2 = match.groupValues[2].toLongOrNull() ?: return null
        val g3 = match.groupValues[3]
        val g4 = match.groupValues[4]
        val g5 = match.groupValues[5]

        return if (g4.isNotEmpty()) {
            // [hh:mm:ss(.xxx)]
            val hours = g1
            val minutes = g2
            val seconds = g4.toLongOrNull() ?: return null
            if (minutes !in 0..59 || seconds !in 0..59) return null
            val millis = parseFractionMs(g5)
            ((hours * 3600L + minutes * 60L + seconds) * 1000L) + millis
        } else {
            // [mm:ss(.xxx)]
            val minutes = g1
            val seconds = g2
            if (seconds !in 0..59) return null
            val millis = parseFractionMs(g3)
            ((minutes * 60L + seconds) * 1000L) + millis
        }
    }

    private fun parseFractionMs(rawFraction: String): Long {
        if (rawFraction.isEmpty()) return 0L
        return when (rawFraction.length) {
            1 -> (rawFraction.toLongOrNull() ?: 0L) * 100L
            2 -> (rawFraction.toLongOrNull() ?: 0L) * 10L
            else -> rawFraction.take(3).toLongOrNull() ?: 0L
        }
    }

    private fun parseTtmlTimeMs(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.endsWith("ms", ignoreCase = true)) {
            return value.dropLast(2).toDoubleOrNull()?.roundToLong()?.coerceAtLeast(0L)
        }
        if (value.endsWith("s", ignoreCase = true)) {
            return value.dropLast(1).toDoubleOrNull()?.let { (it * 1000.0).roundToLong().coerceAtLeast(0L) }
        }
        val parts = value.split(':')
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()?.let { (it * 1000.0).roundToLong().coerceAtLeast(0L) }
            2 -> {
                val mins = parts[0].toLongOrNull() ?: return null
                val secs = parts[1].toDoubleOrNull() ?: return null
                (mins * 60_000L + (secs * 1000.0).roundToLong()).coerceAtLeast(0L)
            }
            3 -> {
                val hrs = parts[0].toLongOrNull() ?: return null
                val mins = parts[1].toLongOrNull() ?: return null
                val secs = parts[2].toDoubleOrNull() ?: return null
                (hrs * 3_600_000L + mins * 60_000L + (secs * 1000.0).roundToLong()).coerceAtLeast(0L)
            }
            else -> null
        }
    }

    private fun parseXmlAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (m in TTML_ATTR_REGEX.findAll(attrString)) {
            val key = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val value = m.groupValues[2].ifEmpty { m.groupValues[4] }
            if (key.isNotEmpty()) {
                result[key.lowercase()] = value
            }
        }
        return result
    }

    private fun cleanXmlText(raw: String): String {
        val stripped = raw.replace(Regex("""<[^>]*>"""), "")
        return decodeXmlEntities(stripped).replace(Regex("""\s+"""), " ").trim()
    }

    private fun decodeXmlEntities(text: String): String =
        XML_ENTITY_REGEX.replace(text) { match ->
            val entity = match.groupValues[1].lowercase()
            when {
                entity.startsWith("#x") -> {
                    entity.drop(2).toIntOrNull(16)?.let { code ->
                        String(Character.toChars(code))
                    } ?: match.value
                }
                entity.startsWith("#") -> {
                    entity.drop(1).toIntOrNull(10)?.let { code ->
                        String(Character.toChars(code))
                    } ?: match.value
                }
                else -> NAMED_ENTITIES[entity] ?: match.value
            }
        }
}
