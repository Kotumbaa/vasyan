package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

class ApiError(
    val status: Int,
    override val message: String,
    val code: String? = null,
    val requestId: String? = null,
) : Exception(message)

@Serializable
data class ApiErrorBody(
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val reqId: String? = null,
    val requestId: String? = null,
)
