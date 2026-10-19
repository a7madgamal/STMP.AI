package com.mido.phonebridge

import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.ModeSource
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.Rule as PolicyRule
import com.mido.phonebridge.policy.isAttended
import com.mido.phonebridge.policy.resolve
import com.mido.phonebridge.policy.resolveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The policy file is the one thing that decides whether a human is asked at all,
 * so its failure modes matter more than its happy path.
 *
 * Every test here exists because the alternative is a silent behaviour change: a
 * policy that fails to load must never quietly become `SECURE` (reattaching a gate
 * the user turned off) nor quietly become anything weaker (detaching one they rely on).
 */
class PolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gmailDelete = PolicyRule("gmail", Verb.DELETE, Mode.NOTIFY)

    // --- resolveMode: the whole engine ---

    @Test
    fun `secure resolves globally, ignoring rules entirely`() {
        // A rule that would say NOTIFY, and a switch that overrides it. SECURE is the
        // strictest value, so it wins over any stored decision.
        val rules = listOf(gmailDelete)
        assertEquals(Mode.SECURE, resolveMode(GlobalMode.SECURE, rules, "gmail", Verb.DELETE))
        assertEquals(
            ModeSource.GLOBAL,
            resolve(GlobalMode.SECURE, rules, "gmail", Verb.DELETE).source
        )
    }

    @Test
    fun `no switch value can weaken a request below NOTIFY`() {
        // The 0.12.0 invariant. `OFF` used to resolve to an ungated Mode.OFF; it is gone,
        // and NOTIFY is the floor. So whatever the switch says, a request still reaches a
        // human. This is the test that would fail if an auto-approve value were ever
        // reintroduced without someone deciding to reopen this question.
        val rules = listOf(gmailDelete)
        for (g in GlobalMode.values()) {
            val mode = resolveMode(g, rules, "gmail", Verb.DELETE)
            assertTrue(
                "switch $g resolved to $mode, which is below the NOTIFY floor",
                mode == Mode.NOTIFY || mode == Mode.SECURE
            )
        }
    }

    @Test
    fun `notify applies to every request regardless of rules`() {
        val rules = listOf(PolicyRule("gmail", Verb.READ, Mode.SECURE))
        assertEquals(Mode.NOTIFY, resolveMode(GlobalMode.NOTIFY, rules, "gmail", Verb.READ))
        assertEquals(Mode.NOTIFY, resolveMode(GlobalMode.NOTIFY, rules, "calendar", Verb.READ))
    }

    @Test
    fun `automatic uses a matching rule and reports it as RULE`() {
        val r = resolve(GlobalMode.AUTOMATIC, listOf(gmailDelete), "gmail", Verb.DELETE)
        assertEquals(Mode.NOTIFY, r.mode)
        assertEquals(ModeSource.RULE, r.source)
        assertEquals("gmail/DELETE", r.ruleKey)
    }

    @Test
    fun `automatic with no matching rule is SECURE, and says DEFAULT`() {
        // The invariant: silence means "ask me". Nothing auto-approves by accident.
        val r = resolve(GlobalMode.AUTOMATIC, listOf(gmailDelete), "calendar", Verb.DELETE)
        assertEquals(Mode.SECURE, r.mode)
        assertEquals(ModeSource.DEFAULT, r.source)
        assertNull(r.ruleKey)
    }

    @Test
    fun `automatic matches on both plugin and verb, never one of them`() {
        // gmail/READ must not be satisfied by a rule for calendar/READ or gmail/DELETE.
        val rules = listOf(PolicyRule("calendar", Verb.READ, Mode.NOTIFY), gmailDelete)
        assertEquals(Mode.SECURE, resolveMode(GlobalMode.AUTOMATIC, rules, "gmail", Verb.READ))
        assertEquals(Mode.SECURE, resolveMode(GlobalMode.AUTOMATIC, rules, "calendar", Verb.WRITE))
        assertEquals(Mode.NOTIFY, resolveMode(GlobalMode.AUTOMATIC, rules, "calendar", Verb.READ))
    }

    // --- ANY: one axis, two levels (Mido, 2026-10-19) ---

    @Test
    fun `an ANY rule covers every verb of its plugin`() {
        val rules = listOf(PolicyRule("gmail", null, Mode.NOTIFY))
        for (v in Verb.values()) {
            assertEquals(
                "ANY did not cover gmail/${v.name}",
                Mode.NOTIFY, resolveMode(GlobalMode.AUTOMATIC, rules, "gmail", v)
            )
        }
    }

    @Test
    fun `an ANY rule does not leak to another plugin`() {
        // The plugin axis stays exact. This is the whole difference between a bounded
        // two-level rule and the unbounded cascade the plan deleted.
        val rules = listOf(PolicyRule("gmail", null, Mode.NOTIFY))
        assertEquals(Mode.SECURE, resolveMode(GlobalMode.AUTOMATIC, rules, "calendar", Verb.READ))
        assertEquals(Mode.SECURE, resolveMode(GlobalMode.AUTOMATIC, rules, "notifications", Verb.EXECUTE))
    }

    @Test
    fun `an exact rule wins over the plugin's ANY rule`() {
        // The precedence rule, asserted rather than left to list order. ANY is a
        // fallback, so a broad rule cannot silently swallow a deliberate exception.
        val rules = listOf(
            PolicyRule("gmail", null, Mode.NOTIFY),
            PolicyRule("gmail", Verb.DELETE, Mode.SECURE)
        )
        assertEquals(Mode.SECURE, resolveMode(GlobalMode.AUTOMATIC, rules, "gmail", Verb.DELETE))
        assertEquals(Mode.NOTIFY, resolveMode(GlobalMode.AUTOMATIC, rules, "gmail", Verb.READ))
    }

    @Test
    fun `precedence does not depend on rule ORDER`() {
        // The exact-over-ANY rule must hold whichever way the file lists them, or the
        // same policy resolves differently depending on write order — the defect the
        // duplicate-pair guard exists to prevent, in a new shape.
        val exactFirst = listOf(
            PolicyRule("gmail", Verb.DELETE, Mode.SECURE),
            PolicyRule("gmail", null, Mode.NOTIFY)
        )
        val anyFirst = listOf(
            PolicyRule("gmail", null, Mode.NOTIFY),
            PolicyRule("gmail", Verb.DELETE, Mode.SECURE)
        )
        for (rules in listOf(exactFirst, anyFirst)) {
            assertEquals(Mode.SECURE, resolveMode(GlobalMode.AUTOMATIC, rules, "gmail", Verb.DELETE))
            assertEquals(Mode.NOTIFY, resolveMode(GlobalMode.AUTOMATIC, rules, "gmail", Verb.READ))
        }
    }

    @Test
    fun `an ANY rule still cannot weaken a request below NOTIFY`() {
        // ANY widens WHICH requests a rule covers, never what it may do to them. The
        // 0.12.0 floor is untouched by it.
        val rules = listOf(PolicyRule("gmail", null, Mode.NOTIFY))
        for (g in GlobalMode.values()) {
            val mode = resolveMode(g, rules, "gmail", Verb.DELETE)
            assertTrue("switch $g resolved to $mode", mode == Mode.NOTIFY || mode == Mode.SECURE)
        }
    }

    @Test
    fun `an ANY rule round-trips through the file`() {
        val dir = tmp.newFolder()
        val rules = listOf(
            PolicyRule("gmail", null, Mode.NOTIFY),
            PolicyRule("gmail", Verb.DELETE, Mode.SECURE)
        )
        PolicyStore(dir).set(Policy(GlobalMode.AUTOMATIC, rules))
        val back = PolicyStore(dir).get()
        assertEquals(rules, back.rules)
        assertNull(back.rules.first { it.plugin == "gmail" && it.verb == null }.verb)
    }

    @Test
    fun `the stored token for an ANY rule is the literal ANY`() {
        // The file says what it means. A missing verb key must never be read as a
        // wildcard, which is why the token is explicit and required.
        val json = Policy(GlobalMode.AUTOMATIC, listOf(PolicyRule("gmail", null, Mode.NOTIFY))).toJson()
        assertEquals(PolicyRule.ANY, json.getJSONArray("rules").getJSONObject(0).getString("verb"))
    }

    @Test
    fun `a rule with a missing verb is refused, never read as ANY`() {
        val e = assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"AUTOMATIC","rules":[{"plugin":"gmail","mode":"NOTIFY"}]}"""
            )
        }
        assertEquals(true, e.message!!.contains("unknown verb"))
    }

    @Test
    fun `an exact rule and an ANY rule for one plugin may coexist`() {
        // They are different keys, so this is NOT a duplicate. If the guard ever treats
        // them as one, the exact-over-ANY pattern becomes unauthorable.
        val p = Policy.parse(
            """{"version":1,"global":"AUTOMATIC","rules":[""" +
                """{"plugin":"gmail","verb":"ANY","mode":"NOTIFY"},""" +
                """{"plugin":"gmail","verb":"DELETE","mode":"SECURE"}]}"""
        )
        assertEquals(2, p.rules.size)
    }

    @Test
    fun `two ANY rules for one plugin are still a duplicate`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"AUTOMATIC","rules":[""" +
                    """{"plugin":"gmail","verb":"ANY","mode":"NOTIFY"},""" +
                    """{"plugin":"gmail","verb":"ANY","mode":"SECURE"}]}"""
            )
        }
    }

    @Test
    fun `every switch value resolves, so nothing can be gated on undefined`() {
        // Guards the seam: adding a GlobalMode without teaching resolve about it must
        // fail here, not in production.
        for (g in GlobalMode.values()) {
            resolve(g, emptyList(), "gmail", Verb.READ)
        }
    }

    // --- attendance ---

    @Test
    fun `attended requires an unlocked, awake screen`() {
        assertEquals(true, isAttended(screenInteractive = true, keyguardLocked = false))
        assertEquals(false, isAttended(screenInteractive = false, keyguardLocked = false))
        assertEquals(false, isAttended(screenInteractive = true, keyguardLocked = true))
        assertEquals(false, isAttended(screenInteractive = false, keyguardLocked = true))
    }

    // --- parse: fail hard ---

    @Test
    fun `a valid policy round-trips through write and read`() {
        val dir = tmp.newFolder()
        PolicyStore(dir).set(Policy(GlobalMode.NOTIFY))
        // A fresh store on the same dir proves it came off disk, not the cache.
        assertEquals(GlobalMode.NOTIFY, PolicyStore(dir).get().global)
    }

    @Test
    fun `rules round-trip and keep their order`() {
        val dir = tmp.newFolder()
        val rules = listOf(
            PolicyRule("gmail", Verb.DELETE, Mode.SECURE),
            PolicyRule("notifications", Verb.READ, Mode.NOTIFY),
            PolicyRule("calendar", Verb.WRITE, Mode.NOTIFY)
        )
        PolicyStore(dir).set(Policy(GlobalMode.AUTOMATIC, rules))
        val back = PolicyStore(dir).get()
        assertEquals(GlobalMode.AUTOMATIC, back.global)
        assertEquals(rules, back.rules)
    }

    @Test
    fun `an unknown global is a parse error, never a silent SECURE`() {
        // Deliberately NOT "AUTOMATIC": that is a real value since 0.6.0, so using it
        // here would have this test passing for the wrong reason — it would be checking
        // that a known value parses, not that an unknown one fails.
        val e = assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":1,"global":"PANIC","rules":[]}""")
        }
        assertEquals(true, e.message!!.contains("PANIC"))
    }

    @Test
    fun `an unknown verb in a rule is refused`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"AUTOMATIC","rules":[{"plugin":"gmail","verb":"NUKE","mode":"NOTIFY"}]}"""
            )
        }
    }

    @Test
    fun `an unknown mode in a rule is refused`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"AUTOMATIC","rules":[{"plugin":"gmail","verb":"DELETE","mode":"WHATEVER"}]}"""
            )
        }
    }

    @Test
    fun `an auto-approve mode is refused as a rule mode`() {
        // Q12, and now stronger: there is no auto-approve mode at all in 0.12.0, so a
        // rule carrying one is refused as an unknown mode. If this were ever accepted,
        // "no human, no fingerprint" would be settable twelve times and one accidental
        // row would bypass the gate.
        assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"AUTOMATIC","rules":[{"plugin":"gmail","verb":"DELETE","mode":"OFF"}]}"""
            )
        }
    }

    @Test
    fun `a legacy OFF global is refused, not migrated`() {
        // 0.12.0 briefly carried a one-way "OFF" -> NOTIFY load for files written by
        // <=0.11.0. It was removed on the "no backward compatibility while we are not
        // live" call: "OFF" is now just another unrecognised string. If this ever
        // stops throwing, a compatibility shim has been reintroduced without a decision.
        assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":1,"global":"OFF","rules":[]}""")
        }
    }

    @Test
    fun `an off-by-case global is refused too`() {
        // No lenient matching: the refusal above is exact, not a case-insensitive shim
        // wearing a different hat.
        assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":1,"global":"off","rules":[]}""")
        }
    }

    @Test
    fun `a duplicate pair is refused, never last-wins`() {
        // The same file must not resolve differently depending on write order.
        val e = assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"AUTOMATIC","rules":[""" +
                    """{"plugin":"gmail","verb":"DELETE","mode":"NOTIFY"},""" +
                    """{"plugin":"gmail","verb":"DELETE","mode":"SECURE"}]}"""
            )
        }
        assertEquals(true, e.message!!.contains("duplicate"))
    }

    @Test
    fun `a wildcard plugin is refused`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"AUTOMATIC","rules":[{"plugin":"*","verb":"DELETE","mode":"NOTIFY"}]}"""
            )
        }
    }

    @Test
    fun `a missing rules array is refused rather than assumed empty`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":1,"global":"SECURE"}""")
        }
    }

    @Test
    fun `garbage is refused`() {
        assertThrows(PolicyException::class.java) { Policy.parse("not json at all") }
    }

    @Test
    fun `a wrong version is refused`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":99,"global":"SECURE","rules":[]}""")
        }
    }

    @Test
    fun `a corrupt file on disk throws instead of defaulting`() {
        val dir = tmp.newFolder()
        File(dir, "policy.json").writeText("{ this is not json")
        assertThrows(PolicyException::class.java) { PolicyStore(dir).get() }
    }

    // --- editing rules ---

    @Test
    fun `withRule replaces an existing pair instead of adding a second`() {
        // Duplicates are a parse error, so the editor must never be able to create one.
        val p = Policy(GlobalMode.AUTOMATIC, listOf(gmailDelete))
        val next = p.withRule(PolicyRule("gmail", Verb.DELETE, Mode.SECURE))
        assertEquals(1, next.rules.size)
        assertEquals(Mode.SECURE, next.rules.single().mode)
    }

    @Test
    fun `withoutRule removes the pair and leaves the rest alone`() {
        val p = Policy(
            GlobalMode.AUTOMATIC,
            listOf(gmailDelete, PolicyRule("calendar", Verb.READ, Mode.NOTIFY))
        )
        val next = p.withoutRule("gmail", Verb.DELETE)
        assertEquals(1, next.rules.size)
        assertEquals("calendar", next.rules.single().plugin)
    }

    // --- first run ---

    @Test
    fun `no file means SECURE, and the choice is written down`() {
        val dir = tmp.newFolder()
        val store = PolicyStore(dir)
        assertEquals(GlobalMode.SECURE, store.get().global)
        // Written immediately, so "never chosen" is not re-derived on every boot.
        assertEquals(true, File(dir, "policy.json").exists())
    }

    @Test
    fun `the file is replaced atomically, leaving no temp behind`() {
        val dir = tmp.newFolder()
        val store = PolicyStore(dir)
        store.set(Policy(GlobalMode.SECURE))
        store.set(Policy(GlobalMode.NOTIFY))
        assertEquals(GlobalMode.NOTIFY, PolicyStore(dir).get().global)
        assertEquals(false, File(dir, "policy.json.tmp").exists())
    }
}
