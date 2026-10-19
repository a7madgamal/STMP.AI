package com.mido.phonebridge

import com.mido.phonebridge.core.SummaryGuard
import com.mido.phonebridge.core.Verb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that stops a small model misleading the human.
 *
 * Every case here is a real failure or a real thing the model gets right. The first one is the
 * report that prompted this: a READ summarised as "Adding 8 todos" because the prompt contained a
 * worked example the model then parroted onto every request.
 */
class SummaryGuardTest {

    // The facts of a real read: a list of open tasks. No count is stated, and nothing is added.
    private val readFacts = """
        - TARGET: GET https://api.todoist.com/api/v1/tasks
        - SECRET: todoist
        - HOST: api.todoist.com
    """.trimIndent()

    @Test
    fun `the reported bug is rejected`() {
        // "Adding 8 todos" on a READ: the verb is wrong AND the count is invented. Either
        // check alone is enough; both firing is the expected shape of a parroted example.
        assertFalse(
            SummaryGuard.allows("Adding 8 todos", Verb.READ, readFacts)
        )
    }

    @Test
    fun `an accurate read summary is allowed`() {
        assertTrue(SummaryGuard.allows("Reading your open tasks", Verb.READ, readFacts))
        assertTrue(SummaryGuard.allows("Listing todos in OpenClaw", Verb.READ, readFacts))
        assertTrue(SummaryGuard.allows("Fetching task list", Verb.READ, readFacts))
    }

    @Test
    fun `a mutating verb may say it mutates`() {
        // The same sentence is CORRECT for a write. The guard checks the verb, not the wording.
        assertTrue(SummaryGuard.allows("Adding 8 todos", Verb.WRITE, readFacts))
    }

    @Test
    fun `an invented count is rejected even when the verb is right`() {
        // A believable number is the most dangerous kind of error: "12 tasks" reads as measured.
        assertFalse(
            SummaryGuard.allows("Adding 12 tasks", Verb.WRITE, "no numbers here")
        )
    }

    @Test
    fun `a count present in the facts is allowed`() {
        val facts = "- PAYLOAD: 8 commands (item_add ×8)"
        assertTrue(SummaryGuard.allows("Adding 8 todos", Verb.WRITE, facts))
    }

    @Test
    fun `a read that mentions reading and a negation is not rejected`() {
        // "Listing tasks, adding nothing" is accurate English for a read. Rejecting it would
        // throw away a correct summary because of a word used in negation.
        assertTrue(
            SummaryGuard.allows("Listing tasks, adding nothing", Verb.READ, readFacts)
        )
    }

    @Test
    fun `an empty description is not shown`() {
        // A blank line where a summary belongs reads as a rendering bug, not as "no summary".
        assertFalse(SummaryGuard.allows("", Verb.READ, readFacts))
        assertFalse(SummaryGuard.allows("   ", Verb.READ, readFacts))
    }

    @Test
    fun `single digits are left alone`() {
        // "one task" / "1 item" is ordinary prose, not a measured quantity worth policing;
        // over-strictness here discards good summaries.
        assertTrue(SummaryGuard.allows("Reading 1 task", Verb.READ, readFacts))
    }
}
