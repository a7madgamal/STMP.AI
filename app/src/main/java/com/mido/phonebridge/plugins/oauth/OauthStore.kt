package com.mido.phonebridge.plugins.oauth

import android.content.Context
import com.mido.phonebridge.core.PluginError
import org.json.JSONObject

/**
 * A stored OAuth2 credential: the PUBLIC half of it.
 *
 * **The two secrets are NOT here.** They live in [com.mido.phonebridge.core.SecretVault] under
 * `oauth-<name>`, behind the fingerprint, as one JSON object:
 *
 * ```
 * {"refresh_token":"…","client_secret":"…"}
 * ```
 *
 * One vault entry holding several fields is the whole reason this plugin exists rather than two
 * plain `secret` entries: RFC 6749 §6 needs the client secret AND the refresh token in the same
 * request, and `SecretBroker` substitutes exactly one value for `***`.
 *
 * This record is what must be readable while the vault is LOCKED — the screen renders it, `GET
 * /oauth` lists it, and a locked vault must not make the list answer "unknown". Same split
 * `JwtEntry` makes, for the same reason: a client id, a URL and a host rule are not credentials.
 */
data class OauthEntry(
    /**
     * The OAuth client id. **Deliberately plaintext and out of the vault** — a client id ships
     * inside every OAuth client, web or mobile, and is not a secret by design. Putting it behind a
     * fingerprint would mean being unable to read or copy a value that is safe to print.
     */
    val clientId: String = "",
    /** Where tokens are minted. https only, enforced at save and again at mint. */
    val tokenUrl: String,
    /**
     * The granted scope, for DISPLAY ONLY.
     *
     * **Never sent on a refresh.** RFC 6749 §6 permits `scope` on a refresh only as a subset of what
     * was originally granted, and a provider that disagrees answers `invalid_scope`. Omitting it is
     * defined as "same as originally granted", which is what a refresh means.
     */
    val scope: String = "",
    /**
     * How the client authenticates at the token endpoint: [AUTH_BASIC] or [AUTH_BODY].
     *
     * Basic is the default because RFC 6749 §2.3.1 says a server **MUST** support it for clients
     * issued a password, while credentials in the form body are only a **MAY**. Getting this
     * backwards yields `invalid_client`, which is indistinguishable from a wrong secret.
     */
    val authStyle: String = AUTH_BASIC,
    /** Where its API is documented. Plaintext, exactly like a secret's docs link. */
    val docs: String = "",
) {

    fun toJson(): JSONObject = JSONObject()
        .put("clientId", clientId)
        .put("tokenUrl", tokenUrl)
        .put("scope", scope)
        .put("authStyle", authStyle)
        .put("docs", docs)

    companion object {
        const val AUTH_BASIC = "basic"
        const val AUTH_BODY = "body"

        val AUTH_STYLES = listOf(AUTH_BASIC, AUTH_BODY)

        /**
         * Parse a stored record.
         *
         * `tokenUrl` is REQUIRED and fails loudly when absent: a credential with no token endpoint
         * is not a degraded credential, it is one that cannot work, and defaulting it would produce
         * a request to a URL nobody chose.
         */
        fun fromJson(raw: String): OauthEntry {
            val o = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw PluginError("oauth_bad_config", "stored oauth config is not JSON: ${e.message}")
            }
            val tokenUrl = o.optString("tokenUrl", "").trim()
            if (tokenUrl.isEmpty()) {
                throw PluginError("oauth_bad_config", "stored oauth config has no token url")
            }
            val style = o.optString("authStyle", AUTH_BASIC).trim().ifEmpty { AUTH_BASIC }
            if (style !in AUTH_STYLES) {
                throw PluginError(
                    "oauth_bad_config",
                    "stored oauth authStyle '$style' is not one this build can use"
                )
            }
            return OauthEntry(
                clientId = o.optString("clientId", "").trim(),
                tokenUrl = tokenUrl,
                scope = o.optString("scope", "").trim(),
                authStyle = style,
                docs = o.optString("docs", "").trim(),
            )
        }
    }
}

/**
 * The plaintext sidecar: one [OauthEntry] per credential name.
 *
 * A separate store rather than fields on `SecretVault.SecretMeta`, for the reason `JwtStore`
 * documents: adding token-URL and auth-style knobs to the vault index would make every future
 * OAuth change a change to a structure that also holds SSH keys, and a change there is a change to
 * a file the human cannot repair by hand. This is additive — an older build does not read it.
 */
object OauthStore {

    private const val PREFS = "phonebridge_oauth"
    private const val KEY_ENTRIES = "entries"

    /** The vault name for a credential. Names are `[a-z0-9_-]`, hence the prefix. */
    fun vaultName(name: String): String = "oauth-$name"

    /** Every configured name, sorted. Readable while the vault is locked. */
    fun names(ctx: Context): List<String> = readAll(ctx).keys.sorted()

    fun get(ctx: Context, name: String): OauthEntry =
        readAll(ctx)[name] ?: throw PluginError("unknown_oauth", "no oauth credential named '$name'")

    @Synchronized
    fun put(ctx: Context, name: String, entry: OauthEntry) {
        val next = readAll(ctx).toMutableMap()
        next[name] = entry
        writeAll(ctx, next)
    }

    @Synchronized
    fun remove(ctx: Context, name: String) {
        val all = readAll(ctx)
        if (!all.containsKey(name)) return
        writeAll(ctx, all - name)
    }

    /**
     * Every stored record.
     *
     * A single unreadable record does not fail the whole list — one typo must not hide every other
     * credential. It is skipped here and surfaces through that plugin's own status, the same way
     * `JwtStore` treats a bad record.
     */
    private fun readAll(ctx: Context): Map<String, OauthEntry> {
        val raw = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyMap()
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw PluginError("oauth_bad_config", "oauth config store is unreadable: ${e.message}")
        }
        val out = mutableMapOf<String, OauthEntry>()
        for (name in o.keys()) {
            val entryRaw = o.optJSONObject(name)?.toString() ?: continue
            runCatching { OauthEntry.fromJson(entryRaw) }.getOrNull()?.let { out[name] = it }
        }
        return out
    }

    private fun writeAll(ctx: Context, all: Map<String, OauthEntry>) {
        val o = JSONObject()
        for ((name, entry) in all) o.put(name, entry.toJson())
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, o.toString())
            .commit()
    }
}
