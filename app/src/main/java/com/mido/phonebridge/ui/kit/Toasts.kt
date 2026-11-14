package com.mido.phonebridge.ui.kit

import android.content.Context
import android.widget.Toast

/** The only place the app makes a toast (COMPONENTS 17): confirm what the user just did. */
object Toasts {
    fun show(ctx: Context, text: String, duration: Int = Toast.LENGTH_SHORT) {
        // LENGTH_LONG stays available only for the legacy call sites; owner WPs retire it.
        val length = if (duration == Toast.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(ctx.applicationContext, text, length).show()
    }
}
