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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {

    companion object {
        const val REQ_POST_NOTIF = 1001
    }

    private lateinit var logView: TextView
    private lateinit var statusContainer: LinearLayout
    private lateinit var serverButton: MaterialButton
    private lateinit var auditBanner: TextView
    private lateinit var gateCard: LinearLayout
    private lateinit var gateTitle: TextView
    private lateinit var gateSub: TextView
    private lateinit var gateButtons: LinearLayout
    private lateinit var secretsButton: MaterialButton
    private lateinit var pluginContainer: LinearLayout
    private lateinit var loginButton: MaterialButton
    private lateinit var accountBanner: TextView
    private lateinit var heroCard: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private var logExpanded = false

    /**
     * Guards the load prompt so returning to the app (onResume) never re-prompts on
     * its own. One prompt per process — which is exactly the session.
     */
    private var promptAttempted = false

    /**
     * Guards auto-start so it runs once per process.
     *
     * Without this, a deliberate STOP would be undone by the next onResume — you would
     * stop the server, switch apps, come back, and find it running again with no way to
     * keep it down. Auto-start is a convenience on launch, not a policy that overrides
     * the human (Mido, 2026-10-19: *"start server on app load automatically"*).
     */
    private var autoStartAttempted = false

    /** Guards the unrecoverable-vault reset dialog so it does not stack. */
    private var resetOffered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load the OAuth config + account email from disk BEFORE anything else.
        // This is what makes an update-without-wipe keep working: the config and
        // identity no longer depend on BridgeService having run since boot.
        com.mido.phonebridge.core.AuthManager.init(
            applicationContext,
            BridgeService.GOOGLE_CLIENT_ID,
            BridgeService.GOOGLE_SERVER_CLIENT_ID,
            BridgeService.GOOGLE_CLIENT_SECRET
        )
        Debug.log("config loaded; clientId len=${com.mido.phonebridge.core.AuthManager.clientId.length}")

        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Theme.BG)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        // --- 1. HERO: the one thing you need to know ---
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(this@MainActivity, 16), Theme.dp(this@MainActivity, 14),
                Theme.dp(this@MainActivity, 16), Theme.dp(this@MainActivity, 14))
            background = Theme.surface(this@MainActivity, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        heroTitle = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.ON_SURFACE)
        }
        heroSub = TextView(this).apply {
            textSize = 14f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(this@MainActivity, 4), 0, 0)
        }
        heroCard.addView(heroTitle)
        heroCard.addView(heroSub)
        content.addView(heroCard)

        // --- 2. CONTROLS: the primary action ---
        serverButton = Theme.button(this, "START SERVER", Theme.ButtonStyle.SUCCESS) {
            if (BridgeService.serverUp) {
                Debug.log("stop requested")
                stopService(Intent(this@MainActivity, BridgeService::class.java))
            } else {
                startBridge()
            }
            statusContainer.postDelayed({ render() }, 600)
        }
        serverButton.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Theme.dp(this@MainActivity, 16) }
        content.addView(serverButton)

        // --- 3. CHECKLIST: compact status rows, each actionable ---
        content.addView(Theme.sectionHeader(this, "Setup"))
        statusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(statusContainer)

        // --- accounts / plugins ---
        content.addView(Theme.sectionHeader(this, "Account"))
        accountBanner = TextView(this).apply {
            textSize = 15f
            setTextColor(Theme.ON_SURFACE)
        }
        content.addView(accountBanner)
        loginButton = Theme.button(this, "LOGIN WITH GOOGLE", Theme.ButtonStyle.PRIMARY) { startGoogleLogin() }
        loginButton.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Theme.dp(this@MainActivity, 8) }
        content.addView(loginButton)

        secretsButton = Theme.button(this, "SECRETS & HOSTS", Theme.ButtonStyle.NEUTRAL) {
            startActivity(Intent(this@MainActivity, SecretsActivity::class.java))
        }
        secretsButton.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Theme.dp(this@MainActivity, 8) }
        content.addView(secretsButton)

        // --- 3b. GATE SWITCH: the one control that decides whether anything is
        // asked of a human at all. Above the plugins, below Setup, because it is
        // the second question after "is it running?" — "is it still asking me?".
        content.addView(Theme.sectionHeader(this, "Approval gate"))
        gateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(this@MainActivity, 14), Theme.dp(this@MainActivity, 12),
                Theme.dp(this@MainActivity, 14), Theme.dp(this@MainActivity, 12))
            background = Theme.surface(this@MainActivity, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        gateTitle = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.ON_SURFACE)
        }
        gateSub = TextView(this).apply {
            textSize = 13f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(this@MainActivity, 4), 0, 0)
        }
        gateButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Theme.dp(this@MainActivity, 10), 0, 0)
        }
        gateCard.addView(gateTitle)
        gateCard.addView(gateSub)
        gateCard.addView(gateButtons)
        content.addView(gateCard)

        content.addView(Theme.sectionHeader(this, "Plugins"))
        pluginContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(pluginContainer)

        // --- 4. AUDIT LOG: one entry point, and the degraded banner above it ---
        content.addView(Theme.sectionHeader(this, "Request log"))
        auditBanner = TextView(this).apply {
            textSize = 14f
            setTextColor(Theme.ON_COLOR)
            background = Theme.surface(this@MainActivity, Theme.DANGER_SURFACE, Theme.RADIUS_CONTROL)
            setPadding(Theme.dp(this@MainActivity, 12), Theme.dp(this@MainActivity, 10),
                Theme.dp(this@MainActivity, 12), Theme.dp(this@MainActivity, 10))
            visibility = TextView.GONE
        }
        content.addView(auditBanner)
        content.addView(fullWidthButton("OPEN REQUEST LOG", Theme.ButtonStyle.NEUTRAL) {
            startActivity(Intent(this, LogActivity::class.java))
        })
        content.addView(fullWidthButton("PER-PAIR RULES", Theme.ButtonStyle.NEUTRAL) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        // --- 5. Debug: collapsed by default, out of the main flow ---
        content.addView(Theme.sectionHeader(this, "Debug"))
        content.addView(fullWidthButton("SHOW LOG", Theme.ButtonStyle.GHOST) {
            logExpanded = !logExpanded
            logView.visibility = if (logExpanded) TextView.VISIBLE else TextView.GONE
            render()
        })
        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            visibility = TextView.GONE
        }
        content.addView(logView)

        root.addView(scroll)
        setContentView(root)

        Debug.init(applicationContext)
        Debug.log("=== PhoneBridge ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) ===")
        Debug.log("app launched")

        // Open the audit log now, not on first request: a store that cannot be
        // created is a problem worth surfacing before any request needs recording
        // it. Idempotent, so the service opening it later is a no-op.
        try {
            val store = AuditLog.from(applicationContext)
            Debug.log("audit log ready; ${store.headers().size} record(s) indexed")
        } catch (e: Exception) {
            AuditHealth.markDegraded("audit store unavailable: ${e.message}")
            Debug.log("AUDIT STORE FAILED: ${e.message}")
        }
        render()
        lockOnLoad()
        maybeAutoStartServer()
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
     */
    private fun maybeAutoStartServer() {
        if (autoStartAttempted) return
        autoStartAttempted = true
        if (BridgeService.serverUp) {
            Debug.log("auto-start skipped: server already up")
            return
        }
        Debug.log("auto-start: bringing the server up on app load")
        startBridge()
        statusContainer.postDelayed({ render() }, 600)
    }

    /**
     * The app lock: the load prompt IS the vault unlock (plan D9). One scan does both
     * jobs, because the prompt carries a CryptoObject bound to the vault key — so the
     * finger that says "you're you" also unwraps the data key.
     *
     * Fails closed: a declined/failed prompt leaves the vault locked. The app still
     * renders (the bridge is a separate process concern and keeps running), but every
     * secret operation will answer `secret_locked` until the human unlocks.
     */
    private fun lockOnLoad(force: Boolean = false) {
        if (SecretVault.isUnlocked()) return
        if (promptAttempted && !force) return
        promptAttempted = true
        if (SecretVault.isUnrecoverable(this)) {
            // A vault from 0.14.x: the data key was wrapped without storing the IV, and
            // AES-GCM cannot be unwrapped without it. The secrets inside are gone. Say
            // so plainly and offer the only honest way forward — a reset — instead of
            // throwing a cipher error at every unlock attempt.
            offerVaultReset()
            return
        }
        if (VaultGate.hasVault(this)) {
            VaultGate.unlock(
                this,
                onUnlocked = { Debug.log("vault unlocked (session open)"); render() },
                onFailed = { Debug.log("vault unlock not completed: $it"); render() }
            )
        } else {
            // First run: the same prompt authorises creating the vault.
            VaultGate.initialize(
                this,
                onReady = { Debug.log("vault created (session open)"); render() },
                onFailed = { Debug.log("vault creation not completed: $it"); render() }
            )
        }
    }

    /**
     * The 0.14.x vault is dead (missing wrap IV). Offer a reset, once per process, and
     * be honest that the stored secrets cannot be recovered — a silent fresh vault would
     * look like success while quietly discarding a credential.
     */
    private fun offerVaultReset() {
        if (resetOffered) return
        resetOffered = true
        AlertDialog.Builder(this)
            .setTitle("Secret vault needs a reset")
            .setMessage(
                "This vault was created by an earlier build that saved the key without " +
                    "the IV it needs to be reopened. The secrets inside it cannot be " +
                    "recovered — this is a bug in that build, not something you did.\n\n" +
                    "Resetting clears the vault so you can create a fresh one and re-add " +
                    "your secrets. Nothing else on the phone is affected."
            )
            .setPositiveButton("reset vault") { _, _ ->
                SecretVault.destroy(this)
                Debug.log("vault reset (0.14.x unrecoverable wrap)")
                promptAttempted = false
                lockOnLoad()
                render()
            }
            .setNegativeButton("not now", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        render()
        // If a request is still waiting, put its modal back on screen. The approval
        // lives in its OWN recents-hidden task, so returning to the app (launcher
        // icon, recents) lands on MainActivity — which knows nothing about the
        // pending request. Without this the modal stays stranded off-screen until
        // the window expires. No-op when nothing is pending (the common case).
        ApprovalGate.relaunchCurrent(this)
    }

    // --- rendering ---

    private fun render() {
        // --- 1. HERO: one line that answers "is it working?" ---
        val listenerOk = BridgeService.listenerRef != null
        val notifOk = hasNotifPermission()
        val healthy = BridgeService.serverUp && listenerOk && notifOk
        // The state colour is applied ONCE, here. The old code set a background per
        // branch and then overwrote it two lines later, so the hero card was always
        // the neutral grey regardless of state.
        val heroSurface: Int
        when {
            BridgeService.serverUp && healthy -> {
                heroSurface = Theme.SUCCESS_SURFACE
                heroTitle.text = "● Running"
                heroTitle.setTextColor(Theme.SUCCESS)
            }
            BridgeService.serverUp -> {
                heroSurface = Theme.WARNING_SURFACE
                heroTitle.text = "● Running — needs attention"
                heroTitle.setTextColor(Theme.WARNING)
            }
            else -> {
                heroSurface = Theme.SURFACE
                heroTitle.text = "● Stopped"
                heroTitle.setTextColor(Theme.ON_SURFACE_VARIANT)
            }
        }
        heroCard.background = Theme.surface(this, heroSurface, Theme.RADIUS_CARD)
        heroSub.text = when {
            !BridgeService.serverUp -> "Tap START SERVER to begin"
            BridgeService.lastError.isNotBlank() -> BridgeService.lastError
            AuthManager.isLoggedIn(this) -> (AuthManager.accountEmail ?: "signed in") +
                " · " + BridgeService.bindAddress.ifBlank { "?" } + ":" + BridgeService.PORT
            else -> "Not signed in to Google"
        }
        heroSub.setTextColor(Theme.ON_SURFACE_VARIANT)

        // --- 3. CHECKLIST: compact, each row says what to do when broken ---
        // Start/Stop colour rule (user's standing rule): grey = idle/stopped,
        // green = running, and NEVER red for a normal stopped state. The button
        // is not green just because it is tappable — green means the server is up.
        // Tint, never `setBackgroundColor`: assigning a background drawable to a
        // MaterialButton replaces the ripple/state-list machinery. `backgroundTintList`
        // recolours the same component and keeps the feedback.
        if (BridgeService.serverUp) {
            serverButton.text = "STOP SERVER"
            serverButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Theme.SURFACE_VARIANT)
            serverButton.setTextColor(Theme.ON_SURFACE)
        } else {
            serverButton.text = "START SERVER"
            serverButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Theme.SUCCESS)
            serverButton.setTextColor(Theme.ON_COLOR)
        }

        statusContainer.removeAllViews()
        statusContainer.addView(
            Theme.statusRow(
                this, "Server",
                if (BridgeService.serverUp) "running on ${BridgeService.bindAddress.ifBlank { "?" }}:${BridgeService.PORT}"
                else "stopped",
                if (BridgeService.serverUp) Theme.SUCCESS else Theme.ON_SURFACE_VARIANT
            )
        )
        statusContainer.addView(
            Theme.statusRow(
                this, "Notification access",
                if (listenerOk) "connected" else "tap to grant",
                if (listenerOk) Theme.SUCCESS else Theme.WARNING,
                onClick = { openNotifAccessSettings() }
            )
        )
        statusContainer.addView(
            Theme.statusRow(
                this, "Notifications permission",
                if (notifOk) "granted" else "tap to grant",
                if (notifOk) Theme.SUCCESS else Theme.WARNING,
                onClick = { requestNotifPermission() }
            )
        )
        val fsiOk = ApprovalGate.canUseFullScreenIntent(this)
        statusContainer.addView(
            Theme.statusRow(
                this, "Full-screen popup",
                if (fsiOk) "granted" else "tap to grant",
                if (fsiOk) Theme.SUCCESS else Theme.WARNING,
                onClick = { openFullScreenIntentSettings() }
            )
        )
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        statusContainer.addView(
            Theme.statusRow(
                this, "Draw over apps",
                if (overlayOk) "granted" else "tap to grant",
                if (overlayOk) Theme.SUCCESS else Theme.WARNING,
                onClick = { openOverlaySettings() }
            )
        )
        val batteryOk = isIgnoringBatteryOptimizations()
        statusContainer.addView(
            Theme.statusRow(
                this, "Battery optimization",
                if (batteryOk) "exempt" else "tap to allow background",
                if (batteryOk) Theme.SUCCESS else Theme.WARNING,
                onClick = { requestIgnoreBatteryOptimizations() }
            )
        )
        // The vault: locked by default on every new process; one scan opens it for the
        // session. Shown here because a locked vault is the reason a secret call would
        // answer `secret_locked`, and that must be visible before it happens.
        val vaultOk = SecretVault.isUnlocked()
        statusContainer.addView(
            Theme.statusRow(
                this, "Secret vault",
                if (vaultOk) "unlocked (this session)" else "locked — tap to unlock",
                if (vaultOk) Theme.SUCCESS else Theme.WARNING,
                onClick = { lockOnLoad(force = true) }
            )
        )

        // The AI model. CORE functionality (Mido, 2026-10-19), so this row is not a
        // nicety: until it is ready the bridge refuses every request with 503.
        //
        // Rendered as a BUTTON, not a status row, because here the action and its feedback
        // are the same thing: you press it to fetch, and the thing you pressed fills with
        // progress (Mido, 2026-10-19). A ready model is a plain row again, so the control
        // only exists while there is something to do.
        val modelState = com.mido.phonebridge.ai.ModelRegistry.readiness(this)
        val downloading = com.mido.phonebridge.ai.ModelProvisioner.running
        val progress = com.mido.phonebridge.ai.ModelProvisioner.progress
        if (downloading) {
            val pct = progress?.percent ?: 0
            statusContainer.addView(
                Theme.progressButton(
                    this,
                    "AI MODEL — DOWNLOADING ${pct}%",
                    pct
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        } else {
            statusContainer.addView(
                Theme.statusRow(
                    this, "AI model",
                    if (modelState.ready) "${modelState.detail} — tap to re-download"
                    else "tap to download — ${modelState.detail}",
                    if (modelState.ready) Theme.SUCCESS else Theme.WARNING,
                    // A ready model CONFIRMS before re-fetching 474 MB. Tapping a green row
                    // used to silently start the download again (Mido, 2026-10-19: *"the
                    // model downloaded, I touched it it started downloading again"*), and
                    // the provisioner's idempotence alone only made the tap a no-op — this
                    // makes the intent explicit instead of guessing it.
                    onClick = {
                        if (modelState.ready) confirmRedownload() else startModelDownload()
                    }
                )
            )
        }

        // --- account ---
        if (AuthManager.isLoggedIn(this)) {
            val email = AuthManager.accountEmail ?: "(email unknown)"
            accountBanner.text = "👤 $email"
            accountBanner.setTextColor(Theme.SUCCESS)
        } else {
            accountBanner.text = "👤 Not signed in"
            accountBanner.setTextColor(Theme.ON_SURFACE_VARIANT)
        }

        loginButton.text = if (AuthManager.isLoggedIn(this)) "RE-LOGIN" else "LOGIN WITH GOOGLE"
        loginButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (AuthManager.isLoggedIn(this)) Theme.SURFACE_VARIANT else Theme.ACCENT
        )
        loginButton.setTextColor(if (AuthManager.isLoggedIn(this)) Theme.ON_SURFACE else Theme.ON_COLOR)

        renderGate()

        pluginContainer.removeAllViews()
        val plugins = PluginRegistry.all()
        if (plugins.isEmpty()) {
            pluginContainer.addView(Theme.text(this, "no plugins registered (start the server)", 13f, Theme.ON_SURFACE_VARIANT))
        } else {
            for (p in plugins) {
                val st = p.status()
                val color = when (st.state) {
                    com.mido.phonebridge.core.PluginStatus.State.OK -> Theme.SUCCESS
                    com.mido.phonebridge.core.PluginStatus.State.NEEDS_ATTENTION -> Theme.WARNING
                    com.mido.phonebridge.core.PluginStatus.State.ERROR -> Theme.DANGER
                    com.mido.phonebridge.core.PluginStatus.State.OFF -> Theme.ON_SURFACE_VARIANT
                }
                pluginContainer.addView(Theme.statusRow(this, st.title, st.detail.ifBlank { st.state.name }, color))
            }
        }

        // A gate you cannot audit must be visible without opening the log: this
        // banner is the only surface that says so on the home screen.
        val degraded = AuditHealth.reason
        if (degraded != null) {
            auditBanner.visibility = TextView.VISIBLE
            auditBanner.text = "⚠ AUDIT DEGRADED — requests are being refused\n$degraded"
        } else {
            auditBanner.visibility = TextView.GONE
        }

        logView.text = Debug.dump()
        logView.visibility = if (logExpanded) TextView.VISIBLE else TextView.GONE
        (logView.parent as? ScrollView)?.post { (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN) }
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
            PolicyStore.from(applicationContext).get().global
        } catch (e: PolicyException) {
            // An unreadable policy is not a quiet fallback to SECURE: say it here, and
            // BridgeService refuses to serve on the same condition. This screen must
            // never show a switch position the engine is not actually honouring.
            gateCard.background = Theme.surface(this, Theme.DANGER_SURFACE, Theme.RADIUS_CARD)
            gateTitle.text = "⚠ POLICY UNREADABLE"
            gateSub.text = e.message ?: "policy could not be read"
            gateTitle.setTextColor(Theme.DANGER)
            gateSub.setTextColor(Theme.ON_SURFACE_VARIANT)
            gateButtons.removeAllViews()
            return
        }

        when (current) {
            // Only AUTOMATIC reads the rules. The other two force one mode on every
            // request, and the card says which so the home screen never shows a switch
            // position without showing what it implies.
            GlobalMode.AUTOMATIC -> {
                gateCard.background = Theme.surface(this, Theme.SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "AUTOMATIC"
                gateSub.text = rulesSummary()
            }
            GlobalMode.SECURE -> {
                gateCard.background = Theme.surface(this, Theme.SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "SECURE"
                gateSub.text = "Every request asks you first. Biometric required."
            }
            GlobalMode.NOTIFY -> {
                // Amber, not red: the gate still exists, it is just quiet and fast.
                // Calling it red would make the real panic switch less loud by
                // comparison, which is the failure that matters.
                gateCard.background = Theme.surface(this, Theme.WARNING_SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "⚠ NOTIFY"
                gateSub.text = "Small overlay toast. Silent 5s = approved. Tap ✕ to reject."
            }
        }
        gateTitle.setTextColor(Theme.ON_SURFACE)
        gateSub.setTextColor(Theme.ON_SURFACE_VARIANT)

        gateButtons.removeAllViews()
        for (m in GlobalMode.values()) {
            val on = m == current
            val b = Theme.button(this, m.name, if (on) Theme.ButtonStyle.PRIMARY else Theme.ButtonStyle.NEUTRAL, small = true) {
                requestGate(m)
            }
            gateButtons.addView(b, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = Theme.dp(this@MainActivity, 6) })
        }
    }

    /**
     * Apply a switch change. `NOTIFY` gets one confirmation, because it is the only
     * value where doing nothing approves a request — the single place in the app where
     * silence means YES, so it gets one sentence saying so.
     */
    private fun requestGate(next: GlobalMode) {
        val store = PolicyStore.from(applicationContext)
        val current = try {
            store.get().global
        } catch (e: PolicyException) {
            Toast.makeText(this, "policy unreadable: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (next == current) return

        if (next == GlobalMode.NOTIFY) {
            // Not a red-alert confirmation: NOTIFY still asks, just quietly. But it is
            // the value where silence means YES, so it gets one sentence saying so.
            AlertDialog.Builder(this)
                .setTitle("Use overlay prompts?")
                .setMessage(
                    "Every request shows a small toast for 5 seconds. Doing nothing " +
                        "approves it. Tap ✕ to reject.\n\nYour custom rules are ignored " +
                        "while this is selected."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use NOTIFY") { _, _ -> applyGate(store, current, next) }
                .show()
        } else {
            applyGate(store, current, next)
        }
    }

    /** One line of truth about the rule table, for the AUTOMATIC card. */
    private fun rulesSummary(): String = try {
        val p = PolicyStore.from(applicationContext).get()
        val n = p.rules.size
        when (n) {
            0 -> "Per-pair rules decide. No rules set, so every request asks you."
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
    private fun confirmRedownload() {
        AlertDialog.Builder(this)
            .setTitle("Re-download the AI model?")
            .setMessage(
                "The model is already downloaded and verified. Re-downloading fetches " +
                    "~474 MB again and replaces the working copy."
            )
            .setNegativeButton("Keep it") { _, _ -> Debug.log("re-download declined") }
            .setPositiveButton("Re-download") { _, _ -> startModelDownload(force = true) }
            .show()
    }

    private fun startModelDownload(force: Boolean = false) {
        Debug.log("model download requested (force=$force)")
        // No toast up front. The provisioner is idempotent now, so "Downloading…" would
        // be a LIE whenever the model is already present and the tap is a no-op (Mido hit
        // exactly that on device: *"tabbing download again still shows the message"*).
        // The button itself is the feedback, and the result toast below reports what
        // actually happened.
        render()
        Thread({
            val result = com.mido.phonebridge.ai.ModelProvisioner.provision(
                applicationContext,
                force = force,
                onProgress = { runOnUiThread { render() } }
            )
            runOnUiThread {
                Debug.log("model provision: ${result.detail}")
                Toast.makeText(this, result.detail, Toast.LENGTH_LONG).show()
                render()
            }
        }, "model-download").apply { isDaemon = true }.start()
    }

    private fun applyGate(store: PolicyStore, before: GlobalMode, after: GlobalMode) {
        try {
            // Carry the rules across. `Policy(after)` alone would silently delete every
            // per-pair decision on a switch flip — you would turn NOTIFY back to
            // AUTOMATIC and find your rules gone, with nothing in the UI saying why.
            val existing = store.get()
            store.set(Policy(after, existing.rules))
        } catch (e: Exception) {
            Toast.makeText(this, "could not save policy: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        // Audit the change. A policy record that cannot be written is a failure the
        // home screen must show, not a change that quietly took effect unrecorded.
        try {
            AuditLog.from(applicationContext).append(PolicyAudit.record(before, after, "settings"))
        } catch (e: Exception) {
            AuditHealth.markDegraded("policy change not recorded: ${e.message}")
        }
        render()
    }

    private fun fullWidthButton(
        label: String,
        style: Theme.ButtonStyle,
        onClick: () -> Unit
    ): MaterialButton = Theme.button(this, label, style, onClick = onClick).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Theme.dp(this@MainActivity, 6) }
    }

    // --- actions ---

    private fun hasNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            Debug.log("opened draw-over-apps settings")
        } catch (e: Exception) {
            Debug.log("cannot open overlay settings: ${e.message}")
        }
    }

    /**
     * Doze and app standby suspend background work; Samsung adds app-sleep on top. This
     * exemption is what keeps the server reachable when the screen has been off for a
     * while. It is not a battery-saver bypass — it just says "do not freeze this app".
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * The system dialog that grants the exemption. The direct request intent is the one
     * that actually shows a prompt; the settings list is the fallback for OEM builds that
     * do not honour it (the user then toggles the app manually).
     */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            Debug.log("requested battery-optimization exemption")
        } catch (e: Exception) {
            Debug.log("cannot request exemption: ${e.message} — opening settings list")
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                Debug.log("opened battery-optimization settings list")
            } catch (e2: Exception) {
                Debug.log("cannot open battery settings: ${e2.message}")
            }
        }
    }

    private fun openNotifAccessSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Debug.log("opened notification-access settings")
        } catch (e: Exception) {
            Debug.log("cannot open notif settings: ${e.message}")
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Debug.log("requesting POST_NOTIFICATIONS")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
        } else {
            Debug.log("POST_NOTIFICATIONS not needed on this API level")
        }
    }

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

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startActivity(
                    Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                )
                Debug.log("opened full-screen-intent settings")
            } catch (e: Exception) {
                Debug.log("cannot open FSI settings: ${e.message}")
                try {
                    startActivity(
                        Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                            putExtra("android.provider.extra.APP_PACKAGE", packageName)
                        }
                    )
                } catch (e2: Exception) {
                    Debug.log("fallback settings failed: ${e2.message}")
                }
            }
        } else {
            Debug.log("FSI permission not required below Android 14")
        }
    }

    private fun startGoogleLogin() {
        if (BridgeService.GOOGLE_CLIENT_ID.isBlank()) {
            Debug.log("cannot login: GOOGLE_CLIENT_ID is empty (build not configured)")
            render()
            return
        }
        Debug.log("login tapped; client id len=${BridgeService.GOOGLE_CLIENT_ID.length}")
        startActivity(Intent(this, GoogleLoginActivity::class.java))
    }

    private fun startBridge() {
        Debug.log("START SERVER tapped")
        if (!hasNotifPermission()) {
            Debug.log("no POST_NOTIFICATIONS yet — requesting first")
            requestNotifPermission()
        }
        val i = Intent(this, BridgeService::class.java).apply {
            setClass(this@MainActivity, BridgeService::class.java)
        }
        try {
            // minSdk is 26, so startForegroundService is always available.
            startForegroundService(i)
            Debug.log("startForegroundService dispatched")
        } catch (e: Exception) {
            Debug.log("FAILED to dispatch service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/**
 * Tiny ring-buffer logger rendered on-screen so we can debug without adb.
 */
object Debug {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Redaction kill-switch, for debugging only. When false, raw values reach
     * the on-screen log and logcat. Flip back to true before any real use.
     */
    @Volatile
    var redactEnabled: Boolean = true

    fun init(ctx: Context) { /* no-op placeholder for future file logging */ }

    @Synchronized
    fun log(msg: String) {
        val safe = if (redactEnabled) redact(msg) else msg
        val line = "${fmt.format(Date())}  $safe"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("PhoneBridge", safe)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n").ifBlank { "(no log yet)" }

    /**
     * S13: scrub anything token/secret-shaped before it reaches logcat or the
     * on-screen buffer. Deliberately blunt — a false positive costs a little
     * readability, a false negative leaks a credential.
     */
    private fun redact(s: String): String {
        var out = s
        out = out.replace(Regex("ya29\\.[A-Za-z0-9._\\-]+"), "ya29.[REDACTED]")
        out = out.replace(Regex("1//[A-Za-z0-9._\\-]{10,}"), "1//[REDACTED]")
        out = out.replace(Regex("GOCSPX-[A-Za-z0-9._\\-]+"), "GOCSPX-[REDACTED]")
        out = out.replace(Regex("4/0[A-Za-z0-9._\\-]{10,}"), "4/0[REDACTED]")
        out = out.replace(
            Regex("(?i)(refresh_token|access_token|client_secret|server_auth_code|code)\\s*[=:]\\s*[A-Za-z0-9._/\\-]{8,}"),
            "$1=[REDACTED]"
        )
        out = out.replace(
            Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{10,}"),
            "Bearer [REDACTED]"
        )
        return out
    }
}
