package com.mido.phonebridge

import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.resolveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The policy file is the one thing that decides whether a human is asked at all,
 * so its failure modes matter more than its happy path.
 *
 * Every test here exists because the alternative is a silent behaviour change:
 * a policy that fails to load must never quietly become `SECURE` (reattaching a
 * gate the user turned off) nor quietly become `OFF` (detaching one they rely on).
 */
class PolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- resolveMode ---

    @Test
    fun `secure resolves to secure and off resolves to off`() {
        assertEquals(Mode.SECURE, resolveMode(GlobalMode.SECURE))
        assertEquals(Mode.OFF, resolveMode(GlobalMode.OFF))
    }

    @Test
    fun `every switch value has a mode, so no request can be gated on undefined`() {
        // Guards the R3 seam: adding a value to GlobalMode without teaching
        // resolveMode about it must fail here, not in production.
        for (g in GlobalMode.values()) {
            resolveMode(g)
        }
    }

    // --- parse: fail hard ---

    @Test
    fun `a valid policy round-trips through write and read`() {
        val dir = tmp.newFolder()
        PolicyStore(dir).set(Policy(GlobalMode.OFF))
        // A fresh store on the same dir proves it came off disk, not the cache.
        assertEquals(GlobalMode.OFF, PolicyStore(dir).get().global)
    }

    @Test
    fun `an unknown global is a parse error, never a silent SECURE`() {
        val e = assertThrows(PolicyException::class.java) {
            Policy.parse("""{"version":1,"global":"AUTOMATIC","rules":[]}""")
        }
        assertEquals(true, e.message!!.contains("AUTOMATIC"))
    }

    @Test
    fun `a rules array this build cannot honour is refused, not ignored`() {
        // Running on the file while ignoring a stored rule would mean executing a
        // decision that is sitting right there in the file.
        assertThrows(PolicyException::class.java) {
            Policy.parse(
                """{"version":1,"global":"SECURE","rules":[{"plugin":"gmail","verb":"DELETE","mode":"NOTIFY"}]}"""
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
        store.set(Policy(GlobalMode.OFF))
        assertEquals(GlobalMode.OFF, PolicyStore(dir).get().global)
        assertEquals(false, File(dir, "policy.json.tmp").exists())
    }
}
