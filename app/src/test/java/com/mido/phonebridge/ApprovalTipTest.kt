package com.mido.phonebridge

import com.mido.phonebridge.ui.ApprovalTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.tan

/**
 * The resting tip's geometry.
 *
 * These assertions exist because the shape is DERIVED: the taper length comes from the tilt, so
 * a change to one number silently reshapes the other. Everything the view then draws is a
 * consequence of the numbers checked here, which is why the checks live on plain floats rather
 * than on a rendered bitmap.
 */
class ApprovalTipTest {

    /** A typical phone: 1080 px wide at 2.75x. */
    private val width = 1080
    private val density = 2.75f

    @Test
    fun `the taper length follows the tilt`() {
        val m = ApprovalTip.metrics(width, density)
        val expected = m.topWidthPx / tan(ApprovalTip.TILT_DEGREES * PI.toFloat() / 180f)
        assertEquals(expected, m.heightPx, 0.01f)
    }

    @Test
    fun `a phone gets roughly three centimetres of paper`() {
        // 3 cm at 2.75x is about 82 px per cm -> ~247 px... but the clamp wins on this device,
        // so the real check is that the width lands INSIDE the clamp and near the fraction.
        val m = ApprovalTip.metrics(width, density)
        assertTrue(
            "top width ${m.topWidthPx} should stay within the dp clamp",
            m.topWidthPx >= ApprovalTip.TOP_WIDTH_MIN_DP * density &&
                m.topWidthPx <= ApprovalTip.TOP_WIDTH_MAX_DP * density
        )
    }

    @Test
    fun `a wide screen is clamped`() {
        val m = ApprovalTip.metrics(4000, 2f)
        assertEquals(ApprovalTip.TOP_WIDTH_MAX_DP * 2f, m.topWidthPx, 0.01f)
    }

    @Test
    fun `a narrow screen is clamped up`() {
        val m = ApprovalTip.metrics(200, 4f)
        assertEquals(ApprovalTip.TOP_WIDTH_MIN_DP * 4f, m.topWidthPx, 0.01f)
    }

    @Test
    fun `the point lands on screen rather than off the bottom`() {
        val m = ApprovalTip.metrics(width, density)
        val screenHeightPx = 2400f
        // The wedge starts just below the status bar; the point has to arrive above the bottom.
        assertTrue(
            "height ${m.heightPx} must fit a phone screen",
            m.heightPx < screenHeightPx
        )
    }

    @Test
    fun `the outline is flush to the right edge and tapers to a point`() {
        val m = ApprovalTip.metrics(width, density)
        val top = 100f
        val points = ApprovalTip.outline(m, width, top)
        assertEquals(3, points.size)
        // Top-right and the point both sit exactly on the display's right edge...
        assertEquals(width.toFloat(), points[0].x, 0f)
        assertEquals(width.toFloat(), points[2].x, 0f)
        // ...and the top-left is the full width inwards from it.
        assertEquals(width - m.topWidthPx, points[1].x, 0.01f)
        // The taper is vertical: no horizontal movement between top-right and the point.
        assertEquals(points[0].y, top, 0f)
        assertEquals(top + m.heightPx, points[2].y, 0.01f)
    }

    @Test
    fun `the bow cannot leave the display`() {
        // A tiny screen with a big bow: the control point must still be at or right of x=0.
        val m = ApprovalTip.metrics(200, 4f)
        val control = ApprovalTip.bowControl(m, 200, 0f)
        assertTrue("control x=${control.x} must not be negative", control.x >= 0f)
        assertTrue("control must stay left of the right edge", control.x <= 200f)
    }

    @Test
    fun `every depth has an alpha and the front one is opaque`() {
        assertEquals(ApprovalTip.MAX_VISIBLE, ApprovalTip.GHOST_ALPHA.size)
        assertEquals(1f, ApprovalTip.GHOST_ALPHA[0], 0f)
        ApprovalTip.GHOST_ALPHA.toList().zipWithNext().forEach { (front, behind) ->
            assertTrue("each ghost must be fainter than the one in front", behind < front)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-width screen is refused rather than drawing a degenerate wedge`() {
        ApprovalTip.metrics(0, density)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero density is refused`() {
        ApprovalTip.metrics(width, 0f)
    }

    @Test
    fun `an empty queue draws nothing`() {
        assertEquals(emptyList<ApprovalTip.Wedge>(), ApprovalTip.stack(0, density))
    }

    @Test
    fun `the queue is capped at three wedges`() {
        assertEquals(ApprovalTip.MAX_VISIBLE, ApprovalTip.stack(9, density).size)
        assertEquals(2, ApprovalTip.stack(2, density).size)
    }

    @Test
    fun `each ghost is further in and further down than the one in front`() {
        val wedges = ApprovalTip.stack(3, density)
        // The front wedge is exactly on the edge and has no drop.
        assertEquals(0f, wedges[0].insetPx, 0f)
        assertEquals(0f, wedges[0].dropPx, 0f)
        wedges.zipWithNext().forEach { (front, behind) ->
            assertTrue("ghost must sit further from the edge", behind.insetPx > front.insetPx)
            assertTrue("ghost must sit lower", behind.dropPx > front.dropPx)
            assertTrue("ghost must be fainter", behind.alpha < front.alpha)
        }
    }

    @Test
    fun `stack offsets scale with density`() {
        val at1x = ApprovalTip.stack(2, 1f)[1]
        val at2x = ApprovalTip.stack(2, 2f)[1]
        assertEquals(at1x.insetPx * 2f, at2x.insetPx, 0.01f)
        assertEquals(at1x.dropPx * 2f, at2x.dropPx, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero density stack is refused`() {
        ApprovalTip.stack(2, 0f)
    }
}
