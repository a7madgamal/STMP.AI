package com.mido.phonebridge.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import com.mido.phonebridge.debug.KitHostActivity
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/** Helpers for the launcher and notification icon screenshot tests. Icons do not depend on the theme or font scale. */
object IconScreenshotSupport {

    private const val PX_PER_DP = 4

    /** Light, 100 %: the one cell icons are recorded in. */
    fun recordOnce() {
        Variant(night = false, fontScale = 1f).apply()
    }

    fun drawable(id: Int) = checkNotNull(
        ResourcesCompat.getDrawable(RuntimeEnvironment.getApplication().resources, id, null)
    ).mutate()

    /** [dp] x [dp] square of [background], the drawable drawn into it (optionally tinted). */
    fun tile(id: Int, dp: Int, background: Int, tint: Int? = null, drawDp: Int = dp): Bitmap {
        val size = dp * PX_PER_DP
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(background)
        val d = drawable(id)
        if (tint != null) d.setTint(tint)
        val inset = (dp - drawDp) * PX_PER_DP / 2
        d.setBounds(inset, inset, size - inset, size - inset)
        d.draw(c)
        return bmp
    }

    /** Background colour, then the foreground layer, at 108 dp. */
    fun composed(bgColor: Int, fgId: Int): Bitmap = tile(fgId, 108, bgColor)

    /** The central 72 dp of [src] clipped to [shape] (a path in 72 dp * 4 px space). */
    fun masked(src: Bitmap, shape: (Float) -> Path): Bitmap {
        val visible = 72 * PX_PER_DP
        val off = (src.width - visible) / 2
        val out = Bitmap.createBitmap(visible, visible, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, Rect(off, off, off + visible, off + visible), Rect(0, 0, visible, visible), null)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        val maskBmp = Bitmap.createBitmap(visible, visible, Bitmap.Config.ARGB_8888)
        Canvas(maskBmp).drawPath(shape(visible.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG))
        c.drawBitmap(maskBmp, 0f, 0f, clear)
        return out
    }

    fun circle(size: Float) = Path().apply { addCircle(size / 2, size / 2, size / 2, Path.Direction.CW) }

    fun roundedSquare(size: Float) = Path().apply {
        addRoundRect(RectF(0f, 0f, size, size), 24f * PX_PER_DP, 24f * PX_PER_DP, Path.Direction.CW)
    }

    /** A superellipse (exponent 4): the squircle some launchers use. */
    fun squircle(size: Float) = Path().apply {
        val r = size / 2
        val steps = 360
        for (i in 0..steps) {
            val t = Math.toRadians(i * 360.0 / steps)
            val cs = Math.cos(t); val sn = Math.sin(t)
            val x = r + r * sign(cs).toFloat() * abs(cs).pow(0.5).toFloat()
            val y = r + r * sign(sn).toFloat() * abs(sn).pow(0.5).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** The 66 dp safe-zone circle, drawn in tests only. */
    fun withSafeZone(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFFF00FF.toInt()
        }
        Canvas(out).drawCircle(out.width / 2f, out.height / 2f, 33f * PX_PER_DP, p)
        return out
    }

    /** Hosts [bmp] at its pixel size (as dp-less pixels) and captures it. */
    fun shoot(bmp: Bitmap, screen: String, state: String) {
        val a = Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
        val iv = ImageView(a).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        a.column.addView(iv, android.widget.LinearLayout.LayoutParams(bmp.width, bmp.height))
        Shots.view(iv as View, screen, state)
    }
}
