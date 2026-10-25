package com.mido.phonebridge

import com.mido.phonebridge.core.BridgeStatusNotice
import com.mido.phonebridge.core.BridgeStatusNotice.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status notification's copy and behaviour.
 *
 * **Why this is a test and not three inline `if`s.** The copy is the only thing the human reads
 * when the bridge is down, and it used to live as string literals inside `BridgeService` — so a
 * new state could be added while the notification still described the old one, with no error and
 * no log. Extracting it means the states are enumerable and every one can be asserted.
 *
 * The `WAITING_FOR_TAILNET` case is the one that matters most: it exists because the service used
 * to stop after the retry ladder, which meant reconnecting Tailscale left the bridge down. Its
 * copy has to tell the human that doing NOTHING is correct, which is the opposite of what the old
 * "not running" wording implied.
 */
class BridgeStatusNoticeTest {

    @Test
    fun `every kind has a distinct title`() {
        // Two kinds sharing a title is how "waiting for Tailscale" would silently read as
        // "not running" — the enum would be right while the screen told the old story.
        val titles = Kind.entries.map { BridgeStatusNotice.title(it) }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `the waiting copy says it starts by itself`() {
        // The whole point of the state: the required action is none. If this copy ever tells the
        // human to do something, the state has lost its reason to exist.
        val text = BridgeStatusNotice.text(Kind.WAITING_FOR_TAILNET, 0, 4)
        assertTrue(
            "waiting copy must say it recovers on its own, got: $text",
            text.contains("by itself", ignoreCase = true),
        )
    }

    @Test
    fun `the retrying copy carries the attempt count`() {
        val text = BridgeStatusNotice.text(Kind.RETRYING, 2, 4)
        assertTrue("must show the attempt, got: $text", text.contains("2/4"))
    }

    @Test
    fun `only the finished state is dismissible`() {
        // A notification the human can swipe away while the service is still working would let
        // them remove the only sign that it is waiting. STOPPED is a finished outcome, so it is
        // the one case that may be dismissed.
        assertTrue(BridgeStatusNotice.ongoing(Kind.RETRYING))
        assertTrue(BridgeStatusNotice.ongoing(Kind.WAITING_FOR_TAILNET))
        assertFalse(BridgeStatusNotice.ongoing(Kind.STOPPED))
    }

    @Test
    fun `ongoing and autoCancel are always opposites`() {
        // Derived rather than stored separately, and this pins it: a notification that is both
        // ongoing and auto-cancelling is a contradiction the platform resolves unpredictably.
        for (kind in Kind.entries) {
            assertEquals(
                "kind=$kind",
                !BridgeStatusNotice.ongoing(kind),
                BridgeStatusNotice.autoCancel(kind),
            )
        }
    }

    @Test
    fun `the stopped copy names Tailscale as the thing to check`() {
        // The only state that asks the human to act, so it has to say what to act ON.
        val text = BridgeStatusNotice.text(Kind.STOPPED, 0, 4)
        assertTrue("got: $text", text.contains("Tailscale"))
    }
}
