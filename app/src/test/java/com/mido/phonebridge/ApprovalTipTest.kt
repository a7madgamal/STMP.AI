package com.mido.phonebridge

import com.mido.phonebridge.ui.ApprovalTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The tip's SHAPE, which is what its shadow is made of.
 *
 * The defect this pins was reported twice: "the shadow is still vertical". The shadow was never the
 * problem — the shape had an edge lying exactly along the screen edge, and the shadow of a vertical
 * edge is a vertical band. So the property asserted here is the one that fixes it by construction:
 * **no edge of the paper is parallel to the screen.**
 */
class ApprovalTipTest {

    private val width = 1080
    private val density = 2.75f

    private fun pts(topPx: Float = 120f) =
        ApprovalTip.outline(ApprovalTip.metrics(width, density), width, topPx)

    @Test
    fun `no edge is vertical`() {
        val p = pts()
        p.indices.forEach { i ->
            val a = p[i]
            val b = p[(i + 1) % p.size]
            assertTrue(
                "edge $i runs straight up-down (dx=${b.x - a.x}) — a vertical edge casts a " +
                    "vertical shadow, which is the reported defect",
                abs(b.x - a.x) > 0.01f,
            )
        }
    }

    @Test
    fun `no edge is horizontal either`() {
        val p = pts()
        p.indices.forEach { i ->
            val a = p[i]
            val b = p[(i + 1) % p.size]
            assertTrue(
                "edge $i runs exactly level (dy=${b.y - a.y}) — it would read as parallel to the " +
                    "screen, which the first version was reported for",
                abs(b.y - a.y) > 0.01f,
            )
        }
    }

    @Test
    fun `the paper does not touch the screen edge`() {
        // A shape clipped by the edge can never look like paper lying on a surface.
        pts().forEach { p ->
            assertTrue(
                "the paper reaches the screen edge at x=${p.x} — it must float clear",
                p.x < width - 1f,
            )
        }
    }

    @Test
    fun `all four corners are square`() {
        val p = pts()
        for (i in p.indices) {
            val prev = p[(i + p.size - 1) % p.size]
            val curr = p[i]
            val next = p[(i + 1) % p.size]
            val e1 = Pair(prev.x - curr.x, prev.y - curr.y)
            val e2 = Pair(next.x - curr.x, next.y - curr.y)
            val dot = e1.first * e2.first + e1.second * e2.second
            val n1 = kotlin.math.hypot(e1.first, e1.second)
            val n2 = kotlin.math.hypot(e2.first, e2.second)
            assertEquals("corner $i must be square", 0.0, (dot / (n1 * n2)).toDouble(), 0.02)
        }
    }

    @Test
    fun `the hit area follows the shape, not a rectangle around it`() {
        val m = ApprovalTip.metrics(width, density)
        val p = pts()
        val inside = p.reduce { a, b -> ApprovalTip.Point((a.x + b.x) / 2, (a.y + b.y) / 2) }
        assertTrue("a point well inside must hit", ApprovalTip.contains(m, width, 120f, inside.x, inside.y))
        assertFalse(
            "a point far from the paper must not hit — the wedge's own triangle once made this " +
                "pass while the paper was somewhere else",
            ApprovalTip.contains(m, width, 120f, 5f, 1400f),
        )
    }

    @Test
    fun `the taper length is a sane multiple of the band`() {
        // Long enough to read as a sheet, short enough to stay on screen.
        val m = ApprovalTip.metrics(width, density)
        assertTrue("paper height ${m.heightPx} must be several times its band", m.heightPx > m.topWidthPx * 2f)
        assertTrue("paper height ${m.heightPx} must fit a screen", m.heightPx < 1544f)
    }
}
