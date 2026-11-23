package `fun`.tryliner.android.core.lyrics

import `fun`.tryliner.android.core.model.LyricsStreamEvent
import `fun`.tryliner.android.core.model.LyricsStreamEventSerializer
import kotlinx.serialization.json.Json

/**
 * Raw Server-Sent Event frame emitted by [SseEventParser].
 */
data class SseFrame(
    val event: String? = null,
    val id: String? = null,
    val retryMs: Long? = null,
    val data: String,
)

/**
 * Incremental `text/event-stream` (SSE) frame parser.
 *
 * Handles arbitrary chunk boundaries (including `\r\n`, `\r`, `\n`, and split `\n\n` delimiters).
 */
class SseEventParser {

    private val buffer = StringBuilder()
    private var trailingCr = false

    fun feed(chunk: String): List<SseFrame> {
        if (chunk.isEmpty()) return emptyList()
        appendNormalized(chunk)
        return drainCompleteFrames()
    }

    fun flush(): List<SseFrame> {
        if (trailingCr) {
            buffer.append('\n')
            trailingCr = false
        }
        val frames = drainCompleteFrames().toMutableList()
        val remaining = buffer.toString()
        buffer.clear()
        if (remaining.isNotBlank()) {
            parseRawFrame(remaining)?.let { frames.add(it) }
        }
        return frames
    }

    fun feedLyricsEvents(chunk: String): List<LyricsStreamEvent> =
        feed(chunk).mapNotNull(::decodeLyricsEvent)

    fun flushLyricsEvents(): List<LyricsStreamEvent> =
        flush().mapNotNull(::decodeLyricsEvent)

    fun reset() {
        buffer.clear()
        trailingCr = false
    }

    private fun appendNormalized(chunk: String) {
        var i = 0
        if (trailingCr) {
            buffer.append('\n')
            trailingCr = false
            if (chunk[0] == '\n') {
                i = 1
            }
        }
        while (i < chunk.length) {
            val c = chunk[i]
            when (c) {
                '\r' -> {
                    if (i + 1 < chunk.length) {
                        buffer.append('\n')
                        if (chunk[i + 1] == '\n') {
                            i++
                        }
                    } else {
                        trailingCr = true
                    }
                }
                else -> buffer.append(c)
            }
            i++
        }
    }

    private fun drainCompleteFrames(): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        while (true) {
            val sepIndex = buffer.indexOf("\n\n")
            if (sepIndex == -1) break
            val rawFrame = buffer.substring(0, sepIndex)
            buffer.delete(0, sepIndex + 2)
            parseRawFrame(rawFrame)?.let { frames.add(it) }
        }
        return frames
    }

    private fun parseRawFrame(rawFrame: String): SseFrame? {
        var eventName: String? = null
        var id: String? = null
        var retryMs: Long? = null
        val dataLines = mutableListOf<String>()

        for (line in rawFrame.split('\n')) {
            if (line.isEmpty() || line.startsWith(':')) continue
            val colonIdx = line.indexOf(':')
            val field: String
            val value: String
            if (colonIdx == -1) {
                field = line
                value = ""
            } else {
                field = line.substring(0, colonIdx)
                val rawVal = line.substring(colonIdx + 1)
                value = if (rawVal.startsWith(' ')) rawVal.substring(1) else rawVal
            }
            when (field) {
                "event" -> eventName = value
                "id" -> id = value
                "retry" -> retryMs = value.trim().toLongOrNull()
                "data" -> dataLines.add(value)
            }
        }

        if (dataLines.isEmpty()) return null
        return SseFrame(
            event = eventName,
            id = id,
            retryMs = retryMs,
            data = dataLines.joinToString("\n"),
        )
    }

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun decodeLyricsEvent(frame: SseFrame, json: Json = defaultJson): LyricsStreamEvent? =
            decodeLyricsEvent(frame.data, json)

        fun decodeLyricsEvent(jsonData: String, json: Json = defaultJson): LyricsStreamEvent? =
            runCatching {
                json.decodeFromString(LyricsStreamEventSerializer, jsonData)
            }.getOrNull()
    }
}
