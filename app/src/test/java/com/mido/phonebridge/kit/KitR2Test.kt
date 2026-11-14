package com.mido.phonebridge.kit

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.OutsetRing
import com.mido.phonebridge.ui.kit.RadioMark
import com.mido.phonebridge.ui.kit.SEGMENT_PAD_H_DP
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.kit.Tape
import com.mido.phonebridge.ui.kit.VerbChip
import com.mido.phonebridge.ui.tokens.Dimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R2 kit fixes: states that did not render, the knob padding, the tape column, delete dialogs. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class KitR2Test : KitTest() {

    // --- keys -----------------------------------------------------------------------------

    @Test
    fun `a disabled key is dimmed to 38 percent and an enabled one is not`() {
        val a = host()
        val k = KeyButton.create(a, "Approve", KeyButton.Kind.INK) {}
        assertEquals(1f, k.alpha, 0f)
        k.isEnabled = false
        assertEquals(0.38f, k.alpha, 0.001f)
        k.isEnabled = true
        assertEquals(1f, k.alpha, 0f)
    }

    @Test
    fun `re-enabling a key that is still unarmed keeps it dimmed`() {
        val a = host()
        val k = KeyButton.create(a, "Deny", KeyButton.Kind.OUTLINE) {}
        KeyButton.setArmed(k, false, 2)
        k.isEnabled = false
        k.isEnabled = true
        assertEquals(0.38f, k.alpha, 0.001f)
    }

    @Test
    fun `the progress label is ink at every percentage`() {
        val a = host()
        val ink = Theme.palette(a).ink
        for (pct in listOf(0, 1, 36, 50, 99, 100)) {
            val k = KeyButton.progress(a, "Downloading $pct%", pct)
            assertEquals("label colour at $pct", ink, k.currentTextColor)
            assertFalse(k.isClickable)
        }
    }

    // --- radio mark ----------------------------------------------------------------------

    @Test
    fun `a radio mark in WRAP_CONTENT params still measures 20dp`() {
        val a = host()
        val d = a.resources.displayMetrics.density
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val mark = RadioMark.create(a, true)
        row.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        a.place(row)
        assertEquals((20 * d).toInt(), mark.width)
        assertEquals((20 * d).toInt(), mark.height)
    }

    // --- knob ------------------------------------------------------------------------------

    @Test
    fun `knob segments keep at least 12dp on each side`() {
        val a = host()
        val d = a.resources.displayMetrics.density
        val k = a.place(KnobSelector(a, "Gate mode", listOf("Whitelist", "Paranoid", "Don't disturb")))
        assertTrue(SEGMENT_PAD_H_DP >= 12)
        for (seg in k.segmentViews) {
            assertEquals((SEGMENT_PAD_H_DP * d).toInt(), seg.paddingLeft)
            assertEquals((SEGMENT_PAD_H_DP * d).toInt(), seg.paddingRight)
        }
    }

    @Test
    fun `no knob label breaks inside a word at either font scale`() {
        for (scale in listOf(1f, 1.35f)) {
            RuntimeEnvironment.setFontScale(scale)
            val a = host()
            val k = a.place(KnobSelector(a, "Gate mode", listOf("Whitelist", "Paranoid", "Don't disturb")))
            for (seg in k.segmentViews) {
                val inner = seg.width - seg.paddingLeft - seg.paddingRight
                val widest = seg.text.toString().uppercase().split(" ").maxOf { seg.paint.measureText(it) }
                assertTrue("'${seg.text}' word fits at $scale: $widest <= $inner", widest <= inner + 0.5f)
            }
        }
    }

    @Test
    fun `at 1_0 the gate labels all stay on one line`() {
        val a = host()
        val k = a.place(KnobSelector(a, "Gate mode", listOf("Whitelist", "Paranoid", "Don't disturb")))
        for (seg in k.segmentViews) assertEquals("'${seg.text}'", 1, seg.lineCount)
    }

    // --- tape -------------------------------------------------------------------------------

    @Test
    fun `every tape row starts its text at the same x whatever the verb`() {
        for (scale in listOf(1f, 1.35f)) {
            RuntimeEnvironment.setFontScale(scale)
            val a = host()
            val panel = a.place(com.mido.phonebridge.ui.kit.Panel.create(a))
            Verb.entries.forEach { panel.addView(Tape.row(a, it, "${it.name.lowercase()} on kvm-box", 0L)) }
            idle()
            val lefts = panel.allViews().filterIsInstance<TextView>()
                .filter { it.text.toString().endsWith("on kvm-box") }.map { it.left }.toSet()
            assertEquals("text left edges at $scale", 1, lefts.size)
            val chipWidths = panel.allViews().filterIsInstance<TextView>()
                .filter { t -> Verb.entries.any { it.name == t.text.toString() } }.map { it.width }.toSet()
            assertEquals("chip widths at $scale", setOf(VerbChip.columnWidthPx(a)), chipWidths)
        }
    }

    // --- focus ring ------------------------------------------------------------------------

    @Test
    fun `the focus ring never leaves the view bounds so all four edges show`() {
        val a = host()
        val d = a.resources.displayMetrics.density
        val b = android.graphics.Rect(0, 0, (60 * d).toInt(), (40 * d).toInt())
        for (inset in listOf(0f, 6f)) {
            val ring = OutsetRing(Color.RED, 2 * d, 1 * d, 12 * d, inset * d)
            ring.setBounds(b)
            val r = ring.ringRect(b)
            // The stroke (centre line plus half the stroke) stays inside the bounds on every side.
            assertTrue("left edge inside, inset $inset", r.left - d >= b.left)
            assertTrue("right edge inside, inset $inset", r.right + d <= b.right)
            assertTrue("top edge inside, inset $inset", r.top - d >= b.top)
            assertTrue("bottom edge inside, inset $inset", r.bottom + d <= b.bottom)
        }
    }

    @Test
    fun `a face with margin gets its ring in the margin, clear of the face`() {
        val a = host()
        val d = a.resources.displayMetrics.density
        val b = android.graphics.Rect(0, 0, (60 * d).toInt(), (48 * d).toInt())
        val ring = OutsetRing(Color.RED, 2 * d, 1 * d, 12 * d, 6 * d)
        val r = ring.ringRect(b)
        assertEquals("inner stroke edge is the 1dp gap above the face", 6 * d - d, r.top + d, 0.01f)
    }

    // --- disabled field -------------------------------------------------------------------

    @Test
    fun `a disabled field is a dashed flat box, not the sunken field`() {
        val a = host()
        val f = Field.create(a, "Name", "home laptop", "e.g. home laptop")
        val enabledBackground = f.edit.background
        f.setEnabled(false)
        assertTrue(f.edit.background is GradientDrawable)
        assertNotEquals(enabledBackground, f.edit.background)
        assertEquals(0.38f, f.edit.alpha, 0.001f)
        f.setEnabled(true)
        assertFalse(f.edit.background is GradientDrawable)
    }

    // --- dialogs ----------------------------------------------------------------------------

    @Test
    fun `the delete dialog has the safe key first and an outline delete key last, set apart`() {
        val a = host()
        val d = a.resources.displayMetrics.density
        var deleted = 0
        val dialog = StmpDialog.confirmDelete(a, "Forget laptop?", "It cannot be undone.", "Forget", "Keep") { deleted++ }
        dialog.show()
        idle()
        val keys = dialog.window!!.decorView.let { v -> (v as ViewGroup).allViews() }.filterIsInstance<MaterialButton>().filter { it.text.isNotBlank() }
        assertEquals(listOf("Keep", "Forget"), keys.map { it.text.toString() })
        val (keep, delete) = keys
        assertTrue("delete key is last (below)", (delete.parent as View).let { delete.top > keep.top })
        val gap = delete.top - keep.bottom
        assertTrue("at least ${Dimens.DESTRUCTIVE_GAP}dp apart, was ${gap / d}", gap >= Dimens.DESTRUCTIVE_GAP * d - 1)
        assertTrue("targets are 48dp", keep.height >= 48 * d - 1 && delete.height >= 48 * d - 1)
        val p = Theme.palette(a)
        assertEquals("ink label, never signal orange", p.ink, delete.currentTextColor)
        assertNotEquals(p.signal, delete.currentTextColor)
        delete.performClick()
        idle()
        assertEquals(1, deleted)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `keeping dismisses without deleting`() {
        val a = host()
        var deleted = 0
        var kept = 0
        val dialog = StmpDialog.confirmDelete(a, "T", "M", "Delete", onKeep = { kept++ }) { deleted++ }
        dialog.show()
        idle()
        val keep = (dialog.window!!.decorView as ViewGroup).allViews().filterIsInstance<MaterialButton>().first()
        keep.performClick()
        idle()
        assertEquals(0, deleted)
        assertEquals(1, kept)
    }

    @Test
    fun `the choice dialog rows are 56dp, carry a radio mark and show the current value`() {
        for (scale in listOf(1f, 1.35f)) {
            RuntimeEnvironment.setFontScale(scale)
            val a = host()
            val d = a.resources.displayMetrics.density
            var picked = -1
            val dialog = StmpDialog.choose(
                a, "Gmail · send",
                listOf(
                    StmpDialog.Choice("Default", "Ask for your stamp.", true),
                    StmpDialog.Choice("Don't disturb", "5 s card.", false),
                    StmpDialog.Choice("Paranoid", "Full prompt.", false),
                ),
            ) { picked = it }
            dialog.show()
            idle()
            val rows = (dialog.window!!.decorView as ViewGroup).allViews().filter { it.tag is Int && it.isClickable }
            assertEquals(3, rows.size)
            rows.forEach { assertTrue("row >= 56dp at $scale", it.height >= 56 * d - 1) }
            for (i in 1 until rows.size) {
                assertTrue("rows do not run together", rows[i].top - rows[i - 1].bottom >= Dimens.TARGET_GAP * d - 1)
            }
            assertTrue(rows.all { r -> r.allViews().any { it.width == (RadioMark.SIZE_DP * d).toInt() } })
            rows[2].performClick()
            idle()
            assertEquals(2, picked)
        }
    }
}
