package `fun`.tryliner.android.core.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WasmRequestSignerTest {
    @Test
    fun `signs request deterministically and normalizes method and query`() {
        val signer = WasmRequestSigner()
        val a = signer.signRequest(
            method = "get",
            path = "/v1/me/liked-tracks?limit=50",
            body = null,
            timestamp = 1_700_000_000L,
            nonce = "0123456789abcdef",
        )
        val b = signer.signRequest(
            method = "GET",
            path = "/v1/me/liked-tracks",
            body = "",
            timestamp = 1_700_000_000L,
            nonce = "0123456789abcdef",
        )

        assertTrue(a.signature.isNotBlank())
        assertEquals(a.signature, b.signature)
        assertEquals(1_700_000_000L, a.timestamp)
        assertEquals("0123456789abcdef", a.nonce)

        val headers = a.toHeaderMap()
        assertEquals(a.signature, headers["x-liner-signature"])
        assertEquals("1700000000", headers["x-liner-timestamp"])
        assertEquals("0123456789abcdef", headers["x-liner-nonce"])
    }

    @Test
    fun `different body produces different signature`() {
        val signer = WasmRequestSigner()
        val emptyBody = signer.signRequest(
            method = "POST",
            path = "/v1/me/playlists",
            body = null,
            timestamp = 1_700_000_000L,
            nonce = "abcdef0123456789",
        )
        val jsonBody = signer.signRequest(
            method = "POST",
            path = "/v1/me/playlists",
            body = """{"title":"My Playlist"}""",
            timestamp = 1_700_000_000L,
            nonce = "abcdef0123456789",
        )

        assertNotEquals(emptyBody.signature, jsonBody.signature)
    }

    @Test
    fun `generates random 16-char hex nonce when omitted`() {
        val signer = WasmRequestSigner()
        val signed = signer.signRequest(method = "GET", path = "/v1/health")
        assertTrue(signed.nonce.matches(Regex("^[0-9a-f]{16}$")))
        assertTrue(signed.signature.isNotBlank())
    }

    @Test
    fun `signCoverUrl signMonitorRequest and signRawPayload produce non-empty signatures`() {
        val signer = WasmRequestSigner()
        val coverSig = signer.signCoverUrl("trk_123:640")
        assertTrue(coverSig.isNotBlank())

        val monitorSig = signer.signMonitorRequest(
            method = "POST",
            path = "/v1/monitor/events",
            body = """{"ok":true}""",
            timestamp = 1_700_000_000L,
            nonce = "0011223344556677",
        )
        assertTrue(monitorSig.signature.isNotBlank())

        val rawSig = signer.signRawPayload("hello-liner".toByteArray(Charsets.UTF_8))
        assertTrue(rawSig.isNotBlank())
    }

    @Test
    fun `syncServerTime adjusts timestamp`() {
        val signer = WasmRequestSigner()
        val futureMs = System.currentTimeMillis() + 60_000L
        signer.syncServerTime(futureMs)
        val adjusted = signer.getAdjustedTimestamp()
        val nowSec = System.currentTimeMillis() / 1000L
        assertTrue(abs((adjusted - nowSec) - 60L) <= 2L)

        signer.syncServerTime("Wed, 21 Oct 2015 07:28:00 GMT")
        assertTrue(abs(signer.getAdjustedTimestamp() - 1445412480L) <= 2L)
    }
}
