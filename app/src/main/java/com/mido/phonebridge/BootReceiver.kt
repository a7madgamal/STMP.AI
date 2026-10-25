package com.mido.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore

/**
 * Start the bridge after a reboot or an app update, if the user wants that.
 *
 * **This is the fix for a real outage, not a nicety.** Before this receiver existed the app had
 * no `<receiver>` in its manifest at all, and `MainActivity.startBridge()` was the only
 * `startForegroundService` call site in the codebase. So the bridge came up when the app was
 * opened and never otherwise. `START_STICKY` keeps the SERVICE alive through a system process
 * kill, but it cannot survive a reboot: after one, nothing ever asked the service to run. On
 * 2026-10-25 the phone rebooted at ~05:40 and the bridge stayed down until it was noticed hours
 * later — with the app still painting a green "Running" label.
 *
 * **`MY_PACKAGE_REPLACED` is half the value.** Sideloading a new build kills the process, so
 * without it every update leaves the bridge down until someone opens the app. It costs one more
 * action string in the same filter.
 *
 * **Two actions, not three.** `LOCKED_BOOT_COMPLETED` is deliberately absent: it only reaches
 * `directBootAware` components, and the policy file lives in credential-encrypted storage that is
 * unreadable before first unlock. Firing there would mean starting the server with a policy we
 * could not read — which the service correctly refuses — so it would buy a retry budget spent
 * entirely before the phone is usable.
 *
 * **The preference is read here, not in the service.** "Auto-start on boot" is about THIS
 * trigger; the service should still start when a human taps the button. An unreadable policy is
 * logged and the start is skipped: the service would refuse anyway, and doing it here keeps the
 * refusal out of a foreground-service start that cannot succeed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val enabled = try {
            PolicyStore.from(context.applicationContext).get().autoStartOnBoot
        } catch (e: PolicyException) {
            // Not a silent fallback to "on": the file that decides it is unreadable, and the
            // server would refuse to start on the same file. Say so and stop.
            Debug.log("boot start skipped: policy unreadable (${e.message})")
            return
        }
        if (!enabled) {
            Debug.log("boot start skipped: auto-start is OFF ($action)")
            return
        }

        Debug.log("boot start: dispatching BridgeService ($action)")
        try {
            // minSdk is 26, so startForegroundService is always available.
            context.startForegroundService(Intent(context, BridgeService::class.java))
        } catch (e: Exception) {
            // Android 12+ can refuse a background FGS start. `specialUse` is permitted from a
            // boot receiver on Android 15 (only dataSync/camera/mediaPlayback/phoneCall/
            // mediaProjection/microphone are banned), so this should not fire — but a refusal
            // must be recorded rather than swallowed, or the next outage has no evidence.
            Debug.log("boot start FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
