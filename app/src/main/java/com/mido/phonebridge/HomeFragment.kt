package com.mido.phonebridge

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ui.ServerStatus
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.ui.Theme

class HomeFragment : BridgeFragment(), BridgeControl {

    companion object {
        const val REQ_POST_NOTIF = 1001

        /**
         * How often the hero re-renders while the activity is resumed.
         *
         * 5s is a compromise: fast enough that a dead server cannot stay green through a
         * glance at the screen, slow enough that the repaint is invisible. It reads a CACHED
         * probe result, so the cost is a field read — the real probe runs in the service on its
         * own 60s schedule, off the main thread.
         */
        const val UI_TICK_MS = 5_000L

        /**
         * How long the knob shows a pending look after a tap.
         *
         * Long enough to cover a bind on a slow device, short enough that a start which silently
         * failed does not leave the knob looking busy forever. After this the knob simply reflects
         * whatever the service says, including "still down".
         */
        const val PENDING_WINDOW_MS = 3_000L
    }

    /**
     * The server switch.
     *
     * A toggle rather than a button: the old control had to say "START SERVER" or "STOP SERVER"
     * and be recomputed on every render, so a stale label could claim the server was stopped
     * while it was running. The knob's POSITION is the state, so it cannot disagree with itself.
     */
    private lateinit var serverToggle: com.mido.phonebridge.ui.RuggedToggle

    /**
     * When the last start/stop tap was made, so the knob can show a PENDING look while the
     * service is still binding.
     *
     * The server is a live service, not a stored value: starting takes a moment and can fail. The
     * knob therefore must not slide on the tap — it would be showing intent, and would slide back
     * on a failure. It stays where the last known state put it and takes on the pending colour
     * until `render()` sees the real value.
     */
    private var pendingSince: Long = 0L
    private lateinit var auditBanner: TextView
    private lateinit var gateCard: LinearLayout
    private lateinit var gateTitle: TextView
    private lateinit var gateSub: TextView
    private lateinit var gateButtons: LinearLayout
    private lateinit var gateFingerprint: MaterialButton
    private lateinit var gateFingerprintWhy: TextView
    private lateinit var heroCard: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView

    /**
     * Guards the load prompt so returning to the app (onResume) never re-prompts on
     * its own. One prompt per process — which is exactly the session.
     */

    /**
     * Drives the while-resumed repaint. See [onResume].
     *
     * Main looper because it touches views, and a plain `Handler()` would do — named explicitly
     * so the threading is obvious to a reader rather than inferred from construction order.
     */
    private val uiTickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * What the checklist looked like at the last repaint.
     *
     * **This field exists because a tap was being eaten.** Reported 2026-11-02: *"nothing changed
     * no modal"*. The device log had the answer — a tap logged `pointer 0` and NO `pointer 1`,
     * a gesture cancelled before it could become a click. The cause was `uiTick`: every 5 seconds
     * `render()` rebuilds rows, so a press
     * held for longer than the interval had its View destroyed underneath the finger and the click
     * never fired. Pressing a row is exactly the kind of touch that lasts a second or two, so the
     * interaction was lost often enough to look like "the button does nothing".
     *
     * Worse, the bug is self-concealing: nothing crashed, nothing logged, the screen looked
     * identical because it had been rebuilt with the same content. Two releases of dialog copy
     * were written against a tap that never reached them.
     *
     * So the tick now repaints ONLY when something it displays actually changed. The rows keep
     * their identity between ticks, which is what lets a press survive to become a click.
     */
    private var lastRenderSignature: String? = null

    /** Guards the unrecoverable-vault reset dialog so it does not stack. */

    override val tab = Theme.Tab.HOME

    /**
     * Home's rows: hero, start/stop, the setup checklist, account, tools, audit, debug.
     *
     * Called by [BridgeScreen] with the container already inside the bar layout. Everything here
     * is built once; `render()` updates in place afterwards, which is what keeps a press from
     * being destroyed underneath the finger by the 5s tick.
     */
    override fun buildContent(container: LinearLayout) {
        // --- 1. HERO: the one thing you need to know ---
        heroCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 14),
                Theme.dp(ctx, 16), Theme.dp(ctx, 14))
            background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        heroTitle = TextView(ctx).apply {
            textSize = 20f
            typeface = Theme.handFont(ctx)
            setTextColor(Theme.ON_SURFACE)
        }
        heroSub = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        }

        // The stamp mark, beside the status rather than above it.
        //
        // `WRAP_CONTENT` + `adjustViewBounds`, the same treatment the approval modal gives it:
        // the mark is roughly 2:1, so forcing it into a square box letterboxes it into a smear.
        // Height drives the size and the width follows.
        val mark = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.logo_mark)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "STMP"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Theme.dp(ctx, 44)
            ).apply { marginEnd = Theme.dp(ctx, 12) }
        }
        val heroText = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(heroTitle)
            addView(heroSub)
        }
        val heroRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(mark)
            addView(heroText, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
        }
        heroCard.addView(heroRow)
        container.addView(heroCard)

        // --- 2. CONTROLS: the primary action ---
        //
        // A row, not a full-width button: a switch sized to the whole screen would be a bar with a
        // knob at one end, and the label is what tells you what is being switched.
        val serveRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dp(ctx, 16), 0, 0)
        }
        val serveLabel = TextView(ctx).apply {
            text = "Server"
            textSize = 18f
            typeface = Theme.handFont(ctx)
            setTextColor(Theme.ON_SURFACE)
        }
        serveRow.addView(serveLabel, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        serverToggle = Theme.toggle(
            ctx,
            on = BridgeService.serverUp,
            pending = pendingSince > 0L,
        ) { want ->
            // Record the tap and repaint for the pending look, then act. The knob is NOT flipped
            // here: `render()` moves it once `BridgeService.serverUp` actually changes.
            pendingSince = android.os.SystemClock.elapsedRealtime()
            render()
            if (want) {
                Debug.log("server start requested")
                startBridge()
            } else {
                Debug.log("stop requested")
                stopBridge()
            }
            // The service binds asynchronously; a re-render after a beat picks up the result
            // without waiting for the 5s tick. The tick covers the case where it takes longer.
            uiTickHandler.postDelayed({ render() }, 600)
        }
        serveRow.addView(serverToggle)
        container.addView(serveRow)

        // **The account section moved to the Google plugin** (Mido, 2026-11-03: *"move the
        // account part to the google plugin"*). Signing in is a precondition the Google plugins
        // share, so it belongs with them: Plugins → Google account holds the email, the token
        // state and the sign-in button.

        // --- 3b. GATE SWITCH: the one control that decides whether anything is
        // asked of a human at all. Above the plugins, below Setup, because it is
        // the second question after "is it running?" — "is it still asking me?".
        container.addView(Theme.sectionHeader(ctx, "Approval gate"))
        gateCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 12),
                Theme.dp(ctx, 14), Theme.dp(ctx, 12))
            background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        gateTitle = TextView(ctx).apply {
            textSize = 17f
            typeface = Theme.handFont(ctx)
            setTextColor(Theme.ON_SURFACE)
        }
        gateSub = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        }
        gateButtons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Theme.dp(ctx, 10), 0, 0)
        }
        gateCard.addView(gateTitle)
        gateCard.addView(gateSub)
        gateCard.addView(gateButtons)

        // The fingerprint toggle lives HERE, beside the mode picker, not in Settings.
        //
        // They are one subject: the mode decides WHEN you are asked, this decides HOW you prove
        // it. Split across two screens, neither half can be reasoned about — you could read
        // "AUTOMATIC" and never learn that a SECURE request will ask for a fingerprint.
        val fpRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Theme.dp(ctx, 12), 0, 0)
        }
        gateFingerprint = Theme.button(ctx, "", Theme.ButtonStyle.NEUTRAL, small = true) {
            toggleFingerprintForModals()
        }
        fpRow.addView(gateFingerprint)
        gateFingerprintWhy = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(ctx, 6), 0, 0)
        }
        fpRow.addView(gateFingerprintWhy)
        gateCard.addView(fpRow)

        container.addView(gateCard)

        // The plugin list used to live here and made Home a wall: twelve status cards between
        // the server control and the tools, pushing "is the bridge running" off the first
        // screen. It moved to the Plugins tab, where capability inventory belongs.

        // Tools and Debug moved to Settings (Mido, 2026-11-03: *"rethink each button screen"*).
        // They are destinations and diagnostics, not live state — and REQUEST LOG / SSH were
        // sitting on Home purely because there was nowhere else to put them.

        // **The audit banner keeps its home here, and it must be CREATED here.**
        //
        // This is the row that crashed 0.43.90 on open: `render()` has always painted it, but
        // removing the Debug block took the construction with it and left the `lateinit` field
        // unset. Home is the launch screen, so every open threw
        // `UninitializedPropertyAccessException` before anything else could run.
        //
        // The lesson is narrower than "remember to clean up": a `lateinit` field is a promise the
        // compiler cannot check. `render()` and `buildContent` are far enough apart that nothing
        // links them, so deleting one side compiles cleanly and fails on the device.
        auditBanner = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Theme.ON_COLOR)
            background = Theme.surface(ctx, Theme.DANGER_SURFACE, Theme.RADIUS_CONTROL)
            setPadding(
                Theme.dp(ctx, 12), Theme.dp(ctx, 10),
                Theme.dp(ctx, 12), Theme.dp(ctx, 10)
            )
            visibility = TextView.GONE
        }
        container.addView(auditBanner)
    }


    /**
     * Start the bridge on app load if it is not already running.
     *
     * Mido, 2026-10-19: *"start server on app load automatically"*. The whole point of the
     * app is the server, so requiring a tap to reach the normal state is a step that only
     * ever gets in the way.
     *
     * Two rules keep it from becoming a trap:
     *  - **Once per process.** [autoStartAttempted] means a manual STOP stays stopped; a
     *    restart on every onResume would make STOP impossible to keep.
     *  - **Never over a running server.** `serverUp` covers the case where the service was
     *    already started (or survived), so this cannot double-start or disturb it.
     *
     * **And a third: the preference is honoured.** "Auto-start on boot" is ONE toggle meaning
     * "the server starts itself", so it gates this entry point too. A setting whose OFF state
     * still started the server every time the app opened would be a toggle that does not mean
     * what it says. The service still starts on an explicit tap — that is a human asking.
     */

    /**
     * The app lock: the load prompt IS the vault unlock (plan D9). One scan does both
     * jobs, because the prompt carries a CryptoObject bound to the vault key — so the
     * finger that says "you're you" also unwraps the data key.
     *
     * Fails closed: a declined/failed prompt leaves the vault locked. The app still
     * renders (the bridge is a separate process concern and keeps running), but every
     * secret operation will answer `secret_locked` until the human unlocks.
     */
 
    /**
     * The 0.14.x vault is dead (missing wrap IV). Offer a reset, once per process, and
     * be honest that the stored secrets cannot be recovered — a silent fresh vault would
     * look like success while quietly discarding a credential.
     */
 
    override fun onResume() {
        super.onResume()
        render()
        // Re-render while visible, so the hero cannot go stale under the human's eyes.
        //
        // **This is the other half of the 2026-10-25 bug.** `render()` used to run only on
        // `onResume` and two one-shot 600ms callbacks, so a server that died while the screen
        // was open kept its green label until the activity was left and re-entered. A green
        // label nobody re-checks is how "the app says running" and "callers get refused" were
        // both true at once.
        //
        // The tick reads a CACHED probe result (`isVerifiedAlive`), never a fresh connect — the
        // service's watchdog owns the probing, because a connect here would be network I/O on
        // the main thread. So this is cheap: a field read plus a re-render every few seconds.
        uiTickHandler.removeCallbacksAndMessages(null)
        uiTickHandler.postDelayed(uiTick, UI_TICK_MS)
        // `relaunchCurrent` is handled by BridgeFragment.onResume for every screen, so it is
        // deliberately NOT repeated here: one owner, or a new screen silently loses it.
    }

    override fun onPause() {
        super.onPause()
        // No ticking in the background: it would repaint a screen nobody is looking at, and a
        // resumed activity re-arms it immediately.
        uiTickHandler.removeCallbacksAndMessages(null)
    }

    /**
     * A cheap fingerprint of everything the checklist renders.
     *
     * Deliberately primitives and short strings only — no view construction, no disk, no network.
     * It is compared once every 5 seconds, so it must cost less than the repaint it is avoiding.
     *
     * `progress?.percent` is included because the model download must visibly advance; without it
     * a download would freeze at whatever value was on screen when the tick started skipping.
     */
    private fun renderSignature(): String = buildString(96) {
        append(BridgeService.serverUp).append('|')
        append(BridgeService.isVerifiedAlive()).append('|')
        append(BridgeService.lastError).append('|')
        append(BridgeService.bindAddress).append('|')
        append(BridgeService.listenerRef != null).append('|')
        append(hasNotifPermission()).append('|')
        // `ctx`, NOT `this`: inside buildString the implicit receiver is the
        // StringBuilder, so a bare `this` would be the wrong object entirely.
        append(runCatching { PolicyStore.from(requireContext()).get().fingerprintForModals }
            .getOrDefault(false)).append('|')
        append(ApprovalGate.canUseFullScreenIntent(ctx)).append('|')
        append(android.provider.Settings.canDrawOverlays(ctx)).append('|')
        append(com.mido.phonebridge.ui.SystemAccess.batteryExempt(ctx)).append('|')
        append(SecretVault.isUnlocked()).append('|')
        append(com.mido.phonebridge.ai.AiGateway.enabled(ctx)).append('|')
        append(com.mido.phonebridge.ai.ModelProvisioner.running).append('|')
        append(com.mido.phonebridge.ai.ModelProvisioner.progress?.percent ?: -1).append('|')
        append(com.mido.phonebridge.ai.ModelRegistry.readiness(ctx).ready).append('|')
        append(AuthManager.isLoggedIn(ctx)).append('|')
        append(AuthManager.accountEmail ?: "").append('|')
        append(AuditHealth.reason ?: "").append('|')
    }

    /**
     * Repaints on a timer while the activity is resumed. See [onResume].
     *
     * **Repaints only on a real change.** The unconditional `render()` here rebuilt every row
     * every 5 seconds, which destroyed the View under any press that outlasted the interval and
     * cancelled the click (see [lastRenderSignature] for the evidence). A tap on a checklist row
     * is a 1–2 second gesture, so this ate them routinely while the screen looked unchanged.
     *
     * The alternative — keeping the rebuild and shortening the interval — would make it worse.
     * Skipping unchanged repaints is the only version where a press can complete.
     */
    private val uiTick = object : Runnable {
        override fun run() {
            val signature = renderSignature()
            if (signature != lastRenderSignature) {
                lastRenderSignature = signature
                render()
            }
            uiTickHandler.postDelayed(this, UI_TICK_MS)
        }
    }

    // --- rendering ---

    override fun repaint() = render()

    private fun render() {
        // Baseline the tick's fingerprint. Every render() path — tap, permission result, service
        // callback — re-baselines here, so the 5-second tick cannot immediately undo a repaint
        // that was explicitly requested.
        lastRenderSignature = renderSignature()

        // --- 1. HERO: one line that answers "is it working?" ---
        val listenerOk = BridgeService.listenerRef != null
        val notifOk = hasNotifPermission()
        // A failed start is its own state, and it must be checked BEFORE the stopped state.
        // The precedence lives in `ServerStatus` (pure, unit-tested) rather than here: this
        // ordering used to be inline, so nothing could fail when it was wrong. The old order
        // checked `!serverUp` first, which made the reason a start failed unreachable — the
        // service stops itself when it cannot bind, setting `serverUp=false` while `lastError`
        // held the cause. Mido, 2026-10-20: *"on server fail user should see clear error"*.
        val state = ServerStatus.state(
            serverUp = BridgeService.serverUp,
            // The REAL check, not the flag. `serverUp` only records that start() once
            // succeeded; this records that a socket connected. See ServerStatus.UNRESPONSIVE.
            alive = BridgeService.isVerifiedAlive(),
            listenerOk = listenerOk,
            notifOk = notifOk,
            lastError = BridgeService.lastError,
        )
        val failed = state == ServerStatus.State.FAILED ||
            state == ServerStatus.State.UNRESPONSIVE

        // The state colour is applied ONCE, here. The old code set a background per
        // branch and then overwrote it two lines later, so the hero card was always
        // the neutral grey regardless of state.
        val heroSurface = when (state) {
            ServerStatus.State.RUNNING_HEALTHY -> Theme.SUCCESS_SURFACE
            ServerStatus.State.RUNNING_ATTENTION -> Theme.WARNING_SURFACE
            ServerStatus.State.UNRESPONSIVE -> Theme.DANGER_SURFACE
            ServerStatus.State.FAILED -> Theme.DANGER_SURFACE
            ServerStatus.State.STOPPED -> Theme.SURFACE
        }
        heroTitle.text = ServerStatus.title(state)
        heroTitle.setTextColor(
            when (state) {
                ServerStatus.State.RUNNING_HEALTHY -> Theme.SUCCESS
                ServerStatus.State.RUNNING_ATTENTION -> Theme.WARNING
                ServerStatus.State.UNRESPONSIVE -> Theme.DANGER
                ServerStatus.State.FAILED -> Theme.DANGER
                ServerStatus.State.STOPPED -> Theme.ON_SURFACE_VARIANT
            }
        )
        heroCard.background = Theme.surface(ctx, heroSurface, Theme.RADIUS_CARD)
        // No email here (Mido, 2026-11-03: *"move email from home, it belongs to the google
        // plugin"*). The hero answers "is the bridge running, and where" -- who is signed in is a
        // fact about the ACCOUNT, and it lives on the screen that can change it.
        heroSub.text = ServerStatus.detail(
            state = state,
            lastError = BridgeService.lastError,
            bindAddress = BridgeService.bindAddress,
            port = BridgeService.PORT,
        )
        // The reason is the one thing on this screen that must be read, so it gets the
        // danger colour rather than the muted body colour it used to share with log lines.
        heroSub.setTextColor(if (failed) Theme.DANGER else Theme.ON_SURFACE_VARIANT)

        // --- 3. CHECKLIST: compact, each row says what to do when broken ---
        // Start/Stop colour rule (user's standing rule): grey = idle/stopped,
        // green = running, and NEVER red for a normal stopped state. The button
        // is not green just because it is tappable — green means the server is up.
        // Tint, never `setBackgroundColor`: assigning a background drawable to a
        // MaterialButton replaces the ripple/state-list machinery. `backgroundTintList`
        // recolours the same component and keeps the feedback.
        // The knob renders the STORED state, never the tap. `pendingSince` only suppresses the
        // "settled" look for a moment so a slow bind does not flick the knob back and forth.
        val up = BridgeService.serverUp
        val stillPending = pendingSince > 0L &&
            android.os.SystemClock.elapsedRealtime() - pendingSince < PENDING_WINDOW_MS
        if (stillPending) {
            // The knob is left where the last real state put it; only the colour says "working".
            serverToggle.pending = true
        } else {
            pendingSince = 0L
            serverToggle.pending = false
            if (serverToggle.checked != up) serverToggle.setCheckedAnimated(up)
        }

        // The status checklist moved to Settings > Device access. Home now answers one question
        // — is the bridge running — and offers one control. Everything else is a setting.

        renderGate()

        // A gate you cannot audit must be visible without opening the log. This stays on Home
        // even though the log row moved to Settings: a state where every request is being
        // REFUSED is not a setting, it is the answer to "why is nothing working".
        val degraded = AuditHealth.reason
        if (degraded != null) {
            auditBanner.visibility = TextView.VISIBLE
            auditBanner.text = "⚠ AUDIT DEGRADED — requests are being refused\n$degraded"
        } else {
            auditBanner.visibility = TextView.GONE
        }
    }

    /**
     * The gate switch. `SECURE` is the resting state; `NOTIFY` is the quiet one.
     *
     * There is no auto-approve position (removed in 0.12.0), so this screen cannot
     * show a state where requests run without a human. The switch only chooses how
     * loudly they ask — never whether they ask at all.
     */
    private fun renderGate() {
        val current = try {
            PolicyStore.from(requireContext()).get().global
        } catch (e: PolicyException) {
            // An unreadable policy is not a quiet fallback to SECURE: say it here, and
            // BridgeService refuses to serve on the same condition. This screen must
            // never show a switch position the engine is not actually honouring.
            gateCard.background = Theme.surface(ctx, Theme.DANGER_SURFACE, Theme.RADIUS_CARD)
            gateTitle.text = "⚠ POLICY UNREADABLE"
            gateSub.text = e.message ?: "policy could not be read"
            gateTitle.setTextColor(Theme.DANGER)
            gateSub.setTextColor(Theme.ON_SURFACE_VARIANT)
            gateButtons.removeAllViews()
            // Hidden rather than left showing the last known position: an unreadable policy means
            // the app does not know what it is enforcing, so a switch position here would be a
            // claim it cannot support.
            gateFingerprint.visibility = TextView.GONE
            gateFingerprintWhy.visibility = TextView.GONE
            return
        }

        when (current) {
            // Only AUTOMATIC reads the rules. The other two force one mode on every
            // request, and the card says which so the home screen never shows a switch
            // position without showing what it implies.
            GlobalMode.AUTOMATIC -> {
                gateCard.background = Theme.surface(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "AUTOMATIC"
                gateSub.text = rulesSummary()
            }
            GlobalMode.SECURE -> {
                gateCard.background = Theme.surface(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "SECURE"
                gateSub.text = "Every request needs your stamp. Biometric required."
            }
            GlobalMode.NOTIFY -> {
                // Amber, not red: the gate still exists, it is just quiet and fast.
                // Calling it red would make the real panic switch less loud by
                // comparison, which is the failure that matters.
                gateCard.background = Theme.surface(ctx, Theme.WARNING_SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "⚠ NOTIFY"
                gateSub.text = "Small overlay toast. Silent 5s = approved. Tap ✕ to reject."
            }
        }
        gateTitle.setTextColor(Theme.ON_SURFACE)
        gateSub.setTextColor(Theme.ON_SURFACE_VARIANT)

        gateButtons.removeAllViews()
        for (m in GlobalMode.values()) {
            val on = m == current
            val b = Theme.button(ctx, m.name, if (on) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL, small = true) {
                requestGate(m)
            }
            gateButtons.addView(b, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = Theme.dp(ctx, 6) })
        }

        // The fingerprint control, rendered from the STORED value rather than from what we
        // assume: a write that silently failed must not leave the screen claiming otherwise.
        val policy = try {
            PolicyStore.from(requireContext()).get()
        } catch (e: PolicyException) {
            // Unreachable in practice — the early return above already handled it — but a
            // second read that throws must not crash a screen the user is looking at.
            gateFingerprint.visibility = TextView.GONE
            gateFingerprintWhy.visibility = TextView.GONE
            return
        }
        gateFingerprint.visibility = TextView.VISIBLE
        gateFingerprintWhy.visibility = TextView.VISIBLE
        val fp = policy.fingerprintForModals
        gateFingerprint.text = if (fp) "FINGERPRINT FOR MODALS: ON" else "FINGERPRINT FOR MODALS: OFF"
        gateFingerprint.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (fp) Theme.SUCCESS else Theme.SURFACE_VARIANT
        )
        gateFingerprint.setTextColor(if (fp) Theme.ON_COLOR else Theme.ON_SURFACE)
        gateFingerprintWhy.text = if (fp) {
            "A SECURE request needs your fingerprint to approve. A denied prompt leaves the " +
                "request pending so you can retry in its window."
        } else {
            "A SECURE request approves on a plain tap, like NOTIFY does. The secret vault is " +
                "unaffected — it keeps its own fingerprint."
        }
    }

    /**
     * Flip the fingerprint-for-modals preference.
     *
     * Audited as POLICY_CHANGED, exactly as the Settings screen used to do: a fingerprint
     * requirement DISAPPEARING is the kind of change a later reader wants dated.
     */
    private fun toggleFingerprintForModals() {
        val store = PolicyStore.from(requireContext())
        val current = try {
            store.get()
        } catch (e: PolicyException) {
            toast("policy unreadable: ${e.message}")
            return
        }
        val next = current.withFingerprintForModals(!current.fingerprintForModals)
        try {
            store.set(next)
        } catch (e: Exception) {
            toast("could not save: ${e.message}")
            return
        }
        try {
            AuditLog.from(requireContext()).append(
                com.mido.phonebridge.audit.PolicyAudit.record(next.global, next.global, "home")
            )
        } catch (e: Exception) {
            Debug.log("audit record failed for fingerprint toggle: ${e.message}")
        }
        render()
    }

    /**
     * Apply a switch change. `NOTIFY` gets one confirmation, because it is the only
     * value where doing nothing approves a request — the single place in the app where
     * silence means YES, so it gets one sentence saying so.
     */
    private fun requestGate(next: GlobalMode) {
        val store = PolicyStore.from(requireContext())
        val current = try {
            store.get().global
        } catch (e: PolicyException) {
            toast("policy unreadable: ${e.message}")
            return
        }
        if (next == current) return

        if (next == GlobalMode.NOTIFY) {
            // Not a red-alert confirmation: NOTIFY still asks, just quietly. But it is
            // the value where silence means YES, so it gets one sentence saying so.
            confirm(
                title = "Use overlay prompts?",
                message = "Every request shows a small toast for 5 seconds. Doing nothing " +
                    "approves it. Tap ✕ to reject.\n\nYour custom rules are ignored " +
                    "while this is selected.",
                positive = "Use NOTIFY",
                negative = "Cancel",
            ) { applyGate(store, current, next) }
        } else {
            applyGate(store, current, next)
        }
    }

    /** One line of truth about the rule table, for the AUTOMATIC card. */
    private fun rulesSummary(): String = try {
        val p = PolicyStore.from(requireContext()).get()
        val n = p.rules.size
        when (n) {
            0 -> "Per-pair rules decide. No rules yet, so everything waits for your stamp."
            1 -> "Per-pair rules decide. 1 rule set."
            else -> "Per-pair rules decide. $n rules set."
        }
    } catch (e: PolicyException) {
        "rules unreadable: ${e.message}"
    }

    /**
     * Start the model download and keep the home screen in step with it.
     *
     * The download is owned by [ModelProvisioner], not this Activity: it must survive a
     * rotation or a trip to another app, and the progress it reports is read back by
     * [render] so returning to the screen shows the live state rather than a stale one.
     */
    /**
     * Ask before re-fetching a model that is already present and verified.
     *
     * A 474 MB download is not something to start on a stray tap, and the model is
     * already working — so the default is to do nothing and the human has to mean it.
     */
 
 
    private fun applyGate(store: PolicyStore, before: GlobalMode, after: GlobalMode) {
        try {
            // Carry EVERYTHING across. `Policy(after)` alone would silently delete every
            // per-pair decision on a switch flip — you would turn NOTIFY back to AUTOMATIC and
            // find your rules gone, with nothing in the UI saying why. The same trap now applies
            // to the two preferences, so the wrap is explicit rather than positional: `Policy`
            // has four fields and a positional call is one edit away from dropping the wrong two.
            val existing = store.get()
            store.set(existing.withGlobal(after))
        } catch (e: Exception) {
            toast("could not save policy: ${e.message}")
            return
        }
        // Audit the change. A policy record that cannot be written is a failure the
        // home screen must show, not a change that quietly took effect unrecorded.
        try {
            AuditLog.from(requireContext()).append(PolicyAudit.record(before, after, "settings"))
        } catch (e: Exception) {
            AuditHealth.markDegraded("policy change not recorded: ${e.message}")
        }
        render()
    }

 
    /**
     * A compact destination chip for the "Tools" row. The four loose nav buttons used to
     * each be a full-width NEUTRAL button — visually identical to every other button, which
     * is what made the bottom of the home screen read as "labels mixed together". A tool is
     * a DESTINATION, not live state, so it lives on the Settings screen with the others
     * rather than a full-width row of its own.
     */
    /** One full-width row in the vertical tools list. */
 
    // --- actions ---

    private fun hasNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(ScreenHost.POST_NOTIFICATIONS)
    }

 
    /**
     * Doze and app standby suspend background work; Samsung adds app-sleep on top. This
     * exemption is what keeps the server reachable when the screen has been off for a
     * while. It is not a battery-saver bypass — it just says "do not freeze this app".
     */
 
    /**
     * The system dialog that grants the exemption. The direct request intent is the one
     * that actually shows a prompt; the settings list is the fallback for OEM builds that
     * do not honour it (the user then toggles the app manually).
     */
 
    private fun openNotifAccessSettings() {
        try {
            go(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Debug.log("opened notification-access settings")
        } catch (e: Exception) {
            Debug.log("cannot open notif settings: ${e.message}")
        }
    }

    /**
     * Ask for READ_CONTACTS.
     *
     * Separate from [requestNotifPermission] rather than a generic "request everything" call, and
     * deliberately NOT asked at launch: a contacts prompt on first open, with no visible reason,
     * is the kind of thing people deny reflexively — and a denial is sticky enough that the app
     * then needs Settings to recover. Tapping the row that says "Contacts search" is a request the
     * human understands.
     */
    /** Whether the contacts prompt has EVER been shown, so "not asked" is distinguishable. */
 
    /**
     * App details, where a permanently-denied permission can actually be re-granted.
     *
     * Not the "app permissions" deep link: that one varies by OEM and Samsung's S22 build lands
     * on a page without the contacts toggle in several firmware revisions. App details always
     * shows Permissions.
     */
 
    /**
     * The one place that explains the contacts permission, and the only launcher.
     *
     * [deniedJustNow] changes the copy from "here is what you need" to "that was declined" — the
     * outcome has to arrive somewhere the user is already looking, which is this dialog rather
     * than a Toast that disappears over the row it is describing.
     */
 
 
    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Debug.log("requesting POST_NOTIFICATIONS")
            askPermission(ScreenHost.POST_NOTIFICATIONS, REQ_POST_NOTIF)
        } else {
            Debug.log("POST_NOTIFICATIONS not needed on this API level")
        }
    }

    /**
     * **On the `DEPRECATION` suppression.** `Fragment.onRequestPermissionsResult` is deprecated
     * alongside `requestPermissions`, which [BridgeFragment] documents: the Activity Result API
     * needs a launcher registered at initialisation, and this screen asks at the moment of the
     * tap that needs the permission. The deprecated pair still work and still agree with each
     * other, so suppressing here keeps the ask and the result on the same mechanism rather than
     * half-migrating. Moving both to Activity Result is its own change.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Debug.log("POST_NOTIFICATIONS result: ${if (granted) "granted" else "DENIED"}")
            render()
        }
    }

 
    private fun startGoogleLogin() {
        if (BridgeService.GOOGLE_CLIENT_ID.isBlank()) {
            Debug.log("cannot login: GOOGLE_CLIENT_ID is empty (build not configured)")
            render()
            return
        }
        Debug.log("login tapped; client id len=${BridgeService.GOOGLE_CLIENT_ID.length}")
        go(Intent(ctx, GoogleLoginActivity::class.java))
    }

    override fun startBridge() {
        Debug.log("START SERVER tapped")
        // Clear the previous failure so a retry cannot display a stale reason: the service
        // re-sets this on the way out if it fails again, and a successful bind clears it.
        // Without this, tapping START after fixing the cause would still show the old error
        // for the ~1s between the tap and the bind.
        BridgeService.lastError = ""
        if (!hasNotifPermission()) {
            Debug.log("no POST_NOTIFICATIONS yet — requesting first")
            requestNotifPermission()
        }
        val i = Intent(ctx, BridgeService::class.java).apply {
            setClass(ctx, BridgeService::class.java)
        }
        try {
            // minSdk is 26, so startForegroundService is always available.
            ctx.startForegroundService(i)
            Debug.log("startForegroundService dispatched")
        } catch (e: Exception) {
            Debug.log("FAILED to dispatch service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Stop the service.
     *
     * The intent is built here rather than in the screen so the screen never names a concrete
     * Service class — the same reason [startBridge] wraps `startForegroundService`.
     */
    override fun stopBridge() {
        Debug.log("stop requested")
        ctx.stopService(Intent(ctx, BridgeService::class.java))
    }
}
