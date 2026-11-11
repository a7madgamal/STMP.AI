package com.mido.phonebridge.kit

import android.view.View
import android.view.ViewGroup
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.mido.phonebridge.ui.kit.KnobSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnobSelectorTest : KitTest() {

    private val labels = listOf("Whitelist", "Paranoid", "Don't disturb")

    @Test
    fun `onSelect fires on a tap only and select moves silently`() {
        val a = host()
        val picks = ArrayList<Int>()
        val k = a.place(KnobSelector(a, "Gate mode", labels))
        k.onSelect = { picks.add(it) }
        k.select(2, animate = false)
        assertEquals(2, k.selected)
        assertEquals(emptyList<Int>(), picks)
        (k.getChildAt(1) as View).performClick()
        assertEquals(listOf(1), picks)
        assertEquals("the thumb waits for the caller", 2, k.selected)
    }

    @Test
    fun `roles are radio group and radio buttons`() {
        val a = host()
        val k = a.place(KnobSelector(a, "Gate mode", labels))
        k.select(1, animate = false)
        assertEquals("android.widget.RadioGroup", AccessibilityNodeInfoCompat.wrap(k.createAccessibilityNodeInfo()).className.toString())
        val seg = k.getChildAt(1)
        val info = AccessibilityNodeInfoCompat.wrap(seg.createAccessibilityNodeInfo())
        assertEquals("android.widget.RadioButton", info.className.toString())
        assertTrue(info.isCheckable && info.isChecked)
        assertEquals("Paranoid", seg.contentDescription.toString())
        assertTrue(seg.height >= (48 * a.resources.displayMetrics.density).toInt() - 1)
    }

    @Test
    fun `dpad right moves focus to the next segment`() {
        val a = host()
        val k = a.place(KnobSelector(a, "Gate mode", labels))
        val first = k.getChildAt(0)
        first.requestFocus()
        assertSame(k.getChildAt(1), first.focusSearch(View.FOCUS_RIGHT))
    }
}
