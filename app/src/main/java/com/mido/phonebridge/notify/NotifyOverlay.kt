package com.mido.phonebridge.notify

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The `NOTIFY` prompt: a small stacked overlay toast, drawn with the
 * `SYSTEM_ALERT_WINDOW` permission this app already holds.
 *
 * Deliberately NOT a notification. A heads-up notification is a system-owned
 * surface — it hijacks the shade, collides with DND and other apps, and is
 * suppressed while the phone is in use. An overlay we own is smaller, quieter, and
 * appears above whatever the human is actually looking at, which is the entire
 * point of a mode called `notify`.
 *
 * Each item is self-contained: its own text, its own countdown, its own `✕`. Items
 * do NOT queue behind each other. Serialising them would start the last item's clock
 * long after its request arrived, and "silence means approve" would then approve
 * something the human never had a fair chance to see.
 *
 * Every outcome is reported exactly once, on the main thread. The four results are
 * kept distinct because the audit distinguishes them: a tap is a decision, an expiry
 * is the absence of one, and screen-off mid-window is neither.
 */
object NotifyOverlay {

    /** Items allowed on screen at once. Beyond this the oldest is evicted (denied). */
    private const val MAX_ITEMS = 4

    private val main = Handler(Looper.getMainLooper())

    /** Insertion order, oldest first. Only used to enforce [MAX_ITEMS] and stacking. */
    private val live = ArrayList<Item>()

    /** Why an item ended. Mapped to audit outcomes by the caller, not here. */
    enum class Reason { TAPPED, EXPIRED, UNATTENDED, EVICTED, UNAVAILABLE }

    private class Item(
        val id: Long,
        val root: LinearLayout,
        val params: WindowManager.LayoutParams
    ) {
        /** True once a result has been reported. Guards the tap-versus-timer race. */
        var settled = false

        /** Assigned immediately after construction; see [show]. */
        lateinit var settle: (Reason) -> Unit
    }

    /** What actually happened to one shown item. */
    class Outcome(
        val reason: Reason,
        /** When the item actually became visible, or 0 if it never did. */
        val shownAt: Long,
        val decidedAt: Long,
        val detail: String = ""
    )

    /**
     * Show one item, then report exactly one [Outcome].
     *
     * [onOutcome] runs once, on the main thread. Safe to call from any thread: a
     * server thread is the normal caller, and it is blocked while this is on screen.
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
            val app = ctx.applicationContext
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = app.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            // --- the pill: dark, small, low contrast. A glance, not a dialog. ---
            val root = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(BG)
                    cornerRadius = dp(9).toFloat()
                }
                // Not clickable as a whole: every tap that is not the ✕ must fall
                // through to the app underneath, or this becomes a 5s input trap.
                isClickable = false
            }

            val row = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(6), dp(6))
            }

            // Named `summaryView`, not `text`: a local called `text` shadows the
            // TextView `text` property inside the `apply` blocks below, and the
            // assignment silently targets the local instead. That is a compile error
            // here, but the same shadowing compiles fine elsewhere.
            val summaryView = TextView(app).apply {
                text = summary
                textSize = 11f
                setTextColor(TEXT)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL))
            }
            row.addView(
                summaryView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            // Tiny ✕, 28dp box so the touch target is real without looking like a
            // button — a visible button here would pull the eye off the text.
            val close = TextView(app).apply {
                text = "✕"
                textSize = 12f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = "Reject: $label"
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            row.addView(close, LinearLayout.LayoutParams(dp(28), dp(28)))

            root.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            // Countdown: a 2dp line that drains. scaleX with pivotX 0 is the cheap
            // way to animate width without relayout on every frame, so the animation
            // cannot stutter and drift away from the timer it represents.
            val bar = View(app).apply {
                setBackgroundColor(ACCENT)
                pivotX = 0f
            }
            root.addView(
                bar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2))
            )

            val params = WindowManager.LayoutParams(
                dp(240),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE: never takes focus or the keyboard.
                // NOT_TOUCH_MODAL: taps outside still reach the app below.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            }

            val item = Item(id, root, params)

            // Stamped when the view is actually attached, not when this function was
            // called: `main.post` may run late behind other work on the UI thread, and
            // a `shownAt` taken earlier would inflate every recorded duration by
            // exactly that delay. The window starts at the same instant the human
            // could first see the pill.
            var shownAt = 0L

            // The settle lambda closes over `item` itself, so it can guard its own
            // double-fire. No placeholder object, no throwaway "already settled" flag.
            item.settle = { reason ->
                if (!item.settled) {
                    item.settled = true
                    remove(wm, item)
                    onOutcome(Outcome(reason, shownAt, System.currentTimeMillis()))
                }
            }

            close.setOnClickListener { item.settle(Reason.TAPPED) }

            try {
                wm.addView(root, params)
                shownAt = System.currentTimeMillis()
            } catch (e: Exception) {
                // Permission revoked, or the window service refused the overlay. Report
                // it and let the caller deny. Never approve because we failed to show
                // something: silence is only consent if it was actually seen.
                item.settled = true
                onOutcome(
                    Outcome(
                        Reason.UNAVAILABLE,
                        shownAt = 0L,
                        System.currentTimeMillis(),
                        e.message ?: "overlay unavailable"
                    )
                )
                return@post
            }

            live.add(item)
            while (live.size > MAX_ITEMS) {
                // Eviction is a deny, never an approve: an item the human never got to
                // see must not become consent. It is also the visible symptom of too
                // much traffic, which the log will show as DENIED_UNATTENDED.
                val oldest = live.first()
                if (oldest === item) break
                oldest.settle(Reason.EVICTED)
            }
            stack(wm)

            // Fade plus a small rise: long enough to register as "something appeared",
            // short enough not to eat a meaningful part of the window.
            root.alpha = 0f
            root.translationY = -dp(8).toFloat()
            root.animate().alpha(1f).translationY(0f).setDuration(180).start()

            // The bar and the clock end at the same instant, so what the human sees
            // and what the gate decides cannot disagree.
            bar.scaleX = 1f
            bar.animate()
                .scaleX(0f)
                .setDuration(windowMs)
                .setInterpolator(DecelerateInterpolator())
                .start()

            main.postDelayed({
                if (!item.settled) {
                    // Re-checked at APPROVE time, not just at post time: a phone that
                    // went into a pocket mid-window denies rather than approves, because
                    // the human could not have read the text.
                    item.settle(
                        if (com.mido.phonebridge.policy.isAttended(app)) Reason.EXPIRED
                        else Reason.UNATTENDED
                    )
                }
            }, windowMs)
        }
    }

    /** Remove one item with a fade. Safe to call twice; the second call is a no-op. */
    private fun remove(wm: WindowManager, item: Item) {
        if (!live.remove(item)) return
        item.root.animate()
            .alpha(0f)
            .translationY((-6).toFloat())
            .setDuration(200)
            .withEndAction {
                runCatching { wm.removeView(item.root) }
                stack(wm)
            }
            .start()
    }

    /** Stack live items downward in insertion order, top item first. */
    private fun stack(wm: WindowManager) {
        if (live.isEmpty()) return
        val density = live.first().root.context.resources.displayMetrics.density
        val top = (48 * density).toInt()
        val step = (34 * density).toInt()
        for ((index, item) in live.withIndex()) {
            item.params.y = top + index * step
            runCatching { wm.updateViewLayout(item.root, item.params) }
        }
    }

    /** Remove everything and settle nothing. Called when the service stops. */
    fun dismissAll() {
        main.post {
            for (item in ArrayList(live)) {
                // Marked settled so the pending timer does not fire a result into a
                // dead overlay. The request itself is resolved by the gate's own
                // shutdown path, not by this cleanup.
                item.settled = true
                item.root.animate().cancel()
                val wm = item.root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                runCatching { wm.removeView(item.root) }
            }
            live.clear()
        }
    }

    // Muted on purpose: this must never look like an alarm. The ✕ and the bar carry
    // the interaction; the text carries the fact.
    private const val BG = 0xE6242528.toInt()
    private const val TEXT = 0xFFECEDEE.toInt()
    private const val MUTED = 0xFF9AA0A6.toInt()
    private const val ACCENT = 0xFF8AB4F8.toInt()
}
