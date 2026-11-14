package com.mido.phonebridge

import android.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.showDestructive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A confirm dialog must not open with the destructive key focused.
 *
 * **The platform focuses the positive button** — measured, not assumed. An `AlertDialog` with
 * `delete` positive and `cancel` negative opens with focus on `delete`, so on a hardware
 * keyboard, a D-pad, a switch device or TalkBack the first confirm gesture destroys the record.
 * The dialog was opened to prevent exactly that.
 *
 * The pair of assertions matters: `delete` gaining focus would prove nothing on its own, because
 * a dialog that focused nothing would also be "not focused on delete". Both are checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeleteDialogFocusTest {

    private fun builder(): AlertDialog.Builder = AlertDialog.Builder(
        ApplicationProvider.getApplicationContext(),
    )
        .setTitle("Delete 'todoist'?")
        .setMessage("gone")
        .setPositiveButton("delete") { _, _ -> }
        .setNegativeButton("cancel", null)

    @Test
    fun `the destructive key does not hold focus`() {
        val dialog = builder().showDestructive()
        // `setOnShowListener` is delivered by a HANDLER, so it runs after `show()` returns. Without
        // this the assertion would read the pre-listener state and fail for a reason that has
        // nothing to do with the fix.
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(
            "delete must not be the focused key; a stray confirm destroys the record",
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isFocused,
        )
        dialog.dismiss()
    }

    @Test
    fun `the harmless key does hold focus, so the assertion above is not vacuous`() {
        val dialog = builder().showDestructive()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(
            "keep/cancel must be focused, or focus went nowhere and the first test proves nothing",
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isFocused,
        )
        dialog.dismiss()
    }

    @Test
    fun `a destructive dialog is still screenshot-proof`() {
        // Deleting a secret names it in the title, so this window is as sensitive as the screen
        // behind it — the focus fix must not have quietly dropped the FLAG_SECURE from the chain.
        val dialog = builder().showDestructive()
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        assertTrue(
            dialog.window?.attributes?.flags?.and(flag) == flag,
        )
        dialog.dismiss()
    }
}
