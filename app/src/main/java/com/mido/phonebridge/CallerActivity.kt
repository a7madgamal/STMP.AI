package com.mido.phonebridge

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.callers.CallerPrompt
import com.mido.phonebridge.callers.CallerState
import com.mido.phonebridge.callers.CallerStore
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.mido.phonebridge.ui.CallerCopy
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole
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
        title = "New device"

        val p = Theme.palette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.body)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Theme.dp(this@CallerActivity, Dimens.GUTTER), Theme.dp(this@CallerActivity, Dimens.SPACE_XL),
                Theme.dp(this@CallerActivity, Dimens.GUTTER), Theme.dp(this@CallerActivity, Dimens.SPACE_XL),
            )
        }

        root.addView(Etched.label(this, "NEW DEVICE").also { ViewCompat.setAccessibilityHeading(it, true) })
        root.addView(
            Readout.create(this, ip, Readout.Kind.DISPLAY, prompt = "first request from").root,
            fullWidth().apply { topMargin = dp(Dimens.SPACE_S) },
        )

        // **What this screen is FOR, in plain words.** The previous copy said "this address has
        // never reached this phone before" and jumped straight to a submit button — it stated a
        // fact and left the human to work out why they were being asked.
        root.addView(
            Type.text(
                this,
                "A computer just tried to talk to this phone, and it has not done that before. " +
                    "Give it a name you will recognise and it can connect from now on, or block it.",
                TypeRole.BODY, p.ink,
            ),
            fullWidth().apply { topMargin = dp(Dimens.SPACE_M) },
        )
        root.addView(
            Type.text(
                this,
                "Nothing is allowed until you choose. This is asked once per computer, not once per request.",
                TypeRole.DETAIL, p.etched,
            ),
            fullWidth().apply { topMargin = dp(Dimens.SPACE_S) },
        )

        val field = Field.create(this, "Device name", "", "e.g. Laptop", Field.Kind.TEXT)
        nameField = field.edit.apply {
            // A machine name, not a password: no masking, and no personalised-learning opt-out —
            // the keyboard learning "Ahmed's laptop" is harmless and helps next time.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            // Single-line already (Field); setting it again would reset the 48dp minimum height.
            isSaveEnabled = false
        }
        root.addView(field.root, fullWidth().apply { topMargin = dp(Dimens.GUTTER) })

        status = Type.text(this, "", TypeRole.DETAIL, p.etched).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.addView(status, fullWidth().apply { topMargin = dp(Dimens.SPACE_M) })

        // Three full-width keys, stacked: robust at 1.35 text where a row of three does not fit,
        // and the consequential ones are separated by distance rather than by hoping for aim.
        // BLOCK is last and 24dp from NOT NOW; none of them is the default-focused key.
        root.addView(
            KeyButton.create(
                this, "ALLOW", KeyButton.Kind.INK, KeyButton.Size.LARGE, describe = "Allow this device",
            ) { decide(allow = true) },
            fullWidth().apply { topMargin = dp(Dimens.GUTTER) },
        )
        // **A way out that decides nothing.** Without it the only two choices were allow and
        // block, so a human who was not ready had to press Back — and Back reads as "I broke
        // this" rather than "not now". It resolves the waiting request as unavailable, which
        // is retryable, and writes nothing: the next knock asks again.
        root.addView(
            KeyButton.create(
                this, "NOT NOW", KeyButton.Kind.OUTLINE, KeyButton.Size.REGULAR,
                describe = "Not now. Decide nothing",
            ) {
                if (decided) return@create
                decided = true
                CallerPrompt.resolve(CallerPrompt.Result.Unavailable)
                finish()
            },
            fullWidth().apply { topMargin = dp(Dimens.SPACE_S) },
        )
        root.addView(
            KeyButton.create(
                this, "BLOCK", KeyButton.Kind.OUTLINE, KeyButton.Size.REGULAR, describe = "Block this device",
            ) { decide(allow = false) },
            fullWidth().apply { topMargin = dp(Dimens.SPACE_XL) },
        )

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
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // System bars and the keyboard, so the name field stays above the keyboard at any text size.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }
        guard.addView(scroll)
        setContentView(guard)
        // Spec 3.4.2: the name field has the first focus. Since API 28 a view gets no initial focus in
        // touch mode, so it is asked for here; ALLOW, NOT NOW and BLOCK are never the default.
        nameField.requestFocus()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int) = Theme.dp(this, v)

    /** Sets the status line. A problem is caution-coloured (errors are never signal orange, D6). */
    private fun showStatus(text: String, isProblem: Boolean) {
        val p = Theme.palette(this)
        status.text = text
        status.setTextColor(if (isProblem) p.cautionText else p.etched)
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    @VisibleForTesting
    internal fun showStatusForTest(text: String, isProblem: Boolean) = showStatus(text, isProblem)

    private fun decide(allow: Boolean) {
        if (decided) return
        val name = nameField.text.toString().trim()
        if (allow && name.isEmpty()) {
            // Fail hard rather than storing a verified row with no name: the name IS the record.
            showStatus("A name is required to allow this device.", isProblem = true)
            Haptics.perform(nameField, HapticEvent.REJECT)
            nameField.requestFocus()
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
            showStatus(CallerCopy.cannotConfirm(can), isProblem = true)
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
                    showStatus("Not confirmed: $errString", isProblem = false)
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
            showStatus("Could not save: ${e.message}", isProblem = true)
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
