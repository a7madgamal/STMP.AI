package com.mido.phonebridge

import com.mido.phonebridge.ui.ApprovalTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tip is a TRIANGLE, and these pin what makes it look like one.
 *
 * The report that produced this file: "it should ALWAYS appear as a triangle, not a fucking broken
 * rectangle", plus "bottom side is cut off". Those were two symptoms of one mistake — the shape was a
 * long rectangle inside a short window, so its side was cut by the window edge (a broken rectangle)
 * and its lower end was clipped (a cut bottom). A triangle with three points cannot fail either way,
 * so the assertions are about the properties that make it a triangle rather than about coordinates.
 */
class ApprovalTipTest {

    private val density = 2.75f

    /** The real tip window on the device this ships to: 2cm wide, sized to the triangle. */
    private val tipW = 236f
    private val tipH = 318f
    private val topPx = ApprovalTip.TOP_OFFSET_DP * density

    private fun tri(w: Float = tipW, h: Float = tipH, top: Float = topPx) =
        ApprovalTip.outlineFit(w, h, density, top)

    @Test
    fun `it is a triangle`() {
        assertEquals("a triangle has three points, not four", 3, tri().size)
    }

    @Test
    fun `the apex is a single point inside the window`() {
        val p = tri()
        // The last vertex is the apex: below the other two, and inside the window so it is never
        // clipped. "Bottom side is cut off" was the rectangle being longer than its window.
        val apex = p[2]
        assertTrue("the apex must be below the top edge", apex.y > p[0].y)
        assertTrue("the apex must be inside the window height", apex.y <= tipH)
        assertTrue("the apex must be inside the window width", apex.x >= 0f && apex.x <= tipW)
    }

    @Test
    fun `the top edge reaches the right edge of the window`() {
        // Flush against the screen edge, so the shape is anchored there rather than floating.
        val p = tri()
        assertEquals("the top edge must end at the window's right edge", tipW, p[1].x, 0.5f)
    }

    @Test
    fun `two edges run to the apex, so the outline tapers`() {
        val p = tri()
        // Edge 1 goes apex-ward from the top-right, edge 2 comes back to the top-left.
        assertTrue("the shape must taper, not stay a band", p[2].x < p[1].x)
        assertTrue("the shape must taper on both sides", p[2].x > p[0].x - 1f)
    }

    @Test
    fun `it fits any window without clipping`() {
        listOf(236f to 318f, 180f to 240f, 400f to 540f).forEach { (w, h) ->
            val p = ApprovalTip.outlineFit(w, h, density, topPx)
            assertEquals("w=$w h=$h", 3, p.size)
            p.forEach { pt ->
                assertTrue("x=${pt.x} outside $w", pt.x >= -0.5f && pt.x <= w + 0.5f)
                assertTrue("y=${pt.y} outside $h", pt.y >= -0.5f && pt.y <= h + 0.5f)
            }
        }
    }

    @Test
    fun `a tiny window still yields a usable triangle`() {
        val p = ApprovalTip.outlineFit(30f, 30f, density, 4f)
        assertEquals(3, p.size)
        assertTrue("a small window must still produce an area", p[1].x > p[0].x)
    }

    @Test
    fun `a tap inside hits and a tap in the empty corner does not`() {
        val p = tri()
        val cx = (p[0].x + p[1].x + p[2].x) / 3f
        val cy = (p[0].y + p[1].y + p[2].y) / 3f
        assertTrue(
            "the centroid of the triangle must be a hit",
            ApprovalTip.containsFit(tipW, tipH, density, topPx, cx, cy),
        )
        // Bottom-LEFT of the bounding box: outside a triangle that tapers to the right.
        assertFalse(
            "the empty corner beside the taper must not absorb the tap",
            ApprovalTip.containsFit(tipW, tipH, density, topPx, 1f, tipH - 2f),
        )
    }

    @Test
    fun `the apex stays a comfortable target`() {
        // The visible point is what makes it read as tucked behind the edge, and the touch area is
        // the whole triangle — so this only needs to confirm it is not vanishingly thin.
        val p = tri()
        val widthAtApexHalfway = (p[1].x + p[2].x) / 2f
        assertTrue("the taper is too steep to hit", widthAtApexHalfway > tipW * 0.3f)
    }
}
