package com.mido.phonebridge.screenshots

import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.mido.phonebridge.SshActivity
import com.mido.phonebridge.core.VaultState
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/** SSH connections (WP09 section 5.2). */
class SshScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun shot(state: String) = capture(launch(SshActivity::class.java), "ssh", state)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun ssh_unlocked_empty() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, emptyMap())
        shot("unlocked_empty")
    }

    @Test fun ssh_unlocked_two() {
        Fixtures.sshVault(unlocked = true)
        shot("unlocked_two")
    }

    @Test fun ssh_locked_two() {
        Fixtures.sshVault(unlocked = false)
        shot("locked_two")
    }

    @Test fun ssh_absent() {
        Fixtures.vault(VaultState.State.ABSENT, unlocked = false, emptyMap())
        shot("absent")
    }

    @Test fun ssh_broken_orphaned() {
        Fixtures.vault(VaultState.State.ORPHANED, unlocked = false, emptyMap())
        shot("broken_orphaned")
    }

    @Test fun ssh_list_unreadable() {
        Fixtures.vaultCorruptIndex()
        shot("list_unreadable")
    }

    @Test fun ssh_entry_unreadable() {
        Fixtures.sshVault(unlocked = true, brokenEntry = true)
        shot("entry_unreadable")
    }

    private fun unlockedEmpty(): SshActivity {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, emptyMap())
        return launch(SshActivity::class.java)
    }

    @Test fun ssh_dialog_add() {
        val a = unlockedEmpty()
        findByText(a.window.decorView, "ADD CONNECTION")!!.performClick()
        idle()
        Shots.screen("ssh", "dialog_add")
    }

    @Test fun ssh_dialog_add_error() {
        val a = unlockedEmpty()
        findByText(a.window.decorView, "ADD CONNECTION")!!.performClick()
        idle()
        // An empty name is refused inside the dialog, which stays open with its fields.
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        Shots.screen("ssh", "dialog_add_error")
    }

    @Test fun ssh_dialog_public_key() {
        Fixtures.sshVault(unlocked = true)
        val a = launch(SshActivity::class.java)
        findByDescription(a.window.decorView, "Show the public key of prod")!!.performClick()
        idle()
        Shots.screen("ssh", "dialog_public_key")
    }

    @Test fun ssh_dialog_delete() {
        Fixtures.sshVault(unlocked = true)
        val a = launch(SshActivity::class.java)
        findByDescription(a.window.decorView, "Delete prod")!!.performClick()
        idle()
        Shots.screen("ssh", "dialog_delete")
    }
}
