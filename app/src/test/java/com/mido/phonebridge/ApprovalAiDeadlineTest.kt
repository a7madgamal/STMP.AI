package com.mido.phonebridge

import com.mido.phonebridge.ai.RequestFacts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * The modal must never sit on "summarising…" forever.
 *
 * WHY THIS EXISTS
 *   `Pending.aiState` starts as [ApprovalGate.AiState.Loading] and only the fetch worker
 *   ever moves it. If that worker dies before its own `try`, or is parked on something the
 *   model's internal budget does not cover, the modal keeps saying "summarising…" with
 *   DISABLED buttons — the human cannot even deny. That is the report: *"stuck at
 *   summarising..."*.
 *
 *   The NOTIFY card had the same shape and was fixed in 0.43.68 by giving its tick a
 *   deadline. The fix belongs HERE, in the gate's state accessor, because the accessor is
 *   the single place any reader can obtain a Loading — a bound enforced by whichever UI
 *   happens to poll would be missing for every other reader.
 *
 * WHAT THIS PINS
 *   A Loading past its budget becomes a stated Failed, and the buttons unlock. A Loading
 *   still inside its budget stays Loading, so the summary is never killed by impatience.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalAiDeadlineTest {

    @After
    fun clear() {
        ApprovalGate.setCurrentForTest(null)
    }

    private fun pending(startedAtMs: Long, capMs: Long = 10_000L): ApprovalGate.Pending =
        ApprovalGate.Pending(
            requestId = 9001L,
            latch = CountDownLatch(1),
            aiFacts = RequestFacts(
                plugin = "gmail",
                verb = "READ",
                routeLabel = "READ EMAIL",
                summary = "read a message",
                rows = emptyList(),
            ),
            aiFetchCapMs = capMs,
            shownAtMs = 1L,
        ).also { it.fetchStartedAtMs = startedAtMs }

    @Test
    fun `a Loading state past its budget becomes a stated failure`() {
        val cap = 10_000L
        // Started well beyond cap + slack, so this Loading is definitively expired.
        val stale = System.currentTimeMillis() - (cap + ApprovalGate.AI_DEADLINE_SLACK_MS + 5_000L)
        val p = pending(startedAtMs = stale, capMs = cap)
        ApprovalGate.setCurrentForTest(p)

        val state = ApprovalGate.currentAiState()

        assertTrue("an expired Loading must not be handed to the UI", state is ApprovalGate.AiState.Failed)
        val failed = state as ApprovalGate.AiState.Failed
        assertTrue(
            "the failure must NAME the budget, or the human sees a dead modal with no reason",
            failed.detail.contains(com.mido.phonebridge.ai.SummaryFailure.humanMs(cap))
        )
        assertFalse(
            "the budget must be phrased in seconds, not raw milliseconds " +
                "(Mido, 2026-11-03: \"write the message in seconds\")",
            failed.detail.contains("10000ms")
        )
        assertNotEquals(
            "the buttons must unlock so the human can still decide",
            0L,
            p.enabledAtMs
        )
    }

    @Test
    fun `a Loading state inside its budget stays Loading`() {
        // Just started, well inside the cap: the summary must be allowed to arrive.
        val p = pending(startedAtMs = System.currentTimeMillis(), capMs = 10_000L)
        ApprovalGate.setCurrentForTest(p)

        assertEquals(
            "a fresh fetch must not be killed by impatience",
            ApprovalGate.AiState.Loading,
            ApprovalGate.currentAiState()
        )
    }

    @Test
    fun `before the fetch starts there is no deadline to enforce`() {
        // fetchStartedAtMs == 0 means the request is queued but not yet on screen. The
        // fetch has not begun, so timing it from the modal's construction would expire a
        // request that never started.
        val p = pending(startedAtMs = 0L)
        ApprovalGate.setCurrentForTest(p)

        assertEquals(
            ApprovalGate.AiState.Loading,
            ApprovalGate.currentAiState()
        )
        assertEquals("nothing has been decided, so nothing is armed", 0L, p.enabledAtMs)
    }

    @Test
    fun `no pending request reports NotNeeded rather than Loading`() {
        ApprovalGate.setCurrentForTest(null)
        assertEquals(
            ApprovalGate.AiState.NotNeeded,
            ApprovalGate.currentAiState()
        )
    }

    @Test
    fun `an expired Loading is converted once, not re-evaluated on every read`() {
        val cap = 10_000L
        val stale = System.currentTimeMillis() - (cap + ApprovalGate.AI_DEADLINE_SLACK_MS + 5_000L)
        val p = pending(startedAtMs = stale, capMs = cap)
        ApprovalGate.setCurrentForTest(p)

        val first = ApprovalGate.currentAiState()
        val second = ApprovalGate.currentAiState()

        assertTrue(first is ApprovalGate.AiState.Failed)
        // The state was written back, so a second reader sees the SAME resolved failure
        // rather than a freshly constructed one with a new timestamp.
        assertTrue("the gate must persist the verdict", p.aiState is ApprovalGate.AiState.Failed)
        assertEquals(first, second)
    }
}
