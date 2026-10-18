package com.mido.phonebridge.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heuristic summariser is pure, so its rules are asserted here rather than
 * discovered on a device.
 *
 * The risk rule is the one worth guarding: it is a display-only signal, and a
 * display-only signal that can *understate* danger is worse than no signal. The
 * central invariant below is that the heuristic may widen risk but must never
 * narrow it — a property a future edit could quietly break.
 */
class HeuristicSummarizerTest {

    private fun facts(
        verb: String,
        routeLabel: String = "Do the thing",
        summary: String = "do the thing",
        rows: List<Pair<String, String>> = emptyList()
    ) = RequestFacts(
        plugin = "test",
        verb = verb,
        routeLabel = routeLabel,
        summary = summary,
        rows = rows
    )

    @Test
    fun `read is low risk`() {
        assertEquals(Risk.LOW, HeuristicSummarizer.summarize(facts("READ")).risk)
    }

    @Test
    fun `edit and write are medium risk`() {
        assertEquals(Risk.MEDIUM, HeuristicSummarizer.summarize(facts("EDIT")).risk)
        assertEquals(Risk.MEDIUM, HeuristicSummarizer.summarize(facts("WRITE")).risk)
    }

    @Test
    fun `delete and execute are high risk`() {
        assertEquals(Risk.HIGH, HeuristicSummarizer.summarize(facts("DELETE")).risk)
        assertEquals(Risk.HIGH, HeuristicSummarizer.summarize(facts("EXECUTE")).risk)
    }

    /**
     * An unknown verb must not be treated as harmless. The failure mode this guards is
     * a future sixth `Verb` value silently reading as LOW because the `when` fell
     * through to a permissive default.
     */
    @Test
    fun `unknown verb is not low risk`() {
        assertTrue(HeuristicSummarizer.summarize(facts("TELEPORT")).risk != Risk.LOW)
    }

    /**
     * A write that sends to an external address is widened to HIGH. This is the row
     * pass earning its place: the verb alone (WRITE) would understate "sends mail to a
     * stranger".
     */
    @Test
    fun `write to an external address is widened to high`() {
        val s = HeuristicSummarizer.summarize(
            facts("WRITE", rows = listOf("TO" to "someone@example.com"))
        )
        assertEquals(Risk.HIGH, s.risk)
    }

    /**
     * The invariant: widening only. A READ that happens to name an address must stay
     * LOW — the heuristic may never LOWER a risk, and a READ is already the floor.
     */
    @Test
    fun `read with an external address stays low`() {
        val s = HeuristicSummarizer.summarize(
            facts("READ", rows = listOf("TO" to "someone@example.com"))
        )
        assertEquals(Risk.LOW, s.risk)
    }

    /** The subject is chosen by label preference, not row order. */
    @Test
    fun `subject prefers a meaningful label over row order`() {
        val s = HeuristicSummarizer.summarize(
            facts("WRITE", rows = listOf("NOISE" to "first", "SUBJECT" to "the real subject"))
        )
        assertTrue(s.description.contains("the real subject"))
    }

    /** With no rows there is nothing to name, so the act stands alone — never a colon with nothing after it. */
    @Test
    fun `description never ends with a dangling separator`() {
        val s = HeuristicSummarizer.summarize(facts("READ", routeLabel = "List notifications"))
        assertTrue(s.description == "List notifications")
    }

    @Test
    fun `description always names the act`() {
        val s = HeuristicSummarizer.summarize(facts("DELETE", routeLabel = "TRASH EMAIL"))
        assertTrue(s.description.startsWith("TRASH EMAIL"))
    }
}
