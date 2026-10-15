package com.mido.phonebridge

import android.Manifest
import android.app.Activity
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
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginRegistry
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
    private var logExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            text = t
            textSize = 20f
            setTextColor(UiKit.TEXT_ON_DARK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, UiKit.dp(this@MainActivity, 12), 0, UiKit.dp(this@MainActivity, 4))
        }
        fun body(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(UiKit.TEXT_MUTED)
            setPadding(0, 0, 0, UiKit.dp(this@MainActivity, 8))
        }

        content.addView(TextView(this).apply {
            text = "PhoneBridge"
            textSize = 26f
            setTextColor(UiKit.TEXT_ON_DARK)
            typeface = Typeface.DEFAULT_BOLD
        })

        // --- server control ---
        content.addView(header("Server"))
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
        }
        content.addView(serverButton)

        // --- status ---
        content.addView(header("Status"))
        statusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(statusContainer)

        // --- permissions / actions ---
        content.addView(header("Permissions"))
        content.addView(coloredButton("GRANT NOTIFICATION PERMISSION") {
            requestNotifPermission()
        }, UiKit.NEUTRAL_BG)
        content.addView(coloredButton("GRANT FULL-SCREEN POPUP") {
            openFullScreenIntentSettings()
        }, UiKit.NEUTRAL_BG)
        content.addView(coloredButton("GRANT DRAW-OVER-APPS") {
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
        }, UiKit.NEUTRAL_BG)
        content.addView(coloredButton("OPEN NOTIFICATION ACCESS SETTINGS") {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Exception) {
                Debug.log("cannot open notif settings: ${e.message}")
            }
        }, UiKit.NEUTRAL_BG)

        // --- plugins ---
        content.addView(header("Plugins"))
        content.addView(coloredButton("LOGIN WITH GOOGLE") { startGoogleLogin() }, UiKit.NEUTRAL_BG)
        val pluginContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(pluginContainer)
        this.pluginContainer = pluginContainer

        // --- debug log (collapsed by default) ---
        content.addView(coloredButton("SHOW DEBUG LOG") {
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
        Debug.log("app launched")
        render()
    }

    private lateinit var pluginContainer: LinearLayout

    override fun onResume() {
        super.onResume()
        render()
    }

    // --- rendering ---

    private fun render() {
        if (BridgeService.serverUp) {
            serverButton.text = "STOP SERVER"
            serverButton.background = UiKit.pill(UiKit.RED_BG)
        } else {
            serverButton.text = "START SERVER"
            serverButton.background = UiKit.pill(UiKit.GREEN_BG)
        }
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
        if (BridgeService.lastError.isNotBlank()) {
            statusContainer.addView(UiKit.statusRow(this, "Error", BridgeService.lastError, UiKit.State.ERROR))
        }
        statusContainer.addView(
            UiKit.statusRow(
                this, "Notification listener",
                if (BridgeService.listenerRef != null) "connected" else "not connected",
                if (BridgeService.listenerRef != null) UiKit.State.OK else UiKit.State.WARN
            )
        )
        statusContainer.addView(
            UiKit.statusRow(
                this, "Notifications permission",
                if (hasNotifPermission()) "granted" else "missing",
                if (hasNotifPermission()) UiKit.State.OK else UiKit.State.WARN
            )
        )
        statusContainer.addView(
            UiKit.statusRow(
                this, "Full-screen popup",
                if (ApprovalGate.canUseFullScreenIntent(this)) "granted" else "not granted",
                if (ApprovalGate.canUseFullScreenIntent(this)) UiKit.State.OK else UiKit.State.WARN
            )
        )
        statusContainer.addView(
            UiKit.statusRow(
                this, "Draw over apps",
                if (android.provider.Settings.canDrawOverlays(this)) "granted" else "not granted",
                if (android.provider.Settings.canDrawOverlays(this)) UiKit.State.OK else UiKit.State.WARN
            )
        )

        // plugin cards
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

        logView.text = Debug.dump()
        (logView.parent as? ScrollView)?.post { (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN) }
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

    fun init(ctx: Context) { /* no-op placeholder for future file logging */ }

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("PhoneBridge", msg)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n").ifBlank { "(no log yet)" }
}
