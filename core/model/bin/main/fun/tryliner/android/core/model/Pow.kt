package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TurnstileConfig(
    val mode: String,
    val siteKey: String? = null,
    val edge: Boolean? = null,
)

@Serializable
data class PowChallenge(
    val mode: String,
    val algorithm: String,
    val token: String,
    val salt: String,
    val difficulty: Int,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val hashLength: Int,
    val expiresAt: String,
    val turnstile: TurnstileConfig? = null,
)

@Serializable
data class PowSolution(
    val token: String,
    val nonce: String,
)
