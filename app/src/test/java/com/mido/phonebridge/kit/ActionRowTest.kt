package com.mido.phonebridge.kit

import android.graphics.Rect
import android.view.View
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ui.kit.ActionRow
import com.mido.phonebridge.ui.kit.KeyButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ActionRowTest : KitTest() {

    private fun spec(label: String) = KeyButton.Spec(label, "$label laptop") {}

    private fun gapDp(a: Rect, b: Rect, d: Float): Float {
        val dx = maxOf(a.left - b.right, b.left - a.right, 0)
        val dy = maxOf(a.top - b.bottom, b.top - a.bottom, 0)
        return maxOf(dx, dy) / d
    }

    private fun keys(row: ActionRow) = row.allViews().filterIsInstance<MaterialButton>()

    @Test
    fun `inline at normal size with short labels`() {
        val a = host()
        val row = a.place(ActionRow(a, "laptop", "100.64.0.2", listOf(spec("Edit")), spec("Forget")))
        assertTrue(row.isInline)
        val ks = keys(row)
        assertEquals(ks.map { it.top }.distinct().size, 1)
    }

    @Test
    fun `stacked at 1_35 with the destructive key last and 16dp away`() {
        RuntimeEnvironment.setFontScale(1.35f)
        val a = host()
        val row = a.place(ActionRow(a, "laptop", "100.64.0.2", listOf(spec("Edit")), spec("Forget")))
        assertFalse(row.isInline)
        val d = a.resources.displayMetrics.density
        val ks = keys(row)
        val destructive = ks.last()
        val r1 = Rect(); val r2 = Rect()
        ks.dropLast(1).forEach {
            it.getHitRect(r1); destructive.getHitRect(r2)
            assertTrue("gap ${gapDp(r1, r2, d)}", gapDp(r1, r2, d) >= 16f - 0.5f)
        }
    }

    @Test
    fun `wrap fallback keeps three wide keys inside the row without overlap`() {
        RuntimeEnvironment.setFontScale(1.35f)
        val a = host()
        val row = a.place(ActionRow(a, "API token", "stored", listOf(spec("Replace value"), spec("Copy name")), spec("Delete secret")))
        val d = a.resources.displayMetrics.density
        val ks = keys(row)
        val rects = ks.map { Rect().also { r -> it.getHitRect(r) } }
        rects.forEach {
            assertTrue(it.left >= 0 && it.right <= row.width && it.top >= 0 && it.bottom <= row.height)
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            assertFalse("overlap $i/$j", Rect.intersects(rects[i], rects[j]))
        }
        val destructive = rects.last()
        assertEquals("destructive on the last line", rects.maxOf { it.top }, destructive.top)
        rects.dropLast(1).forEach { assertTrue(gapDp(it, destructive, d) >= 15.5f) }
    }
}
