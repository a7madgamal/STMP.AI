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
import org.robolectric.shadows.ShadowLooper

/**
 * The tip as a view: how many wedges it draws, and that a tap only counts on the front one.
 *
 * Layout is driven explicitly here (`measure`/`layout`) rather than through a host Activity: the
 * thing under test is the wedge geometry and the hit region, both of which exist the moment the
 * view has a size. Robolectric's screen is 320x470 by default, which is narrow enough that the
 * width clamp is exercised too.
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
    fun `a wedge off the right edge is not a hit`() {
        val v = tip()
        var opened = false
        v.onOpen = { opened = true }
        // x = 0 is the far side of the strip: the paper is against the RIGHT edge.
        tap(v, 1f, 10f)
        assertFalse("a tap in the empty part of the strip must not open the sheet", opened)
    }

    @Test
    fun `a tap on the front wedge opens the sheet`() {
        val v = tip()
        var opened = false
        v.onOpen = { opened = true }
        // Just inside the top-right corner, where the wedge is at its widest.
        tap(v, 319f, 4f)
        assertTrue("a tap on the paper must open the sheet", opened)
    }

    @Test
    fun `a tap below the taper point does not open`() {
        val v = tip()
        var opened = false
        v.onOpen = { opened = true }
        // The wedge tapers to a point well above this, so the lower strip is empty.
        tap(v, 319f, 460f)
        assertFalse("the taper means the lower edge has no paper to tap", opened)
    }

    @Test
    fun `a tip with no window snaps to rest instead of stranding mid-slide`() {
        val v = tip()
        v.present()
        assertTrue("an unattached view must not be left parked off screen", v.isAtRest)
    }

    @Test
    fun `dismissing an unattached tip reports completion`() {
        val v = tip()
        var done = false
        v.dismiss { done = true }
        assertTrue("the caller must be told the exit finished", done)
        assertFalse(v.isAtRest)
    }

    @Test
    fun `an attached tip animates the arrival and settles at rest`() {
        val activity = org.robolectric.Robolectric
            .buildActivity(android.app.Activity::class.java).setup().get()
        val v = ApprovalTipView(activity)
        activity.setContentView(v)
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(470, android.view.View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 320, 470)

        v.present()
        assertFalse("the arrival starts off the edge", v.isAtRest)
        // The frame loop stops on the CLOCK, so the shadow clock has to move with the looper.
        org.robolectric.shadows.ShadowLooper.shadowMainLooper()
            .idleFor(java.time.Duration.ofMillis(600))
        assertTrue("the arrival must end at rest", v.isAtRest)
    }

    @Test
    fun `a fresh tip is already at rest`() {
        // Not parked off screen: the paper is where the design puts it before any animation.
        assertTrue(tip().isAtRest)
    }

    @Test
    fun `changing depth rebuilds the wedges`() {
        val v = tip(depth = 1)
        assertEquals(1, v.drawnWedges)
        v.depth = 3
        assertEquals(3, v.drawnWedges)
        // Same value is a no-op, not a rebuild that could drop a frame mid-animation.
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
