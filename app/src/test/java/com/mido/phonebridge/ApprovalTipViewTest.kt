package com.mido.phonebridge

import android.view.MotionEvent
import com.mido.phonebridge.ui.ApprovalTip
import com.mido.phonebridge.ui.ApprovalTipView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The tip as a view: how many wedges it draws, and that a tap only counts on the paper.
 *
 * Layout is driven explicitly here (`measure`/`layout`) rather than through a host Activity: the
 * subject is the wedge geometry and its hit region, both of which exist the moment the view has a
 * size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalTipViewTest {

    private fun tip(depth: Int = 1, width: Int = 320, height: Int = 470): ApprovalTipView {
        val ctx = RuntimeEnvironment.getApplication()
        val v = ApprovalTipView(ctx)
        v.depth = depth
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, width, height)
        return v
    }

    /** Centre of the paper, which is where the geometry says a tap should land. */
    private fun paperCentre(v: ApprovalTipView, height: Int = 470): Pair<Float, Float> {
        val m = ApprovalTip.metrics(v.width, RuntimeEnvironment.getApplication().resources.displayMetrics.density)
        val pts = ApprovalTip.outline(m, v.width, ApprovalTip.TOP_OFFSET_DP)
        return Pair(
            pts.map { it.x }.average().toFloat(),
            pts.map { it.y }.average().toFloat().coerceIn(1f, (height - 2).toFloat()),
        )
    }

    private fun tap(v: ApprovalTipView, x: Float, y: Float): Boolean {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_UP, x, y, 0)
        v.onTouchEvent(down)
        val handled = v.onTouchEvent(up)
        down.recycle()
        up.recycle()
        return handled
    }

    @Test
    fun `depth decides how many wedges are drawn`() {
        assertEquals(1, tip(depth = 1).drawnWedges)
        assertEquals(2, tip(depth = 2).drawnWedges)
        assertEquals(3, tip(depth = 3).drawnWedges)
    }

    @Test
    fun `a deep queue still draws only three`() {
        assertEquals(ApprovalTip.MAX_VISIBLE, tip(depth = 7).drawnWedges)
    }

    @Test
    fun `a tap on the paper opens the sheet`() {
        val v = tip(width = 700, height = 900)
        var opened = false
        v.onOpen = { opened = true }
        val (x, y) = paperCentre(v, 900)
        tap(v, x, y)
        assertTrue("a tap on the paper must open the sheet", opened)
    }

    @Test
    fun `a tap on empty screen does not open`() {
        val v = tip(width = 700, height = 900)
        var opened = false
        v.onOpen = { opened = true }
        // Far below the paper, in the empty part of the strip.
        tap(v, 350f, 880f)
        assertFalse("empty screen must not open the sheet", opened)
    }

    @Test
    fun `a fresh tip is already at rest`() {
        assertTrue(tip().isAtRest)
    }

    @Test
    fun `changing depth rebuilds the wedges`() {
        val v = tip(depth = 1)
        assertEquals(1, v.drawnWedges)
        v.depth = 3
        assertEquals(3, v.drawnWedges)
        v.depth = 3
        assertEquals(3, v.drawnWedges)
    }

    @Test
    fun `a zero size does not build a degenerate wedge`() {
        val ctx = RuntimeEnvironment.getApplication()
        val v = ApprovalTipView(ctx)
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 0, 0)
        assertEquals(0, v.drawnWedges)
    }
}
