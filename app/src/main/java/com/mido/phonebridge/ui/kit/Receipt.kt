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
    private val heightPx = ctx.dp(HEIGHT_DP)
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
        clip.addView(slip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clip, LayoutParams(LayoutParams.MATCH_PARENT, heightPx))
        val slot = View(ctx).apply {
            background = GradientDrawable().apply { setColor(p.readout); cornerRadius = ctx.dpf(3f) }
        }
        addView(slot, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(6)).apply {
            leftMargin = -ctx.dp(10); rightMargin = -ctx.dp(10); gravity = Gravity.TOP
        })
        layoutParams = LayoutParams(
            context.resources.displayMetrics.widthPixels - ctx.dp(80), heightPx,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
        ).apply { bottomMargin = ctx.dp(30) }
        contentDescription = "Receipt $entryNo, $status"
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = true
        isFocusable = true
        setRole(this, "android.widget.Button")
        setOnClickListener { finish() }
        slip.translationY = -heightPx.toFloat()
    }

    /** Start the feed. Reduced motion shows the slip at once with a single tick. */
    fun feed() {
        cancel()
        finished = false
        if (Motion.reduced(context)) {
            slip.translationY = 0f
            Haptics.perform(this, HapticEvent.TAPE_STEP)
            postDelayed(done, Motion.RECEIPT_SHOW_MS)
            return
        }
        val stepPx = context.dp(30).toFloat()
        step = 0
        for (i in 1..Motion.TAPE_STEPS) {
            val r = Runnable {
                step = i
                slip.translationY = if (i == Motion.TAPE_STEPS) 0f else -heightPx + stepPx * i
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

    private companion object { const val HEIGHT_DP = 123 }
}
