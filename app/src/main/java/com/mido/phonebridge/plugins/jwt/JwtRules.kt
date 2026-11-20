package com.mido.phonebridge.plugins.jwt

import com.mido.phonebridge.core.PluginError
import org.json.JSONObject

/**
 * The validation rules for a JWT entry's identifying fields.
 *
 * Pure, no Android, no storage — the same split as `HostAllowlist` and `SecretRules`, and for the
 * same reason: the rule that decides whether a credential may be used has to be testable without a
 * device, and this one is checked in TWO places (the dialog, and again at the vault boundary). One
 * implementation, so the screen cannot accept something the plugin then refuses.
 */
object JwtRules {

    /**
     * The classic Mozilla API key shape: `user:<digits>:<digits>`.
     *
     * Deliberately used only to DIAGNOSE, never to reject on its own. A bare numeric id is by far
     * the most likely mistake — the credentials page shows the account number in the URL and the
     * API key in a separate row — and refusing it here returns the message while the human still
     * has both values in front of them. Any other shape is allowed through, because WebFinger,
     * Okta, Auth0 and a handful of others use a URL or a client id and none of them look like
     * Mozilla's.
     */
    private val AMO_KEY = Regex("""user:\d+:\d+""")

    /** What an issuer must look like at minimum: printable, no whitespace, bounded length. */
    private val ISSUER_SAFE = Regex("""[!-~]{1,512}""")

    /**
     * Validate [issuer], returning it trimmed.
     *
     * Throws with a message that names the likely cause, because the failure it prevents is silent:
     * a wrong `iss` produces a 401 from the upstream that says nothing about which claim was wrong.
     */
    fun requireIssuer(issuer: String): String {
        val v = issuer.trim()
        if (v.isEmpty()) throw PluginError("bad_request", "issuer (API key) is required")

        // **The complete shape FIRST.** The incomplete-shape check below is deliberately broad
        // (`user:` followed by digits and colons), so it also matches a perfectly valid key — which
        // it did, refusing every real Mozilla API key with "the trailing segment is easy to miss"
        // about a trailing segment that was present. The valid shape has to be recognised before the
        // shape that merely resembles it.
        if (v.matches(AMO_KEY)) return v

        // The one mistake worth naming. A bare number is the account id, and it is one field away
        // from the key on Mozilla's own page.
        if (v.matches(Regex("""\d+"""))) {
            throw PluginError(
                "bad_request",
                "this looks like your account id (the number), not the API key. Mozilla's key " +
                    "looks like user:123456:7 — copy the whole value."
            )
        }
        if (v.startsWith("user:")) {
            throw PluginError(
                "bad_request",
                "'$v' is not a complete API key. It must be user:<number>:<number> — " +
                    "the trailing segment is easy to miss."
            )
        }
        if (!v.matches(ISSUER_SAFE)) {
            throw PluginError(
                "bad_request",
                "issuer must be printable ASCII with no spaces (got ${v.length} chars)"
            )
        }
        return v
    }

    /** True when [issuer] has Mozilla's `user:<n>:<n>` shape. Used by the screen for a hint only. */
    fun looksLikeAmoKey(issuer: String): Boolean = issuer.trim().matches(AMO_KEY)

    /**
     * Whether a key length is acceptable for [alg].
     *
     * Kept separate from the minter so the dialog can refuse a short secret BEFORE it is stored —
     * a bad key that is saved is a bad key that resurfaces as a failed call with no memory of the
     * paste.
     */
    fun requireKeyLength(alg: String, keyMaterial: String): Int {
        val signer = JwtSigners.require(alg)
        val size = keyMaterial.toByteArray(Charsets.UTF_8).size
        if (size < signer.minKeyBytes) {
            throw PluginError(
                "bad_request",
                "${signer.name} needs a secret of at least ${signer.minKeyBytes} bytes; " +
                    "this one is $size. Check you copied the whole secret."
            )
        }
        return size
    }

    /**
     * Parse the extra-claims field, which is JSON authored by hand on the phone.
     *
     * Refuses anything that is not an object, refuses reserved names, and refuses nested values:
     * a claim whose value is an object or an array is legal JWT but almost always a pasted config
     * blob rather than something the person meant, and the screen has no way to show it back
     * usefully. Scalar values only — which covers `aud`, `sub`, `scope` and the rest in practice.
     */
    fun parseExtraClaims(raw: String): Map<String, Any> {
        if (raw.isBlank()) return emptyMap()
        val o = try {
            org.json.JSONObject(raw)
        } catch (e: Exception) {
            throw PluginError("bad_request", "extra claims must be a JSON object: ${e.message}")
        }
        val out = mutableMapOf<String, Any>()
        for (k in o.keys()) {
            val v = o.get(k)
            when (v) {
                is String, is Number, is Boolean -> out[k] = v
                JSONObject.NULL -> throw PluginError(
                    "bad_request",
                    "claim '$k' is null — omit it instead of setting a null value"
                )
                else -> throw PluginError(
                    "bad_request",
                    "claim '$k' must be a plain value (string, number or boolean), not " +
                        "${if (v is org.json.JSONArray) "an array" else "an object"}"
                )
            }
        }
        val conflicts = out.keys.filter { it in JwtSpec.RESERVED_CLAIMS }
        if (conflicts.isNotEmpty()) {
            throw PluginError(
                "bad_request",
                "${conflicts.joinToString(", ")} ${if (conflicts.size == 1) "is" else "are"} set " +
                    "by this plugin — remove " + (if (conflicts.size == 1) "it" else "them") +
                    " from extra claims"
            )
        }
        return out
    }
}
