package com.mido.phonebridge.plugins.oauth

import com.mido.phonebridge.core.PluginError
import org.json.JSONObject

/**
 * The SECRET half of an OAuth credential, as it is stored in the vault.
 *
 * One vault entry, two fields, serialized as JSON. That shape is the reason the `oauth` plugin
 * exists at all: RFC 6749 §6 requires the client secret AND the refresh token in the same token
 * request, and `SecretBroker` replaces exactly one value for `***`. Two separate `secret` entries
 * could not both be stepped into one call.
 *
 * **Nothing here is ever logged, echoed, or returned.** The only place these strings go is the
 * token request body and its Authorization header.
 *
 * **[refreshToken] is OPTIONAL, and that is deliberate.** Mido, 2026-11-20: *"i don't like being
 * unblocked by a required field that blocks the json import until I do many steps. we should accept
 * the json data."* A downloaded client file cannot contain a refresh token — it is the client half
 * only — so demanding one refused a file that was perfectly good. A credential may now exist
 * complete-except-for-the-token, which is a named state ([status]) rather than a silent failure:
 * the screen shows it, `/oauth` reports it, and a call refuses before the gate with its own code.
 */
data class OauthSecret(
    /** Empty when the credential has been imported but not yet authorised. */
    val refreshToken: String,
    val clientSecret: String,
) {

    /**
     * Whether this credential can mint at all.
     *
     * The single place that answers the question, so the screen, the plugin and `GET /oauth`
     * cannot each decide it differently.
     */
    val status: OauthSecretStatus
        get() = if (refreshToken.isBlank()) OauthSecretStatus.NEEDS_REFRESH_TOKEN
        else OauthSecretStatus.READY

    fun toJson(): String = JSONObject()
        .put(FIELD_REFRESH, refreshToken)
        .put(FIELD_SECRET, clientSecret)
        .toString()

    companion object {
        const val FIELD_REFRESH = "refresh_token"
        const val FIELD_SECRET = "client_secret"

        /**
         * Parse the stored value.
         *
         * **The client secret is still required; the refresh token is not.** A credential with no
         * client secret cannot be used even after authorisation, so storing one would be storing a
         * dead entry. A credential with no refresh token is a real, visible, finishable state.
         */
        fun fromVault(raw: String): OauthSecret {
            if (raw.isBlank()) {
                throw PluginError("oauth_bad_secret", "the stored credential for this name is empty")
            }
            val o = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw PluginError(
                    "oauth_bad_secret",
                    "the stored credential is not the expected JSON object — re-enter it on the " +
                        "OAuth screen"
                )
            }
            val secret = o.optString(FIELD_SECRET, "").trim()
            if (secret.isEmpty()) {
                throw PluginError(
                    "oauth_bad_secret",
                    "the stored credential has no '${FIELD_SECRET}' — re-enter it on the OAuth screen"
                )
            }
            // Absent and blank mean the same thing here: not yet authorised. Neither is an error.
            val refresh = o.optString(FIELD_REFRESH, "").trim()
            return OauthSecret(refreshToken = refresh, clientSecret = secret)
        }
    }
}

/**
 * Whether a stored OAuth credential can actually mint a token.
 *
 * **A named state, not an absence.** The alternative — treating "no refresh token" as a parse error
 * — is exactly what made the import useless: the file was accepted, then discarded because a field
 * it could not contain was empty. And the other alternative, a placeholder value, is worse still:
 * it would fail later and further away, at the provider, as `invalid_grant`.
 */
enum class OauthSecretStatus {
    /** Has both halves; can mint. */
    READY,

    /** Imported, not yet authorised: every call refuses until the follow-up is completed. */
    NEEDS_REFRESH_TOKEN,
    ;

    /** The value `GET /oauth` reports, so a caller sees the state without guessing. */
    val wireName: String get() = when (this) {
        READY -> "ready"
        NEEDS_REFRESH_TOKEN -> "needs_refresh_token"
    }
}
