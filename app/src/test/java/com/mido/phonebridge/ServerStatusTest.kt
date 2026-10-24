package com.mido.phonebridge

import com.mido.phonebridge.ui.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The precedence guard for [ServerStatus].
 *
 * This exists because the bug it covers was invisible by construction: the render order put
 * `!serverUp` before `lastError`, and `BridgeService` stops itself on a refused bind — so the
 * exact situation that produces an error also produces `serverUp=false`, and the reason was
 * never reached. The user saw "Tap START SERVER to begin" after every failed tap and reported
 * *"i click start server nothing happens"*. Mido, 2026-10-20: *"on server fail user should see
 * clear error"*.
 *
 * **The fail-a-start case is first on purpose.** It is the one branch that was broken, and a
 * test ordered by importance is read in the order the bugs matter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServerStatusTest {

    @Test
    fun `a stopped server carrying an error is FAILED, not STOPPED`() {
        // The regression, stated as an input: the service bound nothing (so serverUp is
        // false) and left the reason behind. Both true at once is NORMAL, not an edge case.
        val state = ServerStatus.state(
            serverUp = false,
            listenerOk = true,
            notifOk = true,
            lastError = "No Tailscale address on this phone.",
        )
        assertEquals(ServerStatus.State.FAILED, state)
    }

    @Test
    fun `FAILED beats STOPPED even when the listener and permission are healthy`() {
        // A refused bind says nothing about the other two, so they must not mask the failure.
        assertEquals(
            ServerStatus.State.FAILED,
            ServerStatus.state(false, true, true, "Could not bind port 8787: bind failed"),
        )
        assertEquals(
            ServerStatus.State.FAILED,
            ServerStatus.state(false, false, false, "Could not bind port 8787: bind failed"),
        )
    }

    @Test
    fun `a quiet stopped server is STOPPED and offers the tap`() {
        val state = ServerStatus.state(false, true, true, "")
        assertEquals(ServerStatus.State.STOPPED, state)
        assertEquals("Tap START SERVER to begin", ServerStatus.detail(state, "", null, "", 8787))
    }

    @Test
    fun `a running server is never FAILED, even with a stale error string`() {
        // A successful bind clears lastError, so this pairing should not occur. If it ever
        // does, "running" is the fact a human needs most — a failure state here would make a
        // serving bridge look dead.
        assertEquals(
            ServerStatus.State.RUNNING_HEALTHY,
            ServerStatus.state(true, true, true, "stale reason"),
        )
        assertEquals(
            ServerStatus.State.RUNNING_ATTENTION,
            ServerStatus.state(true, false, true, "stale reason"),
        )
    }

    @Test
    fun `RUNNING_HEALTHY needs all three, and each missing one is ATTENTION`() {
        assertEquals(ServerStatus.State.RUNNING_HEALTHY, ServerStatus.state(true, true, true, ""))
        assertEquals(
            ServerStatus.State.RUNNING_ATTENTION,
            ServerStatus.state(true, true, false, ""),
        )
        assertEquals(
            ServerStatus.State.RUNNING_ATTENTION,
            ServerStatus.state(true, false, true, ""),
        )
    }

    @Test
    fun `the failed title and detail say what happened`() {
        val err = "No Tailscale address on this phone. Open the Tailscale app, connect it, " +
            "then tap START SERVER again."
        val state = ServerStatus.state(false, true, true, err)
        assertEquals("● Could not start", ServerStatus.title(state))
        // Verbatim: the reason is the payload. Paraphrasing it would mean two strings to keep
        // in step, and the one on screen would be the one nobody tested.
        assertEquals(err, ServerStatus.detail(state, err, null, "", 8787))
    }

    @Test
    fun `the checklist row does not say 'stopped' during a failure`() {
        // "stopped" under a red "Could not start" reads like a contradiction; the row points
        // at the reason instead.
        val failed = ServerStatus.state(false, true, true, "boom")
        assertTrue(ServerStatus.checklistValue(failed, "", 8787).contains("error"))
        assertEquals("stopped", ServerStatus.checklistValue(ServerStatus.State.STOPPED, "", 8787))
        assertEquals(
            "running on 100.64.0.11:8787",
            ServerStatus.checklistValue(ServerStatus.State.RUNNING_HEALTHY, "100.64.0.11", 8787),
        )
    }

    @Test
    fun `every state has a distinct title`() {
        // Two states sharing a title is how "could not start" would silently become "stopped"
        // again — the enum would still be right while the screen told the old story.
        val titles = ServerStatus.State.entries.map { ServerStatus.title(it) }
        assertEquals(titles.size, titles.toSet().size)
    }
}
