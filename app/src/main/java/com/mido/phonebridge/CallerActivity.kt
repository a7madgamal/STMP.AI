package com.mido.phonebridge

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.callers.CallerPrompt
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.callers.CallerStore
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import java.util.concurrent.Executor

/**
 * "Who is this, and may they call?"
 *
 * **The third gate, and the only one that asks about the CALLER rather than the request.** It is
 * triggered by an unnamed address making its first request, and the answer is written to
 * `callers.json` — so it is asked once per machine, never once per request.
 *
 * Two decisions, both requiring a fingerprint (Mido, 2026-11-01). Deny is gated as well as approve:
 * a one-tap block would let someone holding an unlocked phone block the owner's own laptop.
 *
 * **A separate Activity, not the approval modal.** `ApprovalView` is fifteen fields describing a
 * request to approve; this screen has none of them. Reusing it would mean a mode flag inside a
 * class that already owns a queue, arming, a countdown and the stamp — one class doing two jobs,
 * which is the exact shape that let `auditResponseBody` be honoured on one path and ignored on
 * another.
 *
 * Nothing here decides anything by default: if the fingerprint cannot run, both buttons stay dead
 * and the waiting request is refused as `caller_unverified`, which can simply be retried.
 */
class CallerActivity : FragmentActivity() {

    private lateinit var nameField: EditText
    private lateinit var approve: android.widget.Button
    private lateinit var deny: android.widget.Button
    private lateinit var status: TextView
    private lateinit var ip: String

    /** Set once a decision has been taken, so a double-tap cannot write twice. */
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ip = intent.getStringExtra(EXTRA_IP) ?: run {
            // No address means there is nothing to ask about. Finish rather than render a prompt
            // for a question nobody can answer.
            CallerPrompt.resolve(CallerPrompt.Result.Unavailable)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@CallerActivity, 20), Theme.dp(this@CallerActivity, 24),
                Theme.dp(this@CallerActivity, 20), Theme.dp(this@CallerActivity, 24)
            )
            background = Theme.sheet(Theme.BG)
            gravity = Gravity.CENTER_VERTICAL
        }

        root.addView(
            Theme.text(this, "NEW DEVICE", 20f).apply {
                typeface = Theme.handFont(this@CallerActivity)
            }
        )
        root.addView(
            Theme.text(this, ip, 15f, Theme.WARNING, mono = true).apply {
                setPadding(0, Theme.dp(this@CallerActivity, 8), 0, 0)
            }
        )

        // **What this screen is FOR, in plain words.** The previous copy said "this address has
        // never reached this phone before" and jumped straight to a submit button — it stated a
        // fact and left the human to work out why they were being asked.
        root.addView(
            Theme.text(
                this,
                "A computer just tried to talk to this phone, and it has not done that before. " +
                    "Give it a name you will recognise and it can connect from now on — or block " +
                    "it.",
                13f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@CallerActivity, 10), 0, 0) }
        )
        root.addView(
            Theme.text(
                this,
                "Nothing is allowed until you choose, and this is asked once per computer, " +
                    "not once per request.",
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@CallerActivity, 6), 0, 0) }
        )

        nameField = EditText(this).apply {
                // Explicit colours: the platform default is white-on-cream here.
                setTextColor(Theme.ON_SURFACE)
                setHintTextColor(Theme.ON_SURFACE_VARIANT)
                highlightColor = Theme.ACCENT
            // A machine name, not a password: no masking, and no personalised-learning opt-out —
            // the keyboard learning "Ahmed's laptop" is harmless and helps next time.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            hint = "name this device, e.g. Laptop"
            setSaveEnabled(false)
        }
        root.addView(
            nameField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(this@CallerActivity, 18) }
        )

        status = Theme.text(this, "", 12f, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, Theme.dp(this@CallerActivity, 10), 0, 0)
        }
        root.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dp(this@CallerActivity, 20), 0, 0)
        }

        // **A way out that decides nothing.** Without it the only two choices were allow and
        // block, so a human who was not ready had to press Back — and Back reads as "I broke
        // this" rather than "not now". Cancel resolves the waiting request as unavailable, which
        // is retryable, and writes nothing: the next knock asks again.
        val cancel = Theme.button(this, "not now", Theme.ButtonStyle.GHOST, small = true) {
            if (decided) return@button
            decided = true
            CallerPrompt.resolve(CallerPrompt.Result.Unavailable)
            finish()
        }
        buttons.addView(cancel)

        // Spacer: the two consequential buttons sit at the right edge, away from the dismissive
        // one. Approving by reflex should take aim, not luck.
        buttons.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))

        deny = Theme.button(this, "block", Theme.ButtonStyle.DANGER) { decide(allow = false) }
        approve = Theme.button(this, "allow", Theme.ButtonStyle.SUCCESS) { decide(allow = true) }
        buttons.addView(deny)
        buttons.addView(
            approve,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Theme.dp(this@CallerActivity, 10) }
        )
        root.addView(buttons)

        // Tapjacking (D17): while another window fully covers this screen, no touch reaches
        // allow, block, not now or the name field, and the banner says why. Partial cover is
        // allowed (D60). System back still works; it resolves Unavailable, which is safe.
        val banner = ObscuredTouchGuard.banner(this)
        root.addView(banner, 0)
        val guard = ObscuredTouchGuard(this)
        guard.onObscuredTouch = {
            banner.visibility = View.VISIBLE
            Haptics.perform(guard, HapticEvent.REJECT)
        }
        guard.onClearTouch = { banner.visibility = View.GONE }
        guard.addView(Theme.inset(ScrollView(this).apply { addView(root) }))
        setContentView(guard)
    }

    private fun decide(allow: Boolean) {
        if (decided) return
        val name = nameField.text.toString().trim()
        if (allow && name.isEmpty()) {
            // Fail hard rather than storing a verified row with no name: the name IS the record.
            status.text = "A name is required to allow this device."
            status.setTextColor(Theme.DANGER)
            return
        }
        promptFingerprint(allow, name)
    }

    /**
     * The fingerprint is required for BOTH answers.
     *
     * No vault cipher is involved — this is presence, not a secret operation — so a plain prompt
     * is used and `DEVICE_CREDENTIAL` is allowed as a fallback when the sensor is unavailable.
     * That is deliberately more permissive than the SECURE approval path: naming a caller decides
     * nothing secret, and locking someone out of their own setup screen because a finger is wet
     * would be a worse outcome than the risk it avoids.
     */
    private fun promptFingerprint(allow: Boolean, name: String) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            status.text = "Cannot confirm it is you (code $can), so nothing was decided."
            status.setTextColor(Theme.DANGER)
            return
        }
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    commit(allow, name)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Nothing is written. The waiting request stays unverified and can retry.
                    status.text = "Not confirmed: $errString"
                    status.setTextColor(Theme.ON_SURFACE_VARIANT)
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (allow) "Allow $ip?" else "Block $ip?")
                .setSubtitle(if (allow) "Named: $name" else "This address will be refused silently")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    /** Write the decision, then release the waiting request. */
    private fun commit(allow: Boolean, name: String) {
        if (decided) return
        decided = true
        try {
            val store = CallerStore.from(applicationContext)
            if (allow) {
                store.set(ip, CallerState.Verified(name))
            } else {
                store.set(ip, CallerState.Denied)
            }
        } catch (e: Exception) {
            // The write is the decision. If it failed, nothing was decided — do not tell the
            // caller it was allowed, and do not pretend it was blocked.
            status.text = "Could not save: ${e.message}"
            status.setTextColor(Theme.DANGER)
            decided = false
            return
        }
        com.mido.phonebridge.Debug.log(
            "caller ${if (allow) "verified as '$name'" else "blocked"}: $ip"
        )
        CallerPrompt.resolve(
            if (allow) CallerPrompt.Result.Named else CallerPrompt.Result.Blocked
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Finished without deciding (back button, task killed): release the waiter as "unavailable"
        // rather than leaving it to burn its whole window on a screen that is already gone.
        if (!decided) CallerPrompt.resolve(CallerPrompt.Result.Unavailable)
    }

    companion object {
        const val EXTRA_IP = "ip"
    }
}
