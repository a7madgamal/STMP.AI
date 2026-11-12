package com.mido.phonebridge.screenshots

import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.mido.phonebridge.SecretsActivity
import com.mido.phonebridge.core.SecretTemplates
import com.mido.phonebridge.core.VaultState
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/** Secrets (WP09 section 5.2): every state of the vault card and the list, and each dialog. */
class SecretsScreenshotTest(variant: Variant) : ShotTest(variant) {

    private val unlockable = VaultState.State.UNLOCKABLE

    private fun shot(state: String) = capture(launch(SecretsActivity::class.java), "secrets", state)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun unlockedTwo(): SecretsActivity {
        Fixtures.vault(unlockable, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        return launch(SecretsActivity::class.java)
    }

    @Test fun secrets_unlocked_empty() {
        Fixtures.vault(unlockable, unlocked = true, emptyMap())
        shot("unlocked_empty")
    }

    @Test fun secrets_unlocked_two() {
        Fixtures.vault(unlockable, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        shot("unlocked_two")
    }

    @Test fun secrets_locked_two() {
        Fixtures.vault(unlockable, unlocked = false, Fixtures.secretEntries())
        shot("locked_two")
    }

    @Test fun secrets_absent() {
        Fixtures.vault(VaultState.State.ABSENT, unlocked = false, emptyMap())
        shot("absent")
    }

    @Test fun secrets_broken_orphaned() {
        Fixtures.vault(VaultState.State.ORPHANED, unlocked = false, Fixtures.secretEntries())
        shot("broken_orphaned")
    }

    @Test fun secrets_broken_unrecoverable() {
        Fixtures.vault(VaultState.State.UNRECOVERABLE, unlocked = false, Fixtures.secretEntries())
        shot("broken_unrecoverable")
    }

    @Test fun secrets_list_unreadable() {
        Fixtures.vaultCorruptIndex()
        shot("list_unreadable")
    }

    @Test fun secrets_unlock_failed() {
        Fixtures.vault(unlockable, unlocked = false, Fixtures.secretEntries())
        val a = launch(SecretsActivity::class.java)
        a.setVaultErrorForTest("Fingerprint not recognised")
        capture(a, "secrets", "unlock_failed")
    }

    @Test fun secrets_action_error() {
        val a = unlockedTwo()
        findByDescription(a.window.decorView, "Edit links for notion")!!.performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val field = findByDescription(dialog.window!!.decorView, "API DOCS") ?: dialog.window!!.decorView
        firstEdit(field).setText("http://not-https.example.com")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        capture(a, "secrets", "action_error")
    }

    private fun firstEdit(root: android.view.View): android.widget.EditText {
        if (root is android.widget.EditText) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) {
            runCatching { return firstEdit(root.getChildAt(i)) }
        }
        error("no EditText")
    }

    private fun open(description: String) {
        val a = unlockedTwo()
        findByDescription(a.window.decorView, description)!!.performClick()
        idle()
    }

    @Test fun secrets_dialog_add() {
        val a = unlockedTwo()
        findByText(a.window.decorView, "ADD SECRET")!!.performClick()
        idle()
        Shots.screen("secrets", "dialog_add")
    }

    @Test fun secrets_dialog_replace() {
        open("Replace todoist")
        Shots.screen("secrets", "dialog_replace")
    }

    @Test fun secrets_dialog_template_picker() {
        val a = unlockedTwo()
        findByText(a.window.decorView, "ADD FROM TEMPLATE")!!.performClick()
        idle()
        Shots.screen("secrets", "dialog_template_picker")
    }

    @Test fun secrets_dialog_template_add() {
        val a = unlockedTwo()
        findByText(a.window.decorView, "ADD FROM TEMPLATE")!!.performClick()
        idle()
        val picker = ShadowDialog.getLatestDialog() as AlertDialog
        // The first template that is not already in the vault (the fixture holds todoist and notion).
        val index = SecretTemplates.ALL.indexOfFirst { it.name != "todoist" && it.name != "notion" }
        val list = picker.listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
        idle()
        Shots.screen("secrets", "dialog_template_add")
    }

    @Test fun secrets_dialog_links() {
        open("Edit links for todoist")
        Shots.screen("secrets", "dialog_links")
    }

    @Test fun secrets_dialog_host() {
        open("Allow a host for todoist")
        Shots.screen("secrets", "dialog_host")
    }

    @Test fun secrets_dialog_delete() {
        open("Delete todoist")
        Shots.screen("secrets", "dialog_delete")
    }

    @Test fun secrets_dialog_reveal() {
        open("Reveal todoist")
        Shots.screen("secrets", "dialog_reveal")
    }
}
