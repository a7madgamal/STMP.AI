package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.widget.NestedScrollView

/** A scroll view that never grows past [maxHeightPx] and shows a fade while there is more below. */
class MaxHeightScrollView(ctx: Context) : NestedScrollView(ctx) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) { field = value; requestLayout() }

    /** Colour the bottom fade starts from (the readout fill). */
    var fadeColor: Int = Color.TRANSPARENT

    private val fade = GradientDrawable()

    init {
        isNestedScrollingEnabled = true
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = if (maxHeightPx == Int.MAX_VALUE) heightMeasureSpec else {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
            MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }

    /** True while more content lies below the visible part. */
    val hasMoreBelow: Boolean get() = canScrollVertically(1)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!hasMoreBelow) return
        val h = (16 * resources.displayMetrics.density).toInt()
        fade.orientation = GradientDrawable.Orientation.BOTTOM_TOP
        fade.colors = intArrayOf(fadeColor, Color.TRANSPARENT)
        fade.setBounds(scrollX, scrollY + height - h, scrollX + width, scrollY + height)
        fade.draw(canvas)
    }
}
