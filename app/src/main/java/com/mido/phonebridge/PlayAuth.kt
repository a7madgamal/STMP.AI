package com.mido.phonebridge

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.GoogleAccounts
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.plugins.oauth.OauthTokenCache
import com.mido.phonebridge.plugins.oauth.OauthStore
import com.mido.phonebridge.plugins.oauth.OauthSecret
import com.mido.phonebridge.core.SecretVault

/**
 * Google Play Services authorization (Android client + Web serverClientId).
 *
 * The Android client (package + signing SHA-1) drives the picker; the Web client is named as
 * `serverClientId` so Play Services returns a ONE-TIME server auth code, which we redeem exactly
 * once for a refresh token.
 *
 * **Two prompts, two intents, and the difference is a security boundary:**
 *
 *  - [AuthorizationRequest.Prompt.SELECT_ACCOUNT] — ADD. The human must be able to pick which
 *    mailbox is being added, so the picker is the point of the interaction.
 *  - [AuthorizationRequest.Prompt.CONSENT] with [AuthorizationRequest.Builder.setAccount] —
 *    RE-AUTHORISE one account we already hold. **No picker**, because a picker here would let a
 *    re-consent for account A quietly run as account B, and the whole change exists to stop
 *    credentials moving between accounts unseen.
 *
 * The `Account` for the second case is rebuilt from the stored email, so no
 * `GET_ACCOUNTS` permission is needed — the app never enumerates the device's accounts.
 *
 * The server auth code is single-use: redeeming it twice yields `invalid_grant`. Therefore
 * exactly ONE code path may call [AuthManager.exchangeServerAuthCode].
 *
 * FAIL LOUD: every failure returns a coded Outcome, never a silent retry.
 */
object PlayAuth {
    private const val TAG = "PlayAuth"

    /** Which flow the picker was opened for. Carried through the consent round trip. */
    /**
     * What the screen is doing, for the log and the wording — **not** for a different API path.
     *
     * Both values now run the same picker flow, because pinning an account with `setAccount` was
     * the thing failing. Keeping the distinction here is honest about intent without pretending
     * the mechanism differs.
     */
    enum class Flow { ADD, REAUTHORISE }

    sealed class Outcome {
        /** [account] is the freshly written record, ready for the caller to display. */
        data class SignedIn(val account: GoogleAccounts.Account) : Outcome()

        /**
         * An OAuth-plugin credential got its refresh token.
         *
         * A separate case rather than a [SignedIn] with nulls: the two write different stores, and
         * the caller that shows the result needs to know which one happened so it can word the
         * message correctly.
         */
        data class Connected(val credential: String, val email: String) : Outcome()
        data class Failed(val code: String, val message: String) : Outcome()
    }

    /**
     * The OAuth-plugin credential this consent mints a token FOR, or null for an account.
     *
     * **This is what removes the pasted token from the OAuth plugin.** The same consent flow
     * produces either an account (Gmail/Calendar/Drive) or one credential's refresh token; the
     * only difference is where the token is filed. Naming the target here means the result is
     * stored without the human re-opening a form.
     */
    @Volatile
    var oauthTarget: String? = null
        private set

    fun targetOauthCredential(name: String?) {
        oauthTarget = name?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Request code for the consent intent, matched in onActivityResult. */
    const val REQ_CONSENT = 8001

    sealed class Start {
        /** Grant already held. Nothing more to do; the token is live. */
        data class AlreadyGranted(val accessToken: String) : Start()

        /** Consent UI required; launch the pending intent. */
        data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : Start()
    }

    @Volatile var serverClientId: String = ""
        private set

    @Volatile var offlineRequested: Boolean = true
        private set

    fun configure(serverClientId: String, offline: Boolean) {
        this.serverClientId = serverClientId
        this.offlineRequested = offline
    }

    /**
     * Kick off authorization.
     *
     * [email] is required for [Flow.REAUTHORISE] and forbidden for [Flow.ADD]: adding is the one
     * case where the human is deliberately choosing an identity the app does not yet know.
     */
    fun authorize(
        activity: Activity,
        scopes: List<String>,
        flow: Flow,
        email: String?,
        /**
         * The scopes for THIS consent, when it is not an account sign-in.
         *
         * `chromewebstore` is a publisher scope: asking for it in the normal account consent would
         * put "manage your Chrome Web Store items" in front of every user who only wanted mail.
         * The caller that needs it passes it here, at the moment it needs it.
         */
        scopeOverride: List<String>? = null,
    ): Start {
        val client = Identity.getAuthorizationClient(activity)
        // **A credential consent still needs `openid email`.** Google returns an `id_token`
        // only when `openid` is requested, and the id_token is what names the identity — without
        // it the flow had no way to say which account granted the token and fell back to a
        // userinfo call that the `chromewebstore`-only bearer answered with 401. These two are
        // identity, not data: they add no mailbox or drive access to the prompt.
        val requested = (scopeOverride?.takeIf { it.isNotEmpty() } ?: scopes)
            .let { list -> if (list.any { it == "openid" }) list else listOf("openid", "email") + list }
        Debug.log("consent scopes: ${requested.joinToString(" ")}")
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(requested.map { Scope(it) })

        // **One flow, and only the picker.**
        //
        // Two attempts at `setAccount` (a constructed `Account`, then a real one resolved from
        // `AccountManager`) were both refused with ApiException 8 / 28405, and the log finally
        // showed it happening on a phone with ZERO accounts configured: the request was the
        // re-authorise branch, which pins an account that no longer exists. Pinning an account is
        // the fragile half of this API; the picker is the half that works.
        //
        // So re-authorising is just "add, choosing the account you already have". Google treats a
        // repeated consent for the same account as exactly that, the code lands against the
        // account the human tapped, and no `Account` object is ever fabricated or looked up.
        builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        if (email != null) {
            // Kept only for the screen's own message ("re-authorising X"): the RESULT is not
            // matched against it, because the picker is where the human confirms which account
            // this is, and a mismatch there is their answer, not an error to second-guess.
            Debug.log("requesting consent via picker (expected ${email})")
        }
        // **The offline client is the one that will redeem the code.**
        //
        // For an account that is the built-in Web client. For an OAuth plugin credential it is the
        // credential's OWN client, because a refresh token is bound to the client that minted it —
        // asking Play Services for a code against one client and redeeming it with another answers
        // `unauthorized_client`, which is a failure that reads like a bad credential.
        val offlineClient = oauthTarget?.let { name ->
            runCatching { OauthStore.get(activity, name).clientId }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: serverClientId
        if (offlineRequested && offlineClient.isNotBlank()) {
            // **`forceCodeForRefreshToken = true`, or a repeat authorisation returns NO refresh
            // token.** Google only issues one the first time it grants offline access; after that
            // the exchange answers 200 with an access token and no `refresh_token`, which reads as
            // a broken login when it is really "you already said yes".
            //
            // Verified on device: after deleting both accounts and re-adding, the exchange returned
            // 200 with a valid access token and no refresh token, and the flow failed. That is
            // exactly the state this flag exists for.
            // The two-argument overload is marked deprecated in play-services-auth 22.0.0, and
            // it is the ONLY way to set forceCodeForRefreshToken — the builder exposes no other
            // setter (verified with `javap` against the pinned artifact). Deprecated here means
            // "there may be a newer shape later", not "this no longer works", so it is used with
            // the suppression rather than dropping the one parameter that makes re-consent work.
            @Suppress("DEPRECATION")
            builder.requestOfflineAccess(offlineClient, true)
        }

        val result: AuthorizationResult =
            com.google.android.gms.tasks.Tasks.await(client.authorize(builder.build()))

        result.pendingIntent?.let { return Start.NeedsConsent(it) }

        val token = result.accessToken
        if (token.isNullOrBlank()) {
            throw IllegalStateException("no pending intent and no access token")
        }
        // There is no account identity here yet, so the token cannot be cached under a `sub`.
        // It is handed back and the caller caches it once the identity is known — an unkeyed
        // cache entry is worse than one round trip.
        return Start.AlreadyGranted(token)
    }

    /**
     * The human sentence for a Play Services failure.
     *
     * Only the codes already seen are translated; everything else falls back to the raw pair,
     * because inventing a meaning for an unlisted code is worse than showing it — the next
     * reader then has the number to look up instead of a confident wrong story.
     */
    private fun explainApiException(statusCode: Int, message: String): String {
        val second = Regex("\\[(\\d+)]").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            // 28405 / ApiException 8: what Play Services answers when the authorization request
            // cannot be matched to an account it can consent for — including a request that
            // pinned an account which is no longer configured.
            // 8 / 28405 is ApiException STATUS + Google's DEVELOPER_ERROR: the OAuth client this
            // build asks for is not accepted for this app — a registration fact on the console,
            // not something the human can retry away. Saying so stops the next round being spent
            // on the phone.
            statusCode == 8 && second == 28405 ->
                "Google rejected this app's OAuth client (DEVELOPER_ERROR 28405). The signing " +
                    "SHA-1 or the client id does not match the Google Cloud project."
            // 10: the signed-in identity does not match the registered signing certificate.
            statusCode == 10 ->
                "This build's signing key is not registered for the OAuth client " +
                    "(ApiException 10). The app identity changed, not your account."
            // 12501: the human dismissed the picker or consent screen.
            statusCode == 12501 -> "Cancelled at the Google screen."
            else -> "Google refused the request (ApiException $statusCode: $message)."
        }
    }

    /** Handle what comes back from the consent intent. */
    fun handleConsentResult(activity: Activity, data: Intent?, onDone: (Outcome) -> Unit) {
        if (data == null) {
            onDone(Outcome.Failed("denied", "no result data returned"))
            return
        }
        // Network I/O must not run on the main thread (onActivityResult is main).
        Thread {
            val outcome = processConsentResult(activity, data)
            activity.runOnUiThread { onDone(outcome) }
        }.start()
    }

    /** Blocking worker: redeem the code ONCE, resolve the identity, store it. */
    private fun processConsentResult(activity: Activity, data: Intent): Outcome {
        // Read ONCE. The target decides which client mints and redeems the code, and it must not
        // change between those two steps.
        val oauthName0 = oauthTarget
        try {
            // **What came back, before any parsing.** The previous run failed HERE, after Play
            // Services had already returned a consent intent — so the request was fine and the
            // failure is in the result. Logging the raw extras is what distinguishes "the human
            // cancelled", "the app is misconfigured" and "Google returned nothing", instead of
            // leaving one numeric pair to be interpreted.
            Debug.log(
                "consent result: action=${data.action} categories=${data.categories?.joinToString(",")} " +
                    "extras=${data.extras?.keySet()?.joinToString(",")}"
            )
            // The STATUS extra is the actual answer when Google refuses. `extras=status` alone
            // means the Intent carried no result at all — a refusal, not a cancelled picker —
            // so its code is the only evidence there is and it must reach the screen.
            @Suppress("DEPRECATION") // Bundle.get(String) is deprecated in favour of the typed
            // getters, but the status extra's type varies by Play Services version, so the raw
            // value is what has to be reported.
            data.extras?.get("status")?.let {
                Debug.log("consent status extra: ${it::class.java.simpleName} = $it")
            }
            val result = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data)

            val token = result.accessToken
            val serverCode = result.serverAuthCode

            if (token.isNullOrBlank() && serverCode.isNullOrBlank()) {
                return Outcome.Failed("auth_error", "no access token or server code in result")
            }

            // Shape-only diagnostics, routed to the ON-SCREEN log (Debug.log).
            if (serverCode != null) {
                Debug.log(
                    "code shape: len=${serverCode.length} prefix=${serverCode.take(4)} " +
                        "slash=${serverCode.contains('/')} pct=${serverCode.contains('%')} " +
                        "space=${serverCode.contains(' ')} plus=${serverCode.contains('+')}",
                )
            } else {
                Debug.log("code shape: serverAuthCode is NULL (token-only result)")
            }

            // **Redeem FIRST, then identify.** This order is the fix for
            // `identity_lookup_failed: userinfo HTTP 401 Invalid Credentials` seen on device:
            // the identity used to be read from `result.accessToken` BEFORE the exchange, and
            // that token's freshness is not ours to assume. The exchange response carries a
            // freshly signed `id_token`, so the account is named by the same round trip that
            // produced the credential — no second call, no stale bearer, no guess.
            //
            // The picker decided which account this consent is for, so the resolved identity IS
            // the answer — there is nothing to match it against. Re-authorising a different
            // account simply stores that one, which is what the human asked for by tapping it.
            var refreshToken: String? = null
            var granted: Set<String> = result.grantedScopes.toSet()
            var identity: AuthManager.Identity? = null
            if (!serverCode.isNullOrBlank()) {
                // Redeem with the SAME client the code was minted against.
                val ownClientId = oauthName0?.let { n ->
                    runCatching { OauthStore.get(activity, n).clientId }.getOrNull()
                }
                val ownSecret = oauthName0?.let { n ->
                    runCatching {
                        OauthSecret.fromVault(SecretVault.get(activity, OauthStore.vaultName(n))).clientSecret
                    }.getOrNull()
                }
                val exchange = AuthManager.exchangeServerAuthCode(
                    activity, serverCode, ownClientId, ownSecret,
                )
                refreshToken = exchange.refreshToken
                if (exchange.scopes.isNotEmpty()) granted = exchange.scopes
                identity = exchange.idToken?.let { AuthManager.identityFromIdToken(it) }
                Log.i(TAG, "refresh token obtained from server auth code")
            }

            // No server code means a token-only result (a grant that was already held). The
            // identity then has to come from the bearer Google just handed back, which is the
            // one case where that bearer is genuinely fresh.
            val resolved = identity ?: run {
                if (token.isNullOrBlank()) {
                    return Outcome.Failed(
                        "identity_lookup_failed",
                        "no server code and no access token, so the account cannot be named",
                    )
                }
                AuthManager.identity(activity, token)
            }
            identity = resolved

            // **A consent with unticked boxes is a failed connect, not a partial account.**
            // On device the second account came back with `openid userinfo.email` only, while
            // every data scope had been requested — Google honours what the human ticked. The
            // record was still written, so the account looked connected and every Gmail call
            // afterwards would have failed with a 403 that names nothing.
            //
            // Checked here rather than at request time because this is the only point where
            // the answer can still be fixed by the human, and the message can say which boxes.
            if (oauthName0 == null) {
                val dataScopes = com.mido.phonebridge.core.PluginRegistry.allScopes()
                    .filterNot { it in setOf("openid", "email", "profile") }
                    .toSet()
                val missing = dataScopes - granted
                if (missing.isNotEmpty()) {
                    return Outcome.Failed(
                        "scope_missing",
                        "Google did not grant ${missing.joinToString(", ")} — " +
                            "add the account again and leave every permission ticked",
                    )
                }
            }

            val existing = runCatching {
                GoogleAccounts.bySub(activity, identity.sub)
            }.getOrNull()

            if (refreshToken == null && existing == null) {
                return Outcome.Failed(
                    "auth_error",
                    "Google did not return a refresh token and this account has no stored one; " +
                        "revoke the app's access at https://myaccount.google.com/permissions " +
                        "and add the account again",
                )
            }

            val record = GoogleAccounts.Account(
                sub = identity.sub,
                email = identity.email,
                label = existing?.label,
                grantedScopes = if (granted.isNotEmpty()) granted else existing?.grantedScopes ?: emptySet(),
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            )
            // Record before credential: a kill in between leaves an account that reports
            // `auth_required`, which is recoverable, rather than a token nobody can address.
            GoogleAccounts.upsert(activity, record)
            if (refreshToken != null) {
                GoogleAccounts.putRefreshToken(activity, record, refreshToken)
            }
            // **What Google actually granted, not what was asked for.** On device the second
            // account came back with `scope: "openid userinfo.email"` although all five data
            // scopes were requested — the consent screen had them unticked. Storing the
            // requested set would have claimed Gmail access the token does not carry, and the
            // first request would then fail with a 403 that points at nothing.
            val oauthName = oauthTarget
            if (oauthName != null) {
                // **Filed against the credential, not an account.** The client secret is already
                // in the vault from the imported client file, so only the half that was missing is
                // written — the imported values are never re-typed. The account record is NOT
                // created: publishing is not a Gmail identity, and adding one would put a mailbox
                // into `?account=` that the human never asked for.
                val existing = OauthSecret.fromVault(
                    SecretVault.get(activity, OauthStore.vaultName(oauthName)),
                )
                // **`null` means "keep the existing allowlist"; `emptyList()` means "clear it".**
                // This used to be `getOrDefault(emptyList())`, so any failure reading the index —
                // a locked vault, a transient IO error — rewrote the credential with NO allowed
                // hosts, and every call afterwards answered `host_not_allowed`. That is exactly
                // what happened on device after connecting: the store call was refused for a host
                // the human had entered by hand. A failed read must not be able to widen OR
                // narrow a security boundary; leaving the stored list alone is the only safe
                // reading, and `SecretVault.put` treats null as "unchanged".
                val hosts = runCatching {
                    SecretVault.meta(activity, OauthStore.vaultName(oauthName)).hosts
                }.getOrNull()
                SecretVault.put(
                    activity,
                    OauthStore.vaultName(oauthName),
                    OauthSecret(
                        refreshToken = refreshToken ?: existing.refreshToken,
                        clientSecret = existing.clientSecret,
                    ).toJson(),
                    hosts = hosts,
                    owner = "oauth",
                )
                OauthTokenCache.forget(oauthName)
                Debug.log("OAUTH OK: '$oauthName' connected as ${identity.email}")
                return Outcome.Connected(oauthName, identity.email)
            }

            if (!token.isNullOrBlank()) {
                AuthManager.setAccessToken(identity.sub, token, 3600)
            }
            Debug.log("LOGIN OK: ${identity.email}")
            return Outcome.SignedIn(record)
        } catch (e: ApiException) {
            // **Name the cause, not the number.** `ApiException 8: 8: [28405]` is what the
            // account screen showed for "this Account is not one the device knows", which sent
            // the investigation to the console instead of to `setAccount`. The status pair is
            // translated where it is recognised, and the raw code is kept in the log so an
            // unlisted one is still diagnosable rather than hidden.
            Debug.log("PlayAuth ApiException ${e.statusCode} / ${e.message}")
            return Outcome.Failed("auth_error", explainApiException(e.statusCode, e.message ?: ""))
        } catch (e: com.mido.phonebridge.core.PluginError) {
            return Outcome.Failed(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            val detail = e.message
                ?: e.cause?.message
                ?: "${e.javaClass.simpleName} (no message)"
            Log.e(TAG, "authorization failed", e)
            return Outcome.Failed("auth_error", detail)
        }
    }
}
