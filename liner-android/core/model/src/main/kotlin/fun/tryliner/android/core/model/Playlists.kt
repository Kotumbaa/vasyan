package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistSummary(
    val id: String,
    val title: String,
    val description: String? = null,
    val revision: Int = 0,
    val trackCount: Int = 0,
    val durationMs: Long = 0L,
    val cover: Cover? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class PlaylistDetails(
    val id: String,
    val title: String,
    val description: String? = null,
    val revision: Int = 0,
    val trackCount: Int = 0,
    val durationMs: Long = 0L,
    val cover: Cover? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class PlaylistOwner(
    val id: String,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class UserPlaylist(
    val id: String,
    val title: String,
    val description: String? = null,
    val isPublic: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val revision: Int = 0,
    val trackCount: Int? = null,
    val coverUrls: List<String>? = null,
    val ownerId: String? = null,
    val owner: PlaylistOwner? = null,
)

@Serializable
data class UserPlaylistItem(
    val id: String,
    val track: Track,
    val position: Int,
    val addedAt: String,
)

@Serializable
data class UserPlaylistDetails(
    val partial: Boolean = false,
    val trackCount: Int = 0,
    val durationMs: Long = 0L,
    val playlist: UserPlaylist,
    val items: List<UserPlaylistItem> = emptyList(),
    val etag: String? = null,
)

@Serializable
data class LikedTrackItem(
    val track: Track,
    val likedAt: String,
)

@Serializable
data class LikedTracksPage(
    val items: List<LikedTrackItem> = emptyList(),
    val nextCursor: String? = null,
)
