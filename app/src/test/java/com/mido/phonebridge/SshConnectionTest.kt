package com.mido.phonebridge

import com.mido.phonebridge.plugins.ssh.SshConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored-connection shape, and the rules for reading one back.
 *
 * A connection is persisted as JSON inside the vault, so a malformed entry is a real possibility:
 * a hand-edited vault, a partial write, or a field added in a later version. `fromJson` throws on
 * every one of those rather than defaulting a missing host to something that would connect
 * somewhere unintended — a wrong default here is a connection to the wrong machine.
 *
 * The private key is deliberately NOT round-tripped through any transformation: whatever the
 * generator produced is what the connector reads back.
 */
class SshConnectionTest {

    private val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"

    @Test
    fun `a connection round-trips through JSON`() {
        val conn = SshConnection(
            host = "box.example.com",
            port = 2222,
            user = "deploy",
            privateKey = key,
            hostKeyFingerprint = "SHA256:abc123",
        )
        val back = SshConnection.fromJson(conn.toJson().toString())
        assertEquals(conn.host, back.host)
        assertEquals(conn.port, back.port)
        assertEquals(conn.user, back.user)
        assertEquals(conn.privateKey, back.privateKey)
        assertEquals(conn.hostKeyFingerprint, back.hostKeyFingerprint)
    }

    @Test
    fun `an unpinned connection round-trips with a null fingerprint`() {
        // The freshly created case: nothing trusted yet, so the first call must refuse and report.
        val conn = SshConnection("h", 22, "u", key, null)
        assertNull(SshConnection.fromJson(conn.toJson().toString()).hostKeyFingerprint)
    }

    @Test
    fun `the port defaults to 22 when absent`() {
        // The field is omitted, not set to 22 — this is the shape an older or hand-written entry
        // has. Absent means "the standard port", which is unambiguous; a garbage value is refused
        // separately below.
        val obj = org.json.JSONObject()
            .put("host", "h")
            .put("user", "u")
            .put("privateKey", key)
        assertEquals(22, SshConnection.fromJson(obj.toString()).port)
    }

    @Test
    fun `a missing host is refused`() {
        val obj = org.json.JSONObject()
            .put("host", "  ")
            .put("user", "u")
            .put("privateKey", key)
        assertThrowsIllegal { SshConnection.fromJson(obj.toString()) }
    }

    @Test
    fun `a missing user is refused`() {
        val obj = org.json.JSONObject()
            .put("host", "h")
            .put("privateKey", key)
        assertThrowsIllegal { SshConnection.fromJson(obj.toString()) }
    }

    @Test
    fun `a missing private key is refused`() {
        val obj = org.json.JSONObject()
            .put("host", "h")
            .put("user", "u")
        assertThrowsIllegal { SshConnection.fromJson(obj.toString()) }
    }

    @Test
    fun `an out-of-range port is refused`() {
        // 0 and 70000 are both nonsense, and neither should silently become 22 — that would
        // connect to a DIFFERENT port than the one stored.
        for (bad in listOf(0, -1, 70000)) {
            val obj = org.json.JSONObject()
                .put("host", "h")
                .put("user", "u")
                .put("privateKey", key)
                .put("port", bad)
            assertThrowsIllegal { SshConnection.fromJson(obj.toString()) }
        }
    }

    @Test
    fun `the stored private key is preserved byte for byte`() {
        // Newlines in a PEM are load-bearing. A trim or normalise here would produce a key JSch
        // cannot parse, and the failure would look like a bad key rather than a bad storage layer.
        val multiline = "-----BEGIN OPENSSH PRIVATE KEY-----\nline1\nline2\n-----END OPENSSH PRIVATE KEY-----"
        val back = SshConnection.fromJson(
            SshConnection("h", 22, "u", multiline, null).toJson().toString()
        )
        assertEquals(multiline, back.privateKey)
        assertTrue(back.privateKey.contains("\n"))
    }

    private fun assertThrowsIllegal(block: () -> Unit) {
        val threw = try {
            block()
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
