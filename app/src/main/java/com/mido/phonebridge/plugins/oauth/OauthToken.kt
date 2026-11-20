package com.mido.phonebridge.plugins.oauth

import com.mido.phonebridge.core.PluginError

/**
 * One minted access token, with the window it is valid for.
 *
 * Never persisted, never returned to a caller, never audited. It exists in memory between the
 * token request and the outbound call, and is dropped when the cache forgets it.
 */
data class OauthToken(
    val accessToken: String,
    /** Epoch seconds. Used only for the cache decision, never sent anywhere. */
    val expiresAtSec: Long,
)

/**
 * The refusal for a rotated refresh token.
 *
 * RFC 6749 §6 says a new refresh token MUST replace the old one. Writing it back would mean
 * mutating the vault DURING a request — mid-call, with a fingerprint prompt — which is both a
 * worse experience and a race. Failing visibly and asking the human to re-enter it is the honest
 * option: a silently continued call would use a token the provider has just invalidated.
 *
 * A factory rather than an exception subclass, because [PluginError] is final and its `code` is the
 * only thing dispatch needs. The rejected value is a parameter, never a field: holding it would put
 * a fresh credential in a stack trace for no benefit.
 */
internal fun rotatedRefusal(): PluginError = PluginError(
    "refresh_token_rotated",
    "the provider issued a NEW refresh token, which this build will not write back to the vault " +
        "during a call. Open the OAuth screen, replace the refresh token with the new one, and " +
        "retry. (RFC 6749 §6: a rotated refresh token must replace the old one.)",
)

/**
 * Flatten a provider error body into one line that names itself.
 *
 * RFC 6749 §5.2 defines `error` and `error_description`. Using them verbatim is the difference
 * between "invalid_grant: refresh token expired" and a generic 400 that reads as a plugin bug.
 */
internal fun tokenErrorLine(status: Int, body: String): String {
    val parsed = runCatching { org.json.JSONObject(body) }.getOrNull()
    if (parsed != null) {
        val code = parsed.optString("error", "").trim()
        val desc = parsed.optString("error_description", "").trim()
        if (code.isNotEmpty()) {
            return if (desc.isEmpty()) "$code (HTTP $status)" else "$code: $desc (HTTP $status)"
        }
    }
    val trimmed = body.trim().take(300)
    return if (trimmed.isEmpty()) "HTTP $status with an empty body" else "HTTP $status: $trimmed"
}
