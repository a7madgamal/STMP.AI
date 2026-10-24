package com.mido.phonebridge

import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.plugins.secrets.HostAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host matcher is the one function that decides whether a secret may leave the
 * device, so it is tested directly rather than through a broker round trip.
 *
 * This is possible because [HostAllowlist] is pure — no Context, no storage. It used to
 * own a global prefs list; when the hosts moved onto each secret, the matching rule was
 * all that remained, and leaving it pure was the point: a security boundary you cannot
 * unit test is a security boundary you are only hoping works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostAllowlistTest {

    @Test
    fun `exact host matches`() {
        assertTrue(HostAllowlist.allows(listOf("api.todoist.com"), "api.todoist.com"))
        assertFalse(HostAllowlist.allows(listOf("api.todoist.com"), "todoist.com"))
    }

    @Test
    fun `matching is case-insensitive on both sides`() {
        assertTrue(HostAllowlist.allows(listOf("API.Todoist.COM"), "api.todoist.com"))
        assertTrue(HostAllowlist.allows(listOf("api.todoist.com"), "API.TODOIST.COM"))
    }

    @Test
    fun `wildcard matches subdomains`() {
        assertTrue(HostAllowlist.allows(listOf("*.todoist.com"), "api.todoist.com"))
        assertTrue(HostAllowlist.allows(listOf("*.todoist.com"), "a.b.todoist.com"))
    }

    @Test
    fun `wildcard does NOT match the bare domain`() {
        // "*.todoist.com" means subdomains; the apex is a different host and must be
        // named explicitly. Otherwise one rule silently widens to the whole domain.
        assertFalse(HostAllowlist.allows(listOf("*.todoist.com"), "todoist.com"))
    }

    @Test
    fun `wildcard does NOT match a suffix impostor`() {
        // The bug a naive endsWith("todoist.com") would have: "evil-todoist.com" is a
        // completely different registrable domain. The dot boundary is what stops it.
        assertFalse(HostAllowlist.allows(listOf("*.todoist.com"), "evil-todoist.com"))
        assertFalse(HostAllowlist.allows(listOf("api.todoist.com"), "evil-api.todoist.com"))
    }

    @Test
    fun `an empty rule list allows nothing`() {
        assertFalse(HostAllowlist.allows(emptyList(), "api.todoist.com"))
    }

    @Test
    fun `requireRule accepts a bare host and a wildcard`() {
        assertEquals("api.todoist.com", HostAllowlist.requireRule("API.Todoist.com"))
        assertEquals("*.todoist.com", HostAllowlist.requireRule("*.Todoist.com"))
        assertEquals("api.todoist.com", HostAllowlist.requireRule("  api.todoist.com  "))
    }

    @Test
    fun `requireRule refuses anything that is not a bare hostname`() {
        // A scheme, port or path would look like it narrows the rule while the matcher
        // ignores it — worse than refusing, so all three are rejected.
        for (bad in listOf(
            "", "https://api.todoist.com", "api.todoist.com:443",
            "api.todoist.com/v1", "*.", "*. todoist.com", "api todoist.com"
        )) {
            val threw = try {
                HostAllowlist.requireRule(bad)
                false
            } catch (e: PluginError) {
                assertEquals("bad_request", e.code)
                true
            }
            assertTrue("'$bad' must be refused", threw)
        }
    }
}
