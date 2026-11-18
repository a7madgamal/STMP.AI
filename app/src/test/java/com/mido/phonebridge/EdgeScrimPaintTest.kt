package com.mido.phonebridge

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.mido.phonebridge.ui.EdgeScrimScrollView
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Does the edge shadow actually appear, and only where it should? */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeScrimPaintTest {

    private fun build(text: String): EdgeScrimScrollView {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val tv = TextView(a).apply {
            this.text = text
            typeface = Theme.monoFont(a)
            setTextColor(Theme.ON_SURFACE)
            setBackgroundColor(Theme.BG)
        }
        val v = EdgeScrimScrollView(a, tv, Theme.ON_SURFACE, Theme.dpF(a, 20f))
        v.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun pixel(v: EdgeScrimScrollView, x: Int): Int {
        val bm = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bm))
        // `0 until width`, so the last paintable column is width-1.
        //
        // **Sample inside the SCROLL BAND, not the wrapper's middle.** The shadow is deliberately
        // drawn only over the band that responds to a drag, so the wrapper's vertical centre can
        // fall below it — sampling there would report "no shadow" on a screen that shows one.
        val content = (v.getChildAt(0) as android.widget.HorizontalScrollView).getChildAt(0)
        val y = (content.height / 2).coerceIn(0, v.height - 1)
        return bm.getPixel(x.coerceIn(0, v.width - 1), y)
    }

    private fun scroll(v: EdgeScrimScrollView) = v.getChildAt(0) as HorizontalScrollView

    /**
     * Scroll the way the widget does: move, then re-lay-out.
     *
     * `scrollTo` alone updates nothing observable here — a `HorizontalScrollView` clamps and repaints
     * through a layout pass, so a test that only calls `scrollTo` asserts against a scroll position
     * the view never actually took. That is a fact about the harness, not about the shadow.
     */
    private fun scrollTo(v: EdgeScrimScrollView, x: Int) {
        val sc = scroll(v)
        sc.scrollTo(x, 0)
        sc.measure(
            View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        sc.layout(0, 0, sc.measuredWidth, sc.measuredHeight)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    /** At the left end there is nothing behind you, so no left shadow — the ask cuts both ways. */
    @Test
    fun `no left shadow before scrolling`() {
        val v = build("echo " + "x".repeat(300))
        assertEquals("the row's own surface at x=0", Theme.BG, pixel(v, 0))
    }

    /** At the right end there IS content past the edge, so the shadow must be painted there. */
    @Test
    fun `the right shadow is painted when content overflows`() {
        val v = build("echo " + "x".repeat(300))
        val edge = pixel(v, v.width - 1)
        assertNotEquals("a shadow must darken the right edge", Theme.BG, edge)
    }

    /** Scrolled midway: both sides have content, so both shadows exist. */
    @Test
    fun `both shadows appear mid-scroll`() {
        val v = build("echo " + "x".repeat(300))
        scrollTo(v, 120)
        assertNotEquals("left edge must darken once scrolled past the start", Theme.BG, pixel(v, 0))
        assertNotEquals("right edge must still darken", Theme.BG, pixel(v, v.width - 1))
    }

    /**
     * A command that fits has nothing hidden, so no shadow claims otherwise.
     *
     * The right-hand column can be TRANSPARENT rather than [Theme.BG] — a short command does not
     * fill the measured width, so there is simply nothing painted past its last glyph. Both mean
     * "no shadow", which is the claim; asserting one colour would make this test fail on a fact
     * about text width rather than about the affordance.
     */
    @Test
    fun `no shadows on a command that fits`() {
        val v = build("ls")
        assertNoShadow(pixel(v, 0), "left")
        assertNoShadow(pixel(v, v.width - 1), "right")
    }

    /** Nothing here is a shadow: either untouched paper, or nothing painted at all. */
    private fun assertNoShadow(color: Int, side: String) {
        val untouched = color == Theme.BG || (color ushr 24) == 0
        assertTrue("$side edge must carry no shadow, got ${Integer.toHexString(color)}", untouched)
    }
}
