package com.mido.phonebridge

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginError
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

        val pad = UiKit.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }

        root.addView(TextView(this).apply {
            text = "Login with Google"
            textSize = 22f
            setTextColor(UiKit.TEXT_ON_DARK)
        })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(UiKit.TEXT_MUTED)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        val cancel = Button(this).apply {
            text = "CANCEL"
            background = UiKit.pill(UiKit.NEUTRAL_BG)
            setTextColor(UiKit.TEXT_ON_DARK)
            setOnClickListener { finish() }
        }
        root.addView(cancel)

        root.addView(TextView(this).apply {
            text = "\u2014 log \u2014"
            textSize = 12f
            setTextColor(UiKit.TEXT_MUTED)
            setPadding(0, pad, 0, 0)
        })
        val log = TextView(this).apply {
            textSize = 11f
            setTextColor(UiKit.TEXT_MUTED)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(ScrollView(this).apply { addView(log) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })

        setContentView(root)

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
