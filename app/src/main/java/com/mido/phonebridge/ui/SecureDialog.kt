package com.mido.phonebridge.ui

import android.view.WindowManager
import android.app.AlertDialog

/**
 * Show a dialog that must not be captured, copied or summarised by the system.
 *
 * **Why this cannot be inherited from the screen.** `FLAG_SECURE` is per-WINDOW, and a dialog
 * gets its own window — it is not a child of the activity's. So `SecretsActivity` setting the
 * flag on itself protected the list of names and left every dialog it opened unprotected: the
 * reveal dialog (which prints a decrypted credential), the add/edit dialog (a password field
 * holding a token), the link dialog, and SSH's private-key confirmation. Each one was a window a
 * screenshot, a screen recorder, or the recents thumbnail could capture in full.
 *
 * Deliberately an extension on the dialog rather than a helper that builds one: a call site must
 * stay unable to show a secret-bearing dialog without answering the question. The flag is set
 * before `show()` because a window's flags applied afterwards are not guaranteed to take effect
 * for the frame already being composed.
 *
 * On the PLATFORM [AlertDialog], because that is what these screens build — `android.app` and
 * `androidx.appcompat` dialogs are unrelated types and an extension written for one is simply
 * inapplicable to the other, which the compiler says out loud rather than silently skipping the
 * flag.
 */
fun AlertDialog.showSecure(): Unit {
    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    show()
}

/**
 * The same for a builder chain, which is how nearly every one of these dialogs is written:
 * `AlertDialog.Builder(this).setTitle(..).setNegativeButton(..).showSecure()`.
 *
 * `show()` on a Builder RETURNS the dialog, so the flag cannot be set before it is shown from the
 * same expression — [android.app.AlertDialog.Builder.create] has to be called first, and the
 * dialog shown afterwards. Two overloads rather than one shared helper so a call site is flagged
 * by the compiler whichever shape it uses, instead of silently falling back to an unflagged
 * `show()`.
 */
fun AlertDialog.Builder.showSecure(): AlertDialog =
    create().also { dialog -> dialog.showSecure() }

/**
 * Show a dialog whose positive button DESTROYS something, with the harm-free button focused.
 *
 * **The platform focuses the positive button.** Measured, not assumed: an AlertDialog with
 * `delete` positive and `cancel` negative opens with focus on `delete`, and the accessibility
 * order starts there too. So on a hardware keyboard, a D-pad, a switch device, or TalkBack, the
 * first confirm gesture destroys the record — and the dialog was opened to prevent exactly that.
 *
 * `isFocusableInTouchMode` has to be set as well, or `requestFocus` is ignored on a phone that is
 * being touched, which is every phone.
 */
fun AlertDialog.Builder.showDestructive(): AlertDialog =
    create().also { dialog ->
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Set on the SHOW listener: the buttons do not exist until the dialog is created, and
        // their focusability is re-derived when the window is attached.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isFocusableInTouchMode = true
                requestFocus()
            }
        }
        dialog.show()
    }
