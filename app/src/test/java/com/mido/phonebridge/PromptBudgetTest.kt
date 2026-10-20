package com.mido.phonebridge

import com.mido.phonebridge.core.PromptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt budget rule.
 *
 * Replaces a flat `take(200)` per row, which was a guard against a runaway prompt but broke the
 * case the AI exists for: a 5.3 KB Todoist payload arrived as a 200-character prefix, so the
 * model could not count what it was approving (Mido, 2026-10-19: *"the payload is the whole
 * reason I added AI"*).
 *
 * The constraint is on the TOTAL, not per row — the context window does not care how the
 * characters are distributed. These tests assert that division, because a wrong number here
 * either overflows the window (a failed request, since the AI is core) or clips a row to a
 * fragment the model will misread as whole.
 */
class PromptBudgetTest {

    @Test
    fun `more rows get less each, and the total always fits`() {
        // The property that matters: whatever the row count, the rendered prompt stays inside
        // the budget. This is what a flat per-row cap could not guarantee.
        for (n in 1..20) {
            val rows = (1..n).map { "ROW$it" to "x".repeat(5_000) }
            val rendered = PromptBudget.render(rows)
            val budget = PromptBudget.MAX_TOTAL_CHARS + n * 40 // allowance for labels/newlines
            assertTrue(
                "n=$n rendered ${rendered.length} chars, over budget $budget",
                rendered.length <= budget
            )
        }
    }

    @Test
    fun `two rows get the even split, which is what actually fits`() {
        // NOT "the per-row max". With a 16,000 total and 2 rows the even split is 8,000, which is
        // under MAX_PER_ROW (12,000) — so the cap is the SPLIT, not the ceiling. The old assertion
        // demanded the max and only passed because the total was small enough that the ceiling
        // always bound first. Asserting the real relationship keeps this test honest after a
        // budget change instead of pinning a number that happens to be true today.
        val cap = PromptBudget.capFor(2)
        assertEquals(PromptBudget.MAX_TOTAL_CHARS / 2, cap)
        assertTrue("cap must never exceed the per-row ceiling", cap <= PromptBudget.MAX_PER_ROW)
    }

    @Test
    fun `a real 5 KB payload fits without clipping`() {
        // The complaint that raised the budget: a representative Todoist body is ~4,900 chars and
        // was being cut to a 600-char prefix. It must now pass through whole.
        val body = "{\"" + "k\":\"v\"," .repeat(0) // placeholder to keep the diff readable
        val real = "x".repeat(4_931)
        val rendered = PromptBudget.render(listOf("BODY" to real))
        assertFalse("a 5 KB payload must not be clipped: ${rendered.length} chars", rendered.contains("…"))
    }

    @Test
    fun `many rows still get a usable fragment, never zero`() {
        // The floor exists because an empty allowance renders bare labels, which reads as
        // "this field is empty" rather than "this field was clipped".
        assertTrue(PromptBudget.capFor(50) >= PromptBudget.MIN_PER_ROW)
    }

    @Test
    fun `a clipped value is marked so the model cannot mistake it for whole`() {
        // Silence is the bug being fixed here as much as the length: the old cap clipped
        // without a trace, so the model asserted confidently about half a payload.
        val rows = listOf("BODY" to "y".repeat(PromptBudget.MAX_PER_ROW + 500))
        val rendered = PromptBudget.render(rows)
        assertTrue("clipped value must be marked", rendered.contains("…"))
        assertTrue("the mark must come after the excerpt", rendered.indexOf("…") > 10)
    }

    @Test
    fun `a short value is left exactly as it is`() {
        val rendered = PromptBudget.render(listOf("HOST" to "api.todoist.com"))
        assertEquals("- HOST: api.todoist.com", rendered)
        assertTrue("an unclipped value must not carry the mark", !rendered.contains("…"))
    }

    @Test
    fun `no rows renders an explicit none, not an empty string`() {
        // An empty facts block would read as a formatting failure rather than "there is
        // nothing to add".
        assertEquals("(none)", PromptBudget.render(emptyList()))
    }
}
