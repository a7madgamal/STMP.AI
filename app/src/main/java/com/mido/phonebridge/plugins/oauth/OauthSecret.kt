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
 */
data class OauthSecret(
    val refreshToken: String,
    val clientSecret: String,
) {

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
         * Both fields are required and fail by name. A missing refresh token is not a recoverable
         * state — it is a credential that cannot mint — and a message saying which field is absent
         * is the difference between "re-enter the token" and "this plugin is broken".
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
            val refresh = o.optString(FIELD_REFRESH, "").trim()
            if (refresh.isEmpty()) {
                throw PluginError(
                    "oauth_bad_secret",
                    "the stored credential has no '${FIELD_REFRESH}' — re-enter it on the OAuth screen"
                )
            }
            val secret = o.optString(FIELD_SECRET, "").trim()
            if (secret.isEmpty()) {
                throw PluginError(
                    "oauth_bad_secret",
                    "the stored credential has no '${FIELD_SECRET}' — re-enter it on the OAuth screen"
                )
            }
            return OauthSecret(refreshToken = refresh, clientSecret = secret)
        }
    }
}
