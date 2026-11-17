package com.mido.phonebridge

import com.mido.phonebridge.ui.ApprovalTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The tip, DERIVED from the specification and asserted as properties.
 *
 * The three sentences that define it, all from the human:
 *  - two vertices exactly on the right edge,
 *  - the third 1cm to the left,
 *  - the angle AT THAT THIRD VERTEX is 90°.
 *
 * Those name exactly one triangle. Every earlier shape failed at least one, which is why the
 * assertions below are properties (angles, distances, which side of the edge) rather than a table
 * of coordinates I could quietly re-tune.
 */
class ApprovalTipTest {

    private val density = 2.75f
    private val d = ApprovalTip.apexInsetPx(density)

    private val winW: Float
    private val winH: Float

    init {
        val (w, h) = ApprovalTip.requiredSize(density)
        winW = w
        winH = h
    }

    private fun tri() = ApprovalTip.outlineFit(winW, winH, density, ApprovalTip.TOP_OFFSET_DP * density)

    @Test
    fun `it is a triangle`() {
        assertEquals(3, tri().size)
    }

    @Test
    fun `exactly two vertices lie on the right edge`() {
        val onEdge = tri().filter { abs(it.x - winW) < 0.5f }
        assertEquals(
            "two vertices must sit on the right edge, found ${onEdge.size}",
            2,
            onEdge.size,
        )
    }

    @Test
    fun `the third vertex is exactly 1cm in`() {
        val innermost = tri().minBy { it.x }
        assertEquals("the point must be 1cm from the edge", d, winW - innermost.x, 0.5f)
    }

    @Test
    fun `the corner at the inner vertex is exactly 90 degrees`() {
        // The property that makes it paper. Asserted at the INNER vertex, because that is the one
        // the human pointed at when saying "a fucking paper is 90 degrees".
        val p = tri()
        val c = p.minBy { it.x }
        val others = p.filter { it !== c }
        val a = others[0]
        val b = others[1]
        val v1x = a.x - c.x; val v1y = a.y - c.y
        val v2x = b.x - c.x; val v2y = b.y - c.y
        val cosAngle = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y))
        assertEquals("the inner corner must be square (cos=$cosAngle)", 0.0, cosAngle.toDouble(), 0.02)
    }

    @Test
    fun `the base on the edge is 2cm and the two short sides are 1cm times root two`() {
        // Follows arithmetically from the three constraints; asserted so a hand-tuned change to one
        // number cannot silently break the right angle.
        val p = tri()
        val edge = p.filter { abs(it.x - winW) < 0.5f }.sortedBy { it.y }
        val base = abs(edge[1].y - edge[0].y)
        assertEquals("the base on the edge must be 2cm", 2f * d, base, 1f)

        val c = p.minBy { it.x }
        p.filter { it !== c }.forEach { v ->
            val side = hypot(v.x - c.x, v.y - c.y)
            assertEquals("a short side must be 1cm*sqrt(2)", d * kotlin.math.sqrt(2f), side, 1.5f)
        }
    }

    @Test
    fun `no edge is parallel to the screen axes`() {
        // A leg along the edge casts a vertical shadow — reported earlier as the shadow problem.
        // Two short sides are diagonal; the base intentionally lies ON the edge, so it is allowed
        // to be vertical and is asserted separately.
        val p = tri()
        val c = p.minBy { it.x }
        p.filter { it !== c }.forEach { v ->
            assertTrue("a short side must not be vertical", abs(v.x - c.x) > 0.01f)
            assertTrue("a short side must not be horizontal", abs(v.y - c.y) > 0.01f)
        }
    }

    @Test
    fun `everything fits inside the window`() {
        tri().forEach { pt ->
            assertTrue("x=${pt.x} outside $winW", pt.x >= -0.5f && pt.x <= winW + 0.5f)
            assertTrue("y=${pt.y} outside $winH", pt.y >= -0.5f && pt.y <= winH + 0.5f)
        }
    }

    @Test
    fun `the window is exactly big enough for the shape`() {
        // If the window were smaller the shape would be clipped ("bottom side is cut off"); much
        // larger and it steals touch area it does not need.
        val p = tri()
        val spanW = p.maxOf { it.x } - p.minOf { it.x }
        val spanH = p.maxOf { it.y } - p.minOf { it.y }
        assertTrue("window $winW must hold width $spanW", winW >= spanW - 0.5f)
        assertTrue("window $winH must hold height $spanH", winH >= spanH - 0.5f)
    }

    @Test
    fun `a tap inside hits and a tap outside does not`() {
        val p = tri()
        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        assertTrue(
            "the centroid must be a hit",
            ApprovalTip.containsFit(winW, winH, density, ApprovalTip.TOP_OFFSET_DP * density, cx, cy),
        )
        assertFalse(
            "a point off the paper must not absorb the tap",
            ApprovalTip.containsFit(winW, winH, density, ApprovalTip.TOP_OFFSET_DP * density, 0.5f, 0.5f),
        )
    }

    @Test
    fun `the stack grows downwards and the sheets are parallel, not aimed at one point`() {
        // "stacking should happen from under not top" and "should be parallel not one point".
        //
        // Two separate faults, both visible on the device: ghosts that stepped UP put the queue
        // above the sheet the human can actually open, and ghosts re-derived from a shrinking
        // height were three different triangles converging on a shared apex. A stack of paper is
        // congruent copies, each one lower.
        val wedges = ApprovalTip.stack(3, density)
        assertEquals("three queued sheets", 3, wedges.size)
        assertTrue("the front sheet is not offset", wedges[0].dropPx == 0f)
        wedges.zipWithNext { a, b ->
            assertTrue(
                "each sheet steps DOWN from the one in front, not up",
                b.dropPx > a.dropPx,
            )
            assertEquals("sheets do not step inwards", a.insetPx, b.insetPx, 0.01f)
        }

        // Parallel: translating one outline by a drop must give exactly the next sheet's outline.
        val front = ApprovalTip.outlineFit(winW, winH, density, ApprovalTip.TOP_OFFSET_DP * density)
        val drop = wedges[1].dropPx
        val second = ApprovalTip.outlineFit(
            winW, winH, density, ApprovalTip.TOP_OFFSET_DP * density + drop,
        )
        front.zip(second).forEach { (a, b) ->
            assertEquals("a translated sheet keeps its x", a.x, b.x, 0.01f)
            assertEquals("a translated sheet moves down by exactly the drop", a.y + drop, b.y, 0.01f)
        }
    }

    @Test
    fun `the window is tall enough for the whole stack`() {
        // The recurring "bottom side is cut off": the window has to hold the drop above the front
        // sheet plus every step below it. Sized to the bare shape it clipped the last sheet.
        val drop = ApprovalTip.GHOST_DROP_DP * density * (ApprovalTip.MAX_VISIBLE - 1)
        val lastBottom = ApprovalTip.TOP_OFFSET_DP * density + drop + 2f * d
        assertTrue(
            "window $winH must hold the deepest sheet at $lastBottom",
            winH >= lastBottom - 0.5f,
        )
    }

    @Test
    fun `the 1cm is physical, not a share of the screen`() {
        // The same dp size must appear regardless of the window it is drawn into.
        val narrow = ApprovalTip.outlineFit(winW, winH, density, 0f)
        val wide = ApprovalTip.outlineFit(winW * 3f, winH * 3f, density, 0f)
        val nIn = narrow.minBy { it.x }.x
        val wIn = wide.minBy { it.x }.x
        assertEquals(winW - nIn, winW * 3f - wIn, 1f)
    }

    @Test
    fun `a taller window does not change the shape`() {
        // The bug that made the corner 75.7 degrees: the outline was centred in whatever height it
        // was given, so the SAME paper came out squat whenever the window was taller than it. Shape
        // must be a function of the drop and the density only.
        val short = ApprovalTip.outlineFit(winW, winH, density, 0f)
        val tall = ApprovalTip.outlineFit(winW, winH * 4f, density, 0f)
        short.zip(tall).forEach { (a, b) ->
            assertEquals("x must not move with window height", a.x, b.x, 0.01f)
            assertEquals("y must not move with window height", a.y, b.y, 0.01f)
        }
    }
}
