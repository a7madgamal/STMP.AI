package com.mido.phonebridge

import android.app.Activity
import android.view.View.MeasureSpec
import com.mido.phonebridge.ui.PaperSheetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * The sheet's shape change: the wedge opening into the card, and folding back.
 *
 * The properties asserted here are the ones a person would notice: the sheet must not appear as a
 * perfectly square full-screen page (a few degrees of lean is what makes it read as paper on a
 * surface), it must not leave a replay of an entrance for a request already seen, and a motion
 * that cannot get frames must SNAP rather than strand the surface half-open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaperSheetViewTest {

    private fun sheet(attached: Boolean = false): PaperSheetView {
        val ctx = RuntimeEnvironment.getApplication()
        val v = PaperSheetView(ctx)
        v.addView(android.view.View(ctx), android.view.ViewGroup.LayoutParams(100, 100))
        v.measure(
            MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(470, MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 320, 470)
        if (attached) {
            val a: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            a.setContentView(v)
            v.measure(
                MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(470, MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, 320, 470)
        }
        return v
    }

    @Test
    fun `a fresh sheet is settled`() {
        assertTrue("a sheet that was never animated must be where the design puts it", sheet().isSettled)
    }

    @Test
    fun `the resting sheet is tilted, never square`() {
        val v = sheet()
        // Deliberately a LOW bar. The lean is chosen by eye at 0.5 degrees -- enough to read as
        // paper lifted off the surface, small enough to keep a command line on one line. What must
        // never happen is a perfectly square sheet, which reads as a page replacing the screen.
        assertTrue(
            "a square full-screen sheet reads as a page, not as paper resting on something: " +
                "rotation was ${v.rotation}",
            v.rotation > 0f,
        )
    }

    @Test
    fun `the resting sheet is inset so the background shows around it`() {
        val v = sheet()
        assertTrue("the sheet must not be flush to the full size", v.scaleX < 1f)
        assertEquals(v.scaleX, v.scaleY, 0.0001f)
    }

    @Test
    fun `an unattached sheet snaps open instead of stranding mid-motion`() {
        val v = PaperSheetView(RuntimeEnvironment.getApplication())
        var done = false
        v.present { done = true }
        assertTrue("the caller must be told the motion finished", done)
        assertTrue(v.isSettled)
    }

    @Test
    fun `an unattached dismiss reports completion and leaves the wedge geometry`() {
        val v = PaperSheetView(RuntimeEnvironment.getApplication())
        var done = false
        v.dismiss { done = true }
        assertTrue("the caller must be told the fold finished", done)
        assertFalse("a collapsed sheet is no longer settled", v.isSettled)
    }

    @Test
    fun `an attached sheet animates the expand and settles`() {
        val v = sheet(attached = true)
        v.present()
        assertFalse("the motion must start away from the rest position", v.isSettled)
        // The loop stops on the CLOCK, so the shadow clock has to advance with the looper.
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(800))
        assertTrue("the expand must end settled", v.isSettled)
    }

    @Test
    fun `an attached sheet animates the fold and reports it`() {
        val v = sheet(attached = true)
        var done = false
        v.dismiss { done = true }
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(800))
        assertTrue("the fold must report completion", done)
        assertFalse("a folded sheet is no longer settled", v.isSettled)
    }

    @Test
    fun `settle snaps without animating`() {
        val v = sheet(attached = true)
        v.dismiss()
        v.settle()
        assertTrue("settle is the no-animation path used by a re-show", v.isSettled)
    }

    @Test
    fun `a rotation re-derives the transform from the new size`() {
        val v = sheet()
        val before = v.translationX
        v.measure(
            MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(800, MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 480, 800)
        // A settled sheet has no residual translation, which is the point: the transform is a
        // function of progress and size, never a value remembered from the last frame.
        assertEquals(before, v.translationX, 0.001f)
        assertTrue(v.isSettled)
    }
}
