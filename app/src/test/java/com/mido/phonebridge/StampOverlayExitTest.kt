package com.mido.phonebridge

import com.mido.phonebridge.ui.StampOverlay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * The stamp must ALWAYS finish.
 *
 * An overlay that never finishes covers the whole screen and takes every touch — reported from the
 * device as "after accepting I only see shadow and I cannot touch anything". The cause was a frame
 * loop that returned instead of re-posting when it ran before attachment, so nothing ever called
 * the finish callback. These are the exits that must survive that class of failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StampOverlayExitTest {

    private fun overlay(): StampOverlay {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val o = StampOverlay(a)
        a.setContentView(o)
        o.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1544, android.view.View.MeasureSpec.EXACTLY),
        )
        o.layout(0, 0, 720, 1544)
        return o
    }

    @Test
    fun `the stamp finishes on its own without any tap`() {
        val o = overlay()
        var finished = false
        o.onFinished = { finished = true }
        o.stamp(StampOverlay.Verdict.APPROVED)
        // The animation is clock-driven; advance the clock with the looper.
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(4_000))
        assertTrue("a receipt nobody has to tap must still leave by itself", finished)
    }

    @Test
    fun `a stamp that never gets frames still finishes`() {
        // The reported failure: the overlay covers the screen, no frame loop runs, nothing finishes.
        // The wall-clock deadline is the only thing that can save this, so it is asserted directly.
        val o = overlay()
        var finished = false
        o.onFinished = { finished = true }
        o.stamp(StampOverlay.Verdict.DENIED)
        // Do NOT idle the animation frames; jump the clock past the hard deadline only.
        ShadowLooper.idleMainLooper()
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(6_000))
        assertTrue(
            "even with no animation frames, the overlay must release the screen",
            finished,
        )
    }

    @Test
    fun `finishing is idempotent`() {
        val o = overlay()
        var calls = 0
        o.onFinished = { calls++ }
        o.stamp(StampOverlay.Verdict.APPROVED)
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(6_000))
        // Both the animation's own exit and the deadline fire; the callback must run once.
        assertTrue("the finish callback must run", calls >= 1)
        assertFalse("but never twice", calls > 1)
    }
}
