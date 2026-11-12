package com.mido.phonebridge

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.VaultState
import com.mido.phonebridge.screenshots.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** Secrets and SSH: the keys follow the vault, the window is secure, errors stay on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class SecretsActivityStateTest {

    @Before fun before() { Fixtures.dropSingletons(); Fixtures.freezeClock() }

    @After fun after() = Fixtures.reset()

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun secrets() = Robolectric.buildActivity(SecretsActivity::class.java).setup().get()

    private fun key(a: android.app.Activity, label: String): MaterialButton =
        a.window.decorView.all().filterIsInstance<MaterialButton>().first { it.text.toString() == label }

    private fun keyByDescription(a: android.app.Activity, d: String): MaterialButton =
        a.window.decorView.all().filterIsInstance<MaterialButton>().first { it.contentDescription?.toString() == d }

    private fun texts(a: android.app.Activity) =
        a.window.decorView.all().filterIsInstance<TextView>().map { it.text.toString() }.toList()

    @Test
    fun `locked disables reveal replace and the add keys but not delete`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = false, Fixtures.secretEntries())
        val a = secrets()

        assertFalse(keyByDescription(a, "Reveal todoist").isEnabled)
        assertFalse(keyByDescription(a, "Replace todoist").isEnabled)
        assertTrue(keyByDescription(a, "Delete todoist").isEnabled)
        assertFalse(key(a, "ADD SECRET").isEnabled)
        assertFalse(key(a, "ADD FROM TEMPLATE").isEnabled)
        assertTrue("the vault card offers the fix", key(a, "UNLOCK").isEnabled)
    }

    @Test
    fun `unlocked enables everything and drops the unlock key`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        val a = secrets()

        assertTrue(keyByDescription(a, "Reveal todoist").isEnabled)
        assertTrue(keyByDescription(a, "Replace todoist").isEnabled)
        assertTrue(keyByDescription(a, "Delete todoist").isEnabled)
        assertTrue(key(a, "ADD SECRET").isEnabled)
        assertTrue(key(a, "ADD FROM TEMPLATE").isEnabled)
        assertTrue(texts(a).contains("UNLOCKED"))
        assertTrue(a.window.decorView.all().filterIsInstance<MaterialButton>().none { it.text.toString() == "UNLOCK" })
    }

    @Test
    fun `the old wrong instructions are gone`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = false, Fixtures.secretEntries())
        val all = texts(secrets()).joinToString("\n").lowercase()
        assertFalse(all.contains("reopen the app"))
        assertFalse(all.contains("unlock the app first"))
    }

    @Test
    fun `an absent vault offers to create it and a dead one to reset it`() {
        Fixtures.vault(VaultState.State.ABSENT, unlocked = false, emptyMap())
        assertNotNull(key(secrets(), "CREATE VAULT"))
        Fixtures.vault(VaultState.State.ORPHANED, unlocked = false, Fixtures.secretEntries())
        assertNotNull(key(secrets(), "RESET VAULT"))
    }

    @Test
    fun `Secrets and SSH keep FLAG_SECURE`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = false, emptyMap())
        val s = secrets()
        val ssh = Robolectric.buildActivity(SshActivity::class.java).setup().get()
        for (a in listOf<android.app.Activity>(s, ssh)) {
            assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test
    fun `a failed save shows an error block and no toast`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        val a = secrets()
        // An https-only rule: the vault refuses this link, which is a PluginError from setLinks.
        keyByDescription(a, "Edit links for notion").performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val edits = dialog.window!!.decorView.all().filterIsInstance<android.widget.EditText>().toList()
        edits.first().setText("http://not-https.example.com")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        idle()
        assertTrue(texts(a).contains("Couldn't save links"))
        assertNull("an error is shown on the screen, not in a toast", ShadowToast.getLatestToast())
    }

    @Test
    fun `reveal shows the value and it cannot be selected`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        val a = secrets()
        keyByDescription(a, "Reveal todoist").performClick()
        val dialog = ShadowDialog.getLatestDialog()
        val value = dialog.window!!.decorView.all().filterIsInstance<TextView>().first { it.text.toString() == "tdst-DEMO-0000" }
        assertFalse(value.isTextSelectable)
    }

    @Test
    fun `the reveal dialog window keeps FLAG_SECURE`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        val a = secrets()
        keyByDescription(a, "Reveal todoist").performClick()
        val flags = ShadowDialog.getLatestDialog().window!!.attributes.flags
        assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun `delete takes initial focus on cancel`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        val a = secrets()
        keyByDescription(a, "Delete todoist").performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isFocused)
    }

    @Test
    fun `deleting while locked says why on the screen`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = false, Fixtures.secretEntries())
        val a = secrets()
        keyByDescription(a, "Delete todoist").performClick()
        assertTrue(texts(a).contains("Can't delete todoist"))
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `an unreadable index shows the error block with the raw reason`() {
        Fixtures.vaultCorruptIndex()
        val t = texts(secrets())
        assertTrue(t.contains("The vault list can't be read"))
        assertTrue(t.any { it.contains("secret index unreadable") })
    }

    @Test
    fun `a locked vault lists SSH connections by name instead of calling them unreadable`() {
        Fixtures.sshVault(unlocked = false)
        val ssh = Robolectric.buildActivity(SshActivity::class.java).setup().get()
        val t = texts(ssh).joinToString("\n")
        assertTrue(t.contains("Unlock to add a connection."))
        assertTrue(t.contains("Unlock the vault to see this connection."))
        assertFalse(t.lowercase().contains("unreadable"))
    }

    @Test
    fun `an unlocked vault shows the host key state and the stmp key comment`() {
        Fixtures.sshVault(unlocked = true)
        val ssh = Robolectric.buildActivity(SshActivity::class.java).setup().get()
        val t = texts(ssh)
        assertTrue(t.contains("PINNED"))
        assertTrue(t.contains("NOT TRUSTED YET"))
        assertTrue(t.any { it.startsWith("ssh-ed25519 ") && it.endsWith("stmp-prod") })
        assertFalse(t.any { it.contains("phonebridge-") })
    }

    @Test
    fun `one unreadable SSH entry does not hide the others`() {
        Fixtures.sshVault(unlocked = true, brokenEntry = true)
        val t = texts(Robolectric.buildActivity(SshActivity::class.java).setup().get())
        assertTrue(t.any { it.startsWith("Can't read this connection:") })
        assertTrue(t.contains("PINNED"))
    }

    @Test
    fun `the vault rejects a non-https link with a PluginError the screen can show`() {
        Fixtures.vault(VaultState.State.UNLOCKABLE, unlocked = true, Fixtures.secretEntries(), Fixtures.secretValues())
        val ctx: Context = Fixtures.app
        try {
            SecretVault.setLinks(ctx, "notion", "http://x.example.com", "")
            throw AssertionError("expected a PluginError")
        } catch (e: PluginError) {
            assertEquals(false, e.message.isNullOrEmpty())
        }
    }
}
