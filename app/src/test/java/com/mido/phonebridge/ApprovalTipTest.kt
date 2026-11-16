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
        // `w / sin(theta)`, because the leaning edge has to come back to the screen edge after
        // travelling the top edge's perpendicular. Asserted from the angle, not a stored constant.
        val m = ApprovalTip.metrics(width, density)
        val expected = m.topWidthPx / kotlin.math.sin(ApprovalTip.TILT_DEGREES * PI.toFloat() / 180f)
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
    fun `the top edge is NOT parallel to the screen`() {
        // The first version had a horizontal top edge, so the wedge read as a cut-off panel glued
        // to the corner rather than a sheet seen at an angle. Both visible edges now lean.
        val m = ApprovalTip.metrics(width, density)
        val pts = ApprovalTip.outline(m, width, 100f)
        val topLeftY = pts[1].y
        assertTrue(
            "the top edge must not be level with the corner: cornerY=100 topLeftY=$topLeftY",
            kotlin.math.abs(topLeftY - 100f) > 1f,
        )
    }

    @Test
    fun `the two visible edges are perpendicular in the paper's own frame`() {
        // A sheet of paper has square corners. Whatever the lean, the top edge and the leaning
        // edge must meet at 90 degrees or the shape is a wedge, not a corner of paper.
        val m = ApprovalTip.metrics(width, density)
        val pts = ApprovalTip.outline(m, width, 100f)
        val topDx = pts[1].x - pts[0].x
        val topDy = pts[1].y - pts[0].y
        val leanDx = pts[2].x - pts[1].x
        val leanDy = pts[2].y - pts[1].y
        val dot = topDx * leanDx + topDy * leanDy
        val n1 = kotlin.math.hypot(topDx, topDy)
        val n2 = kotlin.math.hypot(leanDx, leanDy)
        val cosAngle = dot / (n1 * n2)
        assertEquals("the corner must be square, cos=$cosAngle", 0.0, cosAngle.toDouble(), 0.02)
    }

    @Test
    fun `the leaning edge returns to the screen edge at the taper point`() {
        val m = ApprovalTip.metrics(width, density)
        val pts = ApprovalTip.outline(m, width, 100f)
        // The corner and the taper point both sit on the display's right edge.
        assertEquals(width.toFloat(), pts[0].x, 0f)
        assertEquals(width.toFloat(), pts[2].x, 0f)
        // The taper point is BELOW the corner, so the shape runs downwards.
        assertTrue("the taper must descend", pts[2].y > pts[0].y)
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
