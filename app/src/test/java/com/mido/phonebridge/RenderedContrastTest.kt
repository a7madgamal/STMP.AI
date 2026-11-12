package com.mido.phonebridge

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mido.phonebridge.kit.allViews
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The rendered contrast sweep (TESTING 6.2): for every screen fixture, in light and in dark, every
 * visible text compared with the nearest ancestor background that is a known solid fill. Minimum
 * 4.5, or 3.0 for large text (24sp and up, or 18.66sp and up at weight 600 or more). Disabled
 * controls (dimmed by design, TOKENS 11) are exempt.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class RenderedContrastTest(private val group: String, private val night: Boolean) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} night={1}")
        fun params() = ScreenSweep.groups.keys.flatMap { g -> listOf(arrayOf<Any>(g, false), arrayOf<Any>(g, true)) }

        private const val NORMAL_MIN = 4.5
        private const val LARGE_MIN = 3.0
        private const val LARGE_SP = 24f
        private const val LARGE_BOLD_SP = 18.66f
        private const val BOLD_WEIGHT = 600
    }

    @Test
    fun `every visible text meets its contrast minimum`() {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        Palettes.clearCacheForTest()
        val failures = ArrayList<String>()
        ScreenSweep.run(group, 1f) { name, root -> sweep(name, root, failures) }
        assertTrue("the sweep measured no text at all in $group", measured > 0)
        assertTrue("rendered contrast failures (${if (night) "dark" else "light"}):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private var measured = 0

    private fun sweep(name: String, root: ViewGroup, failures: MutableList<String>) {
        val fallback = Theme.palette(root.context).body
        for (v in root.allViews()) {
            if (v !is TextView || !v.isShown || v.text.isNullOrBlank()) continue
            // Disabled controls are dimmed by design. The NOTIFY card is mid wall-clock fade-in (ArmGuard),
            // which is not a dimmed control, so its alpha is ignored.
            if (!name.startsWith("notify_card/") && effectiveAlpha(v) < 1f) continue
            // Allow-list: a knob's labels sit on a thumb that is painted by the knob, not a view
            // background, so there is no ancestor fill to measure. The pairs it uses (ink / onInk on
            // ink, ink on sunken) are asserted by ContrastTest.
            if (generateSequence(v as View?) { it.parent as? View }.any { it is com.mido.phonebridge.ui.kit.KnobSelector }) continue
            measured++
            val bg = backgroundBehind(v) ?: fallback
            val min = if (isLarge(v)) LARGE_MIN else NORMAL_MIN
            for (fg in foregrounds(v)) {
                val r = ratio(opaque(fg, bg), bg)
                if (r + 1e-9 < min) {
                    failures += "%s: \"%s\" %s on %s is %.2f < %.1f".format(
                        name, v.text.toString().take(30), hex(fg), hex(bg), r, min,
                    )
                }
            }
        }
    }

    private fun foregrounds(v: TextView): Set<Int> {
        val t = v.text
        if (t is Spanned) {
            val spans = t.getSpans(0, t.length, ForegroundColorSpan::class.java).map { it.foregroundColor }
            if (spans.isNotEmpty()) return spans.toSet()
        }
        return setOf(v.currentTextColor)
    }

    private fun isLarge(v: TextView): Boolean {
        val sp = v.textSize / (v.resources.displayMetrics.density * v.resources.configuration.fontScale)
        val weight = v.typeface?.weight ?: 400
        return sp >= LARGE_SP || (sp >= LARGE_BOLD_SP && weight >= BOLD_WEIGHT)
    }

    private fun effectiveAlpha(v: View): Float {
        var a = 1f
        var cur: View? = v
        while (cur != null) { a *= cur.alpha; cur = cur.parent as? View }
        return a
    }

    private fun backgroundBehind(v: View): Int? {
        var cur: View? = v
        while (cur != null) {
            solidColor(cur.background)?.let { return it }
            cur = cur.parent as? View
        }
        return null
    }

    /** The colour of the top-most known solid fill of [d], or null when it is not a known solid. */
    private fun solidColor(d: Drawable?): Int? = when (d) {
        null -> null
        is ColorDrawable -> d.color.takeIf { Color.alpha(it) == 255 }
        is GradientDrawable -> d.color?.defaultColor?.takeIf { Color.alpha(it) == 255 }
        is StateListDrawable -> solidColor(d.current)
        // Top-most layer that is a known solid. Skipped: a ripple's mask, and layers with an explicit
        // size (the 1dp rule under a bar, a shadow strip), which cover only part of the view.
        is LayerDrawable -> (d.numberOfLayers - 1 downTo 0)
            .filter { d.getId(it) != android.R.id.mask && d.getLayerHeight(it) < 0 && d.getLayerWidth(it) < 0 }
            .firstNotNullOfOrNull { solidColor(d.getDrawable(it)) }
        else -> null
    }

    private fun opaque(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255f
        if (a >= 1f) return fg
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a)).toInt()
        return Color.rgb(mix(Color.red(fg), Color.red(bg)), mix(Color.green(fg), Color.green(bg)), mix(Color.blue(fg), Color.blue(bg)))
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun ratio(fg: Int, bg: Int): Double {
        val a = lum(fg)
        val b = lum(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
