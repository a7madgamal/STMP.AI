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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = Theme.dp(this, 20)
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
        val log = Theme.text(this, "", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT, mono = true)
        root.addView(ScrollView(this).apply { addView(log) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })

        setContentView(Theme.inset(root))

        if (AuthManager.clientId.isBlank()) {
            status.text = "No OAuth client id configured in this build."
            return
        }
        if (AuthManager.isLoggedIn(applicationContext)) {
            status.text = "Already logged in. Re-login will replace the stored token."
        }
        beginFlow(log)
    }

    private fun beginFlow(log: TextView) {
        thread {
            try {
                val scopes = com.mido.phonebridge.core.PluginRegistry.allScopes()
                if (scopes.isEmpty()) {
                    throw PluginError("no_scopes", "no registered plugins declare scopes")
                }

                runOnUiThread {
                    Debug.log("requesting authorization code (Android client)")
                    status.text = "Opening Google account picker\u2026"
                }

                when (val start = PlayAuth.authorize(this@GoogleLoginActivity, scopes)) {
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
                        // Grant already held: token is live, no UI needed.
                        val email = AuthManager.fetchEmail(
                            applicationContext, start.accessToken
                        )
                        runOnUiThread {
                            status.text = "Logged in as ${email ?: "(email unknown)"}"
                            Debug.log("LOGIN OK (cached grant): ${email ?: "?"}")
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
                        status.text = "Logged in as ${outcome.email}"
                        Debug.log("LOGIN OK: ${outcome.email}")
                        // Return to the home screen on success.
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

    private fun render() { /* log TextView updates live; nothing else to repaint here */ }
}
