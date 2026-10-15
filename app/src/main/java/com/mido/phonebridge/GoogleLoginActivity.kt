package com.mido.phonebridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.OAuthLoopback
import com.mido.phonebridge.core.PluginError
import kotlin.concurrent.thread

/**
 * Loopback-redirect login screen (the ONLY supported flow for Gmail scopes).
 *
 * Binds a fixed loopback port, opens the browser for consent, and exchanges the
 * returned code for tokens. No fallback path exists; every failure surfaces loudly.
 */
class GoogleLoginActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var codeView: TextView

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

        codeView = TextView(this).apply {
            textSize = 30f
            setTextColor(0xFFFFD54F.toInt())
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        root.addView(codeView)

        val copyBtn = Button(this).apply {
            text = "COPY CODE"
            background = UiKit.pill(UiKit.NEUTRAL_BG)
            setTextColor(UiKit.TEXT_ON_DARK)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("code", codeView.text))
                Debug.log("user code copied")
            }
        }
        root.addView(copyBtn)

        val cancel = Button(this).apply {
            text = "CANCEL"
            background = UiKit.pill(UiKit.RED_BG)
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
                    Debug.log("binding loopback :${OAuthLoopback.LOOPBACK_PORT}")
                    status.text = "Opening browser for Google consent\u2026"
                }

                val session = OAuthLoopback.start(AuthManager.clientId, scopes)

                runOnUiThread {
                    codeView.text = ":${session.port}"
                    status.text = "Approve access in the browser, then return here.\nWaiting\u2026"
                    Debug.log("auth URL: ${session.authUrl}")
                    try {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(session.authUrl)
                            )
                        )
                    } catch (e: Exception) {
                        Debug.log("could not open browser: ${e.message} \u2014 open the URL from the log")
                    }
                }

                OAuthLoopback.waitForCode(
                    applicationContext,
                    AuthManager.clientId,
                    AuthManager.clientSecret,
                    session,
                    scopes
                )
                val email = AuthManager.fetchEmail(applicationContext)
                runOnUiThread {
                    status.text = "Logged in as ${email ?: "(email unknown)"}"
                    Debug.log("LOGIN OK: ${email ?: "?"}")
                    render()
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

    private fun render() { /* log TextView updates live; nothing else to repaint here */ }
}
