package com.mido.phonebridge

import com.mido.phonebridge.core.VaultState
import com.mido.phonebridge.ui.VaultModule
import com.mido.phonebridge.ui.VaultModule.Action
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.StatusRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The vault card's table (WP09 3.1). */
class VaultModuleTest {

    private fun model(state: VaultState.State, unlocked: Boolean, ssh: Boolean = false) =
        VaultModule.model(state, unlocked, ssh)

    @Test
    fun `unlocked wins over every state and offers no key`() {
        for (state in VaultState.State.entries) {
            val m = model(state, unlocked = true)
            assertEquals("UNLOCKED", m.value)
            assertEquals(Led.State.OK, m.led)
            assertEquals(StatusRow.Tone.OK, m.tone)
            assertNull(m.action)
            assertNull(m.actionLabel)
        }
    }

    @Test
    fun `a locked vault that can be opened offers UNLOCK`() {
        val m = model(VaultState.State.UNLOCKABLE, unlocked = false)
        assertEquals("LOCKED", m.value)
        assertEquals(Led.State.CAUTION, m.led)
        assertEquals(StatusRow.Tone.CAUTION, m.tone)
        assertEquals(Action.UNLOCK, m.action)
        assertEquals("UNLOCK", m.actionLabel)
    }

    @Test
    fun `a vault that was never created offers CREATE VAULT with a neutral light`() {
        val m = model(VaultState.State.ABSENT, unlocked = false)
        assertEquals("NOT CREATED", m.value)
        assertEquals(Led.State.OFF, m.led)
        assertEquals(StatusRow.Tone.NEUTRAL, m.tone)
        assertEquals(Action.CREATE, m.action)
        assertEquals("CREATE VAULT", m.actionLabel)
    }

    @Test
    fun `both dead states need a reset and say why differently`() {
        val old = model(VaultState.State.UNRECOVERABLE, unlocked = false)
        val orphaned = model(VaultState.State.ORPHANED, unlocked = false)
        for (m in listOf(old, orphaned)) {
            assertEquals("NEEDS RESET", m.value)
            assertEquals(Led.State.CAUTION, m.led)
            assertEquals(Action.RESET, m.action)
            assertEquals("RESET VAULT", m.actionLabel)
        }
        assertNotEquals(old.detail, orphaned.detail)
    }

    @Test
    fun `the locked detail differs between Secrets and SSH`() {
        val secrets = model(VaultState.State.UNLOCKABLE, unlocked = false, ssh = false)
        val ssh = model(VaultState.State.UNLOCKABLE, unlocked = false, ssh = true)
        assertNotEquals(secrets.detail, ssh.detail)
        assertEquals("Unlock to add a connection.", ssh.detail)
    }

    @Test
    fun `no detail sends the person to the wrong place`() {
        for (state in VaultState.State.entries) for (unlocked in listOf(true, false)) for (ssh in listOf(true, false)) {
            val d = model(state, unlocked, ssh).detail.lowercase()
            assertFalse(d, d.contains("reopen the app"))
            assertFalse(d, d.contains("unlock the app"))
        }
    }
}
