package com.mido.phonebridge

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.ui.PluginDetailCopy
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.TopBar
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole
import kotlin.concurrent.thread

/**
 * Google sign-in screen. Uses Play Services (AuthorizationClient): package name
 * + signing SHA-1 identify the app, so there is no embedded secret and no local
 * listener. On success it returns to the home screen.
 */
class GoogleLoginActivity : AppCompatActivity() {

    private lateinit var panel: LinearLayout
    private lateinit var cancel: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = Theme.palette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.body)
        }
        root.addView(TopBar.create(this, "Sign in with Google"))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@GoogleLoginActivity, Dimens.GUTTER), Theme.dp(this@GoogleLoginActivity, Dimens.SPACE_L),
                Theme.dp(this@GoogleLoginActivity, Dimens.GUTTER), Theme.dp(this@GoogleLoginActivity, Dimens.SPACE_XL),
            )
        }
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(panel, matchWrap())
        cancel = KeyButton.create(this, "Cancel", KeyButton.Kind.OUTLINE, KeyButton.Size.REGULAR) { finish() }
        body.addView(cancel, matchWrap().apply { topMargin = Theme.dp(this@GoogleLoginActivity, Dimens.SPACE_M) })

        val scroll = ScrollView(this).apply { clipToPadding = false; addView(body) }
        BottomInset.apply(scroll)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (AuthManager.clientId.isBlank()) {
            cancel.visibility = View.GONE
            showNotSetUp()
            return
        }
        if (AuthManager.isLoggedIn(applicationContext)) {
            setStatus(Led.State.OK, "Already signed in. Signing in again replaces the stored token.")
        }
        beginFlow()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun showNotSetUp() {
        panel.removeAllViews()
        panel.addView(StateBlock.create(
            this, StateBlock.Kind.ERROR,
            title = "Google sign-in isn't set up in this build",
            body = "This build has no Google sign-in. Install the official release to use Gmail, Calendar and Drive.",
        ))
    }

    /** One status panel: a light and a sentence, replacing whatever was there. */
    private fun setStatus(led: Led.State, text: String) {
        val p = Theme.palette(this)
        panel.removeAllViews()
        val box = Panel.create(this, Panel.Tone.PLAIN)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Led(this).apply { state = led }, LinearLayout.LayoutParams(
            Theme.dp(this, 16), Theme.dp(this, 16),
        ).apply { marginEnd = Theme.dp(this@GoogleLoginActivity, Dimens.SPACE_M) })
        row.addView(Type.text(this, text, TypeRole.ROW_LABEL, p.ink), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        box.addView(row)
        panel.addView(box)
    }

    private fun showFailed(code: String, message: String?) {
        panel.removeAllViews()
        panel.addView(StateBlock.create(
            this, StateBlock.Kind.ERROR,
            title = "Sign-in failed",
            body = "Google didn't complete the sign-in. You can try again.",
            action = KeyButton.Spec("Try again", "Try sign-in again") { restartFlow() },
        ))
        panel.addView(Readout.create(this, "$code — ${message ?: "no detail"}", Readout.Kind.BODY).root)
    }

    private fun restartFlow() {
        panel.removeAllViews()
        beginFlow()
    }

    private fun beginFlow() {
        thread {
            try {
                val scopes = com.mido.phonebridge.core.PluginRegistry.allScopes()
                if (scopes.isEmpty()) {
                    throw PluginError("no_scopes", "no registered plugins declare scopes")
                }

                runOnUiThread {
                    Debug.log("requesting authorization code (Android client)")
                    setStatus(Led.State.PENDING, "Opening the Google account picker…")
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
                        val email = AuthManager.fetchEmail(applicationContext, start.accessToken)
                        runOnUiThread {
                            setStatus(Led.State.OK, PluginDetailCopy.signedIn(email))
                            Debug.log("LOGIN OK (cached grant): ${email ?: "?"}")
                            finish()
                        }
                    }
                }
            } catch (e: PluginError) {
                runOnUiThread {
                    Debug.log("LOGIN FAILED: ${e.code}: ${e.message}")
                    showFailed(e.code, e.message)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Debug.log("LOGIN ERROR: ${e.message}")
                    showFailed(e.javaClass.simpleName, e.message)
                }
            }
        }
    }

    /** Receives the authorization result from the launched Google intent. */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        PlayAuth.handleConsentResult(this, data) { outcome ->
            runOnUiThread {
                when (outcome) {
                    is PlayAuth.Outcome.SignedIn -> {
                        setStatus(Led.State.OK, PluginDetailCopy.signedIn(outcome.email))
                        Debug.log("LOGIN OK: ${outcome.email}")
                        // Return to the home screen on success.
                        finish()
                    }
                    is PlayAuth.Outcome.Failed -> {
                        Debug.log("LOGIN FAILED: ${outcome.code}: ${outcome.message}")
                        showFailed(outcome.code, outcome.message)
                    }
                }
            }
        }
    }
}
