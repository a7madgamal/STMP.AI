package com.mido.phonebridge

import com.mido.phonebridge.policy.ExternalDialect
import com.mido.phonebridge.ui.LocalModelState
import com.mido.phonebridge.ui.NotificationAccess
import com.mido.phonebridge.ui.RemoteState
import com.mido.phonebridge.ui.SettingsState
import com.mido.phonebridge.ui.VaultRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStateTest {
    private val e = 1000L

    private fun local(exists: Boolean, len: Long, running: Boolean = false, pct: Int = 0, err: String = "") =
        LocalModelState.of(exists, len, e, running, pct, err)

    @Test fun `ready when the file is the expected size`() =
        assertEquals(LocalModelState.Ready(e), local(true, e))

    @Test fun `not downloaded when absent and no error`() =
        assertEquals(LocalModelState.NotDownloaded(e), local(false, 0))

    @Test fun `incomplete when shorter`() =
        assertEquals(LocalModelState.Incomplete(120, e), local(true, 120))

    @Test fun `wrong file when longer`() =
        assertEquals(LocalModelState.WrongFile(e + 1, e), local(true, e + 1))

    @Test fun `downloading carries the percent`() =
        assertEquals(LocalModelState.Downloading(37, e), local(true, 10, running = true, pct = 37))

    @Test fun `failed carries the reason`() =
        assertEquals(LocalModelState.Failed("HTTP 503"), local(false, 0, err = "HTTP 503"))

    @Test fun `running beats ready`() =
        assertEquals(LocalModelState.Downloading(5, e), local(true, e, running = true, pct = 5, err = "x"))

    @Test fun `ready beats a stale failure`() =
        assertEquals(LocalModelState.Ready(e), local(true, e, err = "old"))

    @Test fun `failed beats incomplete`() =
        assertEquals(LocalModelState.Failed("x"), local(true, 10, err = "x"))

    @Test fun `remote states follow configureProblem order`() {
        assertEquals(RemoteState.NOT_SET, SettingsState.remote("", ExternalDialect.entries.first(), "m"))
        assertEquals(RemoteState.NOT_LISTED, SettingsState.remote("http://h:1", null, "m"))
        assertEquals(RemoteState.NO_MODEL, SettingsState.remote("http://h:1", ExternalDialect.entries.first(), " "))
        assertEquals(RemoteState.READY, SettingsState.remote("http://h:1", ExternalDialect.entries.first(), "m"))
    }

    @Test fun `notification access has three states`() {
        assertEquals(NotificationAccess.NEEDED, SettingsState.notificationAccess(false, false))
        assertEquals(NotificationAccess.NEEDED, SettingsState.notificationAccess(false, true))
        assertEquals(NotificationAccess.NOT_CONNECTED, SettingsState.notificationAccess(true, false))
        assertEquals(NotificationAccess.GRANTED, SettingsState.notificationAccess(true, true))
    }

    @Test fun `vault precedence is unlocked, reset, not created, locked`() {
        assertEquals(VaultRow.UNLOCKED, SettingsState.vault(true, true, true, false))
        assertEquals(VaultRow.NEEDS_RESET, SettingsState.vault(false, true, false, true))
        assertEquals(VaultRow.NEEDS_RESET, SettingsState.vault(false, false, true, false))
        assertEquals(VaultRow.NOT_CREATED, SettingsState.vault(false, false, false, false))
        assertEquals(VaultRow.LOCKED, SettingsState.vault(false, false, false, true))
    }

    @Test fun `exception counts use the exception wording`() {
        assertEquals("No exceptions", SettingsState.rulesCount(0))
        assertEquals("1 exception", SettingsState.rulesCount(1))
        assertEquals("2 exceptions", SettingsState.rulesCount(2))
    }
}
