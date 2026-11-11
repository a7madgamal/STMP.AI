package com.mido.phonebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** One restrained outline family for all three top-level destinations. */
class TabIcon(context: Context, private val tab: Theme.Tab) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Theme.dpF(context, 1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    var isActive = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        ink.color = if (isActive) Theme.ACCENT else Theme.ON_SURFACE_VARIANT
        val w = width.toFloat(); val h = height.toFloat()
        val left = w * .16f; val right = w * .84f
        val top = h * .12f; val bottom = h * .86f
        path.reset()
        when (tab) {
            Theme.Tab.HOME -> {
                path.moveTo(left, h * .47f); path.lineTo(w / 2f, top); path.lineTo(right, h * .47f)
                path.moveTo(left + w * .08f, h * .42f); path.lineTo(left + w * .08f, bottom)
                path.lineTo(right - w * .08f, bottom); path.lineTo(right - w * .08f, h * .42f)
                path.moveTo(w * .42f, bottom); path.lineTo(w * .42f, h * .60f)
                path.lineTo(w * .60f, h * .60f); path.lineTo(w * .60f, bottom)
            }
            Theme.Tab.ACTIVITY -> {
                for (index in 0..2) {
                    val y = top + index * (bottom - top) / 2f
                    path.moveTo(left, y); path.lineTo(left, y)
                    path.moveTo(left + w * .18f, y); path.lineTo(right, y)
                }
            }
            Theme.Tab.PREFERENCES -> {
                for (index in 0..2) {
                    val y = top + index * (bottom - top) / 2f
                    path.moveTo(left, y); path.lineTo(right, y)
                    val x = if (index == 1) w * .37f else w * .64f
                    path.moveTo(x, y - h * .09f); path.lineTo(x, y + h * .09f)
                }
            }
        }
        canvas.drawPath(path, ink)
        if (tab == Theme.Tab.ACTIVITY) {
            val old = ink.style; ink.style = Paint.Style.FILL
            for (index in 0..2) canvas.drawCircle(left, top + index * (bottom - top) / 2f, w * .045f, ink)
            ink.style = old
        }
    }
}
