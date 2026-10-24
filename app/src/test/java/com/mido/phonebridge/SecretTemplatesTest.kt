package com.mido.phonebridge

import com.mido.phonebridge.core.SecretRules
import com.mido.phonebridge.core.SecretTemplates
import com.mido.phonebridge.plugins.secrets.HostAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every built-in template must be SAVABLE.
 *
 * This is the whole reason `SecretTemplates` is a pure object and why `SecretRules` was extracted
 * out of `SecretVault`. Without this, a bad template is discovered at the worst possible moment:
 * inside the add dialog, on the device, AFTER the human has pasted a real API key — and the
 * failure surfaces as a `bad_request` toast that reads like *their* mistake rather than a bug in
 * a list they were told to trust.
 *
 * The rules checked are exactly the ones the vault enforces on save, delegated to the same
 * `SecretRules` and `HostAllowlist` objects the vault uses. A test that re-implemented them would
 * pass while the real code disagreed — the failure mode this project has paid for more than once.
 */
class SecretTemplatesTest {

    @Test
    fun `every template has a name the vault will accept`() {
        for (t in SecretTemplates.ALL) {
            assertTrue(
                "template '${t.name}' is not a legal secret name",
                SecretRules.isValidName(t.name)
            )
        }
    }

    @Test
    fun `every template's links are https`() {
        for (t in SecretTemplates.ALL) {
            // requireUrlOrEmpty throws on a non-https or malformed URL; empty is legal but a
            // template with no docs would be a pointless template, so it is asserted separately.
            assertTrue(
                "template '${t.name}' docs is not a valid https URL: ${t.docs}",
                SecretRules.isValidUrlOrEmpty(t.docs)
            )
            assertTrue(
                "template '${t.name}' key link is not a valid https URL: ${t.key}",
                SecretRules.isValidUrlOrEmpty(t.key)
            )
            assertTrue("template '${t.name}' should carry a docs link", t.docs.isNotBlank())
            assertTrue("template '${t.name}' should carry a key link", t.key.isNotBlank())
        }
    }

    @Test
    fun `every template host passes the allowlist rule`() {
        // Hosts are the security boundary. A malformed or over-broad entry here would widen what
        // a stored credential may reach, permanently, at creation.
        for (t in SecretTemplates.ALL) {
            assertTrue("template '${t.name}' declares no hosts", t.hosts.isNotEmpty())
            for (h in t.hosts) {
                // Throws if it is not a bare hostname or a boundary-correct wildcard.
                val normalised = HostAllowlist.requireRule(h)
                assertEquals("host '$h' normalised unexpectedly", h.lowercase(), normalised)
            }
        }
    }

    @Test
    fun `template hosts are EXACT api hosts, not wildcards`() {
        // A wildcard is legal but wider than the intent: `*.todoist.com` would also permit every
        // other subdomain the vendor ever runs. Each template names one API host, so a wildcard
        // appearing here is a decision someone should have to make deliberately.
        for (t in SecretTemplates.ALL) {
            for (h in t.hosts) {
                assertTrue(
                    "template '${t.name}' uses wildcard host '$h'; name the exact API host",
                    !h.startsWith("*.")
                )
            }
        }
    }

    @Test
    fun `each template host actually matches its own api`() {
        // The rule is only useful if the matcher agrees: an entry that never matches would be a
        // secret that silently refuses every call.
        for (t in SecretTemplates.ALL) {
            for (h in t.hosts) {
                assertTrue(
                    "host '$h' does not match itself under the allowlist",
                    HostAllowlist.allows(t.hosts, h)
                )
            }
        }
    }

    @Test
    fun `a host that merely shares a suffix is still refused`() {
        // The boundary property, asserted against the templates' own hosts: `evil-api.todoist.com`
        // must not pass an exact `api.todoist.com` rule.
        for (t in SecretTemplates.ALL) {
            for (h in t.hosts) {
                assertTrue(
                    "'evil-$h' must not satisfy the rule for '$h'",
                    !HostAllowlist.allows(t.hosts, "evil-$h")
                )
            }
        }
    }

    @Test
    fun `names are unique, so the picker cannot offer a duplicate`() {
        val names = SecretTemplates.ALL.map { it.name }
        assertEquals("duplicate template names", names.size, names.toSet().size)
    }

    @Test
    fun `the three agreed templates exist`() {
        // Mido, 2026-10-20: "add github, notion only" — plus todoist, which is the one in use.
        assertEquals(
            listOf("todoist", "github", "notion"),
            SecretTemplates.ALL.map { it.name }
        )
    }

    @Test
    fun `lookup finds a template and returns null for an unknown name`() {
        assertNotNull(SecretTemplates.byName("github"))
        // Null rather than a default: an unknown name must not silently resolve to some other
        // service's hosts.
        assertNull(SecretTemplates.byName("nope"))
    }

    @Test
    fun `a template never carries a value`() {
        // The property that makes shipping a fixed list safe: a template cannot create a working
        // secret on its own, so there is no credential in the source tree.
        val fields = SecretTemplates.ALL.first().javaClass.declaredFields.map { it.name }
        assertTrue(
            "SecretTemplate must not have a value/secret field, found: $fields",
            fields.none { it.contains("value", ignoreCase = true) || it.contains("secret", ignoreCase = true) }
        )
    }
}
