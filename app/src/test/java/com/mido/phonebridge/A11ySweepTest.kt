package com.mido.phonebridge

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.mido.phonebridge.kit.allViews
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.RockerSwitch
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accessibility sweep over every screen fixture (WP12 step 9), light, font scale 1.0:
 * names on icons, switches and knobs; headings; no raw machine value in visible text; and every
 * view at its end state with animations off.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class A11ySweepTest(private val group: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = ScreenSweep.groups.keys.map { arrayOf<Any>(it) }

        private val RAW_VALUES = listOf(
            Regex("""\bnull\b"""), Regex("""\b1[0-9]{12}\b"""), Regex("""\bdont_disturb\b"""),
            Regex("""—\s*$"""), Regex("""\bPhoneBridge\b"""), Regex("""\bOPTIONS\b"""), Regex("""\bAUTOMATIC\b"""),
        )
        /**
         * A plugin id leaks when it IS the text ("ssh", "tts") or is named as the plugin
         * ("the ssh plugin"). The words also occur in ordinary prose ("Search your
         * contacts", "No secrets yet"), which is the display wording and fine.
         */
        private val PLUGIN_IDS = listOf(
            "tts", "notifications", "contacts", "gmail", "calendar", "drive", "google", "secrets", "ssh",
            "audio", "help", "debug", "logs", "probe",
        ).map { Regex("""^$it$|\b$it plugin\b""") }

        /** Alpha values that are a state, not an animation frame: disabled controls and unarmed keys. */
        private val STATE_ALPHAS = listOf(0.38f, 0.35f)
    }

    @Test
    fun `every screen fixture passes the accessibility checks`() {
        android.provider.Settings.Global.putFloat(
            Fixtures_app().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f,
        )
        val failures = ArrayList<String>()
        // The platform sets this from the setting; the test JVM has no settings observer.
        setAnimatorScale(0f)
        try {
            ScreenSweep.run(group, 1f) { name, root -> sweep(name, root, failures) }
        } finally {
            setAnimatorScale(1f)
        }
        assertTrue("accessibility failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** The platform copies the setting into ValueAnimator; the test JVM has no settings observer. */
    private fun setAnimatorScale(scale: Float) {
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, scale)
    }

    private fun Fixtures_app() = com.mido.phonebridge.screenshots.Fixtures.app

    private fun sweep(name: String, root: ViewGroup, failures: MutableList<String>) {
        val all = root.allViews()
        for (v in all) {
            if (v is ImageView && v.isShown && v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO &&
                v.drawable != null && v.contentDescription.isNullOrBlank() && !insideNamedParent(v)
            ) failures += "$name: ImageView without a contentDescription"
            if (v.isShown && v.isClickable && v !is TextView && v.contentDescription.isNullOrBlank() &&
                v !is RockerSwitch && v !is KnobSelector && !hasText(v) && !hasNamedChild(v)
            ) failures += "$name: ${v.javaClass.simpleName} is clickable and has no name"
            if (v is RockerSwitch && v.label.isBlank()) failures += "$name: RockerSwitch without a label"
            if (v is KnobSelector && v.groupLabel.isBlank()) failures += "$name: KnobSelector without a groupLabel"
        }
        for (title in topBarTitles(all)) {
            if (!ViewCompat.isAccessibilityHeading(title)) failures += "$name: top bar title \"${title.text}\" is not a heading"
        }
        for (h in sectionHeaderLooking(all, root)) {
            if (!ViewCompat.isAccessibilityHeading(h)) failures += "$name: section header \"${h.text}\" is not a heading"
        }
        for (v in all.filterIsInstance<TextView>()) {
            if (!v.isShown || v.text.isNullOrBlank() || inReadout(v)) continue
            val text = v.text.toString()
            for (re in RAW_VALUES) if (re.containsMatchIn(text)) failures += "$name: raw value ${re.pattern} in \"${text.take(40)}\""
            for (re in PLUGIN_IDS) if (re.containsMatchIn(text)) failures += "$name: plugin id ${re.pattern} shown in \"${text.take(40)}\""
        }
        for (v in all) {
            if (!v.isShown || v.alpha <= 0f || v.alpha >= 1f) continue
            // Allow-list: the NOTIFY card fades in on a wall-clock ramp (ArmGuard.cardAlpha, a tapjacking
            // guard), not on an animator, so the animator scale cannot finish it. Not motion polish.
            if (name.startsWith("notify_card/")) continue
            if (STATE_ALPHAS.none { Math.abs(it - v.alpha) < 0.005f }) {
                failures += "$name: ${v.javaClass.simpleName} stopped at alpha ${v.alpha} (animations are off)"
            }
        }
    }

    /** An icon inside a parent that is itself named (the parent speaks for it). */
    private fun insideNamedParent(v: View): Boolean =
        generateSequence(v.parent as? View) { it.parent as? View }.any { !it.contentDescription.isNullOrBlank() || it.isClickable && hasText(it) }

    private fun hasText(v: View): Boolean = v is ViewGroup && v.allViews().any { it is TextView && !it.text.isNullOrBlank() }

    private fun hasNamedChild(v: View): Boolean = v is ViewGroup && v.allViews().any { it !== v && !it.contentDescription.isNullOrBlank() }

    /** Verbatim data (a command, a payload, a log) lives in a Readout, whose text is selectable so Copy gives it exactly. */
    private fun inReadout(v: TextView): Boolean = v.isTextSelectable

    /** The title next to a "Back" key is the screen's top bar title. */
    private fun topBarTitles(all: List<View>): List<TextView> =
        all.filter { it.contentDescription?.toString() == "Back" }
            .mapNotNull { (it.parent as? ViewGroup)?.let { bar -> (0 until bar.childCount).map(bar::getChildAt).filterIsInstance<TextView>().firstOrNull() } }

    /**
     * A SectionHeader is a LABEL-role text in etched ink with a 12dp bottom pad and 0 or 24dp top pad;
     * nothing else in the kit is built that way.
     */
    private fun sectionHeaderLooking(all: List<View>, root: View): List<TextView> {
        val ctx = root.context
        val reference = Type.text(ctx, "x", TypeRole.LABEL, 0)
        val etched = Theme.palette(ctx).etched
        return all.filterIsInstance<TextView>().filter {
            it.isShown && it.currentTextColor == etched &&
                it.textSize == reference.textSize && it.typeface == reference.typeface &&
                it.paddingBottom == Theme.dp(ctx, Dimens.SPACE_S) &&
                (it.paddingTop == 0 || it.paddingTop == Theme.dp(ctx, Dimens.SPACE_XL))
        }
    }
}
