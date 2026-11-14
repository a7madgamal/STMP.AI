package com.mido.phonebridge.kit

import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mido.phonebridge.ui.kit.RockerSwitch
import com.mido.phonebridge.ui.kit.ToggleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToggleRowTest : KitTest() {

    @Test
    fun `a click on the row reports the opposite of the shown state`() {
        val a = host()
        val sent = ArrayList<Boolean>()
        val row = a.place(ToggleRow.create(a, "Sound effects", "A short sound", on = false) { sent.add(it) })
        row.performClick()
        assertEquals(listOf(true), sent)
    }

    @Test
    fun `the node is a switch with its state and the rocker is hidden from accessibility`() {
        val a = host()
        val row = a.place(ToggleRow.create(a, "Sound effects", "A short sound", on = true) {})
        val info = AccessibilityNodeInfoCompat.wrap(row.createAccessibilityNodeInfo())
        assertEquals("android.widget.Switch", info.className.toString())
        assertTrue(info.isCheckable)
        assertTrue(info.isChecked)
        assertEquals("On", info.stateDescription.toString())
        val rocker = row.allViews().filterIsInstance<RockerSwitch>().first()
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, rocker.importantForAccessibility)
    }

    @Test
    fun `a disabled row does not click`() {
        val a = host()
        val sent = ArrayList<Boolean>()
        val row = a.place(ToggleRow.create(a, "Fingerprint", "No fingerprint", on = false, enabled = false) { sent.add(it) })
        assertFalse(row.isClickable)
        assertEquals(0.38f, row.alpha, 0.001f)
    }
}
