package com.mido.phonebridge

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.mido.phonebridge.ui.EdgeScrimScrollView
import com.mido.phonebridge.ui.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two affordances that say "this command continues sideways".
 *
 * **Why this is asserted and not eyeballed.** A command that scrolls off the right edge looks
 * identical to a command that ends there, and the part nobody scrolled to is the part nobody
 * approved. So the claim is not cosmetic: at least one of the two affordances must be present
 * whenever content is reachable. A scrim that never appears, or a scroller that silently wraps,
 * turns an approval of the visible half into an approval of the whole command.
 *
 * Robolectric lays out real views, so the widths here are measured rather than faked — which is what
 * makes "is there anything to reach" a question this test can answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class CodeScrollerTest {

    private val activity: Activity
        get() = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun scroller(command: String, widthPx: Int): EdgeScrimScrollView {
        val tv = TextView(activity).apply {
            text = command
            textSize = Theme.TEXT_BODY_SP
            typeface = Theme.monoFont(activity)
        }
        val view = EdgeScrimScrollView(
            activity,
            tv,
            shadowColor = Theme.ON_SURFACE,
            scrimWidthPx = Theme.dpF(activity, 20f),
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /** The scroller, found by type — the block is one scroller plus one scrim above it. */
    private fun innerScroll(v: EdgeScrimScrollView): android.widget.HorizontalScrollView {
        val group = v.getChildAt(0) as android.view.ViewGroup
        return group as android.widget.HorizontalScrollView
    }

    /**
     * A SHORT command must not be scrollable, and must not claim to be.
     *
     * This is the half that protects the affordance from becoming noise: a permanent shadow on a
     * command that fits tells the human there is more when there is not, and after the second time
     * they stop believing the shadow on the commands where it matters.
     */
    @Test
    fun `a command that fits does not overflow`() {
        val v = scroller("ls -la", 700)
        val scroll = innerScroll(v)
        val content = scroll.getChildAt(0)
        assertTrue(
            "content (${content.width}px) must fit the ${scroll.width}px scroller",
            content.width <= scroll.width,
        )
        // No scrollable range therefore means canScrollHorizontally is false in both directions.
        assertTrue(!scroll.canScrollHorizontally(-1))
        assertTrue(!scroll.canScrollHorizontally(1))
    }

    /** A long command DOES overflow, which is the precondition for both affordances. */
    @Test
    fun `a long command overflows and can be scrolled`() {
        val v = scroller("echo " + "x".repeat(300), 300)
        val scroll = innerScroll(v)
        val content = scroll.getChildAt(0)
        assertTrue(
            "content (${content.width}px) must exceed the ${scroll.width}px scroller",
            content.width > scroll.width,
        )
        assertTrue("there must be somewhere to scroll to", scroll.canScrollHorizontally(1))
    }

    /**
     * The scroller never wraps: its child is measured unbounded, so a 300-character token stays one
     * line. A wrapped child would be narrower and taller, and `canScrollHorizontally` would say no —
     * the exact silent failure that makes the bar and the scrim both lie.
     */
    @Test
    fun `a long token stays on one line`() {
        val v = scroller("echo " + "x".repeat(300), 300)
        val tv = innerScroll(v).getChildAt(0) as TextView
        assertEquals("a long token must not wrap", 1, tv.lineCount)
    }

    /** The scrim is a child above the content and must never intercept the drag. */
    @Test
    fun `the scrim does not take touches`() {
        val v = scroller("echo " + "x".repeat(300), 300)
        val scrim = v.getChildAt(1)
        assertTrue("scrim must exist as a sibling above the scroller", scrim !== innerScroll(v))
        assertTrue("the scrim must not be clickable", !scrim.isClickable)
        assertTrue(
            "the scrim must not consume touches",
            !scrim.onTouchEvent(android.view.MotionEvent.obtain(
                0L, 0L, android.view.MotionEvent.ACTION_DOWN, 5f, 5f, 0,
            )),
        )
    }
}
