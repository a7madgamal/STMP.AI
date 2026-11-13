package com.mido.phonebridge.kit

import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.StatusRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R2: the long-value rule, the LED on the first line and the end-aligned action key. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class StatusRowTest : KitTest() {

    private fun isLong(value: String, fontScale: Float): Boolean {
        RuntimeEnvironment.setFontScale(fontScale)
        val a = host()
        return StatusRow.isLongValue(a, value)
    }

    @Test
    fun `short statuses stay at the end of the row at both font scales`() {
        for (v in listOf("On", "Off", "Running", "Failed", "Granted")) {
            assertFalse("$v at 1.0", isLong(v, 1f))
            assertFalse("$v at 1.35", isLong(v, 1.35f))
        }
    }

    @Test
    fun `long statuses go under the title at both font scales`() {
        for (v in listOf("Not connected", "Not downloaded", "Needs attention")) {
            assertTrue("$v at 1.0", isLong(v, 1f))
            assertTrue("$v at 1.35", isLong(v, 1.35f))
        }
    }

    @Test
    fun `the rule is measured so a mid length value flips with the font scale`() {
        assertFalse(isLong("Not set up", 1f))
        assertTrue(isLong("Not set up", 1.35f))
    }

    @Test
    fun `a long value is a line of the body and the body keeps the whole row width`() {
        val a = host()
        val panel = a.place(Panel.create(a))
        val row = StatusRow.create(a, "Notification access", "Not connected", StatusRow.Tone.CAUTION, detail = "Allow it in settings.")
        panel.addView(row)
        idle()
        val value = row.allViews().filterIsInstance<TextView>().first { it.text.toString() == "Not connected" }
        val label = row.allViews().filterIsInstance<TextView>().first { it.text.toString() == "Notification access" }
        assertEquals("under the title, in the same column", label.parent, value.parent)
        assertTrue("below the title", value.top >= label.bottom)
        assertTrue("the title is not squeezed", label.width > row.width * 0.8f)
    }

    @Test
    fun `the light is centred on the first line of the title`() {
        val a = host()
        val panel = a.place(Panel.create(a))
        val row = StatusRow.create(
            a, "Notification access", "Not connected", StatusRow.Tone.CAUTION,
            detail = "A long explanation that wraps onto a second and a third line of text in the row.",
            led = Led.State.CAUTION,
        )
        panel.addView(row)
        idle()
        val led = row.allViews().filterIsInstance<Led>().first()
        val label = row.allViews().filterIsInstance<TextView>().first { it.text.toString() == "Notification access" }
        fun View.yIn(root: View): Int { var y = 0; var v: View = this; while (v !== root) { y += v.top; v = v.parent as View }; return y }
        val labelCentre = label.yIn(row) + label.lineHeight / 2f
        val ledCentre = led.yIn(row) + led.height / 2f
        assertEquals(labelCentre, ledCentre, 1.5f)
    }

    @Test
    fun `the action key ends at the row's end edge whatever the value is`() {
        for ((value, scale) in listOf("Off" to 1f, "Not connected" to 1f, "Not connected" to 1.35f)) {
            RuntimeEnvironment.setFontScale(scale)
            val a = host()
            val panel = a.place(Panel.create(a))
            val row = StatusRow.create(a, "Contacts", value, StatusRow.Tone.CAUTION, action = KeyButton.Spec("Open settings", "Open settings") {})
            panel.addView(row)
            idle()
            val key = row.allViews().filterIsInstance<MaterialButton>().firstOrNull()
            assertNotNull(key)
            val keyRight = key!!.right
            assertEquals("$value at $scale", row.width - row.paddingRight, keyRight)
        }
    }

    @Test
    fun `a detail that repeats the value is shown once`() {
        val a = host()
        val row = StatusRow.create(a, "AI model", "None", detail = "none")
        val texts = row.allViews().filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(listOf("AI model", "None"), texts)
    }

    @Test
    fun `the light is centred on the title of a single line row at both font scales`() {
        for (scale in listOf(1f, 1.35f)) {
            RuntimeEnvironment.setFontScale(scale)
            val a = host()
            val panel = a.place(Panel.create(a))
            val row = StatusRow.create(a, "Bridge", "On", StatusRow.Tone.OK, led = Led.State.OK)
            panel.addView(row)
            idle()
            val led = row.allViews().filterIsInstance<Led>().first()
            val label = row.allViews().filterIsInstance<TextView>().first { it.text.toString() == "Bridge" }
            fun View.yIn(root: View): Int { var y = 0; var v: View = this; while (v !== root) { y += v.top; v = v.parent as View }; return y }
            val labelCentre = label.yIn(row) + label.lineHeight / 2f
            val ledCentre = led.yIn(row) + led.height / 2f
            assertEquals("single line at $scale", labelCentre, ledCentre, 1.5f)
        }
    }

    @Test
    fun `a clickable row keeps its content clear of the focus ring`() {
        val a = host()
        val panel = a.place(Panel.create(a))
        val row = StatusRow.create(a, "Debug log", "Open", onClick = {})
        panel.addView(row)
        idle()
        val ring = (com.mido.phonebridge.ui.kit.FOCUS_ROW_INSET_DP * a.resources.displayMetrics.density).toInt()
        assertTrue("left padding clears the ring", row.paddingLeft >= ring)
        assertTrue("right padding clears the ring", row.paddingRight >= ring)
    }
}
