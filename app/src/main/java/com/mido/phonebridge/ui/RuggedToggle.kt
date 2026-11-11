package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import android.widget.Switch

/**
 * A rugged, grained, rectangular on/off switch.
 *
 * **It draws the app's card, not an imitation of it.** Mido, 2026-11-04: *"the toggles are still not
 * matching the look and feel of the cards. I want same border color and width, same params for
 * wrinkle effect, same grain effect"*.
 *
 * That is not a request for closer numbers — it is a request to stop having two implementations.
 * Both shapes here are `Theme.tornCard(...)` drawables. So the border colour, the stroke width, the
 * nine-sample wrinkle pattern and the grain are not "matched": they are the same values, because it
 * is the same drawable that every card on every screen uses. If the card edge changes, this changes
 * with it, and there is no second copy to forget.
 *
 * **State, not action.** The knob's POSITION is the answer: left is off, right is on. There is no
 * "TURN OFF" label to recompute, so the control cannot go stale and claim the wrong thing.
 *
 * **Flat.** No gradient, no drop shadow, no highlight. The texture and the torn edge carry it.
 */
class RuggedToggle(context: Context) : View(context) {
    private var accessibilityLabel: CharSequence = "Switch"

    // --- geometry, in dp, resolved once ---

    private val trackHeightPx = dp(25)
    private val trackWidthPx = dp(45)
    private val insetPx = dp(2.8f)

    /** The knob is square-ish on purpose: a circle would read as a Material thumb. */
    private val knobSizePx = trackHeightPx - insetPx * 2

    /** How far the knob travels between its two resting places. */
    private val travelPx = trackWidthPx - knobSizePx - insetPx * 2

    private val rect = RectF()
    private val knobRect = RectF()

    /**
     * The track: the card drawable at the control's own size.
     *
     * Rebuilt only if the radius changes, which it cannot at runtime — so this is built once.
     * `tearDp` is 2 rather than the card's 6 because the track is 31dp tall: a 6dp tear would eat a
     * fifth of the shape. The PATTERN is shared; only the depth is proportioned.
     */
    /**
     * The track and knob wear the SAME material as every button.
     *
     * Mido, 2026-11-04: *"or even better match the buttons, so that all inputs have the same look
     * and feel"*.
     *
     * Buttons do not use `tornCard`. They use `backgroundTintList` for fill plus a FOREGROUND
     * `LayerDrawable` holding grain and a `SquiggleBorder` at `SQUIGGLE_STROKE_DP` (1.2dp) with
     * alpha 0x55. The toggle had been drawing the CARD material instead — 1.5dp at alpha 0x2E,
     * which is less than half the ink. That is why it read as "toooo light" no matter how the tear
     * was tuned: it was the wrong material entirely, not the wrong number.
     *
     * So the outline is built the same way here, and a switch and a button now differ only in
     * shape and behaviour rather than in material.
     */
    private fun surfaceDrawable(fillColor: Int, radiusDp: Int): Drawable {
        val radius = Theme.dpF(context, radiusDp.toFloat())
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
        }
        // Tint-equivalent background, then grain, then the wobbling edge — the same three layers,
        // in the same order, as `Theme.button`'s foreground.
        return android.graphics.drawable.LayerDrawable(
            arrayOf(
                bg,
                GrainOverlay(radius),
                SquiggleBorder(
                    color = Theme.ON_SURFACE and 0x00FFFFFF or (0x55 shl 24),
                    strokePx = Theme.dpF(context, Theme.SQUIGGLE_STROKE_DP),
                    radiusPx = radius,
                    amplitudePx = Theme.dpF(context, Theme.SQUIGGLE_DP),
                ),
            )
        )
    }

    private val track: Drawable = surfaceDrawable(Theme.SURFACE, 3)

    /**
     * The knob, rebuilt only when its colour changes.
     *
     * Cards rebuild their drawable on a colour change too, so this is the same cost profile — and
     * caching by colour means a repaint that changed nothing reuses the existing instance.
     */
    private var knob: Drawable? = null
    private var knobColorKey: Int = 0

    /** 0f = left (off), 1f = right (on). */
    private var fraction = 0f
    private var animator: android.animation.ValueAnimator? = null

    var checked: Boolean = false
        private set

    /**
     * Draw the knob as "working on it" without moving it.
     *
     * The server takes a moment to bind, and a knob that slides then slides back would be showing
     * intent rather than truth.
     */
    var pending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshAccessibilityState()
            knob = null
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        // A minimum touch target larger than the drawing: 31dp of paint is easy to see and hard to
        // hit, and a switch that misses is worse than one that looks slightly small.
        minimumWidth = Theme.dp(context, 64)
        minimumHeight = Theme.dp(context, 48)
        contentDescription = accessibilityLabel
        refreshAccessibilityState()
    }

    /** A stable subject, not a second source of checked-state truth. */
    fun setAccessibilityLabel(label: CharSequence) {
        accessibilityLabel = label
        refreshAccessibilityState()
    }

    private fun refreshAccessibilityState() {
        val state = when {
            pending -> "changing"
            checked -> "on"
            else -> "off"
        }
        contentDescription = "$accessibilityLabel, $state"
    }

    private fun announceState() {
        val state = if (checked) "on" else "off"
        announceForAccessibility("$accessibilityLabel $state")
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** Set the state WITHOUT animating. Used on first render, where motion would be a lie. */
    fun setCheckedImmediately(value: Boolean) {
        val changed = checked != value
        animator?.cancel()
        animator = null
        checked = value
        fraction = if (value) 1f else 0f
        refreshAccessibilityState()
        knob = null
        invalidate()
        if (changed) announceState()
    }

    /** Set the state and animate to it. Used after a real change. */
    fun setCheckedAnimated(value: Boolean) {
        if (checked == value && animator == null) {
            setCheckedImmediately(value)
            return
        }
        checked = value
        refreshAccessibilityState()
        knob = null
        val from = fraction
        val to = if (value) 1f else 0f
        if (from == to) {
            invalidate()
            return
        }
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A running animator holds the View through its listener, and this app has already paid
        // for a stray callback outliving the thing it painted.
        animator?.cancel()
        animator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(Theme.dp(context, 64), widthMeasureSpec),
            resolveSize(Theme.dp(context, 48), heightMeasureSpec),
        )
    }

    /**
     * Dim when disabled.
     *
     * A switch that ignores taps while looking identical to a live one is the worst of both:
     * it reads as responsive and then does nothing. The reason it is disabled is stated by the
     * screen beside it — see the fingerprint row on Home.
     */
    override fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Centred in the touch target, so the drawing stays small while the hit area stays
        // generous.
        val left = (width - trackWidthPx) / 2f
        val top = (height - trackHeightPx) / 2f
        rect.set(left, top, left + trackWidthPx, top + trackHeightPx)

        // Alpha, not a different colour: the shape and texture stay recognisable, so a disabled
        // switch still reads as the same control rather than as a grey placeholder.
        val dim = if (isEnabled) 255 else 90
        track.setBounds(
            rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()
        )
        track.setAlpha(dim)
        track.draw(canvas)

        val knobLeft = rect.left + insetPx + travelPx * fraction
        val knobTop = rect.top + insetPx
        knobRect.set(knobLeft, knobTop, knobLeft + knobSizePx, knobTop + knobSizePx)

        val color = when {
            pending -> withAlpha(Theme.WARNING, 0xB0)
            checked -> Theme.SUCCESS
            else -> Theme.ON_SURFACE
        }
        // Rebuilt only on an actual colour change, so a repaint that changed nothing reuses it.
        val current = knob.let { if (it != null && knobColorKey == color) it else null }
        val drawable = current ?: surfaceDrawable(color, 2).also {
            knob = it
            knobColorKey = color
        }

        drawable.setBounds(
            knobRect.left.toInt(), knobRect.top.toInt(),
            knobRect.right.toInt(), knobRect.bottom.toInt(),
        )
        drawable.setAlpha(dim)
        drawable.draw(canvas)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE
        ) {
            return performClick()
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val compat = AccessibilityNodeInfoCompat.wrap(info)
        compat.className = Switch::class.java.name
        compat.isCheckable = true
        compat.isChecked = checked
        compat.text = accessibilityLabel
        compat.contentDescription = contentDescription
        compat.isEnabled = isEnabled
        compat.stateDescription = when {
            !isEnabled -> "Unavailable"
            pending -> "Changing"
            checked -> "On"
            else -> "Off"
        }
        compat.addAction(AccessibilityActionCompat(
            AccessibilityNodeInfo.ACTION_CLICK,
            if (checked) "Turn off" else "Turn on",
        ))
    }

    override fun onPopulateAccessibilityEvent(event: AccessibilityEvent) {
        super.onPopulateAccessibilityEvent(event)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            event.text.add(contentDescription)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(v: Float): Float = Theme.dpF(context, v)
    private fun dp(v: Int): Float = Theme.dpF(context, v.toFloat())
}
