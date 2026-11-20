package com.mido.phonebridge.plugins.oauth

import com.mido.phonebridge.core.PluginError
import org.json.JSONObject

/**
 * What a downloaded Google OAuth client file can tell us, and nothing more.
 *
 * The file is the CLIENT half of a credential. It carries no refresh token, no scope and no API
 * host — those come from an authorisation round trip and from the human — so this type deliberately
 * has no fields pretending otherwise.
 */
data class OauthClientImport(
    val clientId: String,
    val clientSecret: String,
    val tokenUri: String,
    /** A suggested credential name, derived from the client id. The human may change it. */
    val name: String,
    /**
     * The redirect URIs the client is registered for.
     *
     * Not a form field: this is reported so a mismatch is visible while the client is being
     * imported, rather than surfacing later as a provider error.
     */
    val redirectUris: List<String>,
) {
}

/**
 * Parses a Google `client_secret_*.json` into the fields the OAuth screen can pre-fill.
 *
 * **Why a separate object rather than a method on `OauthActivity`.** This is the only part of the
 * import with rules — an outer wrapper key, five mandatory members, an https check, a size cap — and
 * a rule that lives inside an Activity cannot be tested without a device. Everything here is pure.
 *
 * **The schema is not guessed.** Google's client-library documentation states the format: one of two
 * outer keys, `web` (Web application) or `installed` (Desktop), and inside it five MANDATORY members
 * — `client_id`, `client_secret`, `redirect_uris`, `auth_uri`, `token_uri` — plus optional
 * `client_email` and two certificate URLs. That is why a missing `token_uri` is refused rather than
 * defaulted: the format does not have a variant without one, so its absence means a truncated or
 * hand-edited file, and inventing an endpoint would paper over that with a URL nobody chose.
 *
 * **This input is untrusted.** It is a file the human downloaded, from a browser, possibly not even
 * from Google. So: a size cap before parsing, an https re-check on `token_uri` (the format says
 * https, but the FILE is what is being read), and every refusal names a field while never echoing a
 * value — an error that quotes the client secret would put it in a toast, and toasts get
 * screenshotted.
 */
object OauthClientJson {

    /** The Playground's redirect URI, which is what makes it usable at all. */

    /**
     * A real client file is well under 1 KiB. 64 KiB is a ceiling on a hostile or accidentally huge
     * file, not a guess at the normal size.
     */
    const val MAX_BYTES = 64 * 1024

    /** The two wrapper keys Google documents. Anything else is refused by name. */
    private val WRAPPERS = listOf("web", "installed")

    private val MANDATORY = listOf(
        "client_id", "client_secret", "redirect_uris", "auth_uri", "token_uri",
    )

    /**
     * Parse [raw], or throw with a message that says what to fix.
     *
     * @throws PluginError `oauth_import_failed`, always. The code is one because the repair is
     *   always the same: pick a different file.
     */
    fun parse(raw: String): OauthClientImport {
        if (raw.isBlank()) refuse("the file is empty")
        // Cheap check on the STRING rather than bytes: this is already in memory, and the caller
        // read it under the same cap. Kept as a guard so a caller that forgets still cannot parse
        // something huge.
        if (raw.length > MAX_BYTES) {
            refuse("the file is larger than ${MAX_BYTES / 1024} KiB, which a client secret file never is")
        }

        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            refuse("this is not JSON — pick the client_secret_*.json file downloaded from Google")
        }

        // The wrapper key is the TYPE of client, not a field name. Report what was actually found,
        // because "expected web or installed, found 'service_account'" is the whole explanation for
        // someone who downloaded the wrong file from the same console page.
        val wrapperKey = WRAPPERS.firstOrNull { root.has(it) }
        if (wrapperKey == null) {
            val found = root.keys().asSequence().toList()
            val suggestion = when {
                found.contains("type") && root.optString("type") == "service_account" ->
                    " — that is a service-account key, not an OAuth client. Use the OAuth client " +
                        "under Credentials, not a service account."
                found.isEmpty() -> " (the object is empty)"
                else -> " (found: ${found.joinToString(", ")})"
            }
            refuse("no 'web' or 'installed' object at the top level$suggestion")
        }

        val inner = root.optJSONObject(wrapperKey)
            ?: refuse("'$wrapperKey' is present but is not an object")

        // Report EVERY missing mandatory member at once. One at a time would make a truncated file
        // cost a round trip per field.
        val missing = MANDATORY.filter { inner.optString(it, "").isBlank() }
        if (missing.isNotEmpty()) {
            refuse(
                "the file is missing ${missing.joinToString(", ")}. All five of " +
                    "${MANDATORY.joinToString(", ")} are mandatory in Google's client file — this " +
                    "one looks truncated or hand-edited."
            )
        }

        val clientId = inner.optString("client_id").trim()
        val clientSecret = inner.optString("client_secret").trim()
        val tokenUri = inner.optString("token_uri").trim()

        // Re-checked here even though the format says https: the FILE is what is being read, and a
        // client secret posted over cleartext is a secret in someone else's log.
        if (!tokenUri.startsWith("https://")) {
            refuse("token_uri in the file is not https, so this build will not use it")
        }

        val redirects = inner.optJSONArray("redirect_uris")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").trim().ifEmpty { null } }
        } ?: emptyList()

        return OauthClientImport(
            clientId = clientId,
            clientSecret = clientSecret,
            tokenUri = tokenUri,
            name = deriveName(clientId),
            redirectUris = redirects,
        )
    }

    /**
     * A starting name from the client id, because the human has to type one anyway.
     *
     * A Google client id looks like `503841343554-j8d5….apps.googleusercontent.com`. The project
     * number is the least useful part; the tail before `.apps` is at least distinctive. When
     * nothing useful can be derived the caller gets a fixed stem and can edit it — a name is a
     * label, not a credential.
     */
    internal fun deriveName(clientId: String): String {
        val stem = clientId.substringBefore('.').substringAfterLast('-')
        val cleaned = stem.lowercase().replace(Regex("[^a-z0-9_-]"), "")
        // SecretRules requires [a-z0-9_-], 1-64 chars, starting alphanumeric.
        val safe = cleaned.take(48).trimStart('-', '_')
        return safe.ifEmpty { "oauth-client" }
    }

    private fun refuse(message: String): Nothing =
        throw PluginError("oauth_import_failed", message)
}
