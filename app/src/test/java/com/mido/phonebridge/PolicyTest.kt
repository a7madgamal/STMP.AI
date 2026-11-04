package com.mido.phonebridge

import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.AiBackend
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.ModeSource
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.Rule as PolicyRule
import com.mido.phonebridge.policy.exceptionModeFor
import com.mido.phonebridge.policy.isAttended
import com.mido.phonebridge.policy.resolve
import com.mido.phonebridge.policy.resolveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gmailDelete = PolicyRule("gmail", Verb.DELETE, Mode.DONT_DISTURB)

    // --- resolveMode: the whole engine ---

    @Test
    fun `secure resolves globally, ignoring rules entirely`() {
        // A rule that would say NOTIFY, and a switch that overrides it. SECURE is the
        // strictest value, so it wins over any stored decision.
        val rules = listOf(gmailDelete)
        assertEquals(Mode.PARANOID, resolveMode(GlobalMode.PARANOID, rules, "gmail", Verb.DELETE))
        assertEquals(
            ModeSource.GLOBAL,
            resolve(GlobalMode.PARANOID, rules, "gmail", Verb.DELETE).source
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
                mode == Mode.DONT_DISTURB || mode == Mode.PARANOID
            )
        }
    }

    @Test
    fun `notify applies to every request regardless of rules`() {
        val rules = listOf(PolicyRule("gmail", Verb.READ, Mode.PARANOID))
        assertEquals(Mode.DONT_DISTURB, resolveMode(GlobalMode.DONT_DISTURB, rules, "gmail", Verb.READ))
        assertEquals(Mode.DONT_DISTURB, resolveMode(GlobalMode.DONT_DISTURB, rules, "calendar", Verb.READ))
    }

    @Test
    fun `automatic uses a matching rule and reports it as RULE`() {
        val r = resolve(GlobalMode.WHITELIST, listOf(gmailDelete), "gmail", Verb.DELETE)
        assertEquals(Mode.DONT_DISTURB, r.mode)
        assertEquals(ModeSource.RULE, r.source)
        assertEquals("gmail/DELETE", r.ruleKey)
    }

    @Test
    fun `automatic with no matching rule is SECURE, and says DEFAULT`() {
        // The invariant: silence means "ask me". Nothing auto-approves by accident.
        val r = resolve(GlobalMode.WHITELIST, listOf(gmailDelete), "calendar", Verb.DELETE)
        assertEquals(Mode.PARANOID, r.mode)
        assertEquals(ModeSource.DEFAULT, r.source)
        assertNull(r.ruleKey)
    }

    @Test
    fun `automatic matches on both plugin and verb, never one of them`() {
        // gmail/READ must not be satisfied by a rule for calendar/READ or gmail/DELETE.
        val rules = listOf(PolicyRule("calendar", Verb.READ, Mode.DONT_DISTURB), gmailDelete)
        assertEquals(Mode.PARANOID, resolveMode(GlobalMode.WHITELIST, rules, "gmail", Verb.READ))
        assertEquals(Mode.PARANOID, resolveMode(GlobalMode.WHITELIST, rules, "calendar", Verb.WRITE))
        assertEquals(Mode.DONT_DISTURB, resolveMode(GlobalMode.WHITELIST, rules, "calendar", Verb.READ))
    }

    // --- ANY: one axis, two levels (Mido, 2026-10-19) ---

    @Test
    fun `an ANY rule covers every verb of its plugin`() {
        val rules = listOf(PolicyRule("gmail", null, Mode.DONT_DISTURB))
        for (v in Verb.values()) {
            assertEquals(
                "ANY did not cover gmail/${v.name}",
                Mode.DONT_DISTURB, resolveMode(GlobalMode.WHITELIST, rules, "gmail", v)
            )
        }
    }

    @Test
    fun `an ANY rule does not leak to another plugin`() {
        // The plugin axis stays exact. This is the whole difference between a bounded
        // two-level rule and the unbounded cascade the plan deleted.
        val rules = listOf(PolicyRule("gmail", null, Mode.DONT_DISTURB))
        assertEquals(Mode.PARANOID, resolveMode(GlobalMode.WHITELIST, rules, "calendar", Verb.READ))
        assertEquals(Mode.PARANOID, resolveMode(GlobalMode.WHITELIST, rules, "notifications", Verb.EXECUTE))
    }

    @Test
    fun `an exact rule wins over the plugin's ANY rule`() {
        // The precedence rule, asserted rather than left to list order. ANY is a
        // fallback, so a broad rule cannot silently swallow a deliberate exception.
        val rules = listOf(
            PolicyRule("gmail", null, Mode.DONT_DISTURB),
            PolicyRule("gmail", Verb.DELETE, Mode.PARANOID)
        )
        assertEquals(Mode.PARANOID, resolveMode(GlobalMode.WHITELIST, rules, "gmail", Verb.DELETE))
        assertEquals(Mode.DONT_DISTURB, resolveMode(GlobalMode.WHITELIST, rules, "gmail", Verb.READ))
    }

    @Test
    fun `precedence does not depend on rule ORDER`() {
        // The exact-over-ANY rule must hold whichever way the file lists them, or the
        // same policy resolves differently depending on write order — the defect the
        // duplicate-pair guard exists to prevent, in a new shape.
        val exactFirst = listOf(
            PolicyRule("gmail", Verb.DELETE, Mode.PARANOID),
            PolicyRule("gmail", null, Mode.DONT_DISTURB)
        )
        val anyFirst = listOf(
            PolicyRule("gmail", null, Mode.DONT_DISTURB),
            PolicyRule("gmail", Verb.DELETE, Mode.PARANOID)
        )
        for (rules in listOf(exactFirst, anyFirst)) {
            assertEquals(Mode.PARANOID, resolveMode(GlobalMode.WHITELIST, rules, "gmail", Verb.DELETE))
            assertEquals(Mode.DONT_DISTURB, resolveMode(GlobalMode.WHITELIST, rules, "gmail", Verb.READ))
        }
    }

    @Test
    fun `an ANY rule still cannot weaken a request below NOTIFY`() {
        // ANY widens WHICH requests a rule covers, never what it may do to them. The
        // 0.12.0 floor is untouched by it.
        val rules = listOf(PolicyRule("gmail", null, Mode.DONT_DISTURB))
        for (g in GlobalMode.values()) {
            val mode = resolveMode(g, rules, "gmail", Verb.DELETE)
            assertTrue("switch $g resolved to $mode", mode == Mode.DONT_DISTURB || mode == Mode.PARANOID)
        }
    }

    @Test
    fun `an ANY rule round-trips through the file`() {
        val dir = tmp.newFolder()
        val rules = listOf(
            PolicyRule("gmail", null, Mode.DONT_DISTURB),
            PolicyRule("gmail", Verb.DELETE, Mode.PARANOID)
        )
        PolicyStore(dir).set(Policy(GlobalMode.WHITELIST, rules))
        val back = PolicyStore(dir).get()
        assertEquals(rules, back.rules)
        assertNull(back.rules.first { it.plugin == "gmail" && it.verb == null }.verb)
    }

    @Test
    fun `the stored token for an ANY rule is the literal ANY`() {
        // The file says what it means. A missing verb key must never be read as a
        // wildcard, which is why the token is explicit and required.
        val json = Policy(GlobalMode.WHITELIST, listOf(PolicyRule("gmail", null, Mode.DONT_DISTURB))).toJson()
        assertEquals(PolicyRule.ANY, json.getJSONArray("rules").getJSONObject(0).getString("verb"))
    }

    @Test
    fun `a rule with a missing verb is dropped, never read as ANY`() {
        // A missing verb must never become a wildcard. Dropping is the safe direction: the pair
        // falls back to the global mode, which still asks a human.
        val p = Policy.parse(
            """{"version":1,"global":"WHITELIST","rules":[{"plugin":"gmail","mode":"DONT_DISTURB"}]}"""
        )
        assertEquals(0, p.rules.size)
        assertEquals(true, p.repaired)
    }

    @Test
    fun `an exact rule and an ANY rule for one plugin may coexist`() {
        // They are different keys, so this is NOT a duplicate. If the guard ever treats
        // them as one, the exact-over-ANY pattern becomes unauthorable.
        val p = Policy.parse(
            """{"version":1,"global":"WHITELIST","rules":[""" +
                """{"plugin":"gmail","verb":"ANY","mode":"DONT_DISTURB"},""" +
                """{"plugin":"gmail","verb":"DELETE","mode":"PARANOID"}]}"""
        )
        assertEquals(2, p.rules.size)
    }

    @Test
    fun `two ANY rules for one plugin are still a duplicate`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"WHITELIST","rules":[""" +
                    """{"plugin":"gmail","verb":"ANY","mode":"DONT_DISTURB"},""" +
                    """{"plugin":"gmail","verb":"ANY","mode":"PARANOID"}]}"""
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
        PolicyStore(dir).set(Policy(GlobalMode.DONT_DISTURB))
        // A fresh store on the same dir proves it came off disk, not the cache.
        assertEquals(GlobalMode.DONT_DISTURB, PolicyStore(dir).get().global)
    }

    @Test
    fun `rules round-trip and keep their order`() {
        val dir = tmp.newFolder()
        val rules = listOf(
            PolicyRule("gmail", Verb.DELETE, Mode.PARANOID),
            PolicyRule("notifications", Verb.READ, Mode.DONT_DISTURB),
            PolicyRule("calendar", Verb.WRITE, Mode.DONT_DISTURB)
        )
        PolicyStore(dir).set(Policy(GlobalMode.WHITELIST, rules))
        val back = PolicyStore(dir).get()
        assertEquals(GlobalMode.WHITELIST, back.global)
        assertEquals(rules, back.rules)
    }

    @Test
    fun `a legacy global name is upgraded, not refused`() {
        // The rename AUTOMATIC/SECURE/NOTIFY -> WHITELIST/PARANOID/DONT_DISTURB must not break an
        // existing file. SECURE and PARANOID are the same gate, so this is a rename rather than a
        // behaviour change — and it is marked repaired so the store rewrites the file.
        val p = Policy.parse("""{"version":1,"global":"SECURE","rules":[]}""")
        assertEquals(GlobalMode.PARANOID, p.global)
        assertEquals(true, p.repaired)
    }

    @Test
    fun `an unknown global falls back instead of refusing to start`() {
        // Mido, 2026-11-04: a rename took the app down because an unknown value was a hard parse
        // error. Falling back keeps the bridge up; the repair is recorded so the file is rewritten.
        val p = Policy.parse("""{"version":1,"global":"PANIC","rules":[]}""")
        assertEquals(GlobalMode.WHITELIST, p.global)
        assertEquals(true, p.repaired)
        assertEquals(true, p.preferenceWarnings.any { it.contains("PANIC") })
    }

    @Test
    fun `a rule with an unknown verb is dropped, never widened to ANY`() {
        // Widening to ANY would grant the rule to every verb — the one direction that must never
        // happen by accident. Dropping falls back to the global mode, which still asks a human.
        val p = Policy.parse(
            """{"version":1,"global":"WHITELIST","rules":[{"plugin":"gmail","verb":"NUKE","mode":"DONT_DISTURB"}]}"""
        )
        assertEquals(0, p.rules.size)
        assertEquals(true, p.repaired)
    }

    @Test
    fun `a rule with an unknown mode falls back to the stricter mode`() {
        val p = Policy.parse(
            """{"version":1,"global":"WHITELIST","rules":[{"plugin":"gmail","verb":"DELETE","mode":"WHATEVER"}]}"""
        )
        assertEquals(1, p.rules.size)
        assertEquals(Mode.PARANOID, p.rules[0].mode)
        assertEquals(true, p.repaired)
    }

    @Test
    fun `an auto-approve mode can never be resurrected by a rule`() {
        // There is no auto-approve mode, so "OFF" is unknown and falls back to PARANOID — the
        // strictest value. If a guess ever landed on anything quieter, one bad row would bypass
        // the gate.
        val p = Policy.parse(
            """{"version":1,"global":"WHITELIST","rules":[{"plugin":"gmail","verb":"DELETE","mode":"OFF"}]}"""
        )
        assertEquals(1, p.rules.size)
        assertEquals(Mode.PARANOID, p.rules[0].mode)
    }

    @Test
    fun `a legacy OFF global falls back without resurrecting auto-approve`() {
        // `OFF` was an auto-approve value in <=0.11.0 and must NEVER come back. Falling back to
        // WHITELIST is safe for a reason worth stating: WHITELIST reads the rule table, and an
        // unmatched pair resolves to PARANOID, the strictest mode. So the fallback can only ask
        // MORE, never less.
        val p = Policy.parse("""{"version":1,"global":"OFF","rules":[]}""")
        assertEquals(GlobalMode.WHITELIST, p.global)
        assertEquals(true, p.repaired)
        // Proof that no auto-approve survived: an unknown pair still demands the strictest mode.
        assertEquals(Mode.PARANOID, resolveMode(p.global, p.rules, "gmail", Verb.DELETE))
    }

    @Test
    fun `an off-by-case global falls back, and is not matched leniently`() {
        // Exact matching still applies — `off` is not `OFF` and is not a known name either. It
        // falls back to WHITELIST rather than being quietly read as some near-miss value.
        val p = Policy.parse("""{"version":1,"global":"off","rules":[]}""")
        assertEquals(GlobalMode.WHITELIST, p.global)
        assertEquals(true, p.repaired)
    }

    @Test
    fun `a duplicate pair is refused, never last-wins`() {
        // The same file must not resolve differently depending on write order.
        val e = assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"WHITELIST","rules":[""" +
                    """{"plugin":"gmail","verb":"DELETE","mode":"DONT_DISTURB"},""" +
                    """{"plugin":"gmail","verb":"DELETE","mode":"PARANOID"}]}"""
            )
        }
        assertEquals(true, e.message!!.contains("duplicate"))
    }

    @Test
    fun `a wildcard plugin is dropped, never matched as every plugin`() {
        // Widening `*` to match every plugin would grant a rule to the whole bridge — the one
        // direction that must not happen. Dropping falls back to the global mode, which still
        // asks a human.
        val p = Policy.parse(
            """{"version":1,"global":"WHITELIST","rules":[{"plugin":"*","verb":"DELETE","mode":"DONT_DISTURB"}]}"""
        )
        assertEquals(0, p.rules.size)
        assertEquals(true, p.repaired)
    }

    @Test
    fun `a missing rules array is refused rather than assumed empty`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":1,"global":"PARANOID"}""")
        }
    }

    @Test
    fun `garbage is refused`() {
        assertThrows(PolicyException::class.java) { Policy.parse("not json at all") }
    }

    @Test
    fun `a wrong version is refused`() {
        assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":99,"global":"PARANOID","rules":[]}""")
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
        val p = Policy(GlobalMode.WHITELIST, listOf(gmailDelete))
        val next = p.withRule(PolicyRule("gmail", Verb.DELETE, Mode.PARANOID))
        assertEquals(1, next.rules.size)
        assertEquals(Mode.PARANOID, next.rules.single().mode)
    }

    @Test
    fun `withoutRule removes the pair and leaves the rest alone`() {
        val p = Policy(
            GlobalMode.WHITELIST,
            listOf(gmailDelete, PolicyRule("calendar", Verb.READ, Mode.DONT_DISTURB))
        )
        val next = p.withoutRule("gmail", Verb.DELETE)
        assertEquals(1, next.rules.size)
        assertEquals("calendar", next.rules.single().plugin)
    }

    // --- the modal's exception offer ---

    @Test
    fun `an exception is the OTHER mode, and can never auto-approve`() {
        // The whole point of the button: one tap changes HOW a pair is asked about.
        assertEquals(Mode.DONT_DISTURB, exceptionModeFor(Mode.PARANOID))
        assertEquals(Mode.PARANOID, exceptionModeFor(Mode.DONT_DISTURB))
        // The floor holds by construction — there is no auto-approve Mode to return.
        for (m in Mode.values()) {
            assertTrue(
                "exception mode must be a rule-settable mode",
                exceptionModeFor(m) in PolicyRule.ALLOWED_MODES
            )
        }
    }

    @Test
    fun `a modal-written exception round-trips through the file`() {
        // What the button writes must reload as the same decision, or the tap is a lie
        // the next boot undoes.
        val written = Policy(GlobalMode.WHITELIST).withRule(
            PolicyRule("gmail", Verb.DELETE, exceptionModeFor(Mode.PARANOID))
        )
        val reloaded = Policy.parse(written.toJson().toString())
        assertEquals(written.rules, reloaded.rules)
        assertEquals(
            Mode.DONT_DISTURB,
            resolveMode(reloaded.global, reloaded.rules, "gmail", Verb.DELETE)
        )
    }

    @Test
    fun `a modal exception does not disturb sibling rules`() {
        // Read-modify-write, never a fresh Policy: constructing a new one would drop every
        // sibling rule, which this project already paid for once in applyGate.
        val p = Policy(
            GlobalMode.WHITELIST,
            listOf(PolicyRule("calendar", Verb.READ, Mode.DONT_DISTURB))
        )
        val next = p.withRule(PolicyRule("gmail", Verb.DELETE, Mode.DONT_DISTURB))
        assertEquals(2, next.rules.size)
        assertEquals(GlobalMode.WHITELIST, next.global)
        assertEquals(
            Mode.DONT_DISTURB,
            resolveMode(next.global, next.rules, "calendar", Verb.READ)
        )
    }

    @Test
    fun `an exact modal exception beats the plugin's ANY rule`() {
        // The precedence the engine already promises must survive a write from the modal.
        val rules = listOf(
            PolicyRule("gmail", null, Mode.DONT_DISTURB),
            PolicyRule("gmail", Verb.DELETE, Mode.PARANOID)
        )
        assertEquals(Mode.PARANOID, resolveMode(GlobalMode.WHITELIST, rules, "gmail", Verb.DELETE))
        assertEquals(Mode.DONT_DISTURB, resolveMode(GlobalMode.WHITELIST, rules, "gmail", Verb.READ))
    }

    // --- first run ---

    @Test
    fun `no file means AUTOMATIC with a help rule, and the choice is written down`() {
        val dir = tmp.newFolder()
        val store = PolicyStore(dir)
        val fresh = store.get()

        // AUTOMATIC, not SECURE: it is the only mode that READS the rule table, so it is the
        // only one where a seeded default rule means anything. Under a forced SECURE/NOTIFY
        // every rule is inert and the `help` rule below would be a line that never fires.
        assertEquals(GlobalMode.WHITELIST, fresh.global)

        // The seeded exception: /help is a normal gated route, downgraded to the 5s card so a
        // newcomer's first call does not cost a full-screen modal. NOTIFY is still the floor —
        // the card appears and silence approves — so this is a downgrade, never an exemption.
        assertEquals(1, fresh.rules.size)
        val help = fresh.rules.single()
        assertEquals("help", help.plugin)
        assertNull("ANY, so a future help route is downgraded too", help.verb)
        assertEquals(Mode.DONT_DISTURB, help.mode)

        // And AUTOMATIC with no rule still means SECURE: the default changed which mode is in
        // force, not how much a rule can grant.
        assertEquals(
            Mode.PARANOID,
            resolveMode(GlobalMode.WHITELIST, fresh.rules, "gmail", Verb.DELETE),
        )

        // Written immediately, so "never chosen" is not re-derived on every boot.
        assertEquals(true, File(dir, "policy.json").exists())
    }

    @Test
    fun `the file is replaced atomically, leaving no temp behind`() {
        val dir = tmp.newFolder()
        val store = PolicyStore(dir)
        store.set(Policy(GlobalMode.PARANOID))
        store.set(Policy(GlobalMode.DONT_DISTURB))
        assertEquals(GlobalMode.DONT_DISTURB, PolicyStore(dir).get().global)
        assertEquals(false, File(dir, "policy.json.tmp").exists())
    }

    // --- preferences (fingerprint / AI) ---

    @Test
    fun `an old file with no preference keys parses with both OFF`() {
        // The migration-free path: `policy.json` written before these fields existed must parse
        // unchanged, because VERSION did not move. A bump here would invalidate the file on every
        // device that already has one — and that file now survives a reinstall, so the damage
        // would be sticky.
        val raw = """{"version":1,"global":"PARANOID","rules":[]}"""
        val p = Policy.parse(raw)
        assertEquals(false, p.fingerprintForModals)
        assertEquals(AiBackend.OFF, p.aiBackend)
        // Auto-start is the ONE preference that defaults ON: an install predating it should get
        // the new behaviour without a migration, because a bridge that only runs after someone
        // opens the app is the bug this release fixes.
        assertEquals(true, p.autoStartOnBoot)
    }

    @Test
    fun `auto-start can be turned OFF and survives a round trip`() {
        val dir = tmp.newFolder()
        val store = PolicyStore(dir)
        store.set(Policy(GlobalMode.WHITELIST, emptyList(), autoStartOnBoot = false))
        // Re-read through a NEW store, so this proves the file, not the cache.
        assertEquals(false, PolicyStore(dir).get().autoStartOnBoot)
    }

    @Test
    fun `a non-boolean auto-start falls back to ON instead of throwing`() {
        // Same leniency contract as the other preferences, with a different default: the safe
        // direction here is ON, because the failure mode is a visible running server rather than
        // a silently dead bridge.
        val raw = """{"version":1,"global":"PARANOID","rules":[],"autoStartOnBoot":"yes"}"""
        val p = Policy.parse(raw)
        assertEquals(true, p.autoStartOnBoot)
        assertTrue(p.preferenceWarnings.isNotEmpty())
    }

    @Test
    fun `every preference survives a rule edit`() {
        // The carry-over trap, now covering all three: each `withX` rebuilds the object
        // positionally, so a new field that is not carried in EVERY builder silently resets.
        val base = Policy(
            GlobalMode.WHITELIST,
            emptyList(),
            fingerprintForModals = true,
            aiBackend = AiBackend.LOCAL,
            autoStartOnBoot = false,
        )
        val afterRule = base.withRule(PolicyRule("gmail", Verb.READ, Mode.DONT_DISTURB))
        assertEquals(false, afterRule.autoStartOnBoot)
        assertEquals(AiBackend.LOCAL, afterRule.aiBackend)

        val afterGlobal = base.withGlobal(GlobalMode.PARANOID)
        assertEquals(false, afterGlobal.autoStartOnBoot)
        assertEquals(true, afterGlobal.fingerprintForModals)

        val afterAi = base.withAiBackend(AiBackend.OFF)
        assertEquals(false, afterAi.autoStartOnBoot)
        assertEquals(true, afterAi.fingerprintForModals)
    }

    @Test
    fun `a preference is read when present`() {
        val raw = """{"version":1,"global":"PARANOID","rules":[],""" +
            """"fingerprintForModals":true,"aiBackend":"LOCAL"}"""
        val p = Policy.parse(raw)
        assertEquals(true, p.fingerprintForModals)
        assertEquals(AiBackend.LOCAL, p.aiBackend)
    }

    @Test
    fun `a NON-BOOLEAN preference falls back to the default instead of throwing`() {
        // The whole reason these are lenient: a preference must never be able to refuse to start
        // the server. `optBoolean` would coerce "yes" to false silently; this asserts the value
        // is ignored AND that the file still parses, which is the contract.
        val raw = """{"version":1,"global":"PARANOID","rules":[],""" +
            """"fingerprintForModals":"yes","aiBackend":0}"""
        val p = Policy.parse(raw)
        assertEquals(false, p.fingerprintForModals)
        assertEquals(AiBackend.OFF, p.aiBackend)
    }

    @Test
    fun `leniency never weakens the gate`() {
        // The invariant that replaced "global is a hard error". A tolerant parse must still never
        // resolve to anything quieter than PARANOID, whatever it could not read.
        val raw = """{"version":1,"global":"NONSENSE","rules":[],"aiBackend":"LOCAL"}"""
        val p = Policy.parse(raw)
        assertEquals(true, p.repaired)
        // Every unknown pair resolves to the strictest mode, so a broken file cannot open a hole.
        for (plugin in listOf("gmail", "drive", "ssh", "contacts")) {
            assertEquals(Mode.PARANOID, resolveMode(p.global, p.rules, plugin, Verb.DELETE))
        }
    }

    @Test
    fun `editing a rule preserves both preferences`() {
        // The carry-over trap: `Policy(global, rules)` compiles fine and silently resets every
        // sibling field. Here that would turn the AI off (or a fingerprint requirement on) every
        // time someone added a rule — with nothing in the UI saying why.
        val base = Policy(
            GlobalMode.WHITELIST,
            listOf(PolicyRule("gmail", Verb.DELETE, Mode.DONT_DISTURB)),
            fingerprintForModals = true,
            aiBackend = AiBackend.LOCAL
        )
        val next = base.withRule(PolicyRule("calendar", Verb.READ, Mode.DONT_DISTURB))
        assertEquals(true, next.fingerprintForModals)
        assertEquals(AiBackend.LOCAL, next.aiBackend)
        assertEquals(2, next.rules.size)

        val removed = next.withoutRule("calendar", Verb.READ)
        assertEquals(true, removed.fingerprintForModals)
        assertEquals(AiBackend.LOCAL, removed.aiBackend)
    }

    @Test
    fun `flipping the gate switch preserves both preferences`() {
        // `applyGate` used to build `Policy(after, existing.rules)` and wipe the rule table; the
        // fix was `withGlobal`. Same trap, same test.
        val base = Policy(
            GlobalMode.PARANOID,
            listOf(PolicyRule("gmail", Verb.DELETE, Mode.DONT_DISTURB)),
            fingerprintForModals = true,
            aiBackend = AiBackend.LOCAL
        )
        val next = base.withGlobal(GlobalMode.WHITELIST)
        assertEquals(GlobalMode.WHITELIST, next.global)
        assertEquals(true, next.fingerprintForModals)
        assertEquals(AiBackend.LOCAL, next.aiBackend)
        // And the rules, which `Policy(after)` alone used to wipe.
        assertEquals(1, next.rules.size)
    }

    @Test
    fun `preferences round-trip through the file`() {
        val dir = tmp.newFolder()
        val store = PolicyStore(dir)
        store.set(
            Policy(
                GlobalMode.WHITELIST,
                listOf(gmailDelete),
                fingerprintForModals = true,
                aiBackend = AiBackend.LOCAL
            )
        )
        val reread = PolicyStore(dir).get()
        assertEquals(true, reread.fingerprintForModals)
        assertEquals(AiBackend.LOCAL, reread.aiBackend)
        assertEquals(1, reread.rules.size)
        assertEquals(GlobalMode.WHITELIST, reread.global)
    }
}
