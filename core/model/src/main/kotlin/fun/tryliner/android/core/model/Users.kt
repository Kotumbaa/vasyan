package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class UserPrivacySettings(
    val isPublic: Boolean,
    val showActivity: Boolean,
    val showHistory: Boolean,
    val showLikedTracks: Boolean,
    val participateLeaderboards: Boolean,
)

@Serializable
data class UserProfile(
    val id: String,
    val email: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val isPublic: Boolean? = null,
    val showActivity: Boolean? = null,
    val showHistory: Boolean? = null,
    val showLikedTracks: Boolean? = null,
    val participateLeaderboards: Boolean? = null,
    val followersCount: Long = 0L,
    val followingCount: Long = 0L,
    val isFollowing: Boolean? = null,
    val createdAt: String = "",
    val privacy: UserPrivacySettings? = null,
)

@Serializable
data class UpdateProfileInput(
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val isPublic: Boolean? = null,
    val showActivity: Boolean? = null,
    val showHistory: Boolean? = null,
    val showLikedTracks: Boolean? = null,
    val participateLeaderboards: Boolean? = null,
)
