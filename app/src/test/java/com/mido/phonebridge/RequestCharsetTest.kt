package com.mido.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The charset guard for request bodies.
 *
 * WHY THIS EXISTS
 *   NanoHTTPD 2.3.1 decodes a request body with
 *   `new String(bytes, contentType.getEncoding())`, and `ContentType.getEncoding()` returns
 *   the literal `"US-ASCII"` when the header carries no `charset=`. Verified in the shipped
 *   jar: `getEncoding` does `if (encoding == null) return "US-ASCII"`.
 *
 *   US-ASCII cannot represent any byte >= 0x80, so EVERY such byte becomes U+FFFD. One UTF-8
 *   character is up to 4 bytes, which is why an em-dash (3 bytes: E2 80 94) arrived as exactly
 *   three replacement characters. `application/json` has no charset parameter precisely
 *   because JSON is defined as UTF-8, so the ordinary request was the one that broke.
 *
 *   The cost was real: a Todoist task written through the broker came back as
 *   `UI ��� when`, and the corruption is silent — nothing fails, the text is just wrong.
 *
 * WHAT THIS TESTS
 *   The fix is `BridgeService.forceUtf8`, which writes `charset=utf-8` onto the session's own
 *   header map before `parseBody` reads it. That is a source-level behaviour, so this test
 *   checks the source: the call must exist, it must come BEFORE `parseBody`, and it must not
 *   override a charset the caller actually specified.
 *
 * A source scan is unusual, and it is the point: the rule is "every body is decoded as UTF-8
 * unless the caller says otherwise", and the only place that can be broken is that function.
 * A runtime test would need a live NanoHTTPD session, which a unit test cannot host.
 */
class RequestCharsetTest {

    private val bridge = File("src/main/java/com/mido/phonebridge/BridgeService.kt")

    @Test
    fun `readRawBody forces utf8 before parsing`() {
        assertTrue("BridgeService.kt not found from ${File(".").absolutePath}", bridge.isFile)
        val source = bridge.readText()

        val bodyStart = source.indexOf("private fun readRawBody(")
        assertTrue("readRawBody is gone — if it was renamed, update this guard", bodyStart > 0)

        val body = source.substring(bodyStart, minOf(source.length, bodyStart + 1_200))
        val forceCall = body.indexOf("forceUtf8(session)")
        val parseCall = body.indexOf("session.parseBody(")

        assertTrue("readRawBody no longer calls forceUtf8 — bodies would decode as US-ASCII again", forceCall >= 0)
        assertTrue("readRawBody no longer calls parseBody", parseCall >= 0)
        assertTrue(
            "forceUtf8 must run BEFORE parseBody: parseBody is what reads the header, so " +
                "patching it afterwards changes nothing",
            forceCall < parseCall
        )
    }

    @Test
    fun `forceUtf8 appends a charset rather than replacing one`() {
        val source = bridge.readText()
        val start = source.indexOf("private fun forceUtf8(")
        assertTrue("forceUtf8 is gone", start > 0)
        val body = source.substring(start, minOf(source.length, start + 900))

        // A caller that stated its own charset must be honoured. Silently rewriting it would
        // be the same class of lie as the US-ASCII default: the request said what it wanted.
        assertTrue(
            "forceUtf8 must skip a header that already declares a charset",
            body.contains("charset=", ignoreCase = true)
        )
        assertTrue(
            "forceUtf8 must guard on the existing header rather than replacing it blindly",
            body.contains("return")
        )
    }

    @Test
    fun `the utf8 default is documented as the NanoHTTPD defect it works around`() {
        // A future reader who deletes this needs to know it is not optional style. The
        // comment is the only thing that carries that, and it names the exact upstream
        // behaviour so it can be re-checked against a NanoHTTPD upgrade.
        val source = bridge.readText()
        assertTrue(
            "the workaround must name US-ASCII and the library, or it reads as arbitrary",
            source.contains("US-ASCII")
        )
        assertTrue(
            "the workaround must cite the NanoHTTPD version it was verified against",
            source.contains("2.3.1")
        )
    }
}
