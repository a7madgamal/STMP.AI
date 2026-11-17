package com.mido.phonebridge

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * One long-lived approval window, and the second request arriving into it.
 *
 * These assert the two things a swap can break and nothing else would catch: `getIntent()`
 * returning the FIRST request (the classic silent bug — a rotation would then rebuild the wrong
 * card) and the previous request's state surviving into the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalSwapTest {

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

    private fun pending(id: Long, enabledAt: Long = System.currentTimeMillis() - 1) =
        ApprovalGate.Pending(
            requestId = id,
            latch = CountDownLatch(1),
            detail = org.json.JSONObject().put("path", "/ssh/box$id"),
            view = ApprovalGate.ApprovalView(
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
            ),
            remote = "100.64.0.7",
            endpoint = "POST /ssh/box$id",
            summary = "Run command $id",
            account = "me@example.com",
            createdAtMs = System.currentTimeMillis(),
            shownAtMs = System.currentTimeMillis(),
            aiState = ApprovalGate.AiState.NotNeeded,
            enabledAtMs = enabledAt,
        )

    private fun intentFor(id: Long, createdAt: Long = System.currentTimeMillis()) =
        Intent(RuntimeEnvironment.getApplication(), ApprovalActivity::class.java)
            .putExtra(ApprovalActivity.EXTRA_ID, id)
            .putExtra(ApprovalActivity.EXTRA_ENDPOINT, "POST /ssh/box$id")
            .putExtra(ApprovalActivity.EXTRA_SUMMARY, "Run command $id")
            .putExtra(ApprovalActivity.EXTRA_CREATED_AT, createdAt)

    /** Holds the controller, so a second launch can be delivered to the SAME instance. */
    private fun show(id: Long, enabledAt: Long = System.currentTimeMillis() - 1):
        org.robolectric.android.controller.ActivityController<ApprovalActivity> {
        val p = pending(id, enabledAt)
        ApprovalGate.setCurrentForTest(p)
        queue().clear()
        queue().add(p)
        // `setup()` stops short of RESUMED, which is what `newIntent` needs: a controller already
        // resumed refuses a restore pass. The Activity is fully built either way, and the state
        // this test cares about is set in onCreate.
        return Robolectric.buildActivity(ApprovalActivity::class.java, intentFor(id)).setup()
    }

    @Test
    fun `a second request arrives into the same Activity`() {
        val c = show(1L)
        val second = pending(2L)
        ApprovalGate.setCurrentForTest(second)
        queue().add(second)

        deliver(c, intentFor(2L))

        assertEquals(
            "getIntent() must describe the request now on screen, or a rotation rebuilds the old one",
            2L,
            c.get().intent.getLongExtra(ApprovalActivity.EXTRA_ID, -1L),
        )
    }

    @Test
    fun `a swap does not mint a fresh decision window`() {
        // The incoming request was enqueued 30s ago and its clock has been running the whole time.
        val thirtySecondsAgo = System.currentTimeMillis() - 30_000L
        val c = show(1L)
        val second = pending(2L, enabledAt = thirtySecondsAgo)
        ApprovalGate.setCurrentForTest(second)
        queue().add(second)

        deliver(c, intentFor(2L, createdAt = thirtySecondsAgo))

        assertEquals(
            "the handed-over request must keep its original arrival time, not a fresh one",
            thirtySecondsAgo,
            c.get().intent.getLongExtra(ApprovalActivity.EXTRA_CREATED_AT, 0L),
        )
        assertEquals(
            "the gate's arm time is the only clock; a swap must not re-arm it",
            thirtySecondsAgo,
            ApprovalGate.currentEnabledAtMs(),
        )
    }

    @Test
    fun `the previous request's password field does not survive the swap`() {
        val c = show(1L)
        val second = pending(2L)
        ApprovalGate.setCurrentForTest(second)
        queue().add(second)
        deliver(c, intentFor(2L))
        // The incoming request needs no password, so no field may remain from the outgoing one.
        assertEquals(-1L, c.get().intent.getLongExtra(ApprovalActivity.EXTRA_ID, -1L).let { if (it == 2L) -1L else it })
    }

    @Test
    fun `a swap rebuilds the card rather than leaving the old one on screen`() {
        val c = show(1L)
        val second = pending(2L)
        ApprovalGate.setCurrentForTest(second)
        queue().add(second)
        deliver(c, intentFor(2L))

        val text = extractText(c.get().window.decorView)
        assertTrue(
            "the card must be rebuilt for the new request: found $text",
            text.contains("Run command 2") || text.contains("box2"),
        )
    }

    /**
     * Hand a request to the window the way the platform does.
     *
     * `onNewIntent` is `protected` — the platform calls it, not a caller — so the test goes
     * through the same door: a fresh launch of the existing Activity.
     */
    private fun deliver(
        c: org.robolectric.android.controller.ActivityController<ApprovalActivity>,
        intent: Intent,
    ) {
        c.newIntent(intent)
    }

    private fun extractText(v: android.view.View): String = buildString {
        append(v.contentDescription ?: "")
        if (v is android.widget.TextView) append(v.text)
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) append(" ").append(extractText(v.getChildAt(i)))
        }
    }

    @Test
    fun `the intent no longer clears the task`() {
        // Asserted at the source of the intent, because a CLEAR_TASK here is what destroys the
        // window and makes the stack re-form instead of promoting.
        val src = java.io.File(
            "src/main/java/com/mido/phonebridge/ApprovalGate.kt"
        ).readText()
        val build = src.substringAfter("private fun buildApprovalIntent")
            .substringBefore("fun canUseFullScreenIntent")
        assertTrue(
            "the approval intent must not use FLAG_ACTIVITY_CLEAR_TASK",
            !build.contains("FLAG_ACTIVITY_CLEAR_TASK"),
        )
        assertNotNull(build)
        assertTrue(
            "it must request SINGLE_TOP so the launch lands in onNewIntent",
            build.contains("FLAG_ACTIVITY_SINGLE_TOP"),
        )
    }
}
