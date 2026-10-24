package com.mido.phonebridge

import com.mido.phonebridge.core.OutboundVerb
import com.mido.phonebridge.core.Verb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The outbound-method → verb mapping.
 *
 * This is a SECURITY mapping: it produces the policy key, and the key decides whether a
 * request is asked about loudly or quietly. Getting it wrong in the quiet direction means a
 * destructive call could be approved by silence on a NOTIFY card, so the tests below pin both
 * the obvious cases and the direction of the fallback.
 *
 * It exists because a fixed `EXECUTE` verb on the broker made "reads only" inexpressible —
 * an exception tapped while reading also silenced deletes. Mido, 2026-10-19: *"I clicked
 * meaning it as a read only operation."*
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboundVerbTest {

    @Test
    fun `methods that cannot change state are reads`() {
        assertEquals(Verb.READ, OutboundVerb.of("GET"))
        assertEquals(Verb.READ, OutboundVerb.of("HEAD"))
        assertEquals(Verb.READ, OutboundVerb.of("OPTIONS"))
    }

    @Test
    fun `DELETE is its own verb, not a generic write`() {
        // The audit and the policy key both distinguish it; collapsing it into WRITE would
        // lose the distinction the rules screen already offers.
        assertEquals(Verb.DELETE, OutboundVerb.of("DELETE"))
    }

    @Test
    fun `mutating methods are writes`() {
        assertEquals(Verb.WRITE, OutboundVerb.of("POST"))
        assertEquals(Verb.WRITE, OutboundVerb.of("PUT"))
        assertEquals(Verb.WRITE, OutboundVerb.of("PATCH"))
    }

    @Test
    fun `the fallback is LOUD, never quiet`() {
        // The one direction that can hurt is guessing READ: an unrecognised method would then
        // be approvable by silence. Every unknown must be a WRITE.
        assertEquals(Verb.WRITE, OutboundVerb.of("TRACE"))
        assertEquals(Verb.WRITE, OutboundVerb.of("PROPFIND"))
        assertEquals(Verb.WRITE, OutboundVerb.of(""))
        assertEquals(Verb.WRITE, OutboundVerb.of("   "))
    }

    @Test
    fun `case and whitespace do not change the answer`() {
        // Providers and callers write `get`, `Get`, `GET `. All three are the same request.
        assertEquals(Verb.READ, OutboundVerb.of("get"))
        assertEquals(Verb.READ, OutboundVerb.of("Get"))
        assertEquals(Verb.READ, OutboundVerb.of("  GET  "))
        assertEquals(Verb.DELETE, OutboundVerb.of(" delete\n"))
    }
}
