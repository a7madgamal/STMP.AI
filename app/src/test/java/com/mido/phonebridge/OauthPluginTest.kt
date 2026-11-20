package com.mido.phonebridge

import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.plugins.oauth.OauthEntry
import com.mido.phonebridge.plugins.oauth.OauthSecret
import com.mido.phonebridge.plugins.oauth.OauthToken
import com.mido.phonebridge.plugins.oauth.OauthTokenCache
import com.mido.phonebridge.plugins.oauth.tokenErrorLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `a missing refresh token is refused by its own name`() {
        try {
            OauthSecret.fromVault("""{"client_secret":"GOCSPX-abc"}""")
            fail("must refuse")
        } catch (e: PluginError) {
            assertEquals("oauth_bad_secret", e.code)
            assertNotNull(e.message)
            assertTrue(
                "the message must name the missing field, or the repair is a guess: ${e.message}",
                e.message!!.contains("refresh_token"),
            )
        }
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
