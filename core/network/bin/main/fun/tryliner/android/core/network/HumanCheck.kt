package `fun`.tryliner.android.core.network

data class HumanCheckRequest(
    val action: String,
    val mode: String,
)

data class HumanCheckResult(
    val token: String? = null,
    val clearanceCookie: String? = null,
)

class HumanCheckCancelledException(
    message: String = "The human check was closed.",
) : Exception(message)

fun interface HumanCheckProvider {
    suspend fun requestHumanCheck(request: HumanCheckRequest): HumanCheckResult?
}
