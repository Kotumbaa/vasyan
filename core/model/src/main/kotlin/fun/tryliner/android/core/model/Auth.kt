package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthUser(
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
)

typealias PublicUser = AuthUser

@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val user: AuthUser,
    val expiresAt: Long? = null,
)

typealias AuthTokens = AuthSession

@Serializable
data class LoginInput(
    val email: String,
    val password: String,
)

@Serializable
data class RegisterInput(
    val email: String,
    val password: String,
    val username: String,
    val displayName: String,
)

@Serializable
data class UsernameAvailability(
    val username: String,
    val valid: Boolean,
    val available: Boolean,
)
