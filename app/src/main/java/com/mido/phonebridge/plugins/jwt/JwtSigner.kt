package com.mido.phonebridge.plugins.jwt

import com.mido.phonebridge.core.PluginError

/**
 * How one JWT algorithm signs, named rather than switched on.
 *
 * **Why an interface and a registry instead of a `when (alg)`.** The launch requirement is HMAC
 * only (Mozilla AMO). Adding RS256/ES256 later must be one new object and one registry line, not a
 * second branch threaded through the route, the store and the screen. With a registry, the `alg`
 * field on the screen is built FROM the registry, so an implementation that exists is choosable and
 * one that does not is simply absent — there is no list to keep in sync with reality.
 *
 * [keyMaterial] is the vault value verbatim. For HMAC that is the shared secret; for an asymmetric
 * algorithm it will be the PEM private key, and nothing outside the signer has to care.
 */
interface JwtSigner {

    /** The JOSE `alg` value, e.g. `HS256`. Also the registry key. */
    val name: String

    /**
     * The shortest key this algorithm may be used with, in bytes.
     *
     * A property of the algorithm, not a preference: RFC 7518 §3.2 requires an HS256 key at least
     * as long as the hash output, and a shorter one is a real weakness rather than a style choice.
     * Declared here so the validator can refuse at save time instead of letting a call fail later.
     */
    val minKeyBytes: Int

    /** Sign [input] (the `header.payload` ASCII bytes) and return the raw signature bytes. */
    fun sign(keyMaterial: String, input: ByteArray): ByteArray
}

/**
 * HMAC-SHA256, the only algorithm at launch.
 *
 * Uses JCA's `Mac`, which is present on every Android since forever — no bundled crypto library, no
 * JNI, nothing to audit but this file.
 */
object HmacSha256 : JwtSigner {

    override val name = "HS256"

    /** 32 bytes: the hash length, per RFC 7518 §3.2. */
    override val minKeyBytes = 32

    override fun sign(keyMaterial: String, input: ByteArray): ByteArray {
        val key = keyMaterial.toByteArray(Charsets.UTF_8)
        // Refused here as well as at save time. A key that reached this point shorter than the
        // minimum is a defect somewhere upstream, and signing anyway would hide it.
        if (key.size < minKeyBytes) {
            throw PluginError(
                "bad_request",
                "an $name key must be at least $minKeyBytes bytes; this one is ${key.size}"
            )
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
}

/**
 * The algorithms this build can sign with.
 *
 * Order is stable ([names] sorts) so the screen's picker does not reorder itself between opens.
 */
object JwtSigners {

    private val registry: Map<String, JwtSigner> = mapOf(
        HmacSha256.name to HmacSha256,
    )

    /** Every supported `alg`, sorted. The screen builds its picker from this and nothing else. */
    fun names(): List<String> = registry.keys.sorted()

    fun isSupported(alg: String): Boolean = registry.containsKey(alg.trim().uppercase())

    /**
     * The signer for [alg], or a refusal naming what IS supported.
     *
     * Fails loud rather than defaulting to HS256: silently signing with a different algorithm than
     * the one configured produces a token the upstream rejects with no explanation, and the config
     * says one thing while the wire says another.
     */
    fun require(alg: String): JwtSigner {
        val key = alg.trim().uppercase()
        return registry[key] ?: throw PluginError(
            "bad_request",
            "unsupported jwt alg '$alg'; this build supports ${names().joinToString(", ")}"
        )
    }
}
