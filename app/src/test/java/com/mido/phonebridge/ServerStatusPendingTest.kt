package com.mido.phonebridge

import com.mido.phonebridge.ui.ServerStatus
import com.mido.phonebridge.ui.ServerStatus.PENDING_WINDOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settling window for a start or stop tap.
 *
 * This exists because the bug it covers was a screen that contradicted itself: for up to three
 * seconds after a tap the hero said `● Stopped` and "Switch the server on to begin" directly under
 * a switch that was already on. Nothing about the STATE was wrong — it genuinely had not changed
 * yet — but the screen was saying nothing was happening while something was.
 *
 * `isPending` is pure so every way the look ends can be stated as an input, including the one that
 * is impossible to reproduce by hand: a start that was refused, which must not sit behind
 * "Starting" for the rest of the window.
 */
class ServerStatusPendingTest {

    private val tap = 1_000L

    @Test
    fun `pending while a start is still on its way`() {
        assertTrue(ServerStatus.isPending(want = true, up = false, since = tap, now = tap + 500, lastError = ""))
    }

    @Test
    fun `pending while a stop is still on its way`() {
        assertTrue(ServerStatus.isPending(want = false, up = true, since = tap, now = tap + 500, lastError = null))
    }

    @Test
    fun `ends the moment the bridge reaches the asked state`() {
        // The normal case, and the reason this is not just a timer: a bind that lands in 200 ms
        // should stop saying "Starting" in 200 ms.
        assertFalse(ServerStatus.isPending(want = true, up = true, since = tap, now = tap + 10, lastError = ""))
        assertFalse(ServerStatus.isPending(want = false, up = false, since = tap, now = tap + 10, lastError = null))
    }

    @Test
    fun `ends when the window closes`() {
        // Boundaries are asserted on both sides: the window is a half-open interval, and an
        // off-by-one here is a second of a screen claiming to be busy.
        assertTrue(ServerStatus.isPending(want = true, up = false, since = tap, now = tap + PENDING_WINDOW - 1, lastError = ""))
        assertFalse(ServerStatus.isPending(want = true, up = false, since = tap, now = tap + PENDING_WINDOW, lastError = ""))
    }

    @Test
    fun `a refused start ends at once`() {
        // `startBridge` clears lastError before it tries, so an error present afterwards is new
        // and is the screen's most useful line. Hiding it behind "Starting" would show the user a
        // spinner in place of the reason.
        assertFalse(ServerStatus.isPending(want = true, up = false, since = tap, now = tap + 10, lastError = "bind failed"))
    }

    @Test
    fun `a refused stop keeps its window`() {
        // A stop has no error of its own to show, and a stale error from an earlier start must not
        // cut the stop's window short — so only a START is ended early by an error.
        assertTrue(ServerStatus.isPending(want = false, up = true, since = tap, now = tap + 10, lastError = "bind failed"))
    }

    @Test
    fun `no tap means not pending`() {
        assertFalse(ServerStatus.isPending(want = null, up = false, since = 0L, now = 5_000L, lastError = ""))
        assertFalse(ServerStatus.isPending(want = true, up = false, since = 0L, now = 5_000L, lastError = ""))
    }

    @Test
    fun `titles and details differ by direction`() {
        // The direction is the whole message: "Starting" under a switch being switched off would be
        // as wrong as "Stopped" was.
        assertEquals("● Starting", ServerStatus.pendingTitle(starting = true))
        assertEquals("● Stopping", ServerStatus.pendingTitle(starting = false))
        assertTrue(ServerStatus.pendingDetail(starting = true).contains("Opening"))
        assertTrue(ServerStatus.pendingDetail(starting = false).contains("Closing"))
    }
}
