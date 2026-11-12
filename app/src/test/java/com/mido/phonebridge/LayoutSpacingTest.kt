package com.mido.phonebridge

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.kit.allViews
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.TabBar
import com.mido.phonebridge.ui.tokens.Dimens
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout spacing over every screen fixture at font scale 1.0 and 1.35, light (D72):
 * 1. a key's label sits inside the key's horizontal padding and is neither ellipsized nor cut;
 * 2. two text or key siblings in one row are at least 8dp apart;
 * 3. content starts at least GUTTER (20dp) from the left screen edge and ends at least 20dp from the right.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class LayoutSpacingTest(private val group: String, private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} at font {1}")
        fun params() = ScreenSweep.groups.keys.flatMap { g -> listOf(arrayOf<Any>(g, 1f), arrayOf<Any>(g, 1.35f)) }

        private const val MIN_ROW_GAP_DP = 8f
        private const val TOLERANCE_DP = 0.5f
        private const val RULE_MAX_DP = 2f
    }

    @Test
    fun `every screen fixture keeps its labels, rows and gutters`() {
        val failures = ArrayList<String>()
        ScreenSweep.run(group, fontScale) { name, root ->
            keyLabels(name, root, failures)
            rowGaps(name, root, failures)
            gutters(name, root, failures)
        }
        assertTrue("layout spacing failures:\n" + failures.sorted().distinct().joinToString("\n"), failures.isEmpty())
    }

    // 1 -------------------------------------------------------------------------------------

    private fun keyLabels(name: String, root: ViewGroup, failures: MutableList<String>) {
        val d = root.resources.displayMetrics.density
        for (key in root.allViews().filterIsInstance<MaterialButton>()) {
            if (!key.isShown || key.text.isNullOrBlank()) continue
            val layout = key.layout ?: continue
            val last = layout.lineCount - 1
            val ellipsized = (0..last).any { layout.getEllipsisCount(it) > 0 }
            val cut = layout.getLineEnd(last) < key.text.length
            val widest = (0..last).maxOf { layout.getLineWidth(it) }
            val room = key.width - key.paddingLeft - key.paddingRight
            val label = "\"${key.text.toString().take(24)}\""
            if (ellipsized || cut) failures += "$name: key $label is ellipsized or cut"
            else if (widest > room + TOLERANCE_DP * d) {
                failures += "$name: key $label text is ${widest / d}dp wide in ${room / d}dp (padding ${key.paddingLeft / d}dp)"
            }
        }
    }

    // 2 -------------------------------------------------------------------------------------

    private fun rowGaps(name: String, root: ViewGroup, failures: MutableList<String>) {
        val d = root.resources.displayMetrics.density
        for (parent in root.allViews().filterIsInstance<ViewGroup>()) {
            if (parent is FrameLayout || parent is ScrollView || parent is NestedScrollView) continue
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) continue
            // Allow-list: the segments of a knob and the cells of a tab bar are one control; they
            // touch by design and each carries its own padding.
            if (parent is KnobSelector || parent is TabBar) continue
            val kids = (0 until parent.childCount).map(parent::getChildAt).filter { it.isShown && textBounds(it) != null }
            for (i in kids.indices) for (j in i + 1 until kids.size) {
                val a = textBounds(kids[i])!!; val b = textBounds(kids[j])!!
                if (minOf(a.bottom, b.bottom) <= maxOf(a.top, b.top)) continue   // not in one row
                val gap = maxOf(b.left - a.right, a.left - b.right)
                if (gap / d < MIN_ROW_GAP_DP - TOLERANCE_DP) {
                    failures += "$name: ${label(kids[i])} and ${label(kids[j])} are ${gap / d}dp apart in one row"
                }
            }
        }
    }

    /**
     * Where the text of [v] is drawn (a key counts as its whole face): the union over its text
     * descendants of the laid-out line extents, in window coordinates. Null when it has no text.
     */
    private fun textBounds(v: View): Rect? {
        if (v is MaterialButton) return if (v.text.isNullOrBlank()) null else rect(v)
        if (v is TextView) {
            val layout = v.layout ?: return null
            if (v.text.isNullOrBlank() || layout.lineCount == 0) return null
            val origin = IntArray(2).also { v.getLocationInWindow(it) }
            val lines = 0 until layout.lineCount
            val left = origin[0] + v.compoundPaddingLeft + lines.minOf { layout.getLineLeft(it) }.toInt()
            val right = origin[0] + v.compoundPaddingLeft + lines.maxOf { layout.getLineRight(it) }.toInt()
            return Rect(left, origin[1], right, origin[1] + v.height)
        }
        if (v !is ViewGroup) return null
        return (0 until v.childCount).mapNotNull { c -> v.getChildAt(c).takeIf { it.isShown }?.let(::textBounds) }
            .reduceOrNull { x, y -> Rect(minOf(x.left, y.left), minOf(x.top, y.top), maxOf(x.right, y.right), maxOf(x.bottom, y.bottom)) }
    }

    // 3 -------------------------------------------------------------------------------------

    private fun gutters(name: String, root: ViewGroup, failures: MutableList<String>) {
        val d = root.resources.displayMetrics.density
        val screenW = root.resources.displayMetrics.widthPixels
        val gutter = Dimens.GUTTER * d
        val skipRoots = root.allViews().filter { it is TabBar || isTopBar(it) }.toSet()
        // Allow-list: the NOTIFY card is a floating window with its own 12dp margin (WP03b), and the
        // receipt is a paper graphic drawn inset by its design; neither is a screen's content column.
        if (name.startsWith("notify_card/") || name.startsWith("kit_receipt/")) return
        for (v in root.allViews()) {
            if (v === root || !v.isShown || v.width == 0 || v.height == 0) continue
            if (generateSequence(v as View?) { it.parent as? View }.any { it in skipRoots }) continue
            if (v.height <= RULE_MAX_DP * d) continue                      // dividers and rules
            if (v.width >= screenW - 1) continue                            // full-bleed backgrounds
            if (v is ViewGroup && v.background == null) continue            // pure layout containers
            if (v is ScrollView || v is NestedScrollView) continue
            val r = rect(v)
            if (r.left < gutter - TOLERANCE_DP * d || r.right > screenW - gutter + TOLERANCE_DP * d) {
                failures += "$name: ${label(v)} spans ${r.left / d}..${r.right / d}dp of ${screenW / d}dp"
            }
        }
    }

    private fun isTopBar(v: View): Boolean =
        v is ViewGroup && (0 until v.childCount).any { v.getChildAt(it).contentDescription?.toString() == "Back" }

    private fun hasText(v: View): Boolean = v is TextView && !v.text.isNullOrBlank() ||
        v is ViewGroup && v.allViews().any { it is TextView && !it.text.isNullOrBlank() && it.isShown }

    private fun rect(v: View): Rect {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun label(v: View): String =
        (v as? TextView)?.let { "${v.javaClass.simpleName}(\"${it.text.toString().take(20)}\")" }
            ?: "${v.javaClass.simpleName}[${(v as? ViewGroup)?.allViews()?.filterIsInstance<TextView>()?.firstOrNull()?.text.toString().take(20)}]"
}
