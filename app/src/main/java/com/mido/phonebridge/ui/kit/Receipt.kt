package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Motion
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The slip that feeds out of a slot after the punch (COMPONENTS 19.2, D16): four steps 60 ms apart,
 * 1.5 s on screen, a tap closes it early. Timed on the main looper's clock, not an animator, so it
 * also runs in an overlay window.
 */
class Receipt(
    ctx: Context,
    lines: List<String>,
    private val entryNo: String,
    private val status: String,
) : FrameLayout(ctx) {

    var onDone: (() -> Unit)? = null

    private val slip = LinearLayout(ctx)
    private var isFeeding = false
    private var step = 0
    private var finished = false
    private var stepRunnables = ArrayList<Runnable>()
    private val done = Runnable { finish() }

    /** The slip's current offset above rest, in px (0 once fed). */
    internal val slipTranslation: Float get() = slip.translationY

    init {
        val p = Theme.palette(ctx)
        clipChildren = false
        clipToPadding = false
        slip.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            background = GradientDrawable().apply { setColor(p.tapePaper) }
            lines.forEach { line ->
                addView(Type.text(ctx, line, TypeRole.READOUT, p.ink).apply {
                    val px = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, 19f, resources.displayMetrics,
                    )
                    TextViewCompat.setLineHeight(this, px.toInt())
                    maxLines = Int.MAX_VALUE
                })
            }
        }
        // The slip sits inside a clip so it appears to come out of the slot.
        val clip = FrameLayout(ctx).apply { clipChildren = true; clipToPadding = true }
        // The slip is as tall as its lines need (at 1.35 text it is taller than at 1.0), so it is
        // never clipped and never padded with empty paper.
        clip.addView(slip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(clip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val slot = View(ctx).apply {
            background = GradientDrawable().apply { setColor(p.readout); cornerRadius = ctx.dpf(3f) }
        }
        addView(slot, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(6)).apply {
            leftMargin = -ctx.dp(10); rightMargin = -ctx.dp(10); gravity = Gravity.TOP
        })
        // Below the punch and above the keys, so the slip never covers them (R1).
        layoutParams = LayoutParams(
            context.resources.displayMetrics.widthPixels - ctx.dp(80), LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP,
        ).apply { topMargin = (context.resources.displayMetrics.heightPixels * SLIP_TOP_FRACTION).toInt() }
        contentDescription = "Receipt $entryNo, $status"
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = true
        isFocusable = true
        setRole(this, "android.widget.Button")
        setOnClickListener { finish() }
        // Hidden in its slot until the feed starts; set again once the real height is known.
        slip.translationY = -ctx.dp(HIDDEN_BEFORE_LAYOUT_DP).toFloat()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!isFeeding && slip.height > 0) slip.translationY = -slip.height.toFloat()
    }

    /** Start the feed. Reduced motion shows the slip at once with a single tick. */
    fun feed() {
        cancel()
        finished = false
        isFeeding = true
        if (Motion.reduced(context)) {
            slip.translationY = 0f
            Haptics.perform(this, HapticEvent.TAPE_STEP)
            postDelayed(done, Motion.RECEIPT_SHOW_MS)
            return
        }
        step = 0
        for (i in 1..Motion.TAPE_STEPS) {
            val r = Runnable {
                step = i
                // Equal steps of the slip's real height, so a taller slip still feeds out completely.
                val slipHeight = maxOf(slip.height, slip.measuredHeight).toFloat()
                slip.translationY = if (i == Motion.TAPE_STEPS) 0f else -slipHeight + slipHeight / Motion.TAPE_STEPS * i
                Haptics.perform(this, HapticEvent.TAPE_STEP)
            }
            stepRunnables.add(r)
            postDelayed(r, Motion.TAPE_STEP_MS * i)
        }
        postDelayed(done, Motion.TAPE_STEP_MS * Motion.TAPE_STEPS + Motion.RECEIPT_SHOW_MS)
    }

    private fun cancel() {
        stepRunnables.forEach { removeCallbacks(it) }
        stepRunnables.clear()
        removeCallbacks(done)
    }

    private fun finish() {
        if (finished) return
        finished = true
        cancel()
        onDone?.invoke()
    }

    /** Steps taken so far (0..TAPE_STEPS). */
    internal val stepsDone: Int get() = step

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancel()
    }

    private companion object {
        /** Before the first layout the slip height is unknown; any value that hides the slip will do. */
        const val HIDDEN_BEFORE_LAYOUT_DP = 400

        /** The slip's top edge, as a share of the screen height. */
        const val SLIP_TOP_FRACTION = 0.56f
    }
}
