@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package `fun`.tryliner.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface SearchResult {
    val id: String

    @Serializable
    @SerialName("track")
    data class TrackItem(
        override val id: String,
        val title: String,
        val artists: List<TrackArtist> = emptyList(),
        val album: TrackAlbum? = null,
        val durationMs: Long = 0L,
        val cover: Cover? = null,
        val explicit: Boolean? = null,
        val audioVariant: String? = null,
        val viewer: TrackViewer? = null,
    ) : SearchResult {
        fun toTrack(): Track = Track(
            id = id,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
            cover = cover,
            explicit = explicit,
            audioVariant = audioVariant,
            type = "track",
            viewer = viewer,
        )
    }

    @Serializable
    @SerialName("artist")
    data class ArtistItem(
        override val id: String,
        val name: String,
        val cover: Cover? = null,
    ) : SearchResult

    @Serializable
    @SerialName("album")
    data class AlbumItem(
        override val id: String,
        val title: String,
        val artists: List<TrackArtist> = emptyList(),
        val year: Int? = null,
        val cover: Cover? = null,
    ) : SearchResult

    @Serializable
    @SerialName("playlist")
    data class PlaylistItem(
        override val id: String,
        val title: String,
        val author: String? = null,
        val cover: Cover? = null,
    ) : SearchResult
}

@Serializable
data class SearchResponse(
    val items: List<SearchResult> = emptyList(),
)

@Serializable
data class ArtistAlbum(
    val id: String,
    val title: String,
    val year: Int? = null,
    val cover: Cover? = null,
    val explicit: Boolean? = null,
    val totalTracks: Int? = null,
    val trackCount: Int? = null,
)

@Serializable
data class ArtistRelease(
    val id: String,
    val title: String,
    val year: Int? = null,
    val cover: Cover? = null,
    val explicit: Boolean? = null,
    val totalTracks: Int? = null,
    val trackCount: Int? = null,
    val releaseType: String = "single",
)

@Serializable
data class ArtistDetails(
    val type: String = "artist",
    val id: String,
    val name: String,
    val bio: String? = null,
    val cover: Cover? = null,
    val monthlyListeners: String? = null,
    val followers: Long? = null,
    val verified: Boolean = false,
    val geniusImageUrl: String? = null,
    val geniusAka: List<String>? = null,
    val geniusUsername: String? = null,
    val tracks: List<Track> = emptyList(),
    val albums: List<ArtistAlbum> = emptyList(),
    val singles: List<ArtistRelease> = emptyList(),
)

@Serializable
data class AlbumDetails(
    val type: String = "album",
    val id: String,
    val title: String,
    val artists: List<TrackArtist> = emptyList(),
    val year: Int? = null,
    val cover: Cover? = null,
    val trackCount: Int? = null,
    val durationMs: Long? = null,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class CatalogPlaylistDetails(
    val type: String = "playlist",
    val id: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val cover: Cover? = null,
    val trackCount: Int? = null,
    val durationMs: Long? = null,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class RadioTrack(
    val id: String,
    val title: String,
    val artists: List<TrackArtist> = emptyList(),
    val album: TrackAlbum? = null,
    val durationMs: Long = 0L,
    val cover: Cover? = null,
    val explicit: Boolean? = null,
) {
    fun toTrack(): Track = Track(
        id = id,
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        cover = cover,
        explicit = explicit,
    )
}

@Serializable
data class RadioResponse(
    val items: List<RadioTrack> = emptyList(),
)
