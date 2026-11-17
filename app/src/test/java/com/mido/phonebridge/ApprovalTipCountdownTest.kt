package com.mido.phonebridge

import com.mido.phonebridge.ui.ApprovalTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The countdown dial on the resting tip: its arithmetic, its geometry, and the edge cases that
 * would make it LIE.
 *
 * A dial is a claim about time. Every test here exists because some way of computing it produces a
 * number that is not the time left: a stale frame, a wrong zero point, a window that is not the one
 * the gate actually waits on, a divide by zero, a clock that jumps. Those are enumerated below
 * rather than discovered on a phone.
 */
class ApprovalTipCountdownTest {

    private val density = 2.75f
    private val winW: Float
    private val winH: Float
    private val d = ApprovalTip.apexInsetPx(density)

    init {
        val (w, h) = ApprovalTip.requiredSize(density)
        winW = w
        winH = h
    }

    private fun tri() = ApprovalTip.outlineFit(
        winW, winH, density, ApprovalTip.TOP_OFFSET_DP * density,
    )

    // ---- the fraction ----

    @Test
    fun `the fraction tracks remaining time`() {
        val total = 60_000L
        assertEquals("a fresh request is full", 1f, ApprovalTip.ringFraction(total, total), 0.0001f)
        assertEquals("half the window is half the dial", 0.5f, ApprovalTip.ringFraction(30_000L, total), 0.0001f)
        assertEquals("expiry is empty", 0f, ApprovalTip.ringFraction(0L, total), 0.0001f)
    }

    @Test
    fun `the fraction cannot go outside zero and one`() {
        // A clock that jumps backwards, or a caller passing a negative remainder, must not draw a
        // disc bigger than the disc.
        assertEquals("negative remaining is empty, not inverted", 0f, ApprovalTip.ringFraction(-5_000L, 60_000L), 0.0001f)
        assertEquals("more than the window is still full", 1f, ApprovalTip.ringFraction(90_000L, 60_000L), 0.0001f)
    }

    @Test
    fun `the fraction is monotonic as time passes`() {
        // If one frame drew a bigger wedge than the one before it, the dial would visibly jump
        // backwards — the single most alarming thing a countdown can do.
        val total = 60_000L
        var previous = 1f
        var remaining = total
        while (remaining >= 0L) {
            val f = ApprovalTip.ringFraction(remaining, total)
            assertTrue("the dial must not grow: $f after $previous", f <= previous + 0.0001f)
            previous = f
            remaining -= 1_000L
        }
    }

    @Test
    fun `a zero window is refused rather than dividing by zero`() {
        // `require`, not a silent 1f: a zero total means the caller has no deadline, and drawing a
        // full dial for it would claim time that does not exist.
        val threw = try {
            ApprovalTip.ringFraction(1_000L, 0L)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("a zero total window must throw", threw)
    }

    @Test
    fun `a negative window is refused too`() {
        val threw = try {
            ApprovalTip.ringFraction(1_000L, -1L)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("a negative total window must throw", threw)
    }

    // ---- the clock the dial reads ----

    @Test
    fun `the deadline is the latch wait, not a 60 second constant`() {
        // The bug this pins: the overlay originally assumed a 60s window, while the latch waits
        // `maxWaitMs()` = misclick guard + AI fetch budget + 60s. The dial drained to empty while
        // the request still had seconds of real life left, so it under-reported time.
        val p = pending()
        val expected = ArmGuard.MISCLICK_GUARD_MS + ApprovalGate.TIMEOUT_SECONDS * 1000L
        assertEquals("the no-AI window is guard plus a full 60s", expected, p.maxWaitMs())
        assertTrue("and it is NOT the bare 60s the old timer used", p.maxWaitMs() > ApprovalGate.TIMEOUT_SECONDS * 1000L)
    }

    @Test
    fun `before the wait starts the dial reports a full window`() {
        // `waitStartedAtMs == 0` means the request has not begun its countdown. Reporting 0 would
        // draw an empty dial on a request nobody has even waited on yet.
        val p = pending()
        p.waitStartedAtMs = 0L
        assertEquals("an unwaited request reports its full window", p.maxWaitMs(), p.remainingMs(nowMs = 999_999L))
    }

    @Test
    fun `the dial counts down from the instant the wait began`() {
        val p = pending()
        val start = 1_000_000L
        p.waitStartedAtMs = start
        assertEquals("at the start, the whole window remains", p.maxWaitMs(), p.remainingMs(start))
        assertEquals("halfway, half remains", p.maxWaitMs() / 2, p.remainingMs(start + p.maxWaitMs() / 2))
        assertEquals("at expiry, nothing remains", 0L, p.remainingMs(start + p.maxWaitMs()))
    }

    @Test
    fun `a request past its deadline reports zero, never a negative`() {
        // A clock that jumps forward, or a latch that wakes late, must not produce a negative
        // remainder: the fraction would clamp, but a negative would also look like a huge unsigned
        // value anywhere it was formatted.
        val p = pending()
        p.waitStartedAtMs = 1_000L
        assertEquals("well past expiry is exactly zero", 0L, p.remainingMs(nowMs = 1_000L + p.maxWaitMs() + 60_000L))
    }

    private fun pending(): ApprovalGate.Pending = ApprovalGate.Pending(
        requestId = 1L,
        latch = java.util.concurrent.CountDownLatch(1),
        detail = null,
        view = null,
        remote = "test",
        endpoint = "/test",
        summary = "test",
    )

    @Test
    fun `headCountdown reports nothing when no request is waiting`() {
        // Nothing waiting must be distinguishable from "expired". A dial that snapped to empty
        // whenever the queue drained would flash red-at-zero on every successful approval.
        assertNull("no head means no countdown", ApprovalGate.headCountdown(nowMs = 0L))
    }

    // ---- the disc ----

    @Test
    fun `the disc sits fully inside the paper`() {
        // The real failure this catches: placing the centre at `radius + gap` from the point put
        // the circle over both edges, because a circle in a 90° corner sits `radius * sqrt(2)`
        // from it. Every point on the circle is checked, not just the centre.
        val centre = ApprovalTip.ringCenter(tri(), density)
        val r = ApprovalTip.ringRadiusPx(density)
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0)
            val x = centre.x + (r * Math.cos(a)).toFloat()
            val y = centre.y + (r * Math.sin(a)).toFloat()
            assertTrue(
                "the disc must stay on the paper, but ($x, $y) is off it",
                ApprovalTip.containsFit(
                    winW, winH, density, ApprovalTip.TOP_OFFSET_DP * density, x, y,
                ),
            )
        }
    }

    @Test
    fun `the disc is small enough to be a mark rather than a dial`() {
        val diameter = 2f * ApprovalTip.ringRadiusPx(density)
        assertTrue(
            "diameter ${diameter}px must be under a third of the ${d}px reach",
            diameter < d / 3f,
        )
    }

    @Test
    fun `the disc clears the point`() {
        // Distance from the apex to the NEAREST point on the circle. The centre is offset by
        // (r + gap) * sqrt(2) along the bisector, so the clearance on that axis is
        // (r + gap) * sqrt(2) - r.
        val apex = tri().minBy { it.x }
        val centre = ApprovalTip.ringCenter(tri(), density)
        val r = ApprovalTip.ringRadiusPx(density)
        val nearest = hypot(centre.x - apex.x, centre.y - apex.y) - r
        val expected = (r + ApprovalTip.ringGapPx(density)) * kotlin.math.sqrt(2f) - r
        assertEquals("clearance from the point", expected, nearest, 1f)
        assertTrue("the disc must not touch the point", nearest > 0f)
    }
}
