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

    /** How wide the tip is, in centimetres — the size it should read as on screen. */
    private const val CENTIMETRES_WIDE = 2f

    /**
     * Window height as a multiple of its width.
     *
     * A triangle that is twice as tall as it is wide reads as a spike; one a little taller than
     * wide reads as a corner of paper. The taper's angle is what the eye judges, not its length.
     */
    private const val TRIANGLE_HEIGHT_RATIO = 1.35f

    /**
     * The tallest the tip window may be, as a share of the display.
     *
     * A hard ceiling, not a preference: from Android 12 an untrusted overlay that covers the
     * display has every touch beneath it blocked. Staying under this keeps the window a sheet.
     */
    private const val MAX_WINDOW_HEIGHT_FRACTION = 0.8f

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
                // **The tip stays until the card has actually painted.**
                //
                // Hiding it on the tap leaves the screen empty for however long the Activity takes
                // to launch and draw — on a debug build that is a visible gap, and the two motions
                // then read as two separate events instead of one slide. The tip is removed from
                // the card's side, on its first drawn frame; see `ApprovalActivity.onFirstFrame`.
                //
                // The tap still asks the gate, because the window that can show the sheet is the
                // Activity and this overlay must never be a second owner of the request.
                if (!ApprovalGate.relaunchCurrent(ctx)) {
                    // Nothing launched: keep the tip rather than leave the human with an empty
                    // edge and no idea what happened.
                    Log.w(TAG, "the sheet did not launch; keeping the tip")
                }
            }
        }

        // **The window is the SIZE OF THE PAPER, not a column.**
        //
        // This is the fix for touches, and it is a platform rule rather than a preference: from
        // Android 12, a tap that would reach another app THROUGH an untrusted overlay is blocked
        // unless the overlay's combined opacity is <= 0.8. A `MATCH_PARENT`-height window counts as
        // a large, mostly-transparent surface over the screen, so the system refused every touch
        // beneath it — reported three times as "touches are blocked even on the transparent parts".
        // `FLAG_NOT_TOUCH_MODAL` cannot help: it decides where THIS window's touches go, not
        // whether the system trusts them.
        //
        // Sizing the window to the paper means the untrusted surface only covers pixels the human
        // can actually see, which is precisely the case the rule permits.
        // **The window's right edge IS the screen's right edge.** The sheet overshoots past the
        // window on purpose: the part the human sees is the part that slid in, and the part beyond
        // the display is simply not there. Anchoring the window to the edge is what makes the cut
        // land on the screen edge rather than in mid-air.
        // **Two centimetres, measured in dp.** 1 inch = 2.54cm = 160dp, so a centimetre is about
        // 63dp. Asking for "2cm from the right side" is a physical size, not a share of the screen,
        // and treating it as a share is what produced a sheet that covered half the display.
        val width = (CENTIMETRES_WIDE * 160f / 2.54f * density).toInt()
            .coerceAtMost(dm.widthPixels)
        // **A TAB near the top, not a column down the side.** A quarter of the display, so what
        // shows is the corner of a sheet that slid in — the rest of the sheet is past the edge, and
        // the paper inside is cut by it.
        //
        // Still capped below full height: a window that spans the display is exactly the case
        // Android 12 refuses to pass touches through.
        // A TRIANGLE wider than it is tall, so the taper reads as a corner rather than a spike.
        val heightCap = (dm.heightPixels * MAX_WINDOW_HEIGHT_FRACTION).toInt()
        val height = (width * TRIANGLE_HEIGHT_RATIO).toInt().coerceAtMost(heightCap)
        val lp = WindowManager.LayoutParams(
            width,
            height,
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
            // Explicitly zero: a tip must never dim the app the human is using, and the default
            // for a non-dialog window is not guaranteed to be 0 across versions.
            dimAmount = 0f
            x = 0
            // At the very top: the VIEW applies its own offset for the paper. Moving the window too
            // would offset the sheet twice and clip it at the bottom.
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

    /**
     * Hide the tip because the CARD is now on screen.
     *
     * Called from the card's first drawn frame, not from the tap: that ordering is what makes the
     * slide continuous. Hiding on tap leaves an empty screen for the launch latency, and the eye
     * reads two motions where there should be one.
     */
    fun hideForCard() = hide()

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
