package com.mido.phonebridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val REQ_POST_NOTIF = 1001
    }

    private lateinit var logView: TextView
    private lateinit var statusContainer: LinearLayout
    private lateinit var serverButton: Button
    private lateinit var auditBanner: TextView
    private lateinit var gateCard: LinearLayout
    private lateinit var gateTitle: TextView
    private lateinit var gateSub: TextView
    private lateinit var gateButtons: LinearLayout
    private var logExpanded = false

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

        val pad = UiKit.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        fun header(t: String) = TextView(this).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xFF78909C.toInt())
            setPadding(0, UiKit.dp(this@MainActivity, 20), 0, UiKit.dp(this@MainActivity, 6))
        }

        // --- 1. HERO: the one thing you need to know ---
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 14),
                UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 14))
        }
        heroTitle = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiKit.TEXT_ON_DARK)
        }
        heroSub = TextView(this).apply {
            textSize = 14f
            setTextColor(UiKit.TEXT_ON_DARK)
            setPadding(0, UiKit.dp(this@MainActivity, 4), 0, 0)
        }
        heroCard.addView(heroTitle)
        heroCard.addView(heroSub)
        content.addView(heroCard)

        // --- 2. CONTROLS: the primary action ---
        serverButton = Button(this).apply {
            setOnClickListener {
                if (BridgeService.serverUp) {
                    Debug.log("stop requested")
                    stopService(Intent(this@MainActivity, BridgeService::class.java))
                } else {
                    startBridge()
                }
                statusContainer.postDelayed({ render() }, 600)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@MainActivity, 16) }
        }
        content.addView(serverButton)

        // --- 3. CHECKLIST: compact status rows, each actionable ---
        content.addView(header("Setup"))
        statusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(statusContainer)

        // --- accounts / plugins ---
        content.addView(header("Account"))
        accountBanner = TextView(this).apply { textSize = 15f }
        content.addView(accountBanner)
        loginButton = Button(this).apply {
            setOnClickListener { startGoogleLogin() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@MainActivity, 8) }
        }
        content.addView(loginButton)

        // --- 3b. GATE SWITCH: the one control that decides whether anything is
        // asked of a human at all. Above the plugins, below Setup, because it is
        // the second question after "is it running?" — "is it still asking me?".
        content.addView(header("Approval gate"))
        gateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 14), UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 14), UiKit.dp(this@MainActivity, 12))
        }
        gateTitle = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiKit.TEXT_ON_DARK)
        }
        gateSub = TextView(this).apply {
            textSize = 13f
            setTextColor(UiKit.TEXT_ON_DARK)
            setPadding(0, UiKit.dp(this@MainActivity, 4), 0, 0)
        }
        gateButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0)
        }
        gateCard.addView(gateTitle)
        gateCard.addView(gateSub)
        gateCard.addView(gateButtons)
        content.addView(gateCard)

        content.addView(header("Plugins"))
        val pluginContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(pluginContainer)
        this.pluginContainer = pluginContainer

        // --- 4. AUDIT LOG: one entry point, and the degraded banner above it ---
        content.addView(header("Request log"))
        auditBanner = TextView(this).apply {
            textSize = 14f
            setTextColor(UiKit.TEXT_ON_DARK)
            background = UiKit.pill(UiKit.RED_BG)
            setPadding(UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 10),
                UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 10))
            visibility = TextView.GONE
        }
        content.addView(auditBanner)
        content.addView(coloredButton("OPEN REQUEST LOG") {
            startActivity(Intent(this, LogActivity::class.java))
        }, UiKit.NEUTRAL_BG)
        content.addView(coloredButton("PER-PAIR RULES") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, UiKit.NEUTRAL_BG)

        // --- 5. Debug: collapsed by default, out of the main flow ---
        content.addView(header("Debug"))
        content.addView(coloredButton("▸ SHOW LOG") {
            logExpanded = !logExpanded
            logView.visibility = if (logExpanded) TextView.VISIBLE else TextView.GONE
            render()
        }, UiKit.NEUTRAL_BG)
        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(UiKit.TEXT_MUTED)
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
    }

    private lateinit var pluginContainer: LinearLayout
    private lateinit var loginButton: Button
    private lateinit var accountBanner: TextView
    private lateinit var heroCard: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView

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
        if (BridgeService.serverUp && healthy) {
            heroCard.background = UiKit.pill(UiKit.GREEN_BG)
            heroTitle.text = "● Running"
        } else if (BridgeService.serverUp) {
            heroCard.background = UiKit.pill(UiKit.AMBER_BG)
            heroTitle.text = "● Running — needs attention"
        } else {
            heroCard.background = UiKit.pill(UiKit.NEUTRAL_BG)
            heroTitle.text = "● Stopped"
        }
        heroCard.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(UiKit.NEUTRAL_BG)
            cornerRadius = UiKit.dp(this@MainActivity, 12).toFloat()
        }
        heroTitle.setTextColor(UiKit.TEXT_ON_DARK)
        heroSub.text = when {
            !BridgeService.serverUp -> "Tap START SERVER to begin"
            BridgeService.lastError.isNotBlank() -> BridgeService.lastError
            AuthManager.isLoggedIn(this) -> (AuthManager.accountEmail ?: "signed in") +
                " · " + BridgeService.bindAddress.ifBlank { "?" } + ":" + BridgeService.PORT
            else -> "Not signed in to Google"
        }
        heroSub.setTextColor(UiKit.TEXT_ON_DARK)

        // --- 3. CHECKLIST: compact, each row says what to do when broken ---
        // Start/Stop colour rule (user's standing rule): grey = idle/stopped,
        // green = running, and NEVER red for a normal stopped state. The button
        // is not green just because it is tappable — green means the server is up.
        serverButton.text = if (BridgeService.serverUp) "STOP SERVER" else "START SERVER"
        serverButton.background = UiKit.pill(if (BridgeService.serverUp) UiKit.RED_BG else UiKit.NEUTRAL_BG)
        serverButton.setTextColor(UiKit.TEXT_ON_DARK)
        statusContainer.removeAllViews()
        statusContainer.addView(
            UiKit.statusRow(
                this, "Server",
                if (BridgeService.serverUp) "running on ${BridgeService.bindAddress.ifBlank { "?" }}:${BridgeService.PORT}"
                else "stopped",
                if (BridgeService.serverUp) UiKit.State.OK else UiKit.State.OFF
            )
        )
        statusContainer.addView(
            UiKit.statusRow(
                this, "Notification access",
                if (listenerOk) "connected" else "tap to grant",
                if (listenerOk) UiKit.State.OK else UiKit.State.WARN,
                onClick = { openNotifAccessSettings() }
            )
        )
        statusContainer.addView(
            UiKit.statusRow(
                this, "Notifications permission",
                if (notifOk) "granted" else "tap to grant",
                if (notifOk) UiKit.State.OK else UiKit.State.WARN,
                onClick = { requestNotifPermission() }
            )
        )
        val fsiOk = ApprovalGate.canUseFullScreenIntent(this)
        statusContainer.addView(
            UiKit.statusRow(
                this, "Full-screen popup",
                if (fsiOk) "granted" else "tap to grant",
                if (fsiOk) UiKit.State.OK else UiKit.State.WARN,
                onClick = { openFullScreenIntentSettings() }
            )
        )
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        statusContainer.addView(
            UiKit.statusRow(
                this, "Draw over apps",
                if (overlayOk) "granted" else "tap to grant",
                if (overlayOk) UiKit.State.OK else UiKit.State.WARN,
                onClick = { openOverlaySettings() }
            )
        )

        // --- account ---
        if (AuthManager.isLoggedIn(this)) {
            val email = AuthManager.accountEmail ?: "(email unknown)"
            accountBanner.text = "👤 $email"
        } else {
            accountBanner.text = "👤 Not signed in"
        }
        accountBanner.setTextColor(if (AuthManager.isLoggedIn(this)) 0xFF81C784.toInt() else UiKit.TEXT_MUTED)

        loginButton.text = if (AuthManager.isLoggedIn(this)) "RE-LOGIN" else "LOGIN WITH GOOGLE"
        loginButton.background = UiKit.pill(if (AuthManager.isLoggedIn(this)) UiKit.NEUTRAL_BG else UiKit.GREEN_BG)
        loginButton.setTextColor(UiKit.TEXT_ON_DARK)

        renderGate()

        pluginContainer.removeAllViews()
        val plugins = PluginRegistry.all()
        if (plugins.isEmpty()) {
            pluginContainer.addView(coloredButton("no plugins registered (start the server)") {}, UiKit.NEUTRAL_BG)
        } else {
            for (p in plugins) {
                val st = p.status()
                val state = when (st.state) {
                    com.mido.phonebridge.core.PluginStatus.State.OK -> UiKit.State.OK
                    com.mido.phonebridge.core.PluginStatus.State.NEEDS_ATTENTION -> UiKit.State.WARN
                    com.mido.phonebridge.core.PluginStatus.State.ERROR -> UiKit.State.ERROR
                    com.mido.phonebridge.core.PluginStatus.State.OFF -> UiKit.State.OFF
                }
                pluginContainer.addView(UiKit.statusRow(this, st.title, st.detail.ifBlank { st.state.name }, state))
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
            gateCard.background = UiKit.pill(UiKit.RED_BG)
            gateTitle.text = "⚠ POLICY UNREADABLE"
            gateSub.text = e.message ?: "policy could not be read"
            gateTitle.setTextColor(UiKit.TEXT_ON_DARK)
            gateSub.setTextColor(UiKit.TEXT_ON_DARK)
            gateButtons.removeAllViews()
            return
        }

        when (current) {
            // Only AUTOMATIC reads the rules. The other two force one mode on every
            // request, and the card says which so the home screen never shows a switch
            // position without showing what it implies.
            GlobalMode.AUTOMATIC -> {
                gateCard.background = UiKit.pill(UiKit.NEUTRAL_BG)
                gateTitle.text = "AUTOMATIC"
                gateSub.text = rulesSummary()
            }
            GlobalMode.SECURE -> {
                gateCard.background = UiKit.pill(UiKit.NEUTRAL_BG)
                gateTitle.text = "SECURE"
                gateSub.text = "Every request asks you first. Biometric required."
            }
            GlobalMode.NOTIFY -> {
                // Amber, not red: the gate still exists, it is just quiet and fast.
                // Calling it red would make the real panic switch less loud by
                // comparison, which is the failure that matters.
                gateCard.background = UiKit.pill(UiKit.AMBER_BG)
                gateTitle.text = "⚠ NOTIFY"
                gateSub.text = "Small overlay toast. Silent 5s = approved. Tap ✕ to reject."
            }
        }
        gateTitle.setTextColor(UiKit.TEXT_ON_DARK)
        gateSub.setTextColor(UiKit.TEXT_ON_DARK)

        gateButtons.removeAllViews()
        for (m in GlobalMode.values()) {
            val on = m == current
            val b = Button(this).apply {
                text = m.name
                textSize = 12f
                background = UiKit.pill(if (on) UiKit.GREEN_BG else UiKit.NEUTRAL_BG)
                setTextColor(UiKit.TEXT_ON_DARK)
                setOnClickListener { requestGate(m) }
            }
            gateButtons.addView(b, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = UiKit.dp(this@MainActivity, 6) })
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

    private fun coloredButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            background = UiKit.pill(UiKit.NEUTRAL_BG)
            setTextColor(UiKit.TEXT_ON_DARK)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(this@MainActivity, 6) }
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
