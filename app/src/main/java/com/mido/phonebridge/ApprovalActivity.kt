package com.mido.phonebridge

import androidx.fragment.app.FragmentActivity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.mido.phonebridge.core.SecretVault

/**
 * Full-screen approval UI. Shows every detail of the pending request and
 * blocks until the human chooses approve/deny.
 */
class ApprovalActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_TIMEOUT_SECONDS = "timeoutSeconds"
        const val EXTRA_CREATED_AT = "createdAt"
        const val EXTRA_WAITING_BEHIND = "waitingBehind"

        /** How long the decision buttons stay disabled after the modal appears. */
        private const val DECISION_DELAY_MS = 3_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw over other apps / show over the lockscreen, turn the screen on,
        // and keep it on while the human decides. This is what makes the modal
        // appear *immediately* regardless of what is in the foreground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val remote = intent.getStringExtra(EXTRA_REMOTE) ?: "?"
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: "?"
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "?"
        val account = intent.getStringExtra(EXTRA_ACCOUNT)
        val timeoutSeconds = intent.getLongExtra(EXTRA_TIMEOUT_SECONDS, ApprovalGate.TIMEOUT_SECONDS)
        val createdAtMs = intent.getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis())
        val waitingBehind = intent.getIntExtra(EXTRA_WAITING_BEHIND, 0)

        // Two sources of truth:
        //  - view:  the RESOLVED, human-readable WHAT/ON (built by the caller
        //           from the live notification + resolved action)
        //  - detail: the raw JSON, shown collapsed under "details"
        val view = ApprovalGate.currentView()
        val detail = ApprovalGate.currentDetailJson()
        // The gate decides whether this request needs a biometric; the UI only renders
        // that decision (see ApprovalView.requiresBiometric). Read once, here.
        val requiresBiometric = view?.requiresBiometric == true

        val pad = (24 * resources.displayMetrics.density).toInt()

        // Scrollable modal body. Long notifications (a big Arabic message, a
        // multi-line task) previously pushed the buttons off the bottom edge with
        // no way to reach them — a timed-out approval through no fault of the
        // user. The whole content scrolls, so every control is always reachable.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }

        fun label(t: String, size: Float, color: Int = 0xFFFFFFFF.toInt()): TextView =
            TextView(this).apply {
                text = t
                textSize = size
                setTextColor(color)
                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            }

        // Small all-caps section header.
        fun section(t: String): TextView = TextView(this).apply {
            text = t
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xFF78909C.toInt())
            setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }

        content.addView(label("🔐 APPROVE?", 22f))

        if (view != null) {
            // --- WHO ---
            content.addView(section("WHO"))
            content.addView(label(view.requester, 17f, 0xFFFFB74D.toInt()))
            if (view.account.isNotBlank() && view.account != "—") {
                content.addView(label("as ${view.account}", 15f, 0xFF81C784.toInt()))
            }

            // --- WHAT (the resolved action, in words) ---
            content.addView(section("WHAT"))
            content.addView(label("\"${view.actionLabel}\"", 20f))

            // Render a labelled row: label on its own quiet line, value below in the
            // style the row asked for. This is the ONE renderer; it knows nothing
            // about Gmail, Calendar, or notifications — only rows. Never print a
            // label with nothing under it (an empty row is a defect, not a state).
            val d = resources.displayMetrics.density
            fun row(r: ApprovalGate.Row) {
                content.addView(TextView(this).apply {
                    text = r.label
                    textSize = 12f
                    letterSpacing = 0.1f
                    setTextColor(0xFFFF7043.toInt())
                    setPadding(0, (8 * d).toInt(), 0, 0)
                })
                content.addView(TextView(this).apply {
                    text = r.value
                    textSize = 16f
                    setTextColor(0xFFFFE0B2.toInt())
                    setTextIsSelectable(true)
                    if (r.style == ApprovalGate.Row.Style.MONO ||
                        r.style == ApprovalGate.Row.Style.WARN
                    ) {
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    setPadding(0, 0, 0, (6 * d).toInt())
                })
            }

            // Plugin-provided sections (Calendar, Gmail, …). Each section renders
            // only when it has rows — the component never invents a header.
            for (sec in view.sections) {
                val rows = sec.rows.filter { it.value.isNotBlank() }
                if (rows.isEmpty()) continue
                content.addView(section(sec.title))
                rows.forEach { row(it) }
            }

            // Legacy target block (mutating routes) — same renderer, one section.
            if (view.destructive.isNotEmpty()) {
                view.destructive
                    .filter { it.second.isNotBlank() }
                    .forEach { row(ApprovalGate.Row(it.first, it.second, ApprovalGate.Row.Style.WARN)) }
            }
            // Scope the read carried (filters). A scoped read must show its scope:
            // "count=3, package=com.whatsapp", never a bare verb.
            if (view.filters.isNotEmpty()) {
                val scope = view.filters.entries.joinToString(", ") { "${it.key}=${it.value}" }
                content.addView(label("filter: $scope", 15f, 0xFFFFB74D.toInt()))
            }
            if (!view.replyText.isNullOrBlank()) {
                content.addView(label("reply: ${view.replyText}", 16f, 0xFF81C784.toInt()))
            }
            // Other buttons the same notification offers (options are a menu, the
            // resolved WHAT is the pick). Show them so the human sees the space.
            if (view.options.isNotEmpty()) {
                content.addView(label("other actions: " + view.options.joinToString(" · "), 14f, 0xFF90A4AE.toInt()))
            }

            // Notification content as ordinary rows — the same renderer as every
            // plugin. Only rendered when there is a notification behind the request;
            // a read route has none and gets no section at all.
            val notifRows = buildList {
                val head = if (view.notifTitle.isNotBlank()) view.notifTitle
                else view.appLabel
                if (head.isNotBlank()) add(ApprovalGate.Row("TITLE", head))
                if (view.notifSender.isNotBlank()) add(ApprovalGate.Row("FROM", view.notifSender))
                if (view.notifText.isNotBlank()) add(ApprovalGate.Row("BODY", view.notifText, ApprovalGate.Row.Style.LONG))
                if (view.messageCount > 1) add(ApprovalGate.Row("", "💬 ${view.messageCount} messages", ApprovalGate.Row.Style.WARN))
            }
            if (notifRows.isNotEmpty()) {
                content.addView(section("ON"))
                notifRows.forEach { row(it) }
            }
        } else {
            // No structured view (e.g. non-notification routes): keep the old fields.
            content.addView(label("Source: $remote", 15f, 0xFFFFB74D.toInt()))
            content.addView(label("Endpoint: $endpoint", 15f, 0xFFFFB74D.toInt()))
            content.addView(label("Summary: $summary", 16f))
        }

        // --- Details: raw endpoint + full JSON, collapsed by default. ---
        val detailBody = TextView(this).apply {
            text = "endpoint: $endpoint\n\n" + (detail ?: "(no detail)")
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val detailScroll = ScrollView(this).apply {
            addView(detailBody)
            visibility = ViewGroup.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this@ApprovalActivity, 220)
            )
        }
        val detailToggle = TextView(this).apply {
            text = "▸ details"
            textSize = 13f
            setTextColor(0xFF78909C.toInt())
            setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            isClickable = true
            setOnClickListener {
                val show = detailScroll.visibility != ViewGroup.VISIBLE
                detailScroll.visibility = if (show) ViewGroup.VISIBLE else ViewGroup.GONE
                text = if (show) "▾ details" else "▸ details"
            }
        }

        // --- Countdown to auto-deny. Visualized, updated every 250ms. ---
        val countdown = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFB74D.toInt())
            setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
        }

        // --- Queue position: only when others are actually waiting. The queue is
        // FIFO, so this request is the one on screen and the rest wait their turn.
        // Never render a "0 waiting" line — an empty statement is noise.
        val queueLine = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
            visibility = ViewGroup.GONE
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val elapsedSec = (System.currentTimeMillis() - createdAtMs) / 1000L
                val left = (timeoutSeconds - elapsedSec).coerceAtLeast(0L)
                val pct = if (timeoutSeconds > 0) (left * 100 / timeoutSeconds) else 0
                countdown.text = "⏳ auto-deny in ${left}s"
                countdown.setTextColor(if (left <= 10) 0xFFEF5350.toInt() else 0xFFFFB74D.toInt())
                // A simple text bar keeps it dependency-free and unmissable.
                countdown.contentDescription = "timeout progress ${pct}%"
                // Refresh the queue label from live state every tick, so it appears
                // the moment a follower queues (never a value sampled once at
                // enqueue time, which was always 0 for the request that became
                // head while the follower was still arriving).
                val behind = ApprovalGate.waitingBehindCurrent()
                queueLine.visibility = if (behind > 0) ViewGroup.VISIBLE else ViewGroup.GONE
                if (behind > 0) {
                    queueLine.text = "≡ $behind waiting behind this one"
                }
                if (left > 0 && !isFinishing) handler.postDelayed(this, 250L)
            }
        }
        handler.post(tick)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad / 2)
        }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@ApprovalActivity, 56),
                UiKit.dp(this@ApprovalActivity, 56)
            )
        }
        iconRow.addView(icon)

        val deny = Button(this).apply {
            text = "DENY"
            background = UiKit.pill(UiKit.NEUTRAL_BG)
            setTextColor(UiKit.TEXT_ON_DARK)
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { finishWith(id, approved = false) }
        }
        val allow = Button(this).apply {
            text = "APPROVE"
            background = UiKit.pill(UiKit.GREEN_BG)
            setTextColor(UiKit.TEXT_ON_DARK)
            isEnabled = false
            alpha = 0.4f
            setOnClickListener {
                // Mutating routes bind APPROVE to a live biometric: the tap opens the
                // system prompt and the request fires only on success. A failed or
                // dismissed prompt leaves the request PENDING with APPROVE still live,
                // so tapping again gives another attempt — the countdown keeps running
                // underneath and is the only limit. Reads approve on a plain tap.
                if (requiresBiometric) {
                    promptBiometric { finishWith(id, approved = true) }
                } else {
                    finishWith(id, approved = true)
                }
            }
        }
        // Fails closed: if a mutating route needs a biometric that this device cannot
        // provide (none enrolled, none present), APPROVE must never be tappable — only
        // DENY works, and the reason is stated. Resolved before the 3s reserve so the
        // button simply never enables.
        val biometricUsable = requiresBiometric && canUseBiometric()
        val biometricBlocked = requiresBiometric && !biometricUsable
        // Reserved-space delay: the buttons render in place immediately but stay
        // disabled for 3s, so a reflexive tap (a muscle-memory DENY) cannot kill a
        // request the human has not actually read. They are never removed, so the
        // layout does not jump under the thumb when they enable.
        val enableDelay = android.os.Handler(android.os.Looper.getMainLooper())
        enableDelay.postDelayed({
            if (!isFinishing) {
                deny.isEnabled = true
                deny.alpha = 1f
                // APPROVE only enables when the route needs no biometric, or the device
                // can actually satisfy it. When blocked, it stays disabled forever and
                // the reason is shown below — fails closed (point 4).
                if (!biometricBlocked) {
                    allow.isEnabled = true
                    allow.alpha = 1f
                }
            }
        }, DECISION_DELAY_MS)
        btnRow.addView(deny, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(allow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Scroll the DESCRIPTIVE content only; pin the decision controls (icon,
        // countdown, details toggle, buttons) to the bottom of the screen so they
        // are always reachable no matter how long the notification is. Buttons
        // that scroll away are buttons the user misses -> a timed-out request.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

        val pinned = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        pinned.addView(detailToggle)
        pinned.addView(detailScroll)
        pinned.addView(queueLine)
        pinned.addView(countdown)
        if (biometricBlocked) {
            pinned.addView(
                label("⚠ APPROVE unavailable — set up a fingerprint/PIN (strong) to approve", 14f, 0xFFFF7043.toInt())
            )
        }
        pinned.addView(iconRow)
        pinned.addView(btnRow)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101418.toInt())
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(pinned)
        }
        setContentView(outer)
    }

    private fun finishWith(id: Long, approved: Boolean) {
        val resolved = ApprovalGate.resolve(id, approved)
        if (!resolved) Log.w("ApprovalActivity", "request $id already resolved/expired")
        finish()
    }

    /**
     * Bind a mutating APPROVE to a live biometric.
     *
     * When a vault exists the prompt carries the vault's `CryptoObject` (decision A:
     * every scan is crypto-bound). Two things fall out of that: the approval is
     * unforgeable — no plugin, bug or stray callback can approve without a live finger on
     * a hardware key — and if the vault happens to be locked, the SAME authorised cipher
     * unwraps it, so one modal both unlocks and approves. Fingerprint-only, by platform
     * rule: a crypto-bound prompt cannot accept `DEVICE_CREDENTIAL`.
     *
     * Before the vault exists there is nothing to bind to, so the prompt falls back to
     * the plain form (STRONG or device credential). That is not a weakening: there is no
     * secret on the device yet, and keeping the PIN escape hatch until setup is done is
     * strictly more usable.
     *
     * On success [onApproved] fires and the request resolves. On failure/dismiss we do
     * NOTHING to the request: it stays pending on its own countdown, APPROVE stays
     * live, and tapping it again re-prompts — the window is the only limit. Reads never
     * reach here (see [ApprovalView.requiresBiometric]).
     */
    private fun promptBiometric(onApproved: () -> Unit) {
        // Prepare the vault cipher FIRST: when a vault exists the prompt must carry it
        // (decision A — every scan is crypto-bound), and if the key was invalidated there
        // is no prompt worth showing. Failing closed here is the whole point.
        val cipher = try {
            if (SecretVault.exists(this)) SecretVault.prepareUnlockCipher(this) else null
        } catch (e: Exception) {
            Log.w("ApprovalActivity", "vault cipher unavailable (${e.message}); not approving")
            return
        }
        val authenticators = if (cipher != null) {
            // Crypto-bound: the platform forbids DEVICE_CREDENTIAL alongside a
            // CryptoObject, so a bound prompt is fingerprint-only by construction.
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        val manager = BiometricManager.from(this)
        val can = manager.canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Fails closed: no prompt, no approval. The caller disables APPROVE and
            // states the reason; this guard exists so a stray tap can never approve.
            Log.w("ApprovalActivity", "biometric unavailable (code=$can); not approving")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cipher != null) {
                        val authorised = result.cryptoObject?.cipher
                        if (authorised == null) {
                            // Impossible in principle: we passed a CryptoObject. Refuse
                            // rather than treat "authenticated" as sufficient.
                            Log.w("ApprovalActivity", "no authorised cipher returned; not approving")
                            return
                        }
                        if (!SecretVault.isUnlocked()) {
                            try {
                                // One modal does both (decision A): the finger that
                                // approves also opens the vault, so a SECURE call on a
                                // locked vault is not a dead end.
                                SecretVault.unlock(this@ApprovalActivity, authorised)
                                Log.i("ApprovalActivity", "vault unlocked by the approval prompt")
                            } catch (e: Exception) {
                                Log.w("ApprovalActivity", "vault unlock failed: ${e.message}")
                                return
                            }
                        }
                    }
                    // Success wins the race: the request is approved even if the
                    // countdown was about to expire. resolve() is idempotent — if the
                    // window DID close first, it is a no-op and logged, never a wrong
                    // approval (resolve only applies while the request is still pending).
                    onApproved()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Dismissed / locked out: leave the request pending. APPROVE stays
                    // live so the human can try again within the window (point 7).
                    Log.i("ApprovalActivity", "biometric error $errorCode: $errString")
                }
                override fun onAuthenticationFailed() {
                    // A single bad read: the prompt stays up for a retry; nothing to do.
                    Log.i("ApprovalActivity", "biometric failed (retryable)")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (cipher != null) "Unlock and approve" else "Approve request")
            .setSubtitle(endpointLabel())
            .setAllowedAuthenticators(authenticators)
        // A STRONG-only prompt REQUIRES a negative button (the platform throws without
        // one). With device credential allowed the system supplies its own, so adding
        // one there would be an error — hence the branch.
        if (cipher != null) info.setNegativeButtonText("Cancel")
        if (cipher != null) {
            prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(cipher))
        } else {
            prompt.authenticate(info.build())
        }
    }

    private fun endpointLabel(): String =
        intent.getStringExtra(EXTRA_ENDPOINT) ?: "PhoneBridge"

    /**
     * Can this device satisfy the approval requirement right now? Uses the SAME
     * authenticator set as [promptBiometric], so the button state and the prompt can
     * never disagree: when a vault exists that is STRONG biometric alone (crypto-bound,
     * decision A), otherwise STRONG or device credential.
     *
     * Also fails closed when the vault cipher cannot be prepared — an invalidated key
     * means the prompt could never succeed, so APPROVE must not enable.
     */
    private fun canUseBiometric(): Boolean {
        val bound = if (SecretVault.exists(this)) {
            try {
                // Prepare it for its side effect: this throws when the key is
                // invalidated, which is exactly the case where APPROVE must not enable.
                SecretVault.prepareUnlockCipher(this)
                true
            } catch (e: Exception) {
                Log.w("ApprovalActivity", "vault cipher unavailable; APPROVE stays disabled: ${e.message}")
                return false
            }
        } else {
            false
        }
        val authenticators = if (bound) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        return BiometricManager.from(this).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    @Deprecated("Deprecated in the platform; kept for back-compat behavior on older devices.")
    override fun onBackPressed() {
        // Back == deny. Never leave a request dangling open by accident.
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        finishWith(id, approved = false)
    }
}
