package `fun`.tryliner.android.core.crypto

import `fun`.tryliner.android.core.model.PowChallenge
import `fun`.tryliner.android.core.model.PowSolution
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

class PowTimeoutException(
    message: String = "The sign-in check took too long, try again.",
) : RuntimeException(message)

object ProofOfWorkSolver {
    const val SUPPORTED_POW_ALGORITHM = "argon2id-v1"
    private const val MAX_EXTRA_BITS = 6
    private const val FALLBACK_BUDGET_MS = 55_000L
    private const val SAFETY_MARGIN_MS = 3_000L

    fun solve(challenge: PowChallenge): PowSolution {
        val useSha256 = challenge.algorithm.equals("sha256", ignoreCase = true) ||
            challenge.algorithm.equals("sha-256", ignoreCase = true)
        val salt = if (useSha256) ByteArray(0) else decodeBase64Url(challenge.salt)
        val deadline = solveDeadline(challenge.expiresAt, System.currentTimeMillis())
        val maxTries = 1L shl min(challenge.difficulty + MAX_EXTRA_BITS, 30)

        var nonce = 0L
        while (nonce < maxTries) {
            if (System.currentTimeMillis() >= deadline) break
            val input = "${challenge.token}:$nonce".toByteArray(Charsets.UTF_8)
            val hash = if (useSha256) {
                MessageDigest.getInstance("SHA-256").digest(input)
            } else {
                Argon2id.hash(
                    password = input,
                    salt = salt,
                    parallelism = challenge.parallelism,
                    iterations = challenge.iterations,
                    memoryKiB = challenge.memoryKiB,
                    hashLength = challenge.hashLength,
                )
            }
            if (leadingZeroBits(hash) >= challenge.difficulty) {
                return PowSolution(token = challenge.token, nonce = nonce.toString())
            }
            nonce += 1L
        }
        throw PowTimeoutException()
    }

    fun leadingZeroBits(bytes: ByteArray): Int {
        var bits = 0
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned == 0) {
                bits += 8
                continue
            }
            return bits + Integer.numberOfLeadingZeros(unsigned) - 24
        }
        return bits
    }

    internal fun decodeBase64Url(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private fun solveDeadline(expiresAt: String, startedAtMs: Long): Long {
        val expiresMs = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull()
        val remaining = if (expiresMs != null) expiresMs - startedAtMs else -1L
        val budget = if (remaining in 1L..(10L * 60_000L)) {
            remaining
        } else {
            FALLBACK_BUDGET_MS
        }
        return startedAtMs + max(1_000L, budget - SAFETY_MARGIN_MS)
    }
}
