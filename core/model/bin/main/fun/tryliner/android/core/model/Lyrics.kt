package `fun`.tryliner.android.core.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class LyricsMatchedTrack(
    val title: String? = null,
    val artists: List<String>? = null,
    val durationMs: Long? = null,
)

@Serializable
data class LyricsPayload(
    val format: String,
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
    val matchedTrack: LyricsMatchedTrack? = null,
    val lyrics: LyricsPayload,
    val syncHint: String? = null,
    val provider: String,
    val contentKind: String,
    val syncLevel: String,
    val topPriority: Boolean? = null,
    val quality: LyricsQuality,
)

@Serializable
data class LyricsSearchResult(
    val provider: String? = null,
    val cached: Boolean = false,
    val candidate: LyricsCandidate,
)

@Serializable
data class LyricsStreamTrack(
    val id: String,
    val title: String,
    val artists: List<TrackArtist> = emptyList(),
    val durationMs: Long = 0L,
)

@Serializable
data class LyricsBestResult(
    val provider: String,
    val quality: Double,
    val syncLevel: String,
)

@Serializable(with = LyricsStreamEventSerializer::class)
sealed interface LyricsStreamEvent {
    @Serializable
    data class Metadata(
        val type: String = "metadata",
        val track: LyricsStreamTrack,
    ) : LyricsStreamEvent

    @Serializable
    data class ProviderFound(
        val type: String = "provider",
        val provider: String,
        val status: String = "found",
        val syncLevel: String,
        val candidate: LyricsCandidate,
        val durationMs: Long? = null,
    ) : LyricsStreamEvent

    @Serializable
    data class ProviderNotFound(
        val type: String = "provider",
        val provider: String,
        val status: String = "not_found",
        val instrumental: Boolean? = null,
        val durationMs: Long? = null,
    ) : LyricsStreamEvent

    @Serializable
    data class ProviderError(
        val type: String = "provider",
        val provider: String,
        val status: String = "error",
        val message: String,
        val durationMs: Long? = null,
    ) : LyricsStreamEvent

    @Serializable
    data class Done(
        val type: String = "done",
        val cached: Boolean = false,
        val best: LyricsBestResult? = null,
        val status: String? = null,
        val instrumental: Boolean? = null,
    ) : LyricsStreamEvent
}

object LyricsStreamEventSerializer :
    JsonContentPolymorphicSerializer<LyricsStreamEvent>(LyricsStreamEvent::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<LyricsStreamEvent> {
        val obj = element.jsonObject
        return when (val type = obj["type"]?.jsonPrimitive?.content) {
            "metadata" -> LyricsStreamEvent.Metadata.serializer()
            "done" -> LyricsStreamEvent.Done.serializer()
            "provider" -> when (val status = obj["status"]?.jsonPrimitive?.content) {
                "found" -> LyricsStreamEvent.ProviderFound.serializer()
                "not_found" -> LyricsStreamEvent.ProviderNotFound.serializer()
                "error" -> LyricsStreamEvent.ProviderError.serializer()
                else -> throw SerializationException("Unknown provider status: $status")
            }
            else -> throw SerializationException("Unknown LyricsStreamEvent type: $type")
        }
    }
}
