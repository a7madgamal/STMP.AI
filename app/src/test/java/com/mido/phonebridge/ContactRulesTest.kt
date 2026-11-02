package com.mido.phonebridge

import com.mido.phonebridge.plugins.contacts.ContactRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact search rules, as rules.
 *
 * The redaction is the one with a real consequence: it decides how much of someone else's phone
 * number survives into an append-only log. That is a decision worth a test rather than a look at
 * a device, because the failure mode is silent — a mask that keeps too much still "works".
 */
class ContactRulesTest {

    @Test
    fun `an empty query is refused, never treated as list-all`() {
        // The whole security property of this route: no query means no answer, not every contact.
        assertNotNull(ContactRules.rejectReason(""))
        assertNotNull(ContactRules.rejectReason("   "))
    }

    @Test
    fun `a one-character query is refused`() {
        // 'a' matches a large fraction of any real address book — a download, not a search.
        assertNotNull(ContactRules.rejectReason("a"))
        assertNull("two characters is the shortest real search", ContactRules.rejectReason("ab"))
    }

    @Test
    fun `an over-long query is refused`() {
        assertNotNull(ContactRules.rejectReason("x".repeat(ContactRules.MAX_QUERY + 1)))
        assertNull(ContactRules.rejectReason("x".repeat(ContactRules.MAX_QUERY)))
    }

    @Test
    fun `half the digits survive redaction`() {
        // Mido, 2026-11-02: *"audit both but keep number half redacted"*.
        assertEquals("+49 151 12•• ••••", ContactRules.redactHalf("+49 151 1234 5678"))
    }

    @Test
    fun `an odd digit count keeps the extra digit rather than losing it`() {
        // Rounding down would mask MORE than half on 7 digits, and "half" is the promise.
        // 7 digits → keep 4.
        assertEquals("1234•••", ContactRules.redactHalf("1234567"))
    }

    @Test
    fun `formatting survives so the log stays readable`() {
        val masked = ContactRules.redactHalf("(030) 123456")
        assertTrue("separators must not be masked", masked.contains("("))
        assertTrue(masked.contains(")"))
        assertTrue(masked.contains(" "))
        assertTrue("some digits must be masked", masked.contains('•'))
    }

    @Test
    fun `a string with no digits is returned unchanged`() {
        // Inventing a mask for something that is not a number would misreport the data.
        assertEquals("no number", ContactRules.redactHalf("no number"))
        assertEquals("", ContactRules.redactHalf(""))
    }

    @Test
    fun `an address keeps its domain and masks the local part`() {
        // The domain is shared with thousands of people and identifies nobody; the local part is
        // the identifying half, so it gets the same half rule as a number.
        // 5 chars → keep (5+1)/2 = 3, rounding UP so "half" never masks more than it says.
        assertEquals("ahm••@gmail.com", ContactRules.redactEmail("ahmed@gmail.com"))
        assertEquals("abc••@x.io", ContactRules.redactEmail("abcde@x.io"))
    }

    @Test
    fun `a string that is not an address is masked whole, never passed through raw`() {
        // Returning malformed input unchanged would leak on exactly the case nobody tests.
        assertEquals("ahm••", ContactRules.redactEmail("ahmed"))
        assertFalse(ContactRules.redactEmail("ahmed").contains("ahmed"))
    }

    @Test
    fun `rows collapse into people, one per contact id`() {
        // A contact with two numbers and one address is FOUR provider rows and ONE person.
        val raw = listOf(
            ContactRules.Raw(7L, "Ahmed Awadi", ContactRules.Kind.PHONE, "+20 100 111 2222"),
            ContactRules.Raw(7L, "Ahmed Awadi", ContactRules.Kind.PHONE, "+201001112222"),
            ContactRules.Raw(7L, "Ahmed Awadi", ContactRules.Kind.EMAIL, "ahmed.awadi@x.com"),
            ContactRules.Raw(9L, "Other Person", ContactRules.Kind.PHONE, "+49 1"),
        )
        val people = ContactRules.collapse(raw)
        assertEquals("count must mean people, not rows", 2, people.size)
        assertEquals("Ahmed Awadi", people[0].name)
        assertEquals(2, people[0].numbers.size)
        assertEquals(listOf("ahmed.awadi@x.com"), people[0].emails)
        assertTrue("a number-only person has no emails", people[1].emails.isEmpty())
    }

    @Test
    fun `a duplicate value is not repeated`() {
        val raw = listOf(
            ContactRules.Raw(1L, "A", ContactRules.Kind.PHONE, "+1 555"),
            ContactRules.Raw(1L, "A", ContactRules.Kind.PHONE, "+1 555"),
        )
        assertEquals(listOf("+1 555"), ContactRules.collapse(raw)[0].numbers)
    }

    @Test
    fun `a blank name on a later row does not erase a good one`() {
        // The provider can return the same id twice with the name populated only once.
        val raw = listOf(
            ContactRules.Raw(1L, "Ahmed", ContactRules.Kind.EMAIL, "a@b.c"),
            ContactRules.Raw(1L, "", ContactRules.Kind.PHONE, "+1 555"),
        )
        assertEquals("Ahmed", ContactRules.collapse(raw)[0].name)
    }

    @Test
    fun `the accumulator stops at the cap and reports overflow`() {
        val acc = ContactRules.Accumulator(cap = 2)
        assertTrue(acc.add(ContactRules.Raw(1L, "A", ContactRules.Kind.PHONE, "+1")))
        assertTrue(acc.add(ContactRules.Raw(2L, "B", ContactRules.Kind.PHONE, "+2")))
        // The third PERSON is where it stops — kept for honest totals, flagged as overflow.
        assertFalse(acc.add(ContactRules.Raw(3L, "C", ContactRules.Kind.PHONE, "+3")))
        assertTrue(acc.overflowed)
        assertEquals(2, acc.count)
    }

    @Test
    fun `the accumulator keeps folding extra values for a person it already has`() {
        // Past the cap, a SECOND NUMBER for an existing person is not "more people" — it must
        // still be collected, or the reply would drop a number the person actually has.
        val acc = ContactRules.Accumulator(cap = 1)
        assertTrue(acc.add(ContactRules.Raw(1L, "A", ContactRules.Kind.PHONE, "+1")))
        assertTrue(acc.add(ContactRules.Raw(1L, "A", ContactRules.Kind.EMAIL, "a@b.c")))
        assertFalse(acc.overflowed)
        assertEquals(1, acc.count)
        assertEquals(listOf("a@b.c"), acc.result()[0].emails)
    }
}
