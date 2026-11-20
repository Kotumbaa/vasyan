package `fun`.tryliner.android.core.crypto

import `fun`.tryliner.android.core.model.PowChallenge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

class ProofOfWorkSolverTest {
    @Test
    fun `argon2id matches RFC 9106 section 5_3 test vector`() {
        val password = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val associatedData = ByteArray(12) { 0x04 }
        val hash = Argon2id.hash(
            password = password,
            salt = salt,
            parallelism = 4,
            iterations = 3,
            memoryKiB = 32,
            hashLength = 32,
            secret = secret,
            associatedData = associatedData,
        )
        val hex = hash.joinToString("") { "%02x".format(it) }
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", hex)
    }

    @Test
    fun `leadingZeroBits counts leading zero bits accurately`() {
        assertEquals(0, ProofOfWorkSolver.leadingZeroBits(byteArrayOf(0x80.toByte())))
        assertEquals(1, ProofOfWorkSolver.leadingZeroBits(byteArrayOf(0x40)))
        assertEquals(8, ProofOfWorkSolver.leadingZeroBits(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals(12, ProofOfWorkSolver.leadingZeroBits(byteArrayOf(0x00, 0x0F)))
    }

    @Test
    fun `solves argon2id PowChallenge matching proofOfWork_ts`() {
        val salt = Base64.getUrlEncoder().withoutPadding().encodeToString("test-salt-123456".toByteArray())
        val challenge = PowChallenge(
            mode = "required",
            algorithm = ProofOfWorkSolver.SUPPORTED_POW_ALGORITHM,
            token = "pow_tok_1",
            salt = salt,
            difficulty = 3,
            memoryKiB = 32,
            iterations = 1,
            parallelism = 1,
            hashLength = 32,
            expiresAt = Instant.now().plusSeconds(60).toString(),
        )

        val solution = ProofOfWorkSolver.solve(challenge)
        assertEquals("pow_tok_1", solution.token)

        val verifyHash = Argon2id.hash(
            password = "${solution.token}:${solution.nonce}".toByteArray(Charsets.UTF_8),
            salt = ProofOfWorkSolver.decodeBase64Url(salt),
            parallelism = challenge.parallelism,
            iterations = challenge.iterations,
            memoryKiB = challenge.memoryKiB,
            hashLength = challenge.hashLength,
        )
        assertTrue(ProofOfWorkSolver.leadingZeroBits(verifyHash) >= challenge.difficulty)
    }

    @Test
    fun `solves sha256 PowChallenge`() {
        val challenge = PowChallenge(
            mode = "required",
            algorithm = "sha256",
            token = "sha_tok_1",
            salt = "",
            difficulty = 6,
            memoryKiB = 0,
            iterations = 1,
            parallelism = 1,
            hashLength = 32,
            expiresAt = Instant.now().plusSeconds(60).toString(),
        )

        val solution = ProofOfWorkSolver.solve(challenge)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${solution.token}:${solution.nonce}".toByteArray(Charsets.UTF_8))
        assertTrue(ProofOfWorkSolver.leadingZeroBits(digest) >= challenge.difficulty)
    }

    @Test
    fun `throws PowTimeoutException when deadline expires`() {
        val hardChallenge = PowChallenge(
            mode = "required",
            algorithm = "sha256",
            token = "impossible",
            salt = "",
            difficulty = 256,
            memoryKiB = 0,
            iterations = 1,
            parallelism = 1,
            hashLength = 32,
            expiresAt = Instant.now().plusMillis(500).toString(),
        )
        assertThrows(PowTimeoutException::class.java) {
            ProofOfWorkSolver.solve(hardChallenge)
        }
    }
}
