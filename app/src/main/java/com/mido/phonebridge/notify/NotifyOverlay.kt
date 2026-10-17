package com.mido.phonebridge.notify

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.core.Verb
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
 * only one timer". So the panel dequeues: the card on screen owns the only countdown,
 * and the next appears when it goes. Ordering and capacity live in [NotifyQueue], which
 * is pure and tested; this file owns pixels only.
 *
 * **No animators.** Three separate defects here — an invisible card, a stalled fade-in,
 * and a countdown that never moved — all traced to the same root: the visual depended on
 * `ValueAnimator`/`ViewPropertyAnimator` actually running. They did not run reliably on
 * the target device, and the failures were silent. The card's whole appearance is now
 * driven by ONE main-thread tick that reads the real clock ([System.currentTimeMillis])
 * and sets alpha and the countdown level directly. If the tick stops, the card stops —
 * which is honest, visible, and logged, instead of a bar that quietly freezes at full
 * while the request gets approved underneath it.
 *
 * The card sits **bottom-left**, is **draggable**, and remembers where it was put.
 *
 * Every outcome is reported exactly once, on the main thread.
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
        /** What the request would DO. Drives the coloured badge, nothing else. */
        val verb: Verb,
        val windowMs: Long,
        val onOutcome: (Outcome) -> Unit
    ) {
        /** True once a result has been reported. Guards the tap-versus-timer race. */
        var settled = false

        /** Stamped when the card is attached, never when [show] was called. */
        var shownAt = 0L

        /** Set by [settle]; the tick reads it to know when to start fading out. */
        var settledAt = 0L

        var root: View? = null
        var timeout: Runnable? = null

        /** The single visual tick. Cancelled only on teardown. */
        var tick: Runnable? = null
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
        verb: Verb,
        windowMs: Long,
        onOutcome: (Outcome) -> Unit
    ) {
        main.post {
            val entry = Entry(ctx.applicationContext, id, summary, label, verb, windowMs, onOutcome)
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

        // The countdown is a BACKGROUND LAYER, never a child view. A MATCH_PARENT child
        // inside a WRAP_CONTENT FrameLayout forces the parent to match ITS parent — the
        // full window — which is why 0.6.1 drew a full-screen-height card. A background
        // cannot participate in layout, so the card's size is decided by the text row
        // alone and the drain is simply clipped to whatever that turns out to be.
        //
        // The drain is a ClipDrawable, not a layer inset. `setLayerInsetRight` mutated
        // the layer bounds without reliably repainting, so the bar sat frozen — the
        // "we lost the progress" report. `ClipDrawable.setLevel` invalidates itself on
        // every change, which is what a per-frame countdown needs.
        val drainFill = GradientDrawable().apply {
            setColor(ACCENT_FILL)
            cornerRadius = radius
        }
        val drain = ClipDrawable(drainFill, Gravity.START, ClipDrawable.HORIZONTAL).apply {
            level = LEVEL_FULL
        }

        val card = FrameLayout(ctx).apply {
            // Rounded corners come from the LAYERS themselves (both carry `cornerRadius`),
            // so `clipToOutline` is not needed — and here it was actively dangerous. Its
            // outline provider reads `view.width`/`view.height`, which are 0 before the
            // first layout, so an early outline is an empty round-rect and the whole card
            // clips to NOTHING. A card that is invisible for its whole window is not
            // cosmetic: silence means APPROVE in this mode, so a prompt nobody can see
            // approves the very request it was meant to ask about.
            background = LayerDrawable(
                arrayOf(
                    GradientDrawable().apply { setColor(BG); cornerRadius = radius },
                    drain
                )
            )
            isClickable = true
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(4), dp(6))
        }

        // The verb badge, first so the eye reads WHAT this is before reading the text.
        // Coloured by consequence, not by name: reads are green because nothing changes,
        // deletes are red because something does, and writes sit between. Deliberately
        // WRAP_CONTENT so it hugs its own text — a stretched badge would look like a
        // header bar and pull weight away from the summary.
        val badge = TextView(ctx).apply {
            text = verbShort(entry.verb)
            textSize = 9f
            setTextColor(BADGE_TEXT)
            setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(verbColor(entry.verb))
                cornerRadius = dp(4).toFloat()
            }
            setPadding(dp(5), dp(1), dp(5), dp(1))
        }
        row.addView(
            badge,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        )

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
        // Stamped on attach, not on entry into show(): `main.post` may run late behind
        // other UI work, and a `shownAt` taken earlier would inflate every recorded
        // duration by exactly that delay. It is also the tick's zero point.
        entry.shownAt = System.currentTimeMillis()
        val deadline = entry.shownAt + entry.windowMs
        val fadeInEnd = entry.shownAt + FADE_IN_MS

        com.mido.phonebridge.Debug.log(
            "notify[${entry.id}] attached w=${dp(CARD_WIDTH_DP)} h=WRAP anchored x=$anchorX y=$anchorY"
        )

        // Start invisible and let the tick bring it in. The card is NEVER left at alpha 0
        // with no mechanism to raise it: that is the defect that made it invisible for a
        // whole window while silence approved the request underneath.
        card.alpha = 0f
        card.translationY = dp(8).toFloat()

        // --- the one tick -------------------------------------------------------
        // Drives fade-in, the countdown, and fade-out, all from the real clock. One
        // mechanism, so the three cannot disagree, and none of it depends on an animator
        // choosing to run.
        var loggedMidpoint = false
        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (!entry.settled) {
                    // fade in
                    val elapsed = now - entry.shownAt
                    card.alpha = (elapsed.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
                    card.translationY = dp(8) * (1f - card.alpha)

                    // countdown: level 10000 = full, 0 = empty, so the bar drains
                    // left-to-right. Computed from the REMAINING time, not accumulated
                    // from frames, so a missed tick cannot make it drift.
                    val remaining = (deadline - now).coerceAtLeast(0L)
                    drain.level = (remaining * LEVEL_FULL / entry.windowMs).toInt()
                    card.invalidate()

                    // One midpoint sample, so the log proves the countdown actually moved
                    // rather than freezing at full — the exact failure being fixed.
                    if (!loggedMidpoint && elapsed >= entry.windowMs / 2) {
                        loggedMidpoint = true
                        com.mido.phonebridge.Debug.log(
                            "notify[${entry.id}] midpoint level=${drain.level} alpha=${card.alpha}"
                        )
                    }
                    main.postDelayed(this, TICK_MS)
                } else {
                    // fade out, then remove. Removal lives HERE and nowhere else, so it
                    // happens exactly once and cannot be skipped by an animator that
                    // never fires (which would strand the card on screen forever).
                    val since = now - entry.settledAt
                    if (since >= FADE_OUT_MS) {
                        card.alpha = 0f
                        runCatching { wm(entry.ctx).removeView(card) }
                        promoteNext()
                    } else {
                        val p = since.toFloat() / FADE_OUT_MS
                        card.alpha = 1f - p
                        card.translationY = dp(10) * p
                        card.invalidate()
                        main.postDelayed(this, TICK_MS)
                    }
                }
            }
        }
        entry.tick = tick
        main.post(tick)

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
     * End a card that never reached the screen: report an outcome with no `shownAt`.
     * Used for refusals and for a card that could not be attached.
     */
    private fun reply(entry: Entry, reason: Reason, detail: String) {
        if (entry.settled) return
        entry.settled = true
        entry.settledAt = System.currentTimeMillis()
        entry.timeout?.let(main::removeCallbacks)
        entry.onOutcome(Outcome(reason, 0L, System.currentTimeMillis(), detail))
    }

    /**
     * Report a card's outcome. Deliberately does NOT remove the view: the tick owns
     * removal, so it happens once, after the fade-out, and cannot be skipped.
     */
    private fun settle(entry: Entry, reason: Reason, detail: String = "") {
        if (entry.settled) return
        entry.settled = true
        entry.settledAt = System.currentTimeMillis()
        entry.timeout?.let(main::removeCallbacks)

        com.mido.phonebridge.Debug.log(
            "notify[${entry.id}] settle reason=$reason " +
                "shownMs=${if (entry.shownAt > 0) entry.settledAt - entry.shownAt else 0} " +
                "alpha=${entry.root?.alpha} shown=${entry.root?.isShown}"
        )
        entry.onOutcome(Outcome(reason, entry.shownAt, entry.settledAt, detail))

        // Retired now, so ordering is correct even while the fade-out is running.
        queue.retire(entry.id)

        // A card that was never attached has no tick to remove it.
        if (entry.root == null) {
            entry.tick?.let(main::removeCallbacks)
            promoteNext()
        }
    }

    /**
     * Give the screen to whoever is now at the head of the line, starting their clock
     * only now. Safe to call once per removal; presenting the next card may itself fail
     * and retire, which recurses back here.
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
                entry.tick?.let(main::removeCallbacks)
                entry.timeout?.let(main::removeCallbacks)
                if (!entry.settled) {
                    entry.settled = true
                    entry.settledAt = System.currentTimeMillis()
                    entry.onOutcome(
                        Outcome(
                            Reason.UNAVAILABLE,
                            entry.shownAt,
                            entry.settledAt,
                            "overlay torn down with the service"
                        )
                    )
                }
                entry.root?.let { runCatching { wm(entry.ctx).removeView(it) } }
            }
        }
    }

    private fun wm(ctx: Context): WindowManager =
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * The badge's word. Short on purpose: the card is ~216dp wide and the badge must not
     * compete with the summary for room.
     */
    private fun verbShort(v: Verb): String = when (v) {
        Verb.READ -> "read"
        Verb.WRITE -> "write"
        Verb.EDIT -> "edit"
        Verb.DELETE -> "del"
        Verb.EXECUTE -> "exec"
    }

    /**
     * The badge's colour, chosen by CONSEQUENCE rather than by verb name: green means
     * nothing changes, red means something is destroyed, amber is the middle. `EXECUTE`
     * takes amber because it covers both "send a message" and "act on a notification" —
     * guessing red would cry wolf, guessing green would understate it.
     */
    private fun verbColor(v: Verb): Int = when (v) {
        Verb.READ -> VERB_READ
        Verb.DELETE -> VERB_DEL
        else -> VERB_WRITE
    }

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

    /** ~30% alpha: clearly visible as motion, still quiet as a colour. */
    private const val ACCENT_FILL = 0x4D8AB4F8.toInt()
    private const val TEXT = 0xFFECEDEE.toInt()
    private const val MUTED = 0xFF9AA0A6.toInt()

    // Badge palette. Light fills with near-black text, because the card is dark: a dark
    // badge on a dark card is unreadable, which defeats the point of colour-coding.
    private const val VERB_READ = 0xFF81C784.toInt()
    private const val VERB_WRITE = 0xFFFFD54F.toInt()
    private const val VERB_DEL = 0xFFE57373.toInt()
    private const val BADGE_TEXT = 0xFF1A1A1A.toInt()

    private const val CARD_WIDTH_DP = 216
    private const val CORNER_DP = 14
    private const val EDGE_DP = 14
    private const val DRAG_SLOP_DP = 8
    private const val FADE_IN_MS = 180L
    private const val FADE_OUT_MS = 200L

    /** ~30fps. A 5s countdown does not need more, and this is one small view. */
    private const val TICK_MS = 33L

    /** `ClipDrawable` level scale: 10000 is fully visible. */
    private const val LEVEL_FULL = 10000

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
}
