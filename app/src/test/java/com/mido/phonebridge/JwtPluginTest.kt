package com.mido.phonebridge

import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.plugins.jwt.HmacSha256
import com.mido.phonebridge.plugins.jwt.JwtEntry
import com.mido.phonebridge.plugins.jwt.JwtMinter
import com.mido.phonebridge.plugins.jwt.JwtRules
import com.mido.phonebridge.plugins.jwt.JwtSigners
import com.mido.phonebridge.plugins.jwt.JwtSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The token minter, tested against the shape Mozilla actually documents.
 *
 * **Why this is a unit test and not a live call.** AMO answers a wrong token with a bare
 * `401 unauthorized` — it does not say which claim was wrong, whether the signature failed, or
 *whether `iss` was malformed. That is the worst possible error surface to debug against, so the
 * token is decoded and checked HERE, claim by claim, before it ever leaves the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JwtMinterTest {

    private val key = "0123456789abcdef0123456789abcdef" // exactly 32 bytes

    private val amoSpec = JwtSpec(
        issuer = "user:543210:23",
        alg = "HS256",
        ttlSeconds = 60,
        skewSeconds = 30,
        scheme = "JWT",
    )

    @Test
    fun `a minted token is three base64url segments`() {
        val t = JwtMinter.mint(amoSpec, key, nowMs = 1_700_000_000_000)
        val parts = t.token.split(".")
        assertEquals(3, parts.size)
        for (p in parts) {
            assertTrue("segment must be base64url, got '$p'", p.matches(Regex("[A-Za-z0-9_-]+")))
            assertFalse("JOSE forbids padding", p.contains("="))
        }
    }

    @Test
    fun `the header names HS256 and JWT`() {
        val t = JwtMinter.mint(amoSpec, key, nowMs = 1_700_000_000_000)
        val header = JSONObject(decodeSegment(t.token.split(".")[0]))
        assertEquals("HS256", header.getString("alg"))
        assertEquals("JWT", header.getString("typ"))
    }

    @Test
    fun `the claims are exactly what AMO requires`() {
        val nowMs = 1_700_000_000_000
        val t = JwtMinter.mint(amoSpec, key, nowMs = nowMs)
        val payload = JSONObject(decodeSegment(t.token.split(".")[1]))

        assertEquals("user:543210:23", payload.getString("iss"))
        // iat is BACKDATED by the skew: a fast phone clock otherwise produces "not yet valid".
        assertEquals(nowMs / 1000 - 30, payload.getLong("iat"))
        assertEquals(nowMs / 1000 + 60, payload.getLong("exp"))
        assertTrue("jti must be present", payload.getString("jti").isNotEmpty())
    }

    @Test
    fun `exp is never more than five minutes past iat`() {
        // AMO's documented ceiling. A ttl above it is allowed by this plugin (other issuers have no
        // such rule) but the guard below is what the AMO-shaped config must satisfy.
        val t = JwtMinter.mint(amoSpec, key, nowMs = 1_700_000_000_000)
        assertTrue(t.expiresAtSec - t.issuedAtSec <= 300)
    }

    @Test
    fun `two tokens for the same instant differ, because jti is random`() {
        val a = JwtMinter.mint(amoSpec, key, nowMs = 1_700_000_000_000).token
        val b = JwtMinter.mint(amoSpec, key, nowMs = 1_700_000_000_000).token
        assertNotEquals("jti must not repeat: a replay nonce identical twice is not a nonce", a, b)
    }

    @Test
    fun `the signature verifies with the same secret`() {
        // The one assertion that proves the whole path: HMAC over `header.payload` with the key.
        val t = JwtMinter.mint(amoSpec, key, nowMs = 1_700_000_000_000)
        val parts = t.token.split(".")
        val expected = HmacSha256.sign(key, "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        assertEquals(b64Url(expected), parts[2])
    }

    @Test
    fun `a key under 32 bytes is refused`() {
        // RFC 7518 3.2: an HS256 key must be at least as long as the hash output. A shorter one is a
        // real weakness, so it is refused rather than accepted quietly.
        val short = "too-short"
        val threw = try {
            JwtMinter.mint(amoSpec, short, nowMs = 1_700_000_000_000)
            false
        } catch (e: PluginError) {
            assertEquals("bad_request", e.code)
            true
        }
        assertTrue("a short HMAC key must be refused", threw)
    }

    @Test
    fun `extra claims cannot redefine a reserved claim`() {
        val bad = amoSpec.copy(extraClaims = mapOf("iss" to "someone-else"))
        val threw = try {
            JwtMinter.mint(bad, key, nowMs = 1_700_000_000_000)
            false
        } catch (e: PluginError) {
            assertEquals("bad_request", e.code)
            assertTrue(e.message!!.contains("iss"))
            true
        }
        assertTrue(threw)
    }

    @Test
    fun `extra claims are merged into the payload`() {
        val spec = amoSpec.copy(extraClaims = mapOf("aud" to "api.example.com", "scope" to "read"))
        val payload = JSONObject(
            decodeSegment(JwtMinter.mint(spec, key, nowMs = 1_700_000_000_000).token.split(".")[1])
        )
        assertEquals("api.example.com", payload.getString("aud"))
        assertEquals("read", payload.getString("scope"))
        // ...and the owned claims still win.
        assertEquals("user:543210:23", payload.getString("iss"))
    }

    @Test
    fun `the scheme is stored on the spec, not baked into the token`() {
        // The scheme decides the HEADER at send time. The token bytes are identical under both
        // schemes and differ only in `jti`/timestamps, so the assertion is that no claim carries the
        // scheme — i.e. it did not leak into the signed payload.
        val bearer = JwtMinter.mint(amoSpec.copy(scheme = "Bearer"), key, nowMs = 1_700_000_000_000)
        val payload = JSONObject(decodeSegment(bearer.token.split(".")[1]))
        assertFalse(payload.has("scheme"))
        assertFalse(payload.has("typ"))
    }

    @Test
    fun `an unsupported alg is refused, not defaulted`() {
        val threw = try {
            JwtMinter.mint(amoSpec.copy(alg = "RS256"), key, nowMs = 1_700_000_000_000)
            false
        } catch (e: PluginError) {
            assertEquals("bad_request", e.code)
            true
        }
        assertTrue("an alg this build cannot sign must refuse", threw)
    }

    @Test
    fun `ttl and skew bounds are enforced`() {
        for (spec in listOf(
            amoSpec.copy(ttlSeconds = 0),
            amoSpec.copy(ttlSeconds = JwtSpec.MAX_TTL_SECONDS + 1),
            amoSpec.copy(skewSeconds = -1),
            amoSpec.copy(skewSeconds = JwtSpec.MAX_SKEW_SECONDS + 1),
        )) {
            val threw = try {
                JwtMinter.mint(spec, key, nowMs = 1_700_000_000_000)
                false
            } catch (e: PluginError) {
                true
            }
            assertTrue("spec $spec must be refused", threw)
        }
    }

    @Test
    fun `an unknown scheme is refused`() {
        val threw = try {
            JwtMinter.mint(amoSpec.copy(scheme = "Token"), key, nowMs = 1_700_000_000_000)
            false
        } catch (e: PluginError) {
            assertTrue(e.message!!.contains("scheme"))
            true
        }
        assertTrue(threw)
    }

    @Test
    fun `the kid appears in the header only when set`() {
        val without = JwtMinter.mint(amoSpec, key, nowMs = 1_700_000_000_000)
        assertFalse(JSONObject(decodeSegment(without.token.split(".")[0])).has("kid"))

        val with = JwtMinter.mint(amoSpec.copy(kid = "key-1"), key, nowMs = 1_700_000_000_000)
        assertEquals("key-1", JSONObject(decodeSegment(with.token.split(".")[0])).getString("kid"))
    }

    // --- helpers -----------------------------------------------------------

    private fun decodeSegment(seg: String): String =
        String(java.util.Base64.getUrlDecoder().decode(seg), Charsets.UTF_8)

    private fun b64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The field-level validation. Each case here is a real paste a human makes: the account number
 * instead of the API key, a truncated key, a JSON blob in the claims box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JwtRulesTest {

    @Test
    fun `a complete Mozilla key is accepted`() {
        assertEquals("user:543210:23", JwtRules.requireIssuer("user:543210:23"))
        assertEquals("user:543210:23", JwtRules.requireIssuer("  user:543210:23  "))
        assertTrue(JwtRules.looksLikeAmoKey("user:543210:23"))
    }

    @Test
    fun `the bare account number is refused with the actual mistake named`() {
        // The single most likely paste: the credentials page shows the account id nearby.
        val e = assertThrowsPluginError { JwtRules.requireIssuer("543210") }
        assertTrue(
            "the message must say it is the account id, got: ${e.message}",
            e.message!!.contains("account id")
        )
    }

    @Test
    fun `a truncated key is refused and the missing segment is named`() {
        val e = assertThrowsPluginError { JwtRules.requireIssuer("user:543210") }
        assertTrue(e.message!!.contains("user:<number>:<number>"))
    }

    @Test
    fun `non-Mozilla issuers are allowed`() {
        // The plugin must not be AMO-shaped: a URL or a client id is a legitimate issuer elsewhere.
        for (v in listOf(
            "https://issuer.example.com",
            "my-client-id",
            "abc123@clients",
            "user@example.com",
        )) {
            assertEquals(v, JwtRules.requireIssuer(v))
        }
    }

    @Test
    fun `an issuer with a space or a blank one is refused`() {
        assertThrowsPluginError { JwtRules.requireIssuer("") }
        assertThrowsPluginError { JwtRules.requireIssuer("has a space") }
    }

    @Test
    fun `a short secret is refused with its actual byte count`() {
        val e = assertThrowsPluginError { JwtRules.requireKeyLength("HS256", "short") }
        assertTrue("must name the required size", e.message!!.contains("32"))
        assertTrue("must name what was given", e.message!!.contains("5"))
    }

    @Test
    fun `a 32-byte secret is accepted`() {
        val size = JwtRules.requireKeyLength("HS256", "0123456789abcdef0123456789abcdef")
        assertEquals(32, size)
    }

    @Test
    fun `extra claims parse as an object of scalars`() {
        val claims = JwtRules.parseExtraClaims("""{"aud":"api","n":1,"ok":true}""")
        assertEquals("api", claims["aud"])
        assertEquals(1, claims["n"])
        assertEquals(true, claims["ok"])
    }

    @Test
    fun `an empty claims field means no claims`() {
        assertTrue(JwtRules.parseExtraClaims("").isEmpty())
        assertTrue(JwtRules.parseExtraClaims("   ").isEmpty())
    }

    @Test
    fun `a reserved claim in the claims box is refused`() {
        for (reserved in JwtSpec.RESERVED_CLAIMS) {
            val e = assertThrowsPluginError {
                JwtRules.parseExtraClaims("""{"$reserved":"x"}""")
            }
            assertTrue(e.message!!.contains(reserved))
        }
    }

    @Test
    fun `nested claims are refused rather than silently sent`() {
        assertThrowsPluginError { JwtRules.parseExtraClaims("""{"aud":["a","b"]}""") }
        assertThrowsPluginError { JwtRules.parseExtraClaims("""{"nested":{"a":1}}""") }
        assertThrowsPluginError { JwtRules.parseExtraClaims("""{"n":null}""") }
    }

    @Test
    fun `a non-object claims field is refused`() {
        assertThrowsPluginError { JwtRules.parseExtraClaims("not json") }
        assertThrowsPluginError { JwtRules.parseExtraClaims("""["a"]""") }
    }

    private fun assertThrowsPluginError(block: () -> Unit): PluginError {
        try {
            block()
        } catch (e: PluginError) {
            return e
        }
        throw AssertionError("expected a PluginError")
    }
}

/**
 * The stored record: config that survives a round trip, and fails loudly when it cannot.
 *
 * Every refusal here is a value that would produce a token the upstream rejects with no explanation,
 * so the test asserts the message rather than only that something threw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JwtEntryTest {

    @Test
    fun `a full entry round trips`() {
        val entry = JwtEntry(
            issuer = "user:1:2",
            alg = "HS256",
            ttlSeconds = 120,
            skewSeconds = 10,
            scheme = "Bearer",
            kid = "k1",
            extraClaims = mapOf("aud" to "api"),
            docs = "https://addons.mozilla.org",
        )
        val back = JwtEntry.fromJson(entry.toJson().toString())
        assertEquals(entry, back)
    }

    @Test
    fun `a minimal entry round trips with defaults`() {
        val back = JwtEntry.fromJson(JwtEntry(issuer = "user:1:2").toJson().toString())
        assertEquals("HS256", back.alg)
        assertEquals(60, back.ttlSeconds)
        assertEquals(30, back.skewSeconds)
        assertEquals("JWT", back.scheme)
        assertEquals("", back.kid)
        assertTrue(back.extraClaims.isEmpty())
    }

    @Test
    fun `a stored record with no issuer refuses`() {
        val e = try {
            JwtEntry.fromJson("""{"alg":"HS256"}""")
            throw AssertionError("expected a refusal")
        } catch (e: PluginError) {
            e
        }
        assertEquals("jwt_bad_config", e.code)
    }

    @Test
    fun `a stored record naming an unsupported alg refuses`() {
        try {
            JwtEntry.fromJson("""{"issuer":"x","alg":"RS512"}""")
            throw AssertionError("expected a refusal")
        } catch (e: PluginError) {
            assertEquals("jwt_bad_config", e.code)
            assertTrue(e.message!!.contains("RS512"))
        }
    }

    @Test
    fun `an out-of-range ttl in storage refuses rather than clamping`() {
        // Clamping would silently change what the config says; a token with an unexpected lifetime
        // is worse than a refusal that names the record.
        try {
            JwtEntry.fromJson("""{"issuer":"x","ttlSeconds":99999}""")
            throw AssertionError("expected a refusal")
        } catch (e: PluginError) {
            assertEquals("jwt_bad_config", e.code)
        }
    }

    @Test
    fun `stored extra claims that shadow a reserved one refuse`() {
        try {
            JwtEntry.fromJson("""{"issuer":"x","extraClaims":{"exp":1}}""")
            throw AssertionError("expected a refusal")
        } catch (e: PluginError) {
            assertEquals("jwt_bad_config", e.code)
        }
    }

    @Test
    fun `malformed JSON refuses with a coded error, not a cast exception`() {
        try {
            JwtEntry.fromJson("{not json")
            throw AssertionError("expected a refusal")
        } catch (e: PluginError) {
            assertEquals("jwt_bad_config", e.code)
        }
    }

    @Test
    fun `toSpec carries every signing field and nothing else`() {
        val entry = JwtEntry(
            issuer = "user:1:2",
            ttlSeconds = 90,
            skewSeconds = 5,
            scheme = "Bearer",
            kid = "k",
            extraClaims = mapOf("aud" to "a"),
            docs = "https://example.com",
        )
        val spec = entry.toSpec()
        assertEquals("user:1:2", spec.issuer)
        assertEquals(90, spec.ttlSeconds)
        assertEquals(5, spec.skewSeconds)
        assertEquals("Bearer", spec.scheme)
        assertEquals("k", spec.kid)
        assertEquals(mapOf("aud" to "a"), spec.extraClaims)
    }

    @Test
    fun `the vault name is prefixed and reversible`() {
        assertEquals("jwt-amo", com.mido.phonebridge.plugins.jwt.JwtStore.vaultName("amo"))
        assertEquals("amo", com.mido.phonebridge.plugins.jwt.JwtStore.nameOf("jwt-amo"))
        // A name that is not one of ours must not be read as one.
        assertEquals(null, com.mido.phonebridge.plugins.jwt.JwtStore.nameOf("amo"))
        assertEquals(null, com.mido.phonebridge.plugins.jwt.JwtStore.nameOf("jwt-"))
    }

    @Test
    fun `only HS256 is registered today and it is discoverable`() {
        assertEquals(listOf("HS256"), JwtSigners.names())
        assertTrue(JwtSigners.isSupported("hs256"))
        assertFalse(JwtSigners.isSupported("RS256"))
    }
}

/**
 * The reuse rules: a token is reused while it is comfortably valid, and never after.
 *
 * These are the cases that decide whether a caller sees a working call or an uncaused 401.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MintedTokenReuseTest {

    private fun token(issued: Long, expires: Long) = com.mido.phonebridge.plugins.jwt.MintedToken(
        token = "a.b.c", issuedAtSec = issued, expiresAtSec = expires,
    )

    @Test
    fun `a fresh token is reusable`() {
        val t = token(1000, 1060)
        assertTrue(t.usableAt(nowMs = 1000_000))
    }

    @Test
    fun `a token inside the reuse margin is not reused`() {
        // 10s left, margin 15s: handing this to an upstream invites a 401 that looks like a bad key.
        val t = token(1000, 1060)
        assertFalse(t.usableAt(nowMs = 1050_000))
    }

    @Test
    fun `an expired token is never reused`() {
        val t = token(1000, 1060)
        assertFalse(t.usableAt(nowMs = 1200_000))
    }

    @Test
    fun `a backwards clock makes a token look spent rather than fresh`() {
        // The cache must mint again, not serve something whose iat/exp are hours in the future
        // relative to the corrected clock: every upstream would reject it for `iat` in the future.
        val t = token(2000, 2060)
        assertFalse("a token minted 'later' must not be reused after the clock jumps back", t.usableAt(nowMs = 0))
        assertFalse(t.usableAt(nowMs = 1_500_000))
    }

    @Test
    fun `a token backdated by skew is still reusable`() {
        // The normal case must not be broken by the future-clock rule: `iat` legitimately sits in the
        // past by the configured skew, and that is a fresh token, not a stale one.
        val t = token(issued = 1970, expires = 2030)
        assertTrue(t.usableAt(nowMs = 2000_000))
    }
}
