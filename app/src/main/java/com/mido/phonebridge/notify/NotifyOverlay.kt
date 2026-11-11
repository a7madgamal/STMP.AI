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
import com.mido.phonebridge.ArmGuard
import com.mido.phonebridge.ai.RequestFacts
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.ai.SummaryProvider
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
 * **Fetch first, then the clock.** The card appears immediately, but the decision
 * window does NOT start until the AI summary has been fetched (or its cap trips). So
 * "silence means approve" always refers to a full window the human had the summary
 * for — the clock can never be eaten by inference. A tap on the card approves at any
 * point, including during the fetch, which is the "skip the wait" escape.
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

    /**
     * Cards allowed to wait in line. Plus the one on screen, this bounds the line to 9.
     *
     * **Raised from 3, and the raise is the point.** With a burst of requests the queue filled
     * immediately and every newcomer was REFUSED — silently, from the caller's side, since the
     * whole admission happened before any card was drawn. A human who saw "progress halted"
     * was watching a queue that had already started rejecting. Each waiting card costs one Entry
     * and no window, so the real constraint is patience (a wait behind 8 cards is
     * `MAX_IN_FLIGHT × CARD_LIFETIME_MS`, which NotifyGate already sizes its escape hatch from),
     * not memory.
     */
    private const val MAX_WAITING = 8

    /**
     * Prompts that can be in the system at once: the one on screen plus the line.
     *
     * Public because the gate sizes its deadlock escape hatch from it — the worst
     * legitimate wait is exactly this many cards' worth of time. Hardcoding that number
     * in two places is how a raised queue length silently starts denying requests that
     * are merely waiting their turn.
     */
    const val MAX_IN_FLIGHT = MAX_WAITING + 1

    private val main = Handler(Looper.getMainLooper())

    /**
     * How often a card that refused to come off is retried.
     *
     * Two seconds: fast enough that a stuck card is gone before a human gives up on it, slow
     * enough that a genuinely broken window manager is not hammered.
     */
    private const val STRANDED_RETRY_MS = 2_000L

    /** How many times a stuck card is retried before the sweep gives up and reports it. */
    private const val MAX_STRANDED_SWEEPS = 30

    /** Consecutive sweeps that found something stuck. Reset when the set empties. */
    private var strandedSweeps = 0

    private val queue = NotifyQueue<Entry>(keyOf = { it.id }, maxWaiting = MAX_WAITING)

    /**
     * Cards that decided but whose window would not come off.
     *
     * A `removeView` can fail for reasons outside this file — the system detaching the window, a
     * WM refusing mid-frame. When it does, the entry must NOT be marked gone (see [teardown]),
     * because a card the app thinks is gone is a card every other path skips: draggable, because
     * its touch listener lives on the view, and undismissable, because the dismiss path reaches a
     * settled entry. That is the stuck card reported on 2026-11-01.
     *
     * So a failed removal lands here and [sweepStranded] retries it. A retry is cheap and idempotent;
     * a write-off is permanent.
     */
    private val stranded = java.util.Collections.synchronizedSet(HashSet<Entry>())

    /** Why a prompt ended. Mapped to audit outcomes by the caller, not here. */
    enum class Reason {
        /** The ✕ was tapped: reject. */
        TAPPED,
        /**
         * The card BODY was tapped (not dragged, not the ✕): approve now.
         *
         * This is "skip the wait" — a deliberate approval, distinct from the silence
         * of [EXPIRED]. It is recorded as its own reason so the log can tell a tap
         * from an expiry, which is the difference between "he read it and said yes"
         * and "he never touched it".
         */
        APPROVED,
        /** The window elapsed in silence with someone present. */
        EXPIRED,
        /** Nobody could have read it: screen off or locked. */
        UNATTENDED,
        /** It could not be drawn at all. */
        UNAVAILABLE,
        /** The line was full, so it was refused rather than shown late. */
        REFUSED,
        /**
         * The AI could not describe the request. The card is torn down WITHOUT a
         * decision: the AI is core (Mido, 2026-10-19), so a card that cannot say what
         * is being approved must not offer to approve it. Distinct from [UNAVAILABLE]
         * (a drawing failure) and from the 503 not-ready gate (no model at all).
         */
        AI_FAILED
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
        /** Facts for the AI summary, or null to skip inference entirely. */
        val facts: RequestFacts?,
        /**
         * Whether this route uses the AI at all (its `Route.aiSummary`). Distinguishes
         * "no AI by design" from "the AI failed": the first is a normal card whose
         * clock starts at once, the second withdraws the card.
         */
        val needsAi: Boolean,
        /** How long the fetch may take before the clock starts regardless. */
        val fetchCapMs: Long,
        val onOutcome: (Outcome) -> Unit
    ) {
        /** True once a result has been reported. Guards the tap-versus-timer race. */
        var settled = false

        /** Stamped when the card is attached, never when [show] was called. */
        var shownAt = 0L

        /** Set by [settle]; the tick reads it to know when to start fading out. */
        var settledAt = 0L

        /**
         * When the DECISION clock started — i.e. when the fetch finished (or its cap
         * tripped). 0 while still fetching, which is the tick's signal that the
         * countdown has not begun.
         */
        var decisionStartAt = 0L

        var root: View? = null
        var timeout: Runnable? = null

        /**
         * True once the view has been handed back to the window manager.
         *
         * Separate from [settled] on purpose, and that separation is the fix for a real
         * defect: `settled` means "the outcome is decided", while this means "the window is
         * gone". Conflating them left a card on screen forever — once `settled` was true,
         * every tap path early-returned, and the only removal was a posted fade chain that
         * could silently stop. Removal now has its own flag and its own guaranteed path.
         */
        var removed = false

        /**
         * The window's layout params, kept so the fade-out can drive WINDOW alpha
         * ([WindowManager.LayoutParams.alpha]) instead of only the view's. The window
         * alpha is what SurfaceFlinger composites with, so the window is genuinely
         * transparent when it is torn down — see [settle].
         */
        var params: WindowManager.LayoutParams? = null

        /** The single visual tick. Cancelled only on teardown. */
        var tick: Runnable? = null

        /** The AI description line, created up front so the fetch can fill it in. */
        var aiLine: TextView? = null

        /** The card's background layer, restroked with the risk colour on arrival. */
        var cardBg: GradientDrawable? = null

        /** The drain behind the reject control. Null until the card is built. */
        var cardDrain: ClipDrawable? = null


    }

    /**
     * Where the card sits, remembered across cards so a drag sticks: y is measured UP
     * from the bottom edge (the card is bottom-anchored). [Int.MIN_VALUE] means "not
     * placed yet" — the first prompt lands at the default corner.
     */
    private var anchorX = Int.MIN_VALUE
    private var anchorY = Int.MIN_VALUE

    /**
     * The orientation [anchorX]/[anchorY] belong to, or 0 when nothing has been placed yet.
     *
     * Needed because the anchor is in-process state: without this, rotating the phone would
     * carry the old orientation's position into the new one, and the per-orientation store
     * would only ever be read on the first card after a fresh start.
     */
    private var anchorOrientation = 0

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
        facts: RequestFacts?,
        needsAi: Boolean,
        fetchCapMs: Long,
        onOutcome: (Outcome) -> Unit
    ) {
        main.post {
            val entry = Entry(
                ctx.applicationContext, id, summary, label, verb, windowMs, facts, needsAi,
                fetchCapMs, onOutcome
            )
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
        // **AN ENTRY IS PRESENTED AT MOST ONCE.** Mido, 2026-11-03: *"touching the hints still
        // freezes them"*.
        //
        // The device log shows the defect exactly: `notify[5014] attached` appears TWICE, 896ms
        // apart, for one entry. The first came from `queue.admit` returning `Now`; the second
        // from `teardown(5013)` calling `promoteNext()`, which asks the queue for the current item
        // — still 5014 — and presents something already on screen.
        //
        // The second attach overwrote `entry.root` with a fresh view, ORPHANING the first window.
        // Nothing holds a reference to an orphan, so `teardown` could never remove it: it stayed
        // on screen forever, swallowing every touch, while the live card underneath was invisible
        // behind it. That is the freeze — the thing being touched was a corpse.
        //
        // Return here rather than patching `promoteNext`: a guard at the one place that attaches a
        // window covers every road into it, including ones added later.
        if (entry.root != null) {
            com.mido.phonebridge.Debug.log(
                "notify[${entry.id}] present ignored: already attached"
            )
            return
        }

        // Retry anything that previously refused to leave. Runs before the new card is built so a
        // stranded window cannot sit under a fresh one and swallow its touches.
        sweepStranded()
        val ctx = entry.ctx
        val wm = wm(ctx)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val radius = dp(CORNER_DP).toFloat()

        // Whatever decorates the card must be a BACKGROUND LAYER, never a child view: a
        // MATCH_PARENT child inside a WRAP_CONTENT FrameLayout forces the parent to match ITS
        // parent — the full window — which is why 0.6.1 drew a full-screen-height card.
        //
        // The countdown used to be such a layer here. It now lives on the reject button instead
        // (see `cardDrain` below), which is still a background and so still cannot affect the
        // card's measured height.
        val cardBg = GradientDrawable().apply {
            setColor(BG)
            cornerRadius = radius
        }

        val entryDrain = ClipDrawable(
            GradientDrawable().apply {
                setColor(ACCENT_FILL)
                cornerRadius = radius
            },
            Gravity.START,
            ClipDrawable.HORIZONTAL,
        ).apply { level = LEVEL_FULL }

        val card = FrameLayout(ctx).apply {
            // Rounded corners come from the LAYERS themselves (both carry `cornerRadius`),
            // so `clipToOutline` is not needed — and here it was actively dangerous. Its
            // outline provider reads `view.width`/`view.height`, which are 0 before the
            // first layout, so an early outline is an empty round-rect and the whole card
            // clips to NOTHING. A card that is invisible for its whole window is not
            // cosmetic: silence means APPROVE in this mode, so a prompt nobody can see
            // approves the very request it was meant to ask about.
            // The card's own wash, full width: the hint's window, as it always was. Putting it
            // on the 30dp ✕ made a chip against the right edge instead of a bar.
            background = LayerDrawable(
                arrayOf(
                    cardBg,
                    entryDrain,
                    // Grain on the NOTIFY card too. Without this the one surface a user actually
                    // touches in AUTOMATIC mode was the only untextured thing on screen.
                    com.mido.phonebridge.ui.GrainOverlay(radius)
                )
            )
            isClickable = true
        }
        entry.cardBg = cardBg
        entry.cardDrain = entryDrain

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(4), dp(6))
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // The verb badge, first so the eye reads WHAT this is before reading the text.
        // Coloured by consequence, not by name: reads are green because nothing changes,
        // deletes are red because something does, and writes sit between. Deliberately
        // WRAP_CONTENT so it hugs its own text — a stretched badge would look like a
        // header bar and pull weight away from the summary.
        val badge = TextView(ctx).apply {
            text = verbShort(entry.verb)
            // Was 9sp: unreadable on a phone at arm's length, and this badge carries the ONE
            // word that says what the request does. 12sp is the app's floor.
            textSize = 12f
            setTextColor(BADGE_TEXT)
            setTypeface(com.mido.phonebridge.ui.Theme.handFont(ctx))
            gravity = Gravity.CENTER
            background = com.mido.phonebridge.ui.Theme.sheetDp(ctx, verbColor(entry.verb), 4)
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
            textSize = 13f
            setTextColor(TEXT)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(com.mido.phonebridge.ui.Theme.handFont(ctx))
        }
        row.addView(summaryView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // A 30dp touch box so the target is real without looking like a button — a
        // visible button here would pull the eye off the text.
        //
        // **The ✕ is plain again.**
        //
        // I put the countdown drain here by mistake: "the modal" in the request meant the APPROVAL
        // modal, and this is the hint card. The result was a 44dp fill squeezed against the right
        // edge — reported as *"hints loading is pushed to the right, not full hint width"*.
        //
        // The countdown now lives where it was asked for, on the modal's DENY button. This card
        // keeps the full-card drain it already had.
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "Reject: ${entry.label}"
        }
        // 30dp square: a touch target, not a bar.
        row.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))

        column.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // The AI description line, present from the start so the fetch can fill it in
        // WITHOUT A RELAYOUT SURPRISE. While the fetch runs it reads as a status, not as an
        // empty row (never print a label with nothing under it). Its row exists from the
        // first frame, capped at two lines, so the arriving summary rewrites text in a
        // box that is already there — it can never add height the card did not have.
        val aiLine = TextView(ctx).apply {
            text = if (entry.facts != null) "◌ summarising…" else ""
            // Was 10sp for the same reason; 12sp is the floor.
            textSize = 12f
            setTextColor(MUTED)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
            visibility = if (entry.facts != null) VISIBLE else GONE
        }
        entry.aiLine = aiLine
        column.addView(aiLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        card.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // **The column is the card's ONLY child, and it must stay that way.** The card is
        // WRAP_CONTENT, so any second child participating in layout can decide the card's
        // size — and a MATCH_PARENT one makes it the full window. That is the 0.6.2 and
        // 0.25.1 bug, twice. Decoration that must not affect size belongs in the background
        // LayerDrawable (tint, grain); `NotifyCardLayoutTest` enforces this.

        val size = screenSize(wm)
        val minEdge = dp(EDGE_DP)
        val orientation = ctx.resources.configuration.orientation
        // Restore the last position for THIS orientation. Reloaded whenever the orientation
        // changed, because the in-memory anchor belongs to the other one — a landscape
        // position kept across a rotation to portrait is exactly the bug this avoids.
        //
        // The clamp runs on RESTORE, not just on drag: a value saved on a wider display
        // would otherwise come back off-screen and the card would be unreachable, which in
        // this mode means a request nobody can see.
        //
        // Card height is not known until layout, so it is 0 here and the clamp is repeated
        // in the post-layout pass below.
        if (anchorX == Int.MIN_VALUE || anchorOrientation != orientation) {
            val stored = CardAnchorStore.load(ctx, orientation)
            val restored = CardAnchor.clamp(
                x = stored?.x ?: minEdge,
                y = stored?.y ?: minEdge,
                screenWidth = size.first,
                screenHeight = size.second,
                cardWidth = dp(CARD_WIDTH_DP),
                cardHeight = 0,
                minEdge = minEdge,
            )
            anchorX = restored.x
            anchorY = restored.y
            anchorOrientation = orientation
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
        // Kept on the entry so the fade-out can lower the WINDOW alpha (see settle).
        entry.params = params

        // --- drag, and the tap-to-approve -------------------------------------
        // Deliberate trade: the touch listener means the card consumes touches inside
        // its own rectangle, so it is opaque to input for its lifetime. That is the
        // price of "allow dragging"; NOT_FOCUSABLE still keeps the keyboard and focus
        // away, and outside the card every tap falls through to the app below.
        //
        // A tap that never moved is an APPROVAL (Mido, 2026-10-18: "clicking (not
        // dragging) the hint should mark as ok and skip the wait"). This is why the
        // drag slop is generous: below it a pocket brush would approve a request
        // nobody read, so the threshold is a security-relevant number, not a
        // comfort setting.
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
                        // One clamp rule for drag and for restore — see CardAnchor.clamp.
                        // y is measured UP from the bottom edge, so dragging up ADDS.
                        val clamped = CardAnchor.clamp(
                            x = (originX + dx).roundToInt(),
                            y = (originY - dy).roundToInt(),
                            screenWidth = size.first,
                            screenHeight = size.second,
                            cardWidth = dp(CARD_WIDTH_DP),
                            cardHeight = card.height,
                            minEdge = minEdge,
                        )
                        anchorX = clamped.x
                        anchorY = clamped.y
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
                        // Persist the position the user chose, for this orientation only.
                        CardAnchorStore.save(
                            ctx, anchorOrientation, CardAnchor(anchorX, anchorY)
                        )
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        // Tap = approve, and skip whatever wait remains (fetch included).
                        // Settle only: this card is meant to fade out normally, and the
                        // settle backstop guarantees its removal.
                        //
                        // **Gated by the same misclick guard as the modal.** A card can appear
                        // under a finger already travelling, and a tap here MEANS APPROVE — so the
                        // first second must not decide anything. Nothing is lost by refusing the
                        // tap: the card simply stays, dimmed, until the guard expires.
                        //
                        // Undim FIRST, then decide. The tick repaints every 33ms, but it only runs
                        // on the next frame, so a tap landing in the gap between the guard expiring
                        // and the next paint used to be allowed while the card still LOOKED
                        // dimmed — live control behind a locked-looking surface (reported on
                        // device 2026-10-26: "I can still dismiss in cool down"). Painting here
                        // closes the gap in the only direction that matters: the picture is
                        // brought up to date before the decision it gates is taken.
                        undim(entry)
                        if (ArmGuard.decisionsAllowed(entry.shownAt, System.currentTimeMillis())) {
                            settle(entry, Reason.APPROVED)
                        } else {
                            com.mido.phonebridge.Debug.log(
                                "notify[${entry.id}] tap ignored: still in the misclick guard"
                            )
                        }
                    }
                    true
                }

                else -> false
            }
        }

        // Dismiss. Settle reports the outcome (once), then teardown removes the window
        // (once) — calling BOTH is the fix for a stuck card: if the card had already
        // settled on its own (e.g. it expired while the human was reaching for the x), the
        // settle above is a no-op and only the teardown does anything. Previously this
        // called settle alone, which early-returned on an already-settled entry and left
        // the window on screen with every control dead (reported on device 2026-10-19:
        // "doesn't even dismiss on x click").
        close.setOnClickListener {
            // **THE ✕ IS NEVER GATED.** Mido, 2026-11-03: *"ensure the x IS ALWAYS CLICKABLE and
            // dismisses the card under any fucking circumstances"*.
            //
            // It used to share the misclick guard with the tap-to-approve (2026-10-24: "don't
            // approve or cancel by mistake"), and that was the wrong trade. A guarded APPROVE
            // protects something; a guarded DISMISS protects nothing and strands a card the human
            // has explicitly asked to be rid of. Worse, the guard reads `decisionsAllowed`, which
            // also refuses when `shownAt == 0` — so a card attached in a bad state had a ✕ that
            // could never work at all.
            //
            // Dismissing is now unconditional on purpose. Every other path in this file assumes
            // the ✕ works, and `settle` + `teardown` are each idempotent, so this is safe to call
            // in any state: before the guard, after it, mid-fetch, after the card already settled,
            // or while a window removal is being retried.
            com.mido.phonebridge.Debug.log("notify[${entry.id}] dismiss tapped")
            settle(entry, Reason.TAPPED)
            teardown(entry)
        }

        // Invisible BEFORE the view enters the window manager. Setting alpha after
        // addView leaves one frame where the freshly attached card is drawn at full
        // opacity — which is the bright blip seen right after the previous card's
        // fade-out, since the next card is what appears next. Set it here, so the card's
        // very first composited frame is already transparent.
        card.alpha = 0f
        card.translationY = dp(8).toFloat()

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

        // Second half of the restore clamp: the card's real height is only known after
        // layout, and a card that grew (a long AI line) can hang off the bottom edge even
        // when its width fitted. X was already clamped at attach, so this only moves Y.
        card.post {
            val h = card.height
            if (h > 0 && anchorOrientation == orientation) {
                val re = CardAnchor.clamp(
                    x = anchorX,
                    y = anchorY,
                    screenWidth = size.first,
                    screenHeight = size.second,
                    cardWidth = dp(CARD_WIDTH_DP),
                    cardHeight = h,
                    minEdge = minEdge,
                )
                if (re.x != anchorX || re.y != anchorY) {
                    anchorX = re.x
                    anchorY = re.y
                    params.x = anchorX
                    params.y = anchorY
                    runCatching { wm.updateViewLayout(card, params) }
                }
            }
        }
        // Stamped on attach, not on entry into show(): `main.post` may run late behind
        // other UI work, and a `shownAt` taken earlier would inflate every recorded
        // duration by exactly that delay. It is also the tick's zero point.
        entry.shownAt = System.currentTimeMillis()

        com.mido.phonebridge.Debug.log(
            "notify[${entry.id}] attached w=${dp(CARD_WIDTH_DP)} h=WRAP anchored x=$anchorX y=$anchorY"
        )

        // The subtle alert. Enforced inside SoundAlerts: it plays only while the
        // screen is on, which is the stated rule for this mode (a NOTIFY card can
        // only exist when someone is attending anyway).
        com.mido.phonebridge.alert.SoundAlerts.notifyTick(ctx)

        // --- the fetch, then the clock -----------------------------------------
        startFetch(entry)

        // --- the one tick -------------------------------------------------------
        // Drives fade-in, the countdown, and (until the fetch lands) a "not started"
        // bar, all from the real clock. One mechanism, so the three cannot disagree,
        // and none of it depends on an animator choosing to run.
        var loggedMidpoint = false
        val tick = object : Runnable {
            override fun run() {
                if (entry.settled) return
                val now = System.currentTimeMillis()

                // fade in
                val elapsed = now - entry.shownAt
                val fadeIn = (elapsed.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
                // DIM WHILE UNARMED. The guard is only safe if a card that refuses taps LOOKS
                // like it refuses taps — otherwise a short guard reads as a broken card, and a
                // long one as a frozen app. Computed by [ArmGuard.cardAlpha], the SAME function
                // the touch paths call, so the picture and the behaviour cannot disagree about
                // when the card went live.
                card.alpha = ArmGuard.cardAlpha(entry.shownAt, now, FADE_IN_MS)
                card.translationY = dp(8) * (1f - fadeIn)

                if (entry.decisionStartAt == 0L) {
                    // Still fetching: the bar waits at full and the clock has not begun.
                    entry.cardDrain?.level = LEVEL_FULL
                    // **BUT NOT FOREVER.** This branch used to have no deadline at all: it held
                    // the card at "◌ summarising…" and relied on a separate backstop Runnable
                    // to end it. When that backstop was missed — a posted callback that never
                    // ran, a card re-attached, a window service hiccup — the card sat on
                    // "summarising…" with a full bar and a clock that had never started, and
                    // nothing in the tick could end it. A frozen card is the worst outcome
                    // here: the human reads it as "still working" while the server thread is
                    // pinned behind it.
                    //
                    // So the tick now enforces its own bound, derived from the same constant
                    // the fetch is given. Two independent clocks that agree by construction:
                    // the fetch has its cap, and the display has the same cap. If the fetch
                    // thread is wedged, the CARD still ends and says why.
                    val fetchDeadline = entry.shownAt + entry.fetchCapMs + FETCH_DEADLINE_SLACK_MS
                    if (now >= fetchDeadline) {
                        com.mido.phonebridge.Debug.log(
                            "notify[${entry.id}] fetch exceeded " +
                                "${com.mido.phonebridge.ai.SummaryFailure.humanMs(entry.fetchCapMs)} " +
                                "on screen " +
                                "(${now - entry.shownAt}ms); withdrawing card rather than " +
                                "leaving it stuck at summarising"
                        )
                        settle(entry, Reason.AI_FAILED, "summary exceeded its budget")
                        return
                    }
                } else {
                    val deadline = entry.decisionStartAt + entry.windowMs
                    if (now >= deadline) {
                        // Re-checked at APPROVE time, not just at post time: a phone
                        // that went into a pocket mid-window must deny rather than
                        // approve.
                        settle(
                            entry,
                            // Approve on silence when the vault session is open, even with
                            // nobody at the screen — the 0.42.0 rule (Mido, 2026-10-25).
                            // Without the vault half, a request that started attended and lost
                            // the screen mid-window would still deny, which would make the rule
                            // depend on WHEN the screen went off rather than on the session.
                            if (com.mido.phonebridge.policy.isAttended(entry.ctx) ||
                                com.mido.phonebridge.core.SecretVault.isUnlocked()
                            ) Reason.EXPIRED
                            else Reason.UNATTENDED
                        )
                        return
                    }
                    // Computed from the REMAINING time, not accumulated from frames, so
                    // a missed tick cannot make it drift.
                    val remaining = deadline - now
                    entry.cardDrain?.level = (remaining * LEVEL_FULL / entry.windowMs).toInt()
                    if (!loggedMidpoint && now - entry.decisionStartAt >= entry.windowMs / 2) {
                        loggedMidpoint = true
                        com.mido.phonebridge.Debug.log(
                            "notify[${entry.id}] midpoint level=" +
                                "${entry.cardDrain?.level} alpha=${card.alpha}"
                        )
                    }
                }
                card.invalidate()
                main.postDelayed(this, TICK_MS)
            }
        }
        entry.tick = tick
        main.post(tick)

        // Backstop only. The normal expiry is owned by the tick (it settles at the
        // decision deadline); this exists so a fetch that somehow never returns cannot
        // leave a card up forever with a clock that never started.
        val backstop = Runnable {
            if (!entry.settled) {
                settle(
                    entry,
                    // Same rule as the tick above: the vault session is what permits an
                    // unattended approve. See the note there.
                    if (com.mido.phonebridge.policy.isAttended(entry.ctx) ||
                        com.mido.phonebridge.core.SecretVault.isUnlocked()
                    ) Reason.EXPIRED
                    else Reason.UNATTENDED
                )
            }
        }
        entry.timeout = backstop
        main.postDelayed(backstop, entry.fetchCapMs + entry.windowMs + BACKSTOP_SLACK_MS)
    }

    /**
     * Fetch the AI summary off the main thread, then start the decision clock.
     *
     * The clock start is the whole point of this method: [Entry.decisionStartAt] stays
     * 0 until here, and the tick will not drain until it is set. A null result (no
     * model, over budget, failure) still starts the clock — the human gets a rows-only
     * card, which is exactly the pre-AI behaviour and never worse.
     */
    private fun startFetch(entry: Entry) {
        // No AI by design (the route declares `aiSummary = false`): there is nothing to
        // fetch and nothing to fail, so the clock starts now and the card shows exactly
        // its deterministic content. This is the pre-AI behaviour, kept for routes that
        // opted out — it is NOT a fallback for a broken model.
        if (!entry.needsAi) {
            entry.decisionStartAt = System.currentTimeMillis()
            return
        }
        val facts = entry.facts
        if (facts == null) {
            // The route USES the AI but no facts were built for it. That is a defect in
            // the caller, not a normal state, and a card that cannot say what it is
            // asking about must not be offered for approval.
            settle(entry, Reason.AI_FAILED, "no facts for the summary")
            return
        }
        Thread({
            com.mido.phonebridge.ai.SummaryFailure.clear()
            val result = try {
                com.mido.phonebridge.ai.AiGateway.summarize(entry.ctx, facts, entry.fetchCapMs)
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed for id=${entry.id}: ${e.message}")
                com.mido.phonebridge.ai.SummaryFailure.record("the summary fetch threw: ${e.message}")
                null
            }
            main.post {
                if (entry.settled) return@post
                if (result == null) {
                    // Fail hard (Mido, 2026-10-19): a card that could not be described is
                    // not a card that may be silently approved. Tear it down with a
                    // distinct reason so the dispatcher answers a fault, never a decision.
                    com.mido.phonebridge.Debug.log(
                        "notify[${entry.id}] AI failed within ${entry.fetchCapMs}ms; withdrawing card"
                    )
                    settle(
                        entry, Reason.AI_FAILED,
                        com.mido.phonebridge.ai.SummaryFailure.take()
                    )
                    return@post
                }
                applyAi(entry, result)
                // The clock starts now, whatever the outcome. "Fetch first, then count."
                entry.decisionStartAt = System.currentTimeMillis()
                com.mido.phonebridge.Debug.log(
                    "notify[${entry.id}] fetch done in " +
                        "${entry.decisionStartAt - entry.shownAt}ms " +
                        "(risk=${result.risk}); decision clock starts"
                )
            }
        }, "notify-fetch-${entry.id}").apply { isDaemon = true }.start()
    }

    /**
     * Render the fetch result onto the card. Since the AI is core (Mido, 2026-10-19),
     * a null result never reaches here — the caller withdraws the card instead. The
     * non-null signature is what makes that guarantee visible in the type.
     */
    private fun applyAi(entry: Entry, result: com.mido.phonebridge.ai.RequestSummary) {
        val line = entry.aiLine ?: return
        line.text = result.description
        line.visibility = View.VISIBLE
        line.setTextColor(TEXT)
        // The risk tints the CARD ITSELF, not a stripe down its edge (Mido, 2026-11-03:
        // *"remove left green border"* ... *"the hint bg to match the level"*). `cardBg` is a
        // GradientDrawable already in the card's LayerDrawable, so recolouring it repaints
        // without touching geometry — the same property that made the old band safe, kept.
        entry.cardBg?.setColor(com.mido.phonebridge.ui.Theme.riskSurface(result.risk))
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
     * Report a card's outcome, then fade it out and remove it. Removal is GUARANTEED, not
     * merely attempted — see [teardown] for why that distinction cost a stuck card.
     */
    /**
     * Bring the card's appearance up to date with the guard, right now.
     *
     * The tick already does this every [TICK_MS], but it only runs on the next frame. A touch
     * that lands in the gap between the guard expiring and that frame would otherwise be decided
     * against a card that still LOOKS unarmed — the human sees a greyed card and it answers, which
     * is the exact opposite of what the dim promises. So every touch path calls this before it
     * decides anything, and the two can no longer disagree by even one frame.
     *
     * Only ever raises the alpha to full. The guard cannot un-expire, so there is no case where a
     * touch should make the card dimmer.
     */
    private fun undim(entry: Entry) {
        if (entry.settled) return
        val card = entry.root ?: return
        // Same function the tick uses, so this cannot drift from what the card would have shown
        // had the next frame arrived first.
        card.alpha = ArmGuard.cardAlpha(entry.shownAt, System.currentTimeMillis(), FADE_IN_MS)
    }

    private fun settle(entry: Entry, reason: Reason, detail: String = "") {
        if (entry.settled) return
        entry.settled = true
        entry.settledAt = System.currentTimeMillis()
        entry.timeout?.let(main::removeCallbacks)
        entry.tick?.let(main::removeCallbacks)

        com.mido.phonebridge.Debug.log(
            "notify[${entry.id}] settle reason=$reason " +
                "shownMs=${if (entry.shownAt > 0) entry.settledAt - entry.shownAt else 0} " +
                "alpha=${entry.root?.alpha} shown=${entry.root?.isShown}"
        )
        entry.onOutcome(Outcome(reason, entry.shownAt, entry.settledAt, detail))

        // Retire now so ordering is correct even while the fade-out runs.
        queue.retire(entry.id)

        // A card that never attached has no view to fade or remove.
        val card = entry.root
        if (card == null) {
            promoteNext()
            return
        }

        // The stamp fires ONLY on a human decision, and only while the card is still on
        // screen — never on EXPIRED/UNATTENDED/UNAVAILABLE, which are the absence of one.
        // It goes in its own non-touchable window rather than as a child of the card: the
        // card is a WRAP_CONTENT FrameLayout, so a MATCH_PARENT child would force it to the
        // full window height (the 0.6.1 bug this file documents) and a fixed-size child
        // would make a small card grow to fit the stamp.
        if (reason == Reason.APPROVED || reason == Reason.TAPPED) {
            // **THE STAMP HAS A DEADLINE, because it is now the ONLY exit on this path.**
            // Mido, 2026-11-03: *"all the notification cards are stuck on summarizing after I
            // touched to accept them"*.
            //
            // This branch returns early and leaves removal to `stampDecision`'s `onFinished`,
            // which fires from an animator listener. That is not a guarantee: `StampOverlay`
            // starts its animation from `onSizeChanged`, so a stamp whose window is added but
            // never laid out never starts, never ends, and never calls back — and the card sits
            // there reading "◌ summarising…" with every control inert, because `settled` is
            // already true. `teardown` is idempotent, so a second caller is harmless.
            main.postDelayed({ teardown(entry) }, STAMP_BACKSTOP_MS)
            stampDecision(entry, approved = reason == Reason.APPROVED)
            // The stamp is ~1.1s (slam + bleed + settle + fade) and the card's own fade-out is
            // 200ms — so tearing the card down on its normal schedule left the stamp drawing over
            // empty space for most of its life, and on a fast device it looked like nothing
            // happened at all. The card stays put until the stamp reports it is done.
            //
            // The backstop and the fade are SKIPPED for these two reasons; `stampDecision`
            // schedules the teardown from `onFinished`, and `dismissAll`/service shutdown still
            // have their own paths. If the overlay fails to start (no draw-over permission), it
            // tears down immediately so the card can never be stranded.
            return
        }

        // THE BACKSTOP, and it is the point of this method. The fade below is a chain of
        // posted runnables; if any link throws or the main thread drops one, the card was
        // left on screen with `settled = true` — so every tap early-returned and nothing
        // could remove it. A user reported exactly that ("card still stuck", then "doesn't
        // even dismiss on x click"). This callback makes removal unconditional: it runs
        // after the fade SHOULD have finished, and tears the window down whether or not the
        // fade got there.
        main.postDelayed({ teardown(entry) }, FADE_OUT_MS + COMMIT_FRAMES_MS + BACKSTOP_SLACK_MS)

        // One-shot fade-out, then remove and hand the screen to the next card.
        //
        // The fade is driven by WINDOW alpha ([WindowManager.LayoutParams.alpha]), not
        // view alpha. The card's `View.alpha` only changes what the app draws; the WINDOW
        // carries its own alpha, and SurfaceFlinger composites the window's last buffer
        // with THAT. Fading only the view left the window at alpha 1, so the moment
        // `removeView` tore the surface down, one frame of the card re-composited at full
        // opacity — exactly "fades out to 0, then a flash of full-opacity card before it
        // disappears". Lowering the window alpha makes the surface itself transparent, so
        // there is no opaque buffer left to flash.
        //
        // View alpha is left alone, so the two multiply cleanly: a card tapped mid-fade-in
        // fades out from whatever opacity it had actually reached, with no jump to full.
        val fadeOut = object : Runnable {
            override fun run() {
                // A failure here must never strand the card: fall straight to teardown.
                try {
                    val p = (System.currentTimeMillis() - entry.settledAt).toFloat() / FADE_OUT_MS
                    val a = (1f - p).coerceIn(0f, 1f)
                    entry.params?.let { lp ->
                        lp.alpha = a
                        runCatching { wm(entry.ctx).updateViewLayout(card, lp) }
                    }
                    card.translationY =
                        (10 * entry.ctx.resources.displayMetrics.density).roundToInt() * p
                    if (p < 1f) {
                        main.postDelayed(this, TICK_MS)
                    } else {
                        // The window is now at alpha 0. Give the compositor a few frames
                        // to commit that transparent buffer BEFORE destroying the surface.
                        main.postDelayed({ teardown(entry) }, COMMIT_FRAMES_MS)
                    }
                } catch (e: Exception) {
                    com.mido.phonebridge.Debug.log(
                        "notify[${entry.id}] fade-out threw (${e.message}); tearing down now"
                    )
                    teardown(entry)
                }
            }
        }
        main.post(fadeOut)
    }

    /**
     * Remove the card's window, exactly once, and hand the screen to the next waiter.
     *
     * Idempotent by [Entry.removed] because it is reachable from four places — the fade's
     * completion, the settle backstop, the `x` tap, and service shutdown — and two of them
     * can race on the same card. Every one of them must be able to say "make this go away"
     * without having to know whether another already did.
     *
     * This is deliberately NOT guarded by [Entry.settled]: a settled card that is still on
     * screen is precisely the bug this exists to fix, and the dismiss control has to work
     * on it.
     */
    private fun teardown(entry: Entry) {
        if (entry.removed) return

        entry.tick?.let(main::removeCallbacks)
        entry.timeout?.let(main::removeCallbacks)

        val card = entry.root
        if (card != null) {
            com.mido.phonebridge.Debug.log(
                "notify[${entry.id}] teardown windowAlpha=${entry.params?.alpha} " +
                    "viewAlpha=${card.alpha}"
            )
            // **A FAILED REMOVE MUST NOT MARK THE ENTRY GONE.**
            //
            // This used to set `removed = true` BEFORE trying, then swallow the exception. So one
            // failed `removeView` (a window already detached by the system, a WM that refused
            // mid-frame) left a card on screen that every later path skipped because the entry
            // said it was already gone. The card stayed: draggable, because its touch listener
            // lived on the view, but undismissable, because the ✕ path reached `settle()` which
            // early-returns on an already-settled entry — and the bar froze, because the tick had
            // been removed. Reported on device 2026-11-01 as exactly that.
            //
            // So the flag now follows the OUTCOME. A failure leaves the entry live, keeps its
            // window reference, and is retried by [sweepStranded] rather than written off.
            val removedOk = try {
                wm(entry.ctx).removeView(card)
                true
            } catch (e: IllegalArgumentException) {
                // "View not attached to window manager" — the platform already dropped it, which
                // is the outcome we wanted. Treat it as success, not as a failure to retry.
                true
            } catch (e: Exception) {
                Log.w(TAG, "notify[${entry.id}] removeView failed: ${e.message}")
                false
            }
            if (removedOk) {
                entry.removed = true
                // Drop the reference so a second teardown cannot touch a dead view, and so the
                // card is collectable while the Entry lives on in the queue's retired list.
                entry.root = null
            } else {
                // Keep it reachable. The sweep below is the retry; until then the entry is
                // still "on screen" and must not be promoted past.
                stranded.add(entry)
                return
            }
        } else {
            entry.removed = true
        }
        promoteNext()
    }

    /**
     * Remove any card that decided but never left.
     *
     * **The second half of the stranded-card fix.** [teardown] can now fail and stay retryable, but
     * something has to actually retry: a card whose fade chain threw, whose stamp never reported
     * finished, or whose remove was refused would otherwise sit there forever — visibly draggable
     * and completely undismissable, which is the worst state this overlay can be in because it
     * looks alive.
     *
     * Runs on every presentation, and is cheap: it only touches entries already in [stranded] plus
     * anything the queue has already retired. It never touches a live card.
     */
    private fun sweepStranded() {
        val doomed = stranded.toList()
        stranded.clear()
        for (entry in doomed) {
            if (!entry.removed) teardown(entry)
        }
        if (!stranded.isEmpty()) {
            // **It must retry itself.** Running only on presentation meant a stranded card with
            // nothing behind it was never revisited — the common case, since a burst drains and
            // the last card is exactly the one that gets stuck.
            //
            // **Capped**, because a self-reposting runnable with no exit is a leak: if the window
            // manager will never release the view, polling every 2s forever buys nothing. After
            // the cap it stops and says so loudly — the card is unremovable, and the log is the
            // only place that can be understood from.
            if (strandedSweeps < MAX_STRANDED_SWEEPS) {
                strandedSweeps++
                main.postDelayed({ sweepStranded() }, STRANDED_RETRY_MS)
            } else {
                com.mido.phonebridge.Debug.log(
                    "notify: ${stranded.size} card(s) could not be removed after " +
                        "$MAX_STRANDED_SWEEPS sweeps — the window manager will not release them"
                )
            }
        } else {
            strandedSweeps = 0
        }
    }

    /**
     * Give the screen to whoever is now at the head of the line, starting their clock
     * only now. Safe to call once per removal; presenting the next card may itself fail
     * and retire, which recurses back here.
     */
    private fun promoteNext() {
        val next = queue.currentItem() ?: return
        // Already on screen. `promoteNext` runs from every teardown, so a teardown that is not
        // for the head of the queue would otherwise re-present a card that is already attached —
        // which attaches a SECOND window and orphans the first. The guard in `present` stops the
        // damage; this stops the attempt, so the log stays honest about what happened.
        if (next.root != null) return
        present(next)
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
                // Same idempotent path as every other removal, so shutdown cannot race
                // the fade and the two cannot both try to remove one window.
                teardown(entry)
            }
        }
    }

    /**
     * The rubber-stamp moment on a NOTIFY decision.
     *
     * Until now the stamp existed only on the SECURE modal, so anyone running the default
     * `AUTOMATIC` mode never saw it — the "viral moment" was behind a mode most requests do not
     * reach. This puts the same overlay over the card's decision.
     *
     * A separate window, not a child view, for two reasons: the card is `WRAP_CONTENT` (a
     * `MATCH_PARENT` child would blow it up to full screen), and the stamp must survive the
     * card's 200ms fade-out to read as one gesture rather than two.
     *
     * `FLAG_NOT_TOUCHABLE` is mandatory: this window covers the whole screen for ~1s, and a
     * stamp that swallowed taps would make the app feel frozen for exactly the moment the user
     * is most likely to tap again.
     */
    private fun stampDecision(entry: Entry, approved: Boolean) {
        val ctx = entry.ctx
        // No scrim: the card stays visible underneath for the whole animation, so dimming the
        // screen would just make it go dark for a second.
        val overlay = com.mido.phonebridge.ui.StampOverlay(ctx, scrimAlpha = 0)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        // **THE STAMP MUST FINISH EXACTLY ONCE, AND MUST ALWAYS FINISH.**
        //
        // `onFinished` is driven by an animator listener, and the animation only starts from
        // `onSizeChanged` — so a stamp whose window is added but never laid out never starts,
        // never ends, and never calls back. That is not hypothetical: it is the same shape that
        // left cards frozen at "◌ summarising…" (Mido, 2026-11-03). An overlay is a full-screen
        // window, so a stamp that never finishes is worse than a card that does not.
        //
        // `finished` makes it idempotent in both directions: the animator calling back twice,
        // the deadline firing first, and a throw inside the animator all converge on one removal.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) {
                try {
                    wm(ctx).removeView(overlay)
                } catch (e: Exception) {
                    // "View not attached" means the platform already dropped it — the outcome we
                    // wanted. Anything else is reported, because it is the only clue that the
                    // overlay is still on screen.
                    if (e !is IllegalArgumentException) {
                        Log.e(TAG, "STAMP OVERLAY REMOVE FAILED: ${e.message}", e)
                    }
                }
                // The stamp is the exit. Only now does the card go away.
                teardown(entry)
            }
        }
        overlay.onFinished = { finish() }
        // The deadline, posted before the window is even added so no ordering can skip it.
        // Longer than the stamp's own ~740ms sequence, short enough that a stamp which failed to
        // start does not sit over the screen.
        main.postDelayed({
            if (!finished.get()) {
                Log.w(TAG, "stamp for id=${entry.id} never reported finished; forcing removal")
                finish()
            }
        }, STAMP_BACKSTOP_MS)
        try {
            wm(ctx).addView(overlay, lp)
            // Reported, not substituted: no beep, no silence-by-accident.
            try {
                com.mido.phonebridge.alert.SoundAlerts.stamp(ctx)
            } catch (e: com.mido.phonebridge.alert.SoundAlerts.SoundUnavailable) {
                Log.e(TAG, "STAMP SOUND FAILED: ${e.message}", e)
            }
            overlay.stamp(
                if (approved) com.mido.phonebridge.ui.StampOverlay.Verdict.APPROVED
                else com.mido.phonebridge.ui.StampOverlay.Verdict.DENIED
            )
        } catch (e: Exception) {
            // The decision is already recorded. A stamp that cannot be drawn must never fail the
            // request or leave the card stranded — so tear the card down now, since the normal
            // fade/backstop were skipped for this reason. Logged as an ERROR, not a warning: this
            // is the line that would have named the 1ms-flash bug immediately.
            Log.e(TAG, "STAMP OVERLAY FAILED: ${e.message}", e)
            teardown(entry)
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
     * The risk strip's colour. Display-only; it never changes what the card does.
     *
     * Delegates to [com.mido.phonebridge.ui.Theme.riskColor] so the card, the modal's risk
     * line and the modal's APPROVE button cannot drift apart. They previously had separate
     * palettes — the card used its own softer hues while the modal used the semantic ones —
     * which meant "HIGH" was literally two different reds depending on where you looked.
     */
    private fun riskColor(r: Risk): Int = com.mido.phonebridge.ui.Theme.riskColor(r)

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
    private val BG: Int get() = com.mido.phonebridge.ui.tokens.Colors.withAlpha(com.mido.phonebridge.ui.Theme.SURFACE, 0xF5)

    /** ~30% alpha: clearly visible as motion, still quiet as a colour. */
    private val ACCENT_FILL: Int get() = com.mido.phonebridge.ui.tokens.Colors.withAlpha(com.mido.phonebridge.ui.Theme.ON_SURFACE, 0x4D)
    private val TEXT: Int get() = com.mido.phonebridge.ui.Theme.ON_SURFACE
    private val MUTED: Int get() = com.mido.phonebridge.ui.Theme.ON_SURFACE_VARIANT

    // Badge palette. Light fills with near-black text, because the card is dark: a dark
    // badge on a dark card is unreadable, which defeats the point of colour-coding.
    private val VERB_READ: Int get() = com.mido.phonebridge.ui.Theme.SUCCESS_SURFACE
    private val VERB_WRITE: Int get() = com.mido.phonebridge.ui.Theme.WARNING_SURFACE
    private val VERB_DEL: Int get() = com.mido.phonebridge.ui.Theme.current().signalSurface
    private val BADGE_TEXT: Int get() = com.mido.phonebridge.ui.Theme.ON_SURFACE


    private const val CARD_WIDTH_DP = 216
    private const val CORNER_DP = 14
    private const val EDGE_DP = 14

    /**
     * Drag slop. Generous on purpose: a tap is now an APPROVAL, so a small slop would
     * let a pocket brush approve a request nobody read. Below this the gesture is a
     * click; above it, a drag.
     */
    private const val DRAG_SLOP_DP = 16
    private const val FADE_IN_MS = 180L
    private const val FADE_OUT_MS = 200L

    /**
     * A few frames of grace after the window reaches alpha 0, before the surface is
     * destroyed. ~3 frames at 60Hz: invisible time, but enough for the compositor to
     * latch the transparent buffer so the teardown cannot flash the opaque one.
     */
    private const val COMMIT_FRAMES_MS = 48L

    /** Extra time past the fetch cap before the backstop fires. Never the normal path. */
    private const val BACKSTOP_SLACK_MS = 2_000L

    /**
     * How long the stamp may hold the card before its removal is forced.
     *
     * The stamp's own sequence sums to ~1.1s (150 + 45 + 220 + 260 + 260 + 180ms, chained).
     * This is that plus a second of slack — long enough never to cut a working stamp short,
     * short enough that a stamp which failed to start still releases the card quickly.
     */
    private const val STAMP_BACKSTOP_MS = 2_200L

    /**
     * How long past its own budget the fetch may still be on screen before the tick ends
     * the card.
     *
     * Positive on purpose so a fetch that answers at exactly its budget is not killed by
     * frame scheduling — the summary should win a photo finish. Small on purpose too: this
     * is the window in which the human sees "summarising…" while nothing is coming.
     */
    private const val FETCH_DEADLINE_SLACK_MS = 1_500L

    /** ~30fps. A 5s countdown does not need more, and this is one small view. */
    private const val TICK_MS = 33L

    /** `ClipDrawable` level scale: 10000 is fully visible. */
    private const val LEVEL_FULL = 10000

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val VISIBLE = View.VISIBLE
    private const val GONE = View.GONE
}
