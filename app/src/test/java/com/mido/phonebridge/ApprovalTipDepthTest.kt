package com.mido.phonebridge

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.mido.phonebridge.ui.ApprovalTip
import com.mido.phonebridge.ui.ApprovalTipView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch

/**
 * The wedge stack counts what is actually waiting.
 *
 * The number on the tip and the line inside the sheet are two views of one fact, so this asserts
 * the tip against the gate's own count rather than against a number typed into the test: a stack
 * that drifted from the queue would tell the human "one request" while two were pending, which is
 * the one thing this indicator exists to prevent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalTipDepthTest {

    @After
    fun clear() {
        ApprovalGate.setCurrentForTest(null)
        queue().clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(): java.util.ArrayDeque<ApprovalGate.Pending> {
        val f = ApprovalGate::class.java.getDeclaredField("queue").apply { isAccessible = true }
        return f.get(ApprovalGate) as java.util.ArrayDeque<ApprovalGate.Pending>
    }

    private fun presentWith(waiting: Int): ApprovalActivity {
        val now = System.currentTimeMillis()
        val view = ApprovalGate.ApprovalView(
            requester = "OpenClaw",
            account = "me@example.com",
            actionLabel = "RUN COMMAND OVER SSH",
            appLabel = "",
            notifTitle = "",
            notifSender = "",
            notifText = "",
            messageCount = 0,
            replyText = null,
            sections = emptyList(),
        )
        val head = ApprovalGate.Pending(
            requestId = 100L,
            latch = CountDownLatch(1),
            detail = org.json.JSONObject().put("path", "/ssh/box"),
            view = view,
            remote = "100.64.0.7",
            endpoint = "POST /ssh/box",
            summary = "Run a command",
            account = "me@example.com",
            createdAtMs = now,
            shownAtMs = now,
            aiState = ApprovalGate.AiState.NotNeeded,
            enabledAtMs = now - 1,
        )
        ApprovalGate.setCurrentForTest(head)
        queue().clear()
        queue().add(head)
        repeat(waiting) { i ->
            queue().add(
                ApprovalGate.Pending(
                    requestId = 200L + i, latch = CountDownLatch(1), createdAtMs = now,
                )
            )
        }
        val intent = Intent(RuntimeEnvironment.getApplication(), ApprovalActivity::class.java)
            .putExtra(ApprovalActivity.EXTRA_ID, 100L)
            .putExtra(ApprovalActivity.EXTRA_ENDPOINT, "POST /ssh/box")
            .putExtra(ApprovalActivity.EXTRA_SUMMARY, "Run a command")
        val activity = Robolectric.buildActivity(ApprovalActivity::class.java, intent).setup().get()
        // Lay the window out explicitly. The tip only rebuilds its paths once it has a size, so a
        // test that read `drawnWedges` before layout would be asserting about a view that has not
        // been measured yet — and would pass or fail depending on what ran before it.
        val decor = activity.window.decorView
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(411, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(891, android.view.View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 411, 891)
        ShadowLooper.idleMainLooper()
        return activity
    }

    private fun tip(a: ApprovalActivity): ApprovalTipView {
        val found = findTip(a.window.decorView)
        assertNotNull("the resting tip must exist for the whole life of the request", found)
        return found!!
    }

    private fun findTip(v: View): ApprovalTipView? {
        if (v is ApprovalTipView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findTip(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun `a lone request shows one wedge`() {
        val a = presentWith(waiting = 0)
        assertEquals(1, tip(a).drawnWedges)
    }

    @Test
    fun `a second request adds a second wedge`() {
        val a = presentWith(waiting = 1)
        assertEquals(2, tip(a).drawnWedges)
    }

    @Test
    fun `a third request fills the stack`() {
        val a = presentWith(waiting = 2)
        assertEquals(3, tip(a).drawnWedges)
    }

    @Test
    fun `a long queue still draws only three`() {
        val a = presentWith(waiting = 6)
        assertEquals(
            "a fourth sheet would be a second stack, and none of them can be acted on yet",
            ApprovalTip.MAX_VISIBLE,
            tip(a).drawnWedges,
        )
    }

    @Test
    fun `the tip and the sheet agree about how many are waiting`() {
        // The same live count drives both surfaces; asserting them together is what stops the
        // two drifting into different answers on one screen.
        val a = presentWith(waiting = 2)
        val gateCount = ApprovalGate.waitingBehindCurrent()
        assertEquals(2, gateCount)
        assertEquals(gateCount + 1, tip(a).drawnWedges)
    }

    @Test
    fun `the stack updates from the tick without reopening the sheet`() {
        val a = presentWith(waiting = 0)
        assertEquals(1, tip(a).drawnWedges)
        // A follower arrives while the tip is resting.
        queue().add(
            ApprovalGate.Pending(
                requestId = 999L, latch = CountDownLatch(1), createdAtMs = System.currentTimeMillis(),
            )
        )
        // Let the 120ms tick run.
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(400))
        assertEquals(
            "the resting stack must follow the queue without anyone opening the sheet",
            2,
            tip(a).drawnWedges,
        )
    }
}
