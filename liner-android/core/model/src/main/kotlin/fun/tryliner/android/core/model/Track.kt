package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TrackArtist(
    val id: String,
    val name: String,
)

typealias Artist = TrackArtist

@Serializable
data class TrackAlbum(
    val id: String,
    val title: String,
)

typealias Album = TrackAlbum

@Serializable
data class TrackViewer(
    val liked: Boolean = false,
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: List<TrackArtist> = emptyList(),
    val album: TrackAlbum? = null,
    val durationMs: Long = 0L,
    val cover: Cover? = null,
    val explicit: Boolean? = null,
    val audioVariant: String? = null,
    val type: String? = null,
    val viewer: TrackViewer? = null,
) {
    val isLiked: Boolean
        get() = viewer?.liked == true

    val artistNames: String
        get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class TrackDetails(
    val id: String,
    val title: String,
    val artists: List<TrackArtist> = emptyList(),
    val album: TrackAlbum? = null,
    val durationMs: Long = 0L,
    val cover: Cover? = null,
    val explicit: Boolean? = null,
    val audioVariant: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val viewer: TrackViewer? = null,
    val relatedTracks: List<Track> = emptyList(),
)
