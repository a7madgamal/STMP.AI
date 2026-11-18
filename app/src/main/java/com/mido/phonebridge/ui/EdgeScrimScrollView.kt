package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView

/**
 * A horizontally scrolling child with two affordances telling the human there is more to see.
 *
 * **Why both.** A scrollbar is the platform's answer, but Android fades it out after a moment, so
 * a command that scrolled off the right edge looks exactly like a command that ends there — which
 * is a security problem, not a polish one: the part nobody scrolled to is the part nobody approved.
 * A shadow at the edge is the affordance that survives: it is painted on the scroller itself, so it
 * is present while there is content to reach and gone when there is not.
 *
 * **The scrim follows SCROLL POSITION, not existence.** At the left end there is nothing behind you,
 * so no left shadow; past the first pixel, the left shadow fades in. Same on the right, inverted.
 * A permanent pair of shadows would claim hidden content on both sides always, which is the same
 * lie in the other direction.
 *
 * **It is a `FrameLayout` around the scroller, not a custom `HorizontalScrollView`.** Overriding
 * `dispatchDraw` on the scroller would draw the scrim UNDER its children on some versions; a parent
 * drawing after `super` is the ordering that is stable across API levels, and it keeps the touch
 * handling entirely inside the platform widget.
 */
class EdgeScrimScrollView(
    context: Context,
    private val content: View,
    /**
     * The ink the shadow is drawn in.
     *
     * **A shadow DARKENS; it does not fade to the background.** The first version ramped from the
     * surrounding paper colour to transparent, which is a gradient from paper to paper — invisible
     * on the device, and invisible for the right reason: nothing about it changes a pixel enough to
     * read as depth. A shadow is a translucent ink that thins out, and on this app's paper that is
     * the same material every card already uses for its own shadow.
     */
    private val shadowColor: Int,
    /** How far the shadow reaches in from the edge. */
    private val scrimWidthPx: Float,
) : FrameLayout(context) {

    private val scroller: HorizontalScrollView
    private val scrim: Scrim

    /**
     * The scrollbar is asked to stay visible.
     *
     * `setHorizontalScrollBarEnabled(true)` plus `isHorizontalScrollBarEnabled` is not enough on
     * its own — the platform's `ViewConfiguration` decides when to fade it — so the bar's own
     * `scrollBarDefaultDelayBeforeFade` is pushed out. A bar that vanishes after two seconds is a
     * bar that is absent for every second a human is actually reading the command.
     */
    init {
        scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            // Long enough to outlast any reading pass, and refreshed on every touch by the
            // platform. This is a duration, not a behaviour switch: the bar is drawn by the
            // scroller itself, so nothing here can disagree with where the scroller really is.
            scrollBarDefaultDelayBeforeFade = Int.MAX_VALUE
            scrollBarFadeDuration = 0
            addView(
                content,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        // The scrim exists before the listener can fire, so it is constructed here rather than as a
        // property initialiser — a `lateinit`-shaped ordering bug in a file that is otherwise
        // straight-line.
        scrim = Scrim()
        scroller.setOnScrollChangeListener { _, scrollX, _, _, _ -> scrim.onScrolled(scrollX) }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The scrim sits above the content, and must not eat the drag that scrolls it.
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrim.isClickable = false
        scrim.isFocusable = false
    }

    /**
     * Recompute the scrims AFTER the children have been laid out.
     *
     * **This is the hook the first version was missing.** The scrim recalculated on its own size
     * change, which happens before the scroller's content has been measured — every width it read
     * was 0, both alphas stayed 0, and no scroll event ever fires on a view nobody has scrolled.
     * The result was an affordance that existed in code and never appeared on a phone. Laying the
     * parent out is the first moment all three widths are real.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        scrim.refresh()
    }

    /**
     * A view that paints a horizontal fade at whichever edge has content behind it.
     *
     * Kept as a child (rather than painted in the parent's `dispatchDraw`) so it is clipped to the
     * parent's bounds automatically and so its own alpha — the thing that fades with scroll —
     * cannot affect the content underneath.
     */
    private inner class Scrim : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var leftAlpha = 0
        private var rightAlpha = 0
        private var lastScrollX = 0

        init {
            // A View with no background still receives no touches unless it declines them, and the
            // default is to be transparent to touch — stated here so a later `setBackground` on
            // this view cannot silently start swallowing the scroller's drags.
            isClickable = false
            isFocusable = false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false

        fun onScrolled(scrollX: Int) {
            lastScrollX = scrollX
            recalculate()
        }

        /** Recompute from current geometry. Called after layout and on every scroll. */
        fun refresh() = recalculate()

        private fun recalculate() {
            val width = width
            if (width <= 0) return
            val contentWidth = scroller.getChildAt(0)?.width ?: 0
            val maxScroll = (contentWidth - width).coerceAtLeast(0)
            // Nothing to reach in either direction: no shadows, and the bar is the only affordance
            // — which is correct, because there is nothing hidden.
            if (maxScroll <= 0) {
                leftAlpha = 0
                rightAlpha = 0
            } else {
                // Ramp over a short distance rather than popping: the scrim's job is to appear as
                // soon as anything is hidden, and a hard cut at 1px reads as a glitch.
                leftAlpha = (lastScrollX.toFloat() / SCRIM_RAMP_PX * 255).toInt().coerceIn(0, 255)
                val fromEnd = maxScroll - lastScrollX
                rightAlpha = (fromEnd.toFloat() / SCRIM_RAMP_PX * 255).toInt().coerceIn(0, 255)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val band = scrimWidthPx.coerceAtMost(w / 3f)

            // Darkest AT the edge, thinning inward: that is the direction a shadow cast by content
            // sliding under the edge actually falls, and it means the fainter end sits over the
            // characters a human still needs to read.
            if (leftAlpha > 0) {
                paint.shader = LinearGradient(
                    0f, 0f, band, 0f,
                    withAlpha(shadowColor, scale(leftAlpha)), withAlpha(shadowColor, 0),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, band, h, paint)
            }
            if (rightAlpha > 0) {
                paint.shader = LinearGradient(
                    w, 0f, w - band, 0f,
                    withAlpha(shadowColor, scale(rightAlpha)), withAlpha(shadowColor, 0),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(w - band, 0f, w, h, paint)
            }
            paint.shader = null
        }

        /**
         * The 0..255 scroll ramp scaled into the shadow's own maximum opacity.
         *
         * The ramp says "how far past the edge are we"; the ceiling says "how strong may a shadow
         * be at all". Multiplying keeps them separate, so tuning one cannot silently change the
         * other — and the ceiling is deliberately low: this is a hint, not a vignette.
         */
        private fun scale(ramp: Int): Int = (ramp * MAX_SHADOW_ALPHA / 255f).toInt()

        /** [color] with its alpha replaced, so the ramp is the only thing that varies. */
        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }

    private companion object {
        /** How much scrolling turns a scrim from invisible to its full strength. */
        const val SCRIM_RAMP_PX = 24f

        /**
         * Ceiling for the shadow's opacity, out of 255.
         *
         * Around a quarter: strong enough to read as an edge on paper, weak enough that the
         * characters underneath stay legible. A heavier shadow would hide the very text it is
         * pointing at, which is the one thing a "there is more" hint must not do.
         */
        const val MAX_SHADOW_ALPHA = 64
    }
}
