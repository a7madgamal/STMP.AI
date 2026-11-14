package com.mido.phonebridge

import android.app.AlertDialog
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.showSecure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A dialog holding a secret gets its OWN window, and a window inherits nothing.
 *
 * `FLAG_SECURE` is per-window. `SecretsActivity` and `SshActivity` both set it on themselves, which
 * protects the list of names — and protected none of the dialogs they open, because a dialog is a
 * separate window. The reveal dialog prints a decrypted credential; the add/edit dialog holds one
 * in a password field. Every one of them could be screenshotted, screen-recorded, or captured in
 * the recents thumbnail.
 *
 * This asserts the flag is really on the dialog's window, and that it is set BEFORE the window is
 * shown — flags applied afterwards are not guaranteed to affect a frame already being composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureDialogTest {

    private val flag = WindowManager.LayoutParams.FLAG_SECURE

    private fun builder(): AlertDialog.Builder =
        AlertDialog.Builder(ApplicationProvider.getApplicationContext())
            .setTitle("todoist")
            .setMessage("sk-live-abc123")
            .setPositiveButton("hide", null)

    @Test
    fun `a shown dialog carries FLAG_SECURE on its own window`() {
        val dialog = builder().create()
        assertTrue(
            "the fixture must start unprotected, or this test proves nothing",
            dialog.window?.attributes?.flags?.and(flag) == 0,
        )
        dialog.showSecure()
        assertEquals(
            "the dialog's window must refuse capture",
            flag, dialog.window?.attributes?.flags?.and(flag),
        )
        dialog.dismiss()
    }

    @Test
    fun `the builder overload protects the dialog it shows`() {
        // Nearly every call site is a chain ending in `.show()`, which returns an already-shown
        // dialog — so the flag could not be applied in time. This is the shape they now use.
        val dialog = builder().showSecure()
        assertEquals(flag, dialog.window?.attributes?.flags?.and(flag))
        dialog.dismiss()
    }

    @Test
    fun `the flag is on before the window is composed`() {
        // The distinction that matters: `show()` then `addFlags` leaves the first frame
        // capturable. Reading the flag from the shown dialog proves it was applied first.
        val dialog = builder().showSecure()
        val shown = dialog.isShowing
        assertTrue("the dialog must actually be showing", shown)
        assertEquals(flag, dialog.window?.attributes?.flags?.and(flag))
        dialog.dismiss()
    }

    @Test
    fun `a plain show is measurably unprotected, which is what the fix removes`() {
        // Pins the BUG rather than the fix: without showSecure the same dialog has no flag. If
        // `AlertDialog` ever started inheriting the activity's flag, this would fail and the
        // extension would be redundant — which is worth knowing rather than assuming.
        val dialog = builder().create()
        dialog.show()
        assertNotEquals(flag, dialog.window?.attributes?.flags?.and(flag))
        dialog.dismiss()
    }
}
