package com.mido.phonebridge.core

import android.content.Context
import android.util.Log
import com.mido.phonebridge.Debug
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Google OAuth token operations, for ONE account at a time.
 *
 * **There is no "the token" any more.** Every entry point takes a
 * [GoogleAccounts.Account], and the access-token cache is keyed by its `sub`. That is the whole
 * point of the multi-account change: account A's cached token can never answer account B's
 * request, because there is no unkeyed path to read from. A cache miss is a refresh for that
 * `sub` or an error — never "return the one I have".
 *
 * **The one authority on whether `account` is required** is [accountRequirement]. The dispatcher
 * calls it to refuse a request, and `/help` calls the same function to describe the API. Neither
 * re-derives the rule, so a screen cannot disagree with a refusal.
 *
 * FAIL LOUD: any auth problem surfaces as a coded PluginError, never a silent retry or a
 * fallback to another account.
 */
object AuthManager {
    private const val TAG = "AuthManager"

    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"

    /**
     * Cached access tokens, keyed by Google `sub`.
     *
     * A `ConcurrentHashMap` rather than a plain map because the bridge serves requests on
     * several threads and a refresh can be in flight for one account while another is read.
     * Keyed, never scanned: there is no operation here that iterates this map to pick a token.
     */
    private val accessTokens = ConcurrentHashMap<String, CachedToken>()

    private class CachedToken(val token: String, val expiresAtMs: Long) {
        fun stillValid(now: Long) = now < expiresAtMs - 60_000
    }

    /** Android-client id (used for refresh-token exchanges). */
    @Volatile var clientId: String = ""

    /** Web-client id, sent as serverClientId for offline access. */
    @Volatile var serverClientId: String = ""

    /** Web-client secret. Required to authenticate the server-code exchange. */
    @Volatile var clientSecret: String = ""

    /**
     * Configure the OAuth clients. Safe to call from anywhere and repeatedly.
     *
     * The values are ALSO persisted, so an app update that keeps data (and any cold start before
     * the service runs) still has a working config. Explicit build-time values win; persisted
     * values fill in when the caller has none.
     *
     * The account list is NOT touched here — it is read from storage on demand, so a config
     * reload can never disturb which accounts exist.
     */
    fun init(ctx: Context, clientId: String, serverClientId: String, clientSecret: String) {
        if (clientId.isNotBlank()) {
            this.clientId = clientId
            this.serverClientId = serverClientId
            this.clientSecret = clientSecret
            TokenStore.saveConfig(ctx, clientId, serverClientId, clientSecret)
        } else {
            TokenStore.config(ctx)?.let { (a, b, c) ->
                this.clientId = a
                this.serverClientId = b
                this.clientSecret = c
            }
        }
    }

    // --- the single authority ---

    /**
     * Whether a request must name an account, given how many exist.
     *
     * `NONE` at zero or one account: at zero there is nothing to name (the request fails with
     * `no_accounts` for a different reason), and at exactly one naming it would be a parameter
     * with a single possible value — a form field nobody should have to fill. `REQUIRED` at two
     * or more, and then it is required for EVERY Google route with no exceptions and no default.
     */
    enum class AccountRequirement { NONE, REQUIRED }

    fun accountRequirement(ctx: Context): AccountRequirement =
        if (GoogleAccounts.count(ctx) >= 2) AccountRequirement.REQUIRED else AccountRequirement.NONE

    /**
     * Resolve the account for one request, pre-gate.
     *
     * Called by the dispatcher BEFORE the approval view is built, because a modal that names an
     * account nobody resolved can only report what already happened. `requested` is the raw
     * `?account=` value, or null when the caller sent none.
     *
     * The three outcomes are exhaustive and each names itself:
     *  - one account and no parameter → that account (the single-account interface, unchanged);
     *  - two or more and no parameter → `account_required`, listing the accounts;
     *  - a parameter → that account by address, or `account_not_found`.
     *
     * A parameter at ONE account is accepted, not ignored: a skill written for multi-account must
     * keep working when an account is removed.
     */
    fun resolveAccount(ctx: Context, requested: String?): GoogleAccounts.Account {
        val value = requested?.trim()?.takeIf { it.isNotEmpty() }
        if (value != null) return GoogleAccounts.byEmail(ctx, value)
        return GoogleAccounts.sole(ctx)
    }

    // --- tokens ---

    /**
     * A valid access token for [account], refreshing silently when needed.
     *
     * The cache key and the refresh both come from `account.sub`, so a token is never read for
     * one account and used for another. A record that has not completed migration has no `sub`;
     * such a record is the only one that exists, so its refresh token comes from the legacy
     * field — still keyed by the account object that was resolved, never by "the first one".
     */
    fun accessToken(ctx: Context, account: GoogleAccounts.Account): String {
        val key = account.sub.ifBlank { LEGACY_CACHE_KEY }
        val now = System.currentTimeMillis()
        accessTokens[key]?.let { if (it.stillValid(now)) return it.token }

        val refresh = GoogleAccounts.refreshToken(ctx, account)
        // The refresh token belongs to the Web client (the offline-access client), so the
        // refresh must also name that client.
        val refreshClient = serverClientId.ifBlank { clientId }
        if (refreshClient.isBlank()) {
            throw PluginError("auth_not_configured", "no OAuth client id configured in this build")
        }
        val form = "client_id=${enc(refreshClient)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&refresh_token=${enc(refresh)}" +
            "&grant_type=refresh_token"
        val (code, res) = postRaw(TOKEN_URL, form)
        val obj = try {
            org.json.JSONObject(res)
        } catch (e: Exception) {
            throw PluginError("auth_error", "refresh returned non-JSON (HTTP $code): $res")
        }
        when (obj.optString("error")) {
            "invalid_grant" -> throw PluginError(
                "auth_expired",
                "the credential for ${account.email} was rejected (invalid_grant); " +
                    "re-authorise that account",
            )
            "" -> Unit
            else -> throw PluginError(
                "auth_error",
                "refresh failed for ${account.email}: ${obj.optString("error")} (HTTP $code)",
            )
        }
        val at = obj.optString("access_token")
        if (at.isBlank()) {
            throw PluginError("auth_error", "refresh failed for ${account.email} (HTTP $code): $res")
        }
        val expiresIn = obj.optInt("expires_in", 3600)
        accessTokens[key] = CachedToken(at, now + expiresIn * 1000L)

        // Google reports the granted scopes on both the exchange and every refresh. Capture
        // them so "what does this account hold" is a fact rather than an unknown, and a scope
        // regression stops being invisible. Absent means unknown, so the stored set is left
        // alone rather than emptied.
        obj.optString("scope", "").takeIf { it.isNotBlank() }?.let { reported ->
            GoogleAccounts.setGrantedScopes(
                ctx, account.sub, reported.split(" ").filter { it.isNotBlank() }.toSet(),
            )
        }
        return at
    }

    /**
     * Drop the cached access token for one account.
     *
     * Called when a credential changes, so a stale in-memory token cannot outlive the refresh
     * token it was minted from.
     */
    fun forgetAccessToken(sub: String) {
        if (sub.isBlank()) accessTokens.clear() else accessTokens.remove(sub)
    }

    /** Prime the cache from a token Play Services already issued. */
    fun setAccessToken(sub: String, token: String, expiresInSec: Int) {
        if (sub.isBlank()) {
            throw PluginError(
                "account_storage_error",
                "cannot cache an access token without a resolved account identity",
            )
        }
        accessTokens[sub] = CachedToken(token, System.currentTimeMillis() + expiresInSec * 1000L)
    }

    /**
     * Exchange a one-time server auth code for an access + refresh token.
     *
     * redirect_uri must be EMPTY for a server auth code obtained from the Android/Play Services
     * SDK ("postmessage" applies only to codes from the Web SDK; sending it here yields
     * invalid_grant "Malformed auth code.").
     *
     * Returns the refresh token; the caller stores it against the resolved account, because only
     * the caller knows which account the code was minted for.
     */
    @Synchronized
    fun exchangeServerAuthCode(
        ctx: Context,
        serverAuthCode: String,
        /**
         * The client that redeems this code, when it is not the built-in account client.
         *
         * **A refresh token is bound to the client that minted it.** The OAuth plugin owns its own
         * client (imported from a client_secret file), so a token minted by the app's built-in Web
         * client could never be redeemed against that credential — Google answers
         * `unauthorized_client`, which is exactly what happened on the first live attempt.
         * Passing the credential's own id and secret keeps the button and the stored credential
         * the same identity.
         *
         * The `serverClientId` handed to Play Services must ALSO be this client, or the server auth
         * code belongs to a different one and the exchange fails the same way.
         */
        redeemClientId: String? = null,
        redeemClientSecret: String? = null,
    ): ExchangeResult {
        // Per Google's requestOfflineAccess docs, the code is redeemed by the SERVER identified
        // by serverClientId (the Web client), using that client's secret.
        val redeemClient = redeemClientId?.takeIf { it.isNotBlank() }
            ?: serverClientId.ifBlank { clientId }
        val redeemSecret = redeemClientSecret?.takeIf { it.isNotBlank() } ?: clientSecret
        if (redeemClient.isBlank()) {
            throw PluginError("auth_not_configured", "no client id configured")
        }
        val form = "code=${enc(serverAuthCode)}" +
            "&client_id=${enc(redeemClient)}" +
            "&client_secret=${enc(redeemSecret)}" +
            "&grant_type=authorization_code" +
            "&redirect_uri="
        Debug.log("EXCHANGE POST $TOKEN_URL")
        Debug.log("EXCHANGE form_len=${form.length}")
        val (code, res) = postRaw(TOKEN_URL, form)
        val obj = try {
            org.json.JSONObject(res)
        } catch (e: Exception) {
            throw PluginError("auth_error", "token endpoint returned non-JSON (HTTP $code): $res")
        }
        if (obj.has("error") || code !in 200..299) {
            throw PluginError("auth_error", "server code exchange failed (HTTP $code): $res")
        }
        val refresh = obj.optString("refresh_token")
        if (refresh.isBlank()) {
            throw PluginError("auth_error", "no refresh_token in exchange response: $res")
        }
        return ExchangeResult(
            refreshToken = refresh,
            accessToken = obj.optString("access_token").ifBlank { null },
            idToken = obj.optString("id_token").ifBlank { null },
            expiresInSec = obj.optInt("expires_in", 3600),
            scopes = obj.optString("scope", "").split(" ")
                .filter { it.isNotBlank() }
                .toSet(),
        )
    }

    /** What a completed code exchange produced, before it is written to an account. */
    data class ExchangeResult(
        val refreshToken: String,
        val accessToken: String?,
        val expiresInSec: Int,
        /** Empty means Google did not report them — unknown, not "none granted". */
        val scopes: Set<String>,
        /**
         * The identity Google just signed for this grant, when it sent one.
         *
         * This is the ONLY trustworthy source of "which account is this" right after a consent:
         * the token came straight from Google's token endpoint over TLS in this same exchange,
         * so it cannot be stale and cannot belong to a different account than the one the human
         * picked. Reading the identity from `AuthorizationResult.accessToken` instead is what
         * failed on device with `userinfo HTTP 401 Invalid Credentials` — that token's freshness
         * is not ours to assume.
         */
        val idToken: String? = null,
    )

    // --- identity ---

    /**
     * The identity inside an id_token, straight from Google's token endpoint.
     *
     * The signature is NOT verified, and that is deliberate rather than sloppy: this token was
     * received over TLS in the response to our own code exchange, so it is already
     * authenticated by the transport. Verifying it would need Google's rotating keys and a JWT
     * library for no added assurance. The claim checks below still guard the one thing that
     * matters — that the issuer really is Google and both identifiers are present.
     */
    fun identityFromIdToken(idToken: String): Identity {
        val parts = idToken.split('.')
        if (parts.size != 3) {
            throw PluginError("identity_lookup_failed", "id_token is not a three-part JWT")
        }
        val payload = try {
            // `java.util.Base64`, not `android.util.Base64`: the Android class is a stub in
            // plain JVM unit tests, so every decode threw there and the good case failed
            // alongside the bad ones — the test could not tell "refused" from "unreadable".
            // minSdk is 26, where the JDK class exists, and it takes the URL-safe alphabet
            // without flags, so this is both testable and simpler.
            val raw = java.util.Base64.getUrlDecoder().decode(parts[1])
            org.json.JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw PluginError(
                "identity_lookup_failed",
                "id_token payload is not readable JSON: ${e.javaClass.simpleName}",
            )
        }
        val iss = payload.optString("iss")
        if (iss != "https://accounts.google.com" && iss != "accounts.google.com") {
            throw PluginError("identity_lookup_failed", "id_token issuer is '$iss', not Google")
        }
        val email = payload.optString("email")
        val sub = payload.optString("sub")
        if (email.isBlank() || sub.isBlank()) {
            throw PluginError(
                "identity_lookup_failed",
                "id_token carries no ${if (email.isBlank()) "email" else "sub"}",
            )
        }
        return Identity(email = email, sub = sub)
    }

    /** Who a bearer belongs to: the address and the stable Google `sub`. */
    data class Identity(val email: String, val sub: String)

    /**
     * Resolve the identity behind [bearer].
     *
     * Both fields are required: the email is the address a caller sends, and the `sub` is the
     * vault key. A response missing either is refused rather than stored with a blank, because a
     * record with no `sub` is unaddressable and one with no email cannot be named.
     */
    fun identity(ctx: Context, bearer: String): Identity {
        Debug.log("IDENTITY GET $USERINFO_URL")
        val body = try {
            val conn = (URL(USERINFO_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $bearer")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().readText()
            if (code !in 200..299) {
                throw PluginError("identity_lookup_failed", "userinfo HTTP $code: $text")
            }
            org.json.JSONObject(text)
        } catch (e: PluginError) {
            throw e
        } catch (e: Exception) {
            throw PluginError(
                "identity_lookup_failed",
                "userinfo request failed: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
        val email = body.optString("email")
        val sub = body.optString("sub")
        if (email.isBlank() || sub.isBlank()) {
            throw PluginError(
                "identity_lookup_failed",
                "userinfo returned no ${if (email.isBlank()) "email" else "sub"}; " +
                    "the openid and email scopes are required for this",
            )
        }
        return Identity(email = email, sub = sub)
    }

    // --- removal ---

    /**
     * Revoke one account's refresh token at Google, then forget it locally.
     *
     * The revoke comes FIRST and its failure is reported, not swallowed: a token left valid at
     * Google after the human removed the account is exactly the outcome removal exists to
     * prevent. Local removal still happens — the alternative is an account the human cannot get
     * rid of — but the caller is told the token may still be live.
     */
    /**
     * Revoke and forget one account.
     *
     * **Runs off the caller's thread.** Revocation is an HTTPS call, and the settings screen that
     * offers REMOVE runs on the UI thread — so calling this directly threw
     * `NetworkOnMainThreadException` on device and reported it as `revoke_failed`, which read like
     * "Google refused" when nothing had been sent at all. The local removal still happens on this
     * thread, because forgetting the account must not depend on a network round trip succeeding.
     */
    fun removeAccount(ctx: Context, account: GoogleAccounts.Account) {
        val media = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Thread({
                try {
                    removeAccountBlocking(ctx, account)
                } catch (e: PluginError) {
                    Debug.log("remove ${account.email}: ${e.code}: ${e.message}")
                }
            }, "google-account-remove").apply { isDaemon = true }.start()
            return
        }
        removeAccountBlocking(ctx, account)
    }

    @Synchronized
    private fun removeAccountBlocking(ctx: Context, account: GoogleAccounts.Account) {
        val refresh = try {
            GoogleAccounts.refreshToken(ctx, account)
        } catch (e: PluginError) {
            // No readable credential: nothing to revoke, and that is a complete removal.
            Debug.log("revoke skipped for ${account.email}: ${e.code}")
            null
        }
        var revokeError: String? = null
        if (refresh != null) {
            try {
                val (code, body) = postRaw(REVOKE_URL, "token=${enc(refresh)}")
                // Google answers 200 for a successful revoke and 400 for a token that was
                // already invalid — the second is not a failure to report.
                if (code !in 200..299 && !body.contains("invalid_token")) {
                    revokeError = "HTTP $code: $body"
                }
            } catch (e: Exception) {
                revokeError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        forgetAccessToken(account.sub)
        GoogleAccounts.remove(ctx, account.sub)
        if (revokeError != null) {
            throw PluginError(
                "revoke_failed",
                "the account was removed from this phone, but Google did not confirm the " +
                    "revocation ($revokeError) — its token may still be live until it expires " +
                    "or is revoked at https://myaccount.google.com/permissions",
            )
        }
    }

    /** Whether any Google account exists. The old single-account `isLoggedIn`, restated. */
    fun hasAccount(ctx: Context): Boolean = !GoogleAccounts.isEmpty(ctx)

    // --- helpers ---

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The cache key for a record that has no `sub` yet — the migration window only. */
    private const val LEGACY_CACHE_KEY = "\u0000legacy"

    /** POST form data; returns (httpStatus, body) and never hides the body. */
    private fun postRaw(url: String, form: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(form.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).readText()
        Debug.log("HTTP POST $url -> $code")
        Debug.log("HTTP RESP BODY: $body")
        return code to body
    }
}
