package com.mido.phonebridge.kit

import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.tokens.FontFace
import com.mido.phonebridge.ui.tokens.Fonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StmpDialogTest : KitTest() {

    @Test
    fun `builder is a material builder and the dialog wears the panel theme`() {
        val a = host()
        assertEquals(MaterialAlertDialogBuilder::class.java, StmpDialog.builder(a).javaClass)
        val d: AlertDialog = StmpDialog.builder(a).setTitle("Title").setMessage("Body").setPositiveButton("OK", null).show()
        val title = d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!
        assertEquals(Fonts.get(a, FontFace.SANS_600), title.typeface)
        assertEquals(Theme.palette(a).ink, title.currentTextColor)
    }

    @Test
    fun `show maps actions to buttons and runs the handler`() {
        val a = host()
        var ran = 0
        val d = StmpDialog.show(a, "T", android.view.View(a), listOf("Cancel" to {}, "Delete" to { ran++ }))
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, ran)
        assertTrue(!d.isShowing)
    }
}
