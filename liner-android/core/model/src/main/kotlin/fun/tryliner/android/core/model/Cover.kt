package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Cover(
    val url: String,
    val width: Int,
    val height: Int,
    val fallbackUrl: String? = null,
)
