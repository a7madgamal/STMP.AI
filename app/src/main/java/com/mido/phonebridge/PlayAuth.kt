package com.mido.phonebridge

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.mido.phonebridge.core.AuthManager

/**
 * Google Play Services authorization (Android client + Web serverClientId).
 *
 * The Android client (package + signing SHA-1) drives the picker; the Web client
 * is named as serverClientId so Play Services returns a ONE-TIME server auth
 * code, which we redeem exactly once for a refresh token.
 *
 * The server auth code is single-use: redeeming it twice yields
 * invalid_grant ("Malformed auth code."). Therefore exactly ONE code path may
 * call [AuthManager.exchangeServerAuthCode].
 *
 * FAIL LOUD: every failure returns a coded Outcome, never a silent retry.
 */
object PlayAuth {
    private const val TAG = "PlayAuth"

    sealed class Outcome {
        data class SignedIn(val email: String) : Outcome()
        data class Failed(val code: String, val message: String) : Outcome()
    }

    /** Request code for the consent intent, matched in onActivityResult. */
    const val REQ_CONSENT = 8001

    sealed class Start {
        /** Grant already held. Nothing more to do; token is live. */
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
     * NOTE: this method NEVER redeems a server auth code. When Play Services
     * returns a code without a consent UI, the code is handed to the caller via
     * [Start.NeedsConsent]-less path... but in practice Google always requires
     * the consent intent when offline access is requested, so the exchange
     * happens only in [processConsentResult]. A code received here without a
     * pending intent is redeemed by [redeemIfCode] exactly once.
     */
    fun authorize(activity: Activity, scopes: List<String>): Start {
        val client = Identity.getAuthorizationClient(activity)
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(scopes.map { Scope(it) })
        if (offlineRequested && serverClientId.isNotBlank()) {
            builder.requestOfflineAccess(serverClientId)
            // Force a fresh consent so the picker is shown even when a grant is
            // already cached, and so a fresh server auth code is issued.
            builder.setPrompt(AuthorizationRequest.Prompt.CONSENT)
        }
        val result: AuthorizationResult =
            com.google.android.gms.tasks.Tasks.await(client.authorize(builder.build()))

        result.pendingIntent?.let { return Start.NeedsConsent(it) }

        val token = result.accessToken
        if (token.isNullOrBlank()) {
            throw IllegalStateException(
                "no pending intent and no access token"
            )
        }
        AuthManager.setAccessToken(token, 3600, null)
        return Start.AlreadyGranted(token)
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

    /** Blocking worker: redeem the code ONCE, prime the cache, resolve email. */
    private fun processConsentResult(activity: Activity, data: Intent): Outcome {
        try {
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
                        "space=${serverCode.contains(' ')} plus=${serverCode.contains('+')}"
                )
            } else {
                Debug.log("code shape: serverAuthCode is NULL (token-only result)")
            }

            // Redeem the one-time server code. This is the ONLY exchange site.
            if (!serverCode.isNullOrBlank()) {
                AuthManager.exchangeServerAuthCode(activity, serverCode)
                Log.i(TAG, "refresh token stored from server auth code")
            }

            if (!token.isNullOrBlank()) {
                AuthManager.setAccessToken(token, 3600, null)
            }

            val email = AuthManager.fetchEmail(activity, token)
            return Outcome.SignedIn(email ?: "(email unknown)")
        } catch (e: ApiException) {
            return Outcome.Failed("auth_error", "ApiException ${e.statusCode}: ${e.message}")
        } catch (e: Exception) {
            val detail = e.message
                ?: e.cause?.message
                ?: "${e.javaClass.simpleName} (no message)"
            Log.e(TAG, "authorization failed", e)
            return Outcome.Failed("auth_error", detail)
        }
    }
}
