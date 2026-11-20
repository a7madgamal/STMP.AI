package com.mido.phonebridge

import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.plugins.oauth.OauthEntry
import com.mido.phonebridge.plugins.oauth.OauthSecret
import com.mido.phonebridge.plugins.oauth.OauthSecretStatus
import com.mido.phonebridge.plugins.oauth.RefreshTokenPaste
import com.mido.phonebridge.plugins.oauth.OauthToken
import com.mido.phonebridge.plugins.oauth.OauthTokenCache
import com.mido.phonebridge.plugins.oauth.tokenErrorLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The OAuth plugin's rules that a device cannot verify quickly, asserted on the JVM.
 *
 * Three things here are security decisions rather than conveniences, so they get tests:
 *
 * 1. **The token cache never hands out a nearly-expired token.** A token with 2s left produces a
 *    401 that reads exactly like a broken credential, and the whole point of the plugin is that a
 *    caller cannot tell the difference between "your token expired mid-flight" and "your secret is
 *    wrong" — so the phone must never create the first situation.
 * 2. **The secret is parsed by FIELD NAME, refusing by name.** "no refresh_token" and "no
 *    client_secret" are different repairs; one message for both costs the human a round trip.
 * 3. **A provider error is reported as the provider worded it.** RFC 6749 §5.2 defines `error` and
 *    `error_description`, and `invalid_grant` is a fact about the credential — a generic "HTTP 400"
 *    would send someone looking at the plugin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OauthPluginTest {

    @Test
    fun `a cached token is reused while it has more than the margin left`() {
        OauthTokenCache.forgetAll()
        var mints = 0
        val first = OauthTokenCache.tokenFor("t", nowSec = 1000L) {
            mints++
            OauthToken(accessToken = "aaa", expiresAtSec = 1000L + 3600L)
        }
        val second = OauthTokenCache.tokenFor("t", nowSec = 1000L + 100L) {
            mints++
            OauthToken(accessToken = "bbb", expiresAtSec = 1000L + 3600L)
        }
        assertEquals(1, mints)
        assertEquals("aaa", first.accessToken)
        assertEquals("aaa", second.accessToken)
    }

    @Test
    fun `a token inside the margin is re-minted rather than handed out`() {
        OauthTokenCache.forgetAll()
        var mints = 0
        // expires 30s from the reference time: inside the 60s margin, so unusable already.
        OauthTokenCache.tokenFor("t", nowSec = 500L) {
            mints++
            OauthToken("old", 530L)
        }
        val fresh = OauthTokenCache.tokenFor("t", nowSec = 500L) {
            mints++
            OauthToken("new", 500L + 3600L)
        }
        assertEquals(2, mints)
        assertEquals("new", fresh.accessToken)
    }

    @Test
    fun `forget drops one credential without touching another`() {
        OauthTokenCache.forgetAll()
        OauthTokenCache.tokenFor("a", 0L) { OauthToken("tokenA", 3600L) }
        OauthTokenCache.tokenFor("b", 0L) { OauthToken("tokenB", 3600L) }
        OauthTokenCache.forget("a")

        var mintedForB = false
        OauthTokenCache.tokenFor("b", 10L) {
            mintedForB = true
            OauthToken("replacement", 3600L)
        }
        assertTrue("b's cached token must survive forgetting a", !mintedForB)

        var mintedForA = false
        OauthTokenCache.tokenFor("a", 10L) {
            mintedForA = true
            OauthToken("replacement", 3600L)
        }
        assertTrue("a must be minted again after forget", mintedForA)
    }

    @Test
    fun `the vault value round-trips both fields`() {
        val original = OauthSecret(refreshToken = "1//refresh", clientSecret = "GOCSPX-abc")
        val parsed = OauthSecret.fromVault(original.toJson())
        assertEquals("1//refresh", parsed.refreshToken)
        assertEquals("GOCSPX-abc", parsed.clientSecret)
    }

    @Test
    fun `a stored credential WITHOUT a refresh token parses, and reports that it needs one`() {
        // **This is what the import needs.** A downloaded client file is the client half only, so
        // refusing to store a credential without a refresh token discarded a perfectly good import
        // and blocked the user on a field the file cannot contain. The state is now explicit and
        // visible rather than an error.
        val parsed = OauthSecret.fromVault("""{"client_secret":"GOCSPX-abc"}""")
        assertEquals("", parsed.refreshToken)
        assertEquals(OauthSecretStatus.NEEDS_REFRESH_TOKEN, parsed.status)
    }

    @Test
    fun `a stored credential WITH a refresh token reports ready`() {
        val parsed = OauthSecret.fromVault(
            """{"refresh_token":"1//0gTOKEN","client_secret":"GOCSPX-abc"}"""
        )
        assertEquals(OauthSecretStatus.READY, parsed.status)
    }

    @Test
    fun `a blank refresh token is the same state as an absent one`() {
        // Both mean "imported, not authorised". Distinguishing them would be a distinction with no
        // difference to any caller.
        val parsed = OauthSecret.fromVault(
            """{"refresh_token":"   ","client_secret":"GOCSPX-abc"}"""
        )
        assertEquals(OauthSecretStatus.NEEDS_REFRESH_TOKEN, parsed.status)
    }

    @Test
    fun `the status round-trips through storage`() {
        val before = OauthSecret(refreshToken = "", clientSecret = "s")
        val after = OauthSecret.fromVault(before.toJson())
        assertEquals(OauthSecretStatus.NEEDS_REFRESH_TOKEN, after.status)
        // And an empty string survives as empty rather than becoming a placeholder — a placeholder
        // would be a lie that surfaces at the provider as invalid_grant.
        assertEquals("", after.refreshToken)
    }

    @Test
    fun `a missing client secret is refused by its own name`() {
        try {
            OauthSecret.fromVault("""{"refresh_token":"1//x"}""")
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("oauth_bad_secret", e.code)
            assertTrue(
                "the message must name the missing field: ${e.message}",
                e.message!!.contains("client_secret"),
            )
        }
    }

    @Test
    fun `a plain string pasted instead of json is refused with a repair, not a crash`() {
        // The realistic mistake: someone pastes the refresh token alone into the entry.
        try {
            OauthSecret.fromVault("1//0gLONGTOKENVALUE")
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("oauth_bad_secret", e.code)
            assertTrue(e.message!!.contains("OAuth screen"))
        }
    }

    @Test
    fun `an entry without a token url is refused, because it cannot mint`() {
        try {
            OauthEntry.fromJson("""{"clientId":"x"}""")
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("oauth_bad_config", e.code)
            assertTrue(e.message!!.contains("token url"))
        }
    }

    @Test
    fun `an unknown auth style is refused rather than silently defaulted`() {
        // Defaulting would send the client secret in the body when the provider expects Basic —
        // an `invalid_client` that looks like a wrong secret.
        try {
            OauthEntry.fromJson(
                """{"tokenUrl":"https://x/token","authStyle":"digest"}"""
            )
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("oauth_bad_config", e.code)
        }
    }

    @Test
    fun `basic is the default auth style`() {
        val entry = OauthEntry.fromJson("""{"tokenUrl":"https://x/token"}""")
        assertEquals(OauthEntry.AUTH_BASIC, entry.authStyle)
    }

    @Test
    fun `the add form does NOT mark the refresh token as required`() {
        // **A regression guard for a shipped mistake.** 0.51.92 removed the save check but left the
        // field marked `required = true`, so the dialog still showed a REQUIRED badge and still
        // refused — the marker is the contract the user sees, not the code behind it. Mido caught it
        // immediately: *"refresh token still required in the dialog!"*
        //
        // A source scan because this is a LAYOUT fact inside an Activity, and the JVM suite cannot
        // build that Activity's views without a device.
        val source = java.io.File("src/main/java/com/mido/phonebridge/OauthActivity.kt").readText()
        // Slice from the refresh field's own title to the END of its `labelledField(...)` call.
        // `required = false` is written after `inputType`, so a slice stopping there cut the very
        // line being asserted — a guard that failed while the code was correct. The `)` that closes
        // the call comes after `required`, so the slice ends at the first `)\n` following it.
        val field = source.substringAfter("\"REFRESH TOKEN (OPTIONAL)\"", "")
            .substringAfter("inputType")
            .substringBefore("\n            )")
            .let { "inputType" + it }
        assertTrue(
            "the refresh-token field must be declared with required = false, so the form does not " +
                "demand a value the imported file can never contain",
            field.contains("required = false"),
        )
        assertFalse(
            "it must not be marked required: ${field.take(120)}",
            field.contains("required = true"),
        )
    }

    // --- the paste parser ---------------------------------------------------------------------

    @Test
    fun `a bare refresh token is accepted`() {
        val token = "1//0gLONGREFRESHTOKENVALUEhere"
        assertEquals(token, RefreshTokenPaste.extract(token))
    }

    @Test
    fun `the whole json line is accepted, and the token is extracted`() {
        // The most common paste: the human copies the line from the exchange response.
        val line = """  "refresh_token": "1//0gLONGREFRESHTOKENVALUEhere","""
        assertEquals("1//0gLONGREFRESHTOKENVALUEhere", RefreshTokenPaste.extract(line))
    }

    @Test
    fun `quotes and a trailing comma are stripped`() {
        assertEquals(
            "1//0gLONGREFRESHTOKENVALUEhere",
            RefreshTokenPaste.extract("\"1//0gLONGREFRESHTOKENVALUEhere\","),
        )
    }

    @Test
    fun `a client secret pasted into the token field is refused`() {
        // These two sit adjacent in the console, and storing a secret as the refresh token fails at
        // the PROVIDER as invalid_grant — which reads as "my credential is refused" rather than
        // "the wrong field was filled".
        assertNull(RefreshTokenPaste.extract("GOCSPX-abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `something too short to be a token is refused`() {
        assertNull(RefreshTokenPaste.extract("1//x"))
    }

    @Test
    fun `prose is refused rather than stored`() {
        assertNull(RefreshTokenPaste.extract("copy the refresh token value from the page"))
    }

    @Test
    fun `empty input is refused`() {
        assertNull(RefreshTokenPaste.extract("   "))
    }

    @Test
    fun `a provider error is reported with its own code and description`() {
        val line = tokenErrorLine(
            400,
            """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""",
        )
        assertTrue(line.contains("invalid_grant"))
        assertTrue(line.contains("Token has been expired or revoked."))
        assertTrue(line.contains("400"))
    }

    @Test
    fun `a non-json error body is still reported understandably`() {
        val line = tokenErrorLine(502, "<html>bad gateway</html>")
        assertTrue(line.contains("502"))
        assertTrue(line.contains("bad gateway"))
    }
}
