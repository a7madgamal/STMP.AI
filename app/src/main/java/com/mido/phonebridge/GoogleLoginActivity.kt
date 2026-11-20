package com.mido.phonebridge

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.ui.Theme
import kotlin.concurrent.thread

/**
 * Google sign-in screen. Uses Play Services (AuthorizationClient): package name
 * + signing SHA-1 identify the app, so there is no embedded secret and no local
 * listener. On success it returns to the home screen.
 */
class GoogleLoginActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var logScroll: ScrollView? = null
    private val logTick = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = Theme.dp(this, Theme.SCREEN_GUTTER_DP)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Theme.sheet(Theme.BG)
        }

        root.addView(Theme.text(this, "Login with Google", 22f))

        status = Theme.text(this, "", Theme.TEXT_BODY_SP, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        root.addView(Theme.button(this, "CANCEL", Theme.ButtonStyle.NEUTRAL) { finish() })

        root.addView(Theme.text(this, "\u2014 log \u2014", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, pad, 0, 0)
        })
        // **The log view is fed from the Debug ring, and it used to be fed from NOTHING.**
        //
        // `log` was created with empty text and `render()` was an empty stub, so the screen
        // showed a heading over a blank pane while every `Debug.log` went to the ring buffer and
        // to logcat. Two rounds of diagnosis happened on a screen that could not show its own
        // failure. It now repaints on a short tick, which is also what makes a step that never
        // returns visible as a log that stops.
        logView = Theme.text(this, Debug.dump(), Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT, mono = true)
        root.addView(ScrollView(this).apply {
            addView(logView)
            this@GoogleLoginActivity.logScroll = this
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })

        setContentView(Theme.inset(root))

        if (AuthManager.clientId.isBlank()) {
            status.text = "No OAuth client id configured in this build."
            return
        }
        val oauthName = intent.getStringExtra(EXTRA_OAUTH)
        if (oauthName != null) {
            // An OAuth credential's consent: no account picker question, no re-authorise check.
            // The scope comes from the credential so the request asks for exactly what it needs.
            PlayAuth.targetOauthCredential(oauthName)
            val scope = intent.getStringExtra(EXTRA_SCOPE)
            status.text = "Connecting \u2018$oauthName\u2019\u2026"
            startLogTick()
            beginOauthFlow(oauthName, scope)
            return
        }
        val target = intent.getStringExtra(EXTRA_EMAIL)
        val adding = intent.getBooleanExtra(EXTRA_ADD, false)
        if (!adding && target.isNullOrBlank()) {
            // A re-authorisation with no address is a control that cannot do anything: refuse
            // before opening a picker nobody asked for.
            status.text = "Re-authorising needs an account address; nothing was opened."
            return
        }
        if (!adding) {
            status.text = "Re-authorising $target\u2026 choose it in the Google picker."
        }
        startLogTick()
        beginFlow(adding, target)
    }

    /**
     * Repaint the log pane from the ring buffer twice a second.
     *
     * A tick rather than a callback because the writes come from several threads and several
     * classes (PlayAuth, AuthManager, GoogleAccounts); plumbing an observer through all of them
     * would be a second mechanism to keep in sync with the one that already records everything.
     */
    private fun startLogTick() {
        val paint = object : Runnable {
            override fun run() {
                val text = Debug.dump()
                if (this@GoogleLoginActivity::logView.isInitialized && logView.text.toString() != text) {
                    logView.text = text
                    logScroll?.post { logScroll?.fullScroll(android.view.View.FOCUS_DOWN) }
                }
                logTick.postDelayed(this, 500)
            }
        }
        logTick.post(paint)
    }

    override fun onDestroy() {
        super.onDestroy()
        logTick.removeCallbacksAndMessages(null)
    }

    /**
     * The credential consent: the same `authorize()` call, with this credential's scope list.
     *
     * Deliberately not a second mechanism. The account flow and this one differ only in which
     * scopes are requested and where the resulting token is filed — both decided by PlayAuth.
     */
    private fun beginOauthFlow(name: String, scope: String?) {
        thread {
            try {
                val scopes = scope?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
                    ?: com.mido.phonebridge.core.PluginRegistry.allScopes()
                when (
                    val start = PlayAuth.authorize(
                        this@GoogleLoginActivity,
                        scopes,
                        PlayAuth.Flow.ADD,
                        null,
                        scopeOverride = scopes,
                    )
                ) {
                    is PlayAuth.Start.NeedsConsent -> runOnUiThread {
                        Debug.log("launching Google consent intent for '$name'")
                        @Suppress("DEPRECATION")
                        startIntentSenderForResult(
                            start.pendingIntent.intentSender, PlayAuth.REQ_CONSENT, null, 0, 0, 0,
                        )
                    }
                    is PlayAuth.Start.AlreadyGranted -> runOnUiThread {
                        // A grant is held but this credential still has no refresh token. The
                        // honest answer is that a token cannot be minted without the consent
                        // screen, so say that rather than storing an access token that expires.
                        status.text = "Google already granted access; nothing to store. " +
                            "Use the paste field, or revoke access at " +
                            "https://myaccount.google.com/permissions and connect again."
                    }
                }
            } catch (e: PluginError) {
                runOnUiThread { status.text = "Connect failed: ${e.code} \u2014 ${e.message}" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Connect failed: ${e.message}" }
            }
        }
    }

    private fun beginFlow(adding: Boolean, target: String?) {
        // **Clear any credential target.** `oauthTarget` is a process-wide `@Volatile` on PlayAuth,
        // set when the OAuth screen starts a connect. If it survives into an ACCOUNT sign-in, the
        // account gets minted against that credential's client and the credential's own client
        // secret is used to redeem it — and the stored refresh token then belongs to a client that
        // a later account refresh does not name. The result is `unauthorized_client` on the next
        // Gmail call, which is exactly what happened to the second account after the credential
        // was connected. An account flow must start with no credential in scope.
        PlayAuth.targetOauthCredential(null)
        thread {
            try {
                val scopes = com.mido.phonebridge.core.PluginRegistry.allScopes()
                if (scopes.isEmpty()) {
                    throw PluginError("no_scopes", "no registered plugins declare scopes")
                }

                // The exact list Google is being asked for. It is the only input to this call
                // besides the client ids, so when Google answers DEVELOPER_ERROR this line is
                // what decides whether the scope set or the project is at fault.
                Debug.log("requesting scopes: ${scopes.joinToString(" ")}")
                runOnUiThread {
                    Debug.log("requesting authorization code (Android client)")
                    status.text = if (adding) {
                        "Opening Google account picker\u2026"
                    } else {
                        "Opening consent for $target\u2026"
                    }
                }

                when (
                    val start = PlayAuth.authorize(
                        this@GoogleLoginActivity,
                        scopes,
                        if (adding) PlayAuth.Flow.ADD else PlayAuth.Flow.REAUTHORISE,
                        target,
                    )
                ) {
                    is PlayAuth.Start.NeedsConsent -> {
                        Debug.log("launching Google consent intent")
                        runOnUiThread {
                            @Suppress("DEPRECATION")
                            startIntentSenderForResult(
                                start.pendingIntent.intentSender,
                                PlayAuth.REQ_CONSENT, null, 0, 0, 0
                            )
                        }
                    }
                    is PlayAuth.Start.AlreadyGranted -> {
                        // Grant already held: the token is live and no UI was needed. It must
                        // still be attached to an account, and only the identity call can say
                        // which — so the record is completed here rather than assumed.
                        val identity = AuthManager.identity(applicationContext, start.accessToken)
                        AuthManager.setAccessToken(identity.sub, start.accessToken, 3600)
                        runOnUiThread {
                            status.text = "Grant held for ${identity.email}"
                            Debug.log("LOGIN OK (cached grant): ${identity.email}")
                            finish()
                        }
                    }
                }
            } catch (e: PluginError) {
                runOnUiThread {
                    status.text = "Login failed: ${e.code} \u2014 ${e.message}"
                    Debug.log("LOGIN FAILED: ${e.code}: ${e.message}")
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Login error: ${e.message}"
                    Debug.log("LOGIN ERROR: ${e.message}")
                    render()
                }
            }
        }
    }

    /** Receives the authorization result from the launched Google intent. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        PlayAuth.handleConsentResult(this, data) { outcome ->
            runOnUiThread {
                when (outcome) {
                    is PlayAuth.Outcome.SignedIn -> {
                        status.text = "Signed in as ${outcome.account.email}"
                        Debug.log("LOGIN OK: ${outcome.account.email}")
                        // Return to the home screen on success.
                        finish()
                    }
                    is PlayAuth.Outcome.Connected -> {
                        status.text = "'" + outcome.credential + "' connected as " + outcome.email
                        Debug.log("OAUTH OK: " + outcome.credential + " as " + outcome.email)
                        finish()
                    }
                    is PlayAuth.Outcome.Failed -> {
                        status.text = "Login failed: ${outcome.code} \u2014 ${outcome.message}"
                        Debug.log("LOGIN FAILED: ${outcome.code}: ${outcome.message}")
                    }
                }
                render()
            }
        }
    }

    /** The log repaints on its own tick; this is only for the status line. */
    private fun render() {
        status.invalidate()
    }

    companion object {
        /** The address to re-authorise. Absent when adding a new account. */
        const val EXTRA_EMAIL = "email"

        /** True to open the account picker; false to re-consent one known account. */
        const val EXTRA_ADD = "add"

        /** The OAuth-plugin credential whose refresh token this consent mints. */
        const val EXTRA_OAUTH = "oauth"

        /** The scope to request for that credential; blank means the store default. */
        const val EXTRA_SCOPE = "scope"
    }
}
