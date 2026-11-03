package com.mido.phonebridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mido.phonebridge.Debug

/**
 * The system accesses the bridge depends on, and the screen that grants each.
 *
 * **Why this is one object.** These four checks lived in `MainActivity` as private methods, while
 * `PluginDetailActivity` had its own copy of the intent-building for the same accesses and
 * `PluginSettings.SpecialAccess` named them a third time. Three places that must agree about how
 * to reach one Settings screen is three places to get an intent wrong, and a wrong intent lands
 * the user somewhere unrelated with no error to show.
 *
 * Every function here is either a pure state read or a `startActivity` — nothing holds state, so
 * any screen can call it without owning anything.
 */
object SystemAccess {

    /** Draw over other apps: what lets the overlay card and the modal appear. */
    fun overlayGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun openOverlaySettings(ctx: Context) {
        launch(ctx, Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        ), "draw-over-apps")
    }

    /**
     * Doze and app standby suspend background work; Samsung adds app-sleep on top. The exemption
     * is what keeps the server reachable after the screen has been off for a while.
     */
    fun batteryExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * The direct request intent is the one that shows a prompt. The settings list is the fallback
     * for OEM builds that ignore it, where the user has to toggle the app by hand.
     */
    fun requestBatteryExemption(ctx: Context) {
        try {
            ctx.startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Debug.log("requested battery-optimization exemption")
        } catch (e: Exception) {
            Debug.log("cannot request exemption: ${e.message} — opening settings list")
            launch(
                ctx,
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                "battery-optimization list",
            )
        }
    }

    /**
     * Full-screen intent: what wakes a LOCKED screen for an approval.
     *
     * Only API 34+ can revoke it, so on older versions the access is always present and there is
     * nothing to open.
     */
    fun openFullScreenIntentSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ctx.startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Debug.log("opened full-screen-intent settings")
                return
            } catch (e: Exception) {
                Debug.log("cannot open FSI settings: ${e.message}")
            }
        }
        // App notification settings is the nearest thing on an older build, and it is where the
        // toggle lives on the OEM skins that expose it early.
        launch(ctx, Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
            putExtra("android.provider.extra.APP_PACKAGE", ctx.packageName)
        }, "app notification settings")
    }

    /**
     * One launch path, so every failure is reported the same way.
     *
     * A Settings button that silently does nothing is worse than one that says it could not open
     * the screen — the user would conclude the access is granted and wonder why the app still
     * fails.
     */
    private fun launch(ctx: Context, intent: Intent, what: String) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Debug.log("opened $what settings")
        } catch (e: Exception) {
            Debug.log("cannot open $what settings: ${e.message}")
        }
    }
}
