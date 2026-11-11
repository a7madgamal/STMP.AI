package com.mido.phonebridge

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mido.phonebridge.ui.OverflowScrollView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OverflowScrollViewTest {
    @Test
    fun `the cue follows actual overflow and disappears at the end`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val overflow = OverflowScrollView(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(activity), LinearLayout.LayoutParams(100, 2_000))
        }
        overflow.setContent(content)
        activity.setContentView(overflow, ViewGroup.LayoutParams(320, 300))
        overflow.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        )
        overflow.layout(0, 0, 320, 300)
        overflow.scrollView.layout(0, 0, 320, 240)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2_000, View.MeasureSpec.EXACTLY),
        )
        content.layout(0, 0, 320, 2_000)
        overflow.scrollView.scrollTo(0, 0)
        assertTrue(overflow.scrollView.canScrollVertically(1))
        assertTrue(overflow.offBottomForTest())
        overflow.scrollView.scrollTo(0, 2_000)
        assertFalse(overflow.scrollView.canScrollVertically(1))
        assertFalse(overflow.offBottomForTest())
    }

    @Test
    fun `content can only be assigned once`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val overflow = OverflowScrollView(activity)
        overflow.setContent(View(activity))
        val error = runCatching { overflow.setContent(View(activity)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
