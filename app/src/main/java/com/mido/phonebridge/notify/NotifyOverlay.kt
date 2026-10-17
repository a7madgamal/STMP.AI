package com.mido.phonebridge.notify

import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.notify.NotifyQueue.Admission
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The `NOTIFY` prompt: a small overlay card drawn with the `SYSTEM_ALERT_WINDOW`
 * permission this app already holds. Deliberately NOT a notification — a heads-up
 * notification is a system-owned surface that hijacks the shade, collides with DND,
 * and is suppressed while the phone is in use, which is when a request is most likely
 * to arrive.
 *
 * **One card at a time, in arrival order.** The first version drew every in-flight
 * prompt at once at one screen region, and Mido's report was exact: "3 at once I saw
 * only one timer". Three windows, three bars, one thing a human could actually read.
 * So the panel now dequeues: the card on screen owns the only countdown, and the next
 * appears when it goes. Ordering and capacity live in [NotifyQueue], which is pure and
 * tested; this file owns pixels only.
 *
 * Two consequences that are the point of the change:
 *
 *  - **A waiting request has no clock.** Its window starts when it reaches the screen,
 *    so "silence means approve" always refers to a full window the human could see.
 *  - **Nothing is silently dropped.** If the line is full the NEWCOMER is refused
 *    immediately and denied — the stacking version discarded the oldest item, denying a
 *    request the human had been shown and might have been about to answer.
 *
 * The card sits **bottom-left**, is **draggable**, and remembers where it was put, so
 * the human can shove it out of the way of whatever they are doing.
 *
 * Every outcome is reported exactly once, on the main thread. The reasons stay distinct
 * because the audit distinguishes them: a tap is a decision, an expiry is the absence
 * of one, and a card that could not be drawn is neither.
 */
object NotifyOverlay {

    private const val TAG = "NotifyOverlay"

    /** Cards allowed to wait in line. Plus the one on screen, this bounds the line to 4. */
    private const val MAX_WAITING = 3

    /**
     * Prompts that can be in the system at once: the one on screen plus the line.
     *
     * Public because the gate sizes its deadlock escape hatch from it — the worst
     * legitimate wait is exactly this many windows. Hardcoding that number in two
     * places is how a raised queue length silently starts denying requests that are
     * merely waiting their turn.
     */
    const val MAX_IN_FLIGHT = MAX_WAITING + 1

    private val main = Handler(Looper.getMainLooper())

    private val queue = NotifyQueue<Entry>(keyOf = { it.id }, maxWaiting = MAX_WAITING)

    /** Why a prompt ended. Mapped to audit outcomes by the caller, not here. */
    enum class Reason {
        /** The ✕ was tapped. */
        TAPPED,
        /** The window elapsed in silence with someone present. */
        EXPIRED,
        /** Nobody could have read it: screen off or locked. */
        UNATTENDED,
        /** It could not be drawn at all. */
        UNAVAILABLE,
        /** The line was full, so it was refused rather than shown late. */
        REFUSED
    }

    /** What actually happened to one prompt. */
    class Outcome(
        val reason: Reason,
        /** When the card actually became visible, or 0 if it never did. */
        val shownAt: Long,
        val decidedAt: Long,
        val detail: String = ""
    )

    private class Entry(
        val ctx: Context,
        val id: Long,
        val summary: String,
        val label: String,
        val windowMs: Long,
        val onOutcome: (Outcome) -> Unit
    ) {
        /** True once a result has been reported. Guards the tap-versus-timer race. */
        var settled = false

        /** Stamped when the card is attached, never when [show] was called. */
        var shownAt = 0L

        var root: View? = null
        var timeout: Runnable? = null
        var drain: View? = null
    }

    /**
     * Where the card sits, remembered across cards so a drag sticks: y is measured UP
     * from the bottom edge (the card is bottom-anchored). [Int.MIN_VALUE] means "not
     * placed yet" — the first prompt lands at the default corner.
     */
    private var anchorX = Int.MIN_VALUE
    private var anchorY = Int.MIN_VALUE

    /**
     * Offer one prompt. [onOutcome] runs once, on the main thread; safe to call from any
     * thread, and a server thread is normally blocked while this is on screen.
     */
    fun show(
        ctx: Context,
        id: Long,
        summary: String,
        label: String,
        windowMs: Long,
        onOutcome: (Outcome) -> Unit
    ) {
        main.post {
            val entry = Entry(ctx.applicationContext, id, summary, label, windowMs, onOutcome)
            when (val admission = queue.admit(entry)) {
                is Admission.Now -> present(entry)
                // Invisible until its turn. Its clock does NOT run yet; that is the
                // whole reason the queue exists.
                is Admission.Waiting -> Unit
                is Admission.Refused -> reply(
                    entry, Reason.REFUSED,
                    "line full: ${admission.ahead} prompt(s) ahead; refused without showing"
                )
            }
        }
    }

    /** Build and attach one card. Called for the head of the line only. */
    private fun present(entry: Entry) {
        val ctx = entry.ctx
        val wm = wm(ctx)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val radius = dp(CORNER_DP).toFloat()

        // FrameLayout so the countdown can be the card's BACKGROUND, behind the text,
        // rather than a separate strip the eye has to find.
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = radius
            }
            // Rounded clipping, so the draining fill follows the card's corners instead
            // of squaring them off.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            // The card owns its own rectangle: that is what makes dragging possible.
            // See the touch listener below for the trade that costs.
            isClickable = true
        }

        // The countdown: a low-alpha accent that drains left to right. `scaleX` with
        // pivotX 0 avoids a relayout per frame, so it cannot stutter away from the
        // timer it represents.
        val drain = View(ctx).apply {
            setBackgroundColor(ACCENT_FILL)
            pivotX = 0f
            scaleX = 1f
        }
        card.addView(drain, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(6), dp(8))
        }

        // Named `summaryView`, not `text`: a local called `text` shadows the TextView
        // `text` property inside the `apply` blocks below and the assignment silently
        // targets the local instead.
        val summaryView = TextView(ctx).apply {
            text = entry.summary
            textSize = 11f
            setTextColor(TEXT)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL))
        }
        row.addView(summaryView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // A 30dp touch box so the target is real without looking like a button — a
        // visible button here would pull the eye off the text.
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "Reject: ${entry.label}"
        }
        row.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))

        card.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val size = screenSize(wm)
        if (anchorX == Int.MIN_VALUE) {
            anchorX = dp(EDGE_DP)
            anchorY = dp(EDGE_DP)   // measured up from the bottom edge
        }

        val params = WindowManager.LayoutParams(
            dp(CARD_WIDTH_DP),
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: never takes focus or the keyboard.
            // NOT_TOUCH_MODAL: touches outside the card still reach the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = anchorX
            y = anchorY
        }

        // --- drag ---------------------------------------------------------------
        // Deliberate trade: the touch listener means the card consumes touches inside
        // its own rectangle, so it is opaque to input for its lifetime. That is the
        // price of "allow dragging"; NOT_FOCUSABLE still keeps the keyboard and focus
        // away, and outside the card every tap falls through to the app below.
        val slop = dp(DRAG_SLOP_DP)
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        card.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    originX = params.x
                    originY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val minEdge = dp(EDGE_DP)
                        val maxX = (size.first - dp(CARD_WIDTH_DP) - minEdge).coerceAtLeast(minEdge)
                        val maxY = (size.second - card.height - minEdge).coerceAtLeast(minEdge)
                        // y is measured UP from the bottom edge, so dragging up ADDS.
                        anchorX = (originX + dx).roundToInt().coerceIn(minEdge, maxX)
                        anchorY = (originY - dy).roundToInt().coerceIn(minEdge, maxY)
                        params.x = anchorX
                        params.y = anchorY
                        runCatching { wm.updateViewLayout(card, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // A click that never moved is not a drag: leave the anchor alone so
                    // a stray tap cannot nudge the card.
                    if (dragging) {
                        anchorX = params.x
                        anchorY = params.y
                    }
                    true
                }

                else -> false
            }
        }

        close.setOnClickListener { settle(entry, Reason.TAPPED) }

        try {
            wm.addView(card, params)
        } catch (e: Exception) {
            // Permission revoked, or the window service refused. Report it and let the
            // caller deny: "we failed to ask" is not consent. Retire ourselves first so
            // the next in line is not blocked behind a card that never existed.
            Log.w(TAG, "overlay refused for id=${entry.id}: ${e.message}")
            queue.retire(entry.id)
            reply(entry, Reason.UNAVAILABLE, e.message ?: "overlay unavailable")
            promoteNext()
            return
        }

        entry.root = card
        entry.drain = drain
        // Stamped on attach, not on entry into show(): `main.post` may run late behind
        // other UI work, and a `shownAt` taken earlier would inflate every recorded
        // duration by exactly that delay.
        entry.shownAt = System.currentTimeMillis()

        // Fade in with a small rise from below. Long enough to register as "something
        // appeared", short enough not to eat a meaningful part of the window.
        card.alpha = 0f
        card.translationY = dp(8).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(FADE_IN_MS).start()

        // The bar and the clock end at the same instant, so what the human sees and
        // what the gate decides cannot disagree. Linear on purpose: a countdown that
        // eases is a countdown that lies.
        drain.animate()
            .scaleX(0f)
            .setDuration(entry.windowMs)
            .setInterpolator(LinearInterpolator())
            .start()

        val timeout = Runnable {
            if (!entry.settled) {
                // Re-checked at APPROVE time, not just at post time: a phone that went
                // into a pocket mid-window must deny rather than approve.
                settle(
                    entry,
                    if (com.mido.phonebridge.policy.isAttended(entry.ctx)) Reason.EXPIRED
                    else Reason.UNATTENDED
                )
            }
        }
        entry.timeout = timeout
        main.postDelayed(timeout, entry.windowMs)
    }

    /**
     * End a card silently's counterpart: report an outcome for something that was never
     * on screen. Used for refusals and for a card that could not be attached.
     */
    private fun reply(entry: Entry, reason: Reason, detail: String) {
        if (entry.settled) return
        entry.settled = true
        entry.timeout?.let(main::removeCallbacks)
        entry.onOutcome(Outcome(reason, 0L, System.currentTimeMillis(), detail))
    }

    /**
     * End a card that WAS on screen: report the outcome, retire it from the queue, fade
     * it out, then give the next in line the screen and a fresh clock.
     */
    private fun settle(entry: Entry, reason: Reason, detail: String = "") {
        if (entry.settled) return
        entry.settled = true
        entry.timeout?.let(main::removeCallbacks)
        entry.onOutcome(Outcome(reason, entry.shownAt, System.currentTimeMillis(), detail))

        // Retired now, so ordering is correct even while the fade below is running.
        queue.retire(entry.id)

        val card = entry.root
        if (card == null) {
            promoteNext()
            return
        }
        card.animate().cancel()
        // The background drain has its own animator (the child, not the card). Left
        // running it would keep scaling through the fade-out; harmless, but it is the
        // kind of thing that becomes a real bug the moment the fade is removed.
        entry.drain?.animate()?.cancel()
        card.animate()
            .alpha(0f)
            .translationY(card.height / 4f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                runCatching { wm(entry.ctx).removeView(card) }
                promoteNext()
            }
            .start()
    }

    /**
     * Give the screen to whoever is now at the head of the line, starting their clock
     * only now. Recurses safely: if presenting the next card fails it retires itself and
     * calls back here.
     */
    private fun promoteNext() {
        queue.currentItem()?.let { present(it) }
    }

    /**
     * Remove everything and resolve every request as a deny. Called when the service
     * stops: a queued request must not be left waiting for a prompt that will never
     * appear, and the gate's own escape hatch is a slower path to the same answer.
     */
    fun dismissAll() {
        main.post {
            for (entry in queue.clear()) {
                if (entry.settled) continue
                entry.settled = true
                entry.timeout?.let(main::removeCallbacks)
                entry.onOutcome(
                    Outcome(
                        Reason.UNAVAILABLE,
                        entry.shownAt,
                        System.currentTimeMillis(),
                        "overlay torn down with the service"
                    )
                )
                entry.drain?.animate()?.cancel()
                entry.root?.let { runCatching { wm(entry.ctx).removeView(it) } }
            }
        }
    }

    private fun wm(ctx: Context): WindowManager =
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Real screen size, for clamping drags. `currentWindowMetrics` needs API 30; below
     * that the deprecated display call is the only route, and minSdk is 26.
     */
    @Suppress("DEPRECATION")
    private fun screenSize(wm: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = Point()
            wm.defaultDisplay.getSize(point)
            point.x to point.y
        }

    // Muted on purpose: this must never look like an alarm. The ✕ and the draining
    // background carry the interaction; the text carries the fact.
    private const val BG = 0xF0222427.toInt()

    /** ~24% alpha: visible as motion, invisible as a colour. */
    private const val ACCENT_FILL = 0x3D8AB4F8.toInt()
    private const val TEXT = 0xFFECEDEE.toInt()
    private const val MUTED = 0xFF9AA0A6.toInt()

    private const val CARD_WIDTH_DP = 232
    private const val CORNER_DP = 14
    private const val EDGE_DP = 14
    private const val DRAG_SLOP_DP = 8
    private const val FADE_IN_MS = 180L
    private const val FADE_OUT_MS = 200L

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
}
