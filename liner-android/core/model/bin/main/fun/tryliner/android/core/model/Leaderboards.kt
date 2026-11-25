package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LeaderboardEntryUser(
    val id: String,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val user: LeaderboardEntryUser,
    val playedDurationMs: Long,
    val playCount: Long,
)

@Serializable
data class LeaderboardResponse(
    val type: String,
    val targetId: String,
    val period: String,
    val entries: List<LeaderboardEntry> = emptyList(),
    val updatedAt: String = "",
)
