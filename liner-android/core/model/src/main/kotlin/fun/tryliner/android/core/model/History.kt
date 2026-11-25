package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackContext(
    val type: String,
    val id: String? = null,
)

@Serializable
data class RecordPlaybackEventRequest(
    val playbackSessionId: String,
    val listeningSessionId: String,
    val trackId: String,
    val trackSnapshot: Track? = null,
    val playedDurationMs: Long,
    val trackDurationMs: Long? = null,
    val completionRate: Double,
    val completed: Boolean? = null,
    val skipped: Boolean? = null,
    val loopCount: Int? = null,
    val hasSeekBackward: Boolean? = null,
    val context: PlaybackContext? = null,
)

@Serializable
data class PlaybackHistoryItem(
    val id: String,
    val playbackSessionId: String,
    val listeningSessionId: String,
    val track: Track,
    val playedDurationMs: Long,
    val completionRate: Double,
    val completed: Boolean,
    val skipped: Boolean,
    val loopCount: Int,
    val hasSeekBackward: Boolean,
    val context: PlaybackContext? = null,
    val listenedAt: String,
)

@Serializable
data class PlaybackHistoryResponse(
    val items: List<PlaybackHistoryItem> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class PopularTrackItem(
    val rank: Int,
    val track: Track,
    val playCount: Long,
    val uniqueListeners: Long,
    val score: Double,
    val viewer: TrackViewer? = null,
)

@Serializable
data class PopularTracksResponse(
    val window: String,
    val generatedAt: String,
    val items: List<PopularTrackItem> = emptyList(),
)

@Serializable
data class PopularAlbumEntry(
    val id: String,
    val title: String,
    val artist: String,
    val artists: List<TrackArtist>? = null,
    val coverUrl: String? = null,
    val year: Int? = null,
    val playCount: Long,
    val uniqueListeners: Long,
    val trackCount: Int,
    val score: Double,
)

@Serializable
data class PopularAlbumsResponse(
    val window: String,
    val items: List<PopularAlbumEntry> = emptyList(),
)

@Serializable
data class MostSavedEntry(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val artists: List<TrackArtist>? = null,
    val coverUrl: String? = null,
    val saves: Long,
)

@Serializable
data class MostSavedResponse(
    val type: String,
    val items: List<MostSavedEntry> = emptyList(),
)
