package com.mido.phonebridge

import android.view.WindowManager
import androidx.appcompat.app.AlertDialog

/**
 * Dialog windows do not inherit the activity's FLAG_SECURE, so a dialog that holds or shows a
 * secret must set it on its own window before it is shown.
 */
fun AlertDialog.showSecure() {
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
    )
    show()
}

fun AlertDialog.Builder.showSecure(): AlertDialog = create().also { it.showSecure() }
