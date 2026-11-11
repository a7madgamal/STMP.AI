package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Colors
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The auto-deny countdown (COMPONENTS 20): one tick per second, the last 10 in signal with a
 * haptic each. Clock-driven from [deadlineUptimeMs]; no animator.
 */
class CountdownTicks(ctx: Context, val totalSeconds: Int) : LinearLayout(ctx) {

    /** False before the misclick guard has elapsed: every tick is spent and [unarmedText] shows. */
    var armed: Boolean = true
        set(value) { field = value; refresh() }

    /** `SystemClock.uptimeMillis()` at which the request is denied on its own. */
    var deadlineUptimeMs: Long = 0L
        set(value) { field = value; lastSeconds = -1; refresh() }

    var trailingText: String? = null
        set(value) { field = value; end.text = value.orEmpty(); end.visibility = if (value == null) View.GONE else View.VISIBLE }

    /** Text while [armed] is false; null means "ready in 1 s". */
    var unarmedText: String? = null
        set(value) { field = value; refresh() }

    private val p = Theme.palette(ctx)
    private val bar = Bar(ctx)
    private val start: TextView = Type.text(ctx, "", TypeRole.READOUT, p.etched)
    private val end: TextView = Type.text(ctx, "", TypeRole.READOUT, p.etched).apply { visibility = View.GONE; gravity = Gravity.END }
    private var lastSeconds = -1
    private val ticker = Runnable { refresh() }

    /** Whole seconds left at [now], clamped to 0..[totalSeconds]. */
    internal fun secondsLeft(now: Long = SystemClock.uptimeMillis()): Int {
        val rem = deadlineUptimeMs - now
        if (rem <= 0) return 0
        return minOf(totalSeconds, ((rem + 999) / 1000).toInt())
    }

    /** Segments currently lit. */
    val litSegments: Int get() = if (armed) secondsLeft() else 0

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        bar.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(Dimens.TICKS_H)))
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        row.addView(start, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(end, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(Dimens.SPACE_XS) })
        refresh()
    }

    /** Recompute text, haptics and the next wake-up from the clock. Safe to call at any time. */
    internal fun refresh(now: Long = SystemClock.uptimeMillis()) {
        removeCallbacks(ticker)
        if (!armed) {
            start.text = unarmedText ?: "ready in 1 s"
            bar.invalidate()
            return
        }
        val s = secondsLeft(now)
        start.text = "auto-deny in ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        if (s != lastSeconds) {
            if (lastSeconds != -1) {
                if (s in 1..10) Haptics.perform(this, HapticEvent.COUNTDOWN_TICK)
                if (s == 30 || s == 10 || s == 5) announceForAccessibility("Auto-deny in $s seconds")
            }
            lastSeconds = s
        }
        bar.invalidate()
        if (s > 0 && isAttachedToWindow) {
            val remaining = deadlineUptimeMs - now
            postDelayed(ticker, maxOf(1L, remaining - (s - 1) * 1000L))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }

    private inner class Bar(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val gap = context.dpf(Dimens.TICK_GAP_DP)
            val n = totalSeconds
            val w = (width - gap * (n - 1)) / n
            val left = litSegments
            val last10 = armed && secondsLeft() <= 10
            val spent = Colors.withAlpha(p.ink, 0x26)
            val corner = context.dpf(1f)
            for (i in 0 until n) {
                paint.color = when {
                    i >= left -> spent
                    last10 -> p.signalGlyph
                    else -> p.ink
                }
                val x = i * (w + gap)
                r.set(x, 0f, x + w, height.toFloat())
                canvas.drawRoundRect(r, corner, corner, paint)
            }
        }
    }
}
