package `fun`.tryliner.android.core.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Raw Server-Sent Event frame emitted by [SseEventParser].
 */
data class SseFrame(
    val event: String? = null,
    val id: String? = null,
    val retryMs: Long? = null,
    val data: String,
)

@Serializable
data class LyricsArtistMetadata(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class LyricsTrackMetadata(
    val id: String,
    val title: String,
    val artists: List<LyricsArtistMetadata> = emptyList(),
    val durationMs: Long = 0L,
)

@Serializable
data class LyricsMatchedTrack(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val durationMs: Long? = null,
)

@Serializable
data class LyricsPayload(
    val format: LyricsFormat,
    val content: String,
)

@Serializable
data class LyricsQuality(
    val total: Double = 0.0,
    val match: Double = 0.0,
    val content: Double = 0.0,
    val sync: Double = 0.0,
    val source: Double = 0.0,
    val reasons: List<String> = emptyList(),
)

@Serializable
data class LyricsCandidate(
    val provider: String = "",
    val contentKind: LyricsKind = LyricsKind.PLAIN,
    val syncLevel: LyricsSyncLevel = LyricsSyncLevel.PLAIN,
    val topPriority: Boolean? = null,
    val quality: LyricsQuality = LyricsQuality(),
    val matchedTrack: LyricsMatchedTrack? = null,
    val lyrics: LyricsPayload,
    val syncHint: LyricsKind? = null,
)

@Serializable
data class LyricsBestMatch(
    val provider: String,
    val quality: Double = 0.0,
    val syncLevel: LyricsSyncLevel = LyricsSyncLevel.PLAIN,
)

/**
 * Typed SSE events emitted by Liner's `/v1/tracks/{id}/lyrics` stream.
 */
sealed interface LyricsStreamEvent {
    data class Metadata(
        val track: LyricsTrackMetadata,
    ) : LyricsStreamEvent

    data class ProviderFound(
        val provider: String,
        val syncLevel: LyricsSyncLevel,
        val candidate: LyricsCandidate,
        val durationMs: Long? = null,
    ) : LyricsStreamEvent

    data class ProviderNotFound(
        val provider: String,
        val instrumental: Boolean = false,
        val durationMs: Long? = null,
    ) : LyricsStreamEvent

    data class ProviderError(
        val provider: String,
        val message: String,
        val durationMs: Long? = null,
    ) : LyricsStreamEvent

    data class Done(
        val cached: Boolean = false,
        val best: LyricsBestMatch? = null,
        val status: String? = null,
        val instrumental: Boolean = false,
    ) : LyricsStreamEvent
}

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
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun decodeLyricsEvent(frame: SseFrame): LyricsStreamEvent? =
            decodeLyricsEvent(frame.data)

        fun decodeLyricsEvent(jsonData: String): LyricsStreamEvent? {
            val root = runCatching { json.parseToJsonElement(jsonData) as? JsonObject }.getOrNull()
                ?: return null
            val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
            return when (type) {
                "metadata" -> {
                    val trackEl = root["track"] ?: return null
                    val track = runCatching {
                        json.decodeFromJsonElement<LyricsTrackMetadata>(trackEl)
                    }.getOrNull() ?: return null
                    LyricsStreamEvent.Metadata(track)
                }

                "provider" -> {
                    val provider = root["provider"]?.jsonPrimitive?.contentOrNull ?: return null
                    val status = root["status"]?.jsonPrimitive?.contentOrNull ?: return null
                    val durationMs = root["durationMs"]?.jsonPrimitive?.longOrNull
                    when (status) {
                        "found" -> {
                            val candidateEl = root["candidate"] ?: return null
                            val candidate = runCatching {
                                json.decodeFromJsonElement<LyricsCandidate>(candidateEl)
                            }.getOrNull() ?: return null
                            val syncLevel = root["syncLevel"]?.let {
                                runCatching { json.decodeFromJsonElement<LyricsSyncLevel>(it) }.getOrNull()
                            } ?: candidate.syncLevel
                            LyricsStreamEvent.ProviderFound(
                                provider = provider,
                                syncLevel = syncLevel,
                                candidate = candidate,
                                durationMs = durationMs,
                            )
                        }

                        "not_found" -> LyricsStreamEvent.ProviderNotFound(
                            provider = provider,
                            instrumental = root["instrumental"]?.jsonPrimitive?.booleanOrNull == true,
                            durationMs = durationMs,
                        )

                        "error" -> LyricsStreamEvent.ProviderError(
                            provider = provider,
                            message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
                            durationMs = durationMs,
                        )

                        else -> null
                    }
                }

                "done" -> {
                    val best = root["best"]?.let {
                        runCatching { json.decodeFromJsonElement<LyricsBestMatch>(it) }.getOrNull()
                    }
                    LyricsStreamEvent.Done(
                        cached = root["cached"]?.jsonPrimitive?.booleanOrNull == true,
                        best = best,
                        status = root["status"]?.jsonPrimitive?.contentOrNull,
                        instrumental = root["instrumental"]?.jsonPrimitive?.booleanOrNull == true,
                    )
                }

                else -> null
            }
        }
    }
}
