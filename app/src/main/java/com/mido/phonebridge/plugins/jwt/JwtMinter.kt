package com.mido.phonebridge.plugins.jwt

import android.util.Base64
import com.mido.phonebridge.core.PluginError
import java.security.SecureRandom

/**
 * One signed token, ready to send.
 *
 * [expiresAtMs] is a wall-clock millisecond value from `exp`, carried so the cache can decide reuse
 * without re-parsing the token it already has.
 */
data class MintedToken(
    val token: String,
    val issuedAtSec: Long,
    val expiresAtSec: Long,
) {
    fun expiresAtMs(): Long = expiresAtSec * 1000L

    /**
     * Whether this token may be reused at [nowMs].
     *
     * A margin is subtracted rather than reusing right up to `exp`: a token that is valid locally
     * for another two seconds is very likely expired by the time it crosses a network and reaches
     * the upstream, and that failure is a bare 401 with no hint about which side was late.
     *
     * **A token minted in the FUTURE is never reused either**, and that is not a theoretical case.
     * When the phone's clock is corrected backwards — an NTP fix after a reboot, a flight, a manual
     * time change — a token minted under the old clock reads as valid for hours, so the cache would
     * keep serving it while every upstream rejects it for `iat` in the future, and would keep doing so
     * until it finally expired. Treating "issued after now" as spent makes the next call mint again
     * under the corrected clock, which is the whole remedy.
     *
     * Leeway of one reuse margin on the `iat` side is deliberate: the value is backdated by the
     * configured skew, so a freshly minted token legitimately has an `issuedAt` slightly in the past.
     * Only a clock jump LARGER than that margin is treated as "minted in the future".
     */
    fun usableAt(nowMs: Long, marginMs: Long = DEFAULT_REUSE_MARGIN_MS): Boolean {
        if (nowMs >= expiresAtMs() - marginMs) return false
        return nowMs >= issuedAtMs()
    }

    fun issuedAtMs(): Long = issuedAtSec * 1000L

    companion object {
        /** Reuse stops this far before `exp`. Covers network latency and modest clock drift. */
        const val DEFAULT_REUSE_MARGIN_MS = 15_000L
    }
}

/**
 * The claims a JWT for this plugin carries, plus the knobs that decide how it is signed.
 *
 * **Reserved claims are absent from [extraClaims] by construction.** They are the fields above it;
 * letting a caller or a config author also write `iss` into the extra map would create two sources
 * for one value, and whichever won would be an accident. [JwtMinter] refuses the overlap loudly
 * instead of picking.
 */
data class JwtSpec(
    /** The `iss` claim. Mozilla calls this the API key; other issuers use a URL or client id. */
    val issuer: String,
    /** JOSE algorithm name, resolved through [JwtSigners]. */
    val alg: String,
    /** Token lifetime in seconds. */
    val ttlSeconds: Int = 60,
    /**
     * How far in the PAST `iat` is placed.
     *
     * A phone clock that is a few seconds fast produces a token whose `iat` is in the future, which
     * strict upstreams answer with a 401 or an "iat in the future" and nothing actionable. Backdating
     * by a sane margin costs nothing and removes a whole class of "impossible" auth failures.
     */
    val skewSeconds: Int = 30,
    /** Prefix before the token in the Authorization header: `JWT` for Mozilla, `Bearer` elsewhere. */
    val scheme: String = "JWT",
    /** Only when the upstream requires it. Empty means "10x" is invented per token. */
    val kid: String = "",
    /** Any additional claims. Reserved names are refused. */
    val extraClaims: Map<String, Any> = emptyMap(),
) {

    /**
     * The claims that would collide with this object's own fields.
     *
     * A config author typing `iss` into the extra-claims box is trying to set something that is
     * already set, and the honest answer is to say so rather than silently ignore their value.
     */
    fun reservedClaimConflicts(): List<String> =
        extraClaims.keys.filter { it in RESERVED_CLAIMS }.sorted()

    companion object {
        /**
         * Claims this plugin owns. Every one of these is either supplied from [JwtSpec] or minted
         * per token, so a second definition would be a contradiction rather than a merge.
         */
        val RESERVED_CLAIMS: Set<String> = setOf("iss", "iat", "exp", "jti", "nbf")

        /** Bounds on the numeric fields, so a stored value can never be nonsensical. */
        const val MIN_TTL_SECONDS = 1
        const val MAX_TTL_SECONDS = 3600
        const val MAX_SKEW_SECONDS = 300

        /** Header schemes this plugin will send. Anything else is a config typo. */
        val SCHEMES: List<String> = listOf("JWT", "Bearer")

        /**
         * The same set compared case-insensitively.
         *
         * `SCHEMES` is the DISPLAY form (`Bearer` reads as a word), while every comparison in this
         * plugin normalises to uppercase first. Comparing a normalised value against the display list
         * is how `Bearer` was refused as "not one of JWT, Bearer" — a check that named the value it
         * was rejecting, from a list it was visibly on.
         */
        val SCHEMES_UPPER: Set<String> = SCHEMES.map { it.uppercase() }.toSet()

        /**
         * [raw] in its DISPLAY form (`Bearer`, not `BEARER`), or null when it is not a scheme this
         * plugin sends.
         *
         * Comparison is case-insensitive because a human types these boxes by hand, but the value is
         * stored in one canonical form so a round trip compares equal and the screen does not read
         * `BEARER` next to a `Bearer` button.
         */
        fun canonicalScheme(raw: String): String? {
            val up = raw.trim().uppercase()
            return SCHEMES.firstOrNull { it.uppercase() == up }
        }
    }
}

/**
 * Mints an HS256-shaped JWT from a [JwtSpec] and a signing secret.
 *
 * **Pure and Android-free apart from `android.util.Base64`**, which is deliberate: the base64url
 * variant lives there and the alternative is hand-rolling an encoder for a security-relevant path.
 * Everything else — claim assembly, ordering, the reserved-claim check — is plain Kotlin, which is
 * what makes it testable in the JVM suite.
 */
object JwtMinter {

    /**
     * Build and sign one token.
     *
     * [nowMs] is a parameter rather than a `System.currentTimeMillis()` call so a test can place the
     * clock anywhere, including in the past — which is the only way to prove the reuse/cache rules.
     */
    fun mint(spec: JwtSpec, keyMaterial: String, nowMs: Long): MintedToken {
        val conflicts = spec.reservedClaimConflicts()
        if (conflicts.isNotEmpty()) {
            throw PluginError(
                "bad_request",
                "extra claims must not redefine ${conflicts.joinToString(", ")}; " +
                    "those are set by this plugin"
            )
        }
        // A missing issuer is a credential that cannot work, so it is refused here as well as at
        // storage: a token with no `iss` is not a degraded token.
        if (spec.issuer.isBlank()) {
            throw PluginError("bad_request", "jwt issuer must not be empty")
        }
        if (spec.ttlSeconds !in JwtSpec.MIN_TTL_SECONDS..JwtSpec.MAX_TTL_SECONDS) {
            throw PluginError(
                "bad_request",
                "jwt ttl must be ${JwtSpec.MIN_TTL_SECONDS}..${JwtSpec.MAX_TTL_SECONDS} seconds, " +
                    "got ${spec.ttlSeconds}"
            )
        }
        if (spec.skewSeconds !in 0..JwtSpec.MAX_SKEW_SECONDS) {
            throw PluginError(
                "bad_request",
                "jwt skew must be 0..${JwtSpec.MAX_SKEW_SECONDS} seconds, got ${spec.skewSeconds}"
            )
        }
        // Validation only: the scheme does not enter the signed bytes (it decides the HEADER at send
        // time), so nothing below needs the value.
        if (JwtSpec.canonicalScheme(spec.scheme) == null) {
            throw PluginError(
                "bad_request",
                "jwt header scheme must be one of ${JwtSpec.SCHEMES.joinToString(", ")}, got '${spec.scheme}'"
            )
        }

        val signer = JwtSigners.require(spec.alg)
        val nowSec = nowMs / 1000L
        val iat = nowSec - spec.skewSeconds
        val exp = nowSec + spec.ttlSeconds

        // --- header ---
        //
        // Built by hand rather than with a JSON library: the member order is not meaningful to any
        // verifier, and a two-field object does not justify a dependency on this path.
        val header = StringBuilder()
        header.append("{\"alg\":\"").append(escape(signer.name)).append('"')
        header.append(",\"typ\":\"JWT\"")
        if (spec.kid.isNotBlank()) header.append(",\"kid\":\"").append(escape(spec.kid)).append('"')
        header.append('}')

        // --- claims ---
        //
        // Extra claims FIRST, then the fields this plugin owns. Each owned field overwrites, which
        // is belt-and-braces on top of the conflict check above: if that check is ever bypassed, the
        // token still cannot carry two `iss` values.
        val payload = org.json.JSONObject()
        for ((k, v) in spec.extraClaims) payload.put(k, v)
        payload.put("iss", spec.issuer)
        payload.put("iat", iat)
        payload.put("exp", exp)
        payload.put("jti", newJti())

        val signingInput = b64Url(header.toString().toByteArray(Charsets.UTF_8)) +
            "." + b64Url(payload.toString().toByteArray(Charsets.UTF_8))
        val signature = signer.sign(keyMaterial, signingInput.toByteArray(Charsets.US_ASCII))

        return MintedToken(
            token = "$signingInput.${b64Url(signature)}",
            issuedAtSec = iat,
            expiresAtSec = exp,
        )
    }

    /**
     * A `jti` with a high probability of being unique across recent requests.
     *
     * 16 random bytes, base64url — 128 bits. Mozilla's own documentation example uses
     * `Math.random().toString()`, which is a predictable sequence seeded from the clock; a replay
     * nonce is exactly the value where that is the wrong choice. `SecureRandom` is the JCA one and
     * needs no seeding on Android.
     */
    fun newJti(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return b64Url(bytes)
    }

    /** base64url without padding, which is what JOSE requires. */
    private fun b64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Minimal JSON string escaping, for the header fields only. */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
