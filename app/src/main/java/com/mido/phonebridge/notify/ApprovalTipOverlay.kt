package com.mido.phonebridge.notify

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ui.ApprovalTip
import com.mido.phonebridge.ui.ApprovalTipView

/**
 * The resting approval tip, as a SYSTEM OVERLAY rather than an Activity.
 *
 * **Why this had to stop being an Activity.** An Activity, even a translucent one, pauses whatever
 * is behind it — so a 195dp strip still froze the whole screen for input. Reported from the device
 * as "the whole point of transparency is interacting with what's behind, it's not happening".
 * That is not a bug that can be fixed inside an Activity: `FLAG_NOT_TOUCH_MODAL` decides where
 * *this window's* touches go, but the Activity underneath is paused no matter what.
 *
 * A `TYPE_APPLICATION_OVERLAY` window has no such contract. It is drawn by the system on top of
 * whatever is running, the app below keeps running, and `NOT_TOUCH_MODAL` lets every touch outside
 * the strip reach it. The permission is already held — [NotifyOverlay] has used it for the small
 * card since before this feature existed, and its comment states the same trade.
 *
 * **What this class does NOT do.** It never resolves a request, never runs a clock and never owns
 * a decision. It shows the queue and, when tapped, asks the gate to open the sheet. All decision
 * semantics stay in `ApprovalGate` and `ApprovalActivity`.
 */
object ApprovalTipOverlay {

    private const val TAG = "ApprovalTip"

    /** Breathing room around the paper inside its own window, in dp. */
    private const val WINDOW_MARGIN_DP = 14f

    /** The youngest the tip window may be to still read as a sheet, as a share of the display. */
    private const val WINDOW_WIDTH_FRACTION = 0.34f

    private var view: ApprovalTipView? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())

    /** Whether a tip window is currently added. */
    @Volatile
    var isShowing: Boolean = false
        private set

    private fun wm(ctx: Context): WindowManager =
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Show the tip, or update the stack if it is already up.
     *
     * Safe to call from any thread and on every gate tick: adding a window twice would throw, and
     * a tip that threw once would silently stop appearing for the rest of the process.
     */
    fun show(ctx: Context, depth: Int) {
        main.post {
            try {
                val existing = view
                if (existing != null) {
                    // A queued request still ANNOUNCES itself. Both branches below reach
                    // [announce], and it is reached exactly once per request — the whole reason
                    // the sound moved here: a request that arrived while a window was already
                    // open used to be silent.
                    existing.depth = depth.coerceIn(1, ApprovalTip.MAX_VISIBLE)
                    announce(ctx)
                    return@post
                }
                addWindow(ctx, depth)
                announce(ctx)
            } catch (e: Exception) {
                Log.e(TAG, "tip overlay could not be shown: ${e.message}", e)
            }
        }
    }

    /**
     * Haptic plus the paper-crease sound for a request that has just become visible.
     *
     * **Once per request, where the request is SEEN.** The gate used to fire this as it launched
     * the sheet, so only the head of a queue made a noise: every later request arrived on a branch
     * that returned early because a window was already up. Reported as "each queued request should
     * make a sound, not just the first".
     *
     * Best-effort by design: a device with no audio must still show the prompt, so a failure here
     * is logged and never propagates.
     */
    private fun announce(ctx: Context) {
        try {
            com.mido.phonebridge.alert.SoundAlerts.secureVibration(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed: ${e.message}")
        }
        try {
            com.mido.phonebridge.alert.SoundAlerts.modalAppear(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "arrival sound failed: ${e.message}", e)
        }
    }

    private fun addWindow(ctx: Context, depth: Int) {
        if (!com.mido.phonebridge.ui.SystemAccess.overlayGranted(ctx)) {
            // No overlay permission: there is no honest way to draw a tip that the app below keeps
            // running under, so refuse rather than pretend. The gate's own prompt-surface check
            // already covers the "nothing can be shown" case for the modal.
            Log.w(TAG, "no overlay permission; the tip cannot be shown")
            return
        }
        val dm = ctx.resources.displayMetrics
        val metrics = ApprovalTip.metrics(dm.widthPixels, dm.density)
        val density = dm.density

        val tip = ApprovalTipView(ctx).apply {
            screenWidthPx = dm.widthPixels
            this.depth = depth.coerceIn(1, ApprovalTip.MAX_VISIBLE)
            onOpen = {
                // Ask the gate, not this view: the window that can show the sheet is the Activity,
                // and this overlay must never be a second owner of the request.
                hide()
                ApprovalGate.relaunchCurrent(ctx)
            }
        }

        // **The window is the PAPER, plus a small margin — not the paper plus a long reach.**
        //
        // A window eats every touch that lands inside it, and `NOT_TOUCH_MODAL` only passes what
        // falls outside. So each dp of window width is a dp of the screen the human cannot use
        // while a request waits. The paper is already a generous target; a wide margin just takes
        // screen away.
        // **Wide enough for the sheet to be read, and no wider.**
        //
        // The paper is fitted to this window, so the window is the real size control: a paper-plus-
        // margin window produced a sheet small enough to be useless. A share of the display is the
        // honest measure of "big enough to see", and it is still far less than the screen — the app
        // behind stays usable everywhere outside this strip.
        val byFraction = (dm.widthPixels * WINDOW_WIDTH_FRACTION).toInt()
        val byMargin = (metrics.topWidthPx + WINDOW_MARGIN_DP * density).toInt()
        val width = maxOf(byFraction, byMargin).coerceAtMost(dm.widthPixels)
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: never takes the keyboard.
            // NOT_TOUCH_MODAL: every touch outside the strip goes to the app below. This is the
            // whole reason the tip is a window and not an Activity.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = 0
            y = 0
        }

        try {
            wm(ctx).addView(tip, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}", e)
            return
        }
        view = tip
        params = lp
        isShowing = true
        tip.present()
    }

    /** Remove the tip window. Idempotent; safe to call when nothing is shown. */
    fun hide() {
        main.post {
            val v = view ?: return@post
            view = null
            params = null
            isShowing = false
            try {
                wm(v.context).removeView(v)
            } catch (e: Exception) {
                // "View not attached" means the platform already dropped it — the outcome we
                // wanted. Anything else is reported, because it means the tip is still on screen.
                if (e !is IllegalArgumentException) {
                    Log.e(TAG, "removeView failed: ${e.message}", e)
                }
            }
        }
    }

    /** The live view, for tests and for the queue-depth updates. */
    fun currentView(): View? = view
}
