package com.mido.phonebridge

import com.mido.phonebridge.ui.ApprovalTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The tip's SHAPE, which is what its shadow is made of, and that it FITS the view it is drawn in.
 *
 * Two defects are pinned here.
 *
 * 1. Every early shape had an edge lying along the screen edge, so its shadow ran vertical —
 *    reported twice as "the shadow is still vertical". The report was always right: no shadow
 *    parameter can repair a shape with a vertical edge.
 * 2. The first rotated rectangle was built from SCREEN proportions and drawn into a ~195px strip,
 *    so most of it fell outside the view — "only a tiny piece of paper is rendered".
 */
class ApprovalTipTest {

    private val density = 2.75f

    /** The real resting window on the device this shipped to. */
    private val stripW = 195f
    private val stripH = 1544f
    private val topPx = ApprovalTip.TOP_OFFSET_DP * density

    private fun fit(w: Float = stripW, h: Float = stripH, top: Float = topPx) =
        ApprovalTip.outlineFit(w, h, density, top)

    @Test
    fun `the paper fits inside a narrow strip window`() {
        // The reported defect: only a corner appeared because the shape was laid out for a screen.
        fit().forEach { p ->
            assertTrue("corner x=${p.x} is off the left of a ${stripW}px strip", p.x >= 0f)
            assertTrue("corner x=${p.x} is off the right of a ${stripW}px strip", p.x <= stripW)
            assertTrue("corner y=${p.y} is above the view", p.y >= 0f)
            assertTrue("corner y=${p.y} is below the view", p.y <= stripH)
        }
    }

    @Test
    fun `the paper fills a decent share of the strip`() {
        // "A tiny piece" is as wrong as "off screen": the sheet must be the reason the window
        // exists, not a sliver in one corner.
        val p = fit()
        val width = p.maxOf { it.x } - p.minOf { it.x }
        val height = p.maxOf { it.y } - p.minOf { it.y }
        assertTrue(
            "the paper is only ${width}px of a ${stripW}px strip wide",
            width > stripW * 0.5f,
        )
        assertTrue("the paper is only ${height}px tall", height > stripH * 0.2f)
    }

    @Test
    fun `no edge is vertical`() {
        val p = fit()
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
        val p = fit()
        p.indices.forEach { i ->
            val a = p[i]
            val b = p[(i + 1) % p.size]
            assertTrue("edge $i runs exactly level (dy=${b.y - a.y})", abs(b.y - a.y) > 0.01f)
        }
    }

    @Test
    fun `all four corners are square`() {
        val p = fit()
        for (i in p.indices) {
            val prev = p[(i + p.size - 1) % p.size]
            val curr = p[i]
            val next = p[(i + 1) % p.size]
            val e1x = prev.x - curr.x; val e1y = prev.y - curr.y
            val e2x = next.x - curr.x; val e2y = next.y - curr.y
            val dot = e1x * e2x + e1y * e2y
            val n = kotlin.math.hypot(e1x, e1y) * kotlin.math.hypot(e2x, e2y)
            assertEquals("corner $i must be square", 0.0, (dot / n).toDouble(), 0.02)
        }
    }

    @Test
    fun `it fits a wide view as well as a strip`() {
        listOf(195f to 1544f, 720f to 1544f, 1080f to 2400f).forEach { (w, h) ->
            ApprovalTip.outlineFit(w, h, density, topPx).forEach { p ->
                assertTrue("x=${p.x} outside $w", p.x >= 0f && p.x <= w)
                assertTrue("y=${p.y} outside $h", p.y >= 0f && p.y <= h)
            }
        }
    }

    @Test
    fun `a tiny view still yields a usable shape`() {
        // A window that arrives with almost no size must not produce an inverted or empty shape.
        val p = ApprovalTip.outlineFit(40f, 60f, density, 4f)
        assertEquals(4, p.size)
        val w = p.maxOf { it.x } - p.minOf { it.x }
        assertTrue("a small view must still have a shape", w > 0f)
    }

    @Test
    fun `a tap inside the paper hits and a tap below it does not`() {
        val p = fit()
        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        assertTrue(ApprovalTip.containsFit(stripW, stripH, density, topPx, cx, cy))
        assertFalse(
            "empty screen below the paper must not absorb the tap",
            ApprovalTip.containsFit(stripW, stripH, density, topPx, stripW / 2f, stripH - 2f),
        )
    }

    @Test
    fun `the paper starts below the top of the strip`() {
        val minY = fit().minOf { it.y }
        assertTrue(
            "the paper starts at y=$minY; a sheet pinned to the very corner reads as part of the " +
                "status bar",
            minY >= topPx - 1f,
        )
    }

}
