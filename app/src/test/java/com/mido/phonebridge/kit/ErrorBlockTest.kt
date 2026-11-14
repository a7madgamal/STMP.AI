package com.mido.phonebridge.kit

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mido.phonebridge.ui.kit.ErrorBlock
import com.mido.phonebridge.ui.kit.KeyButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R3: raw exception text lives behind "Details", inside the card, next to a recovery key. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ErrorBlockTest : KitTest() {

    private fun walk(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()

    private fun visibleText(v: View): String =
        walk(v).filter { it is TextView && isShown(it) }.joinToString("|") { (it as TextView).text.toString() }

    private fun isShown(v: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur.visibility != View.VISIBLE) return false
            cur = cur.parent as? View
        }
        return true
    }

    @Test fun `raw text is hidden until Details is tapped and the recovery runs`() {
        val a = host()
        var recovered = false
        val raw = "java.io.IOException: Unexpected character at line 3"
        val block = a.place(ErrorBlock.create(
            a, "Policy unreadable", "STMP refuses requests until this is fixed.", raw,
            KeyButton.Spec("Try again", "Read the policy again") { recovered = true },
        ))
        assertFalse(visibleText(block), visibleText(block).contains("IOException"))
        assertTrue(visibleText(block).contains("Policy unreadable"))

        val details = walk(block).filterIsInstance<TextView>().first { it.text == ErrorBlock.DETAILS }
        details.performClick(); idle()
        assertTrue(visibleText(block).contains("IOException"))
        assertEquals(ErrorBlock.HIDE_DETAILS, details.text.toString())

        val retry = walk(block).filterIsInstance<TextView>().first { it.text == "Try again" }
        retry.performClick()
        assertTrue(recovered)
    }

    @Test fun `no details key when there is no raw text`() {
        val a = host()
        val block = a.place(ErrorBlock.create(a, "Title", "Body", null))
        assertFalse(walk(block).filterIsInstance<TextView>().any { it.text == ErrorBlock.DETAILS })
        assertNotNull(block)
    }
}
