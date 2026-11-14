package com.mido.phonebridge.notify

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The "+N more lines" count must equal what the summary TextView really lays out. */
@RunWith(RobolectricTestRunner::class)
class NotifySummaryLineCountTest {

    @Test
    fun `computed line count equals the uncapped view line count near the 3 to 4 line boundary`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = "Delete the notification from Signal for the work-company account now "
        for (reps in 1..8) for (width in intArrayOf(300, 420, 560, 700)) {
            val tv = TextView(ctx).apply {
                text = base.repeat(reps).trim()
                textSize = 16f
                NotifyOverlay.pinSummaryBreaking(this)
            }
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED
            )
            tv.layout(0, 0, width, tv.measuredHeight)
            assertEquals("reps=$reps width=$width", tv.lineCount, NotifyOverlay.summaryFullLineCount(tv, width))
        }
    }
}
