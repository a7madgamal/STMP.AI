package com.mido.phonebridge

import com.mido.phonebridge.ui.ApprovalTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The tip is the visible CORNER OF A SHEET: a right-angled triangle, 1cm.
 *
 * The property that matters is the one the human named last, after several wrong shapes:
 * **"a fucking paper is 90 degrees"**. A sheet's corner is a right angle, so the shape's only
 * interior angle at the corner must be 90° — that is what distinguishes a corner from a wedge, a
 * lozenge or a spike, which is what the earlier geometry produced.
 *
 * The other two requirements are size (1cm, a physical measure) and the legs running to the edge.
 */
class ApprovalTipTest {

    private val density = 2.75f

    /** 1cm in px at this density — the shape's leg length. */
    private val leg = ApprovalTip.apexInsetPx(density)

    /** The real window: the ROTATED corner's bounding box plus a little room for its shadow. */
    private val box = ApprovalTip.boundingBox(density)
    private val winW = box.first + 4f * density
    private val winH = box.second + 4f * density
    private val topPx = 0f

    private fun tri(w: Float = winW, h: Float = winH, top: Float = topPx) =
        ApprovalTip.outlineFit(w, h, density, top)

    @Test
    fun `it is a triangle`() {
        assertEquals("a corner is three points", 3, tri().size)
    }

    @Test
    fun `the corner is exactly 90 degrees`() {
        // The requirement, in one assertion. A sheet's corner is square; anything else is a wedge.
        val p = tri()
        val corner = p.minBy { it.x + it.y }   // the inward corner, furthest from the edge
        val others = p.filter { it !== corner }
        val a = others[0]
        val b = others[1]
        val v1x = a.x - corner.x; val v1y = a.y - corner.y
        val v2x = b.x - corner.x; val v2y = b.y - corner.y
        val dot = v1x * v2x + v1y * v2y
        val n = kotlin.math.hypot(v1x, v1y) * kotlin.math.hypot(v2x, v2y)
        assertEquals(
            "the corner must be square (cos=${dot / n})",
            0.0,
            (dot / n).toDouble(),
            0.02,
        )
    }

    @Test
    fun `both legs are 1cm`() {
        val p = tri()
        val corner = p.minBy { it.x + it.y }
        p.filter { it !== corner }.forEach { v ->
            val d = kotlin.math.hypot(v.x - corner.x, v.y - corner.y)
            assertEquals("a leg must be 1cm", leg, d, 0.5f)
        }
    }

    @Test
    fun `no leg lies flat against the screen edge`() {
        // A leg along the screen edge casts a vertical shadow — the defect reported earlier. The
        // whole shape is rotated, so neither leg is parallel to an axis.
        val p = tri()
        p.indices.forEach { i ->
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val dx = abs(b.x - a.x)
            val dy = abs(b.y - a.y)
            assertTrue("edge $i is vertical", dx > 0.01f)
            assertTrue("edge $i is horizontal", dy > 0.01f)
        }
    }

    @Test
    fun `it fits inside the window`() {
        tri().forEach { pt ->
            assertTrue("x=${pt.x} outside $winW", pt.x >= -0.5f && pt.x <= winW + 0.5f)
            assertTrue("y=${pt.y} outside $winH", pt.y >= -0.5f && pt.y <= winH + 0.5f)
        }
    }

    @Test
    fun `it reaches the right edge of the window`() {
        // Anchored to the edge it emerges from, so it cannot float in the middle.
        assertTrue(
            "the corner must sit against the right edge",
            tri().maxOf { it.x } > winW * 0.85f,
        )
    }

    @Test
    fun `a tap inside hits and a tap in the empty corner does not`() {
        val p = tri()
        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        assertTrue(
            "the centroid must be a hit",
            ApprovalTip.containsFit(winW, winH, density, topPx, cx, cy),
        )
        assertFalse(
            "a point off the paper must not absorb the tap",
            ApprovalTip.containsFit(winW, winH, density, topPx, 0.5f, winH - 0.5f),
        )
    }

    @Test
    fun `a tiny window still yields usable geometry`() {
        val p = ApprovalTip.outlineFit(20f, 20f, density, 0f)
        assertEquals(3, p.size)
        assertTrue("no vertex may sit left of the window", p.all { it.x >= -0.5f })
    }
}
