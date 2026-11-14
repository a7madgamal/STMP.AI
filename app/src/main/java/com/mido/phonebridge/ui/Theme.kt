package com.mido.phonebridge.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Palettes

/**
 * What is left of the old theme object once the paper look is gone: the palette accessors, the
 * dp helpers, the window-inset helper, the shared metrics and the risk colours.
 *
 * Colours come from [Palette] (the colors.xml files read through [Palettes]); components come
 * from `ui/kit`. Nothing here draws.
 */
object Theme {

    @Volatile private var app: android.app.Application? = null

    /** Called once by [com.mido.phonebridge.StmpApp]. */
    fun install(application: android.app.Application) { app = application }

    /** The palette for the app's current configuration. Throws if [install] has not run. */
    fun current(): Palette = Palettes.of(
        checkNotNull(app) { "Theme.install was not called; StmpApp must be the application class" }
    )

    /** The palette for [ctx]. New code reads this once per build. */
    fun palette(ctx: Context): Palette = Palettes.of(ctx)

    /**
     * How a RISK bucket looks, in one place: the NOTIFY card, the modal's risk line and the
     * APPROVE key all read it, so they cannot disagree about what HIGH looks like. LOW is the ok
     * ink, MEDIUM and HIGH share the caution ink; the word tells those two apart. Never signal
     * (D6, D36).
     */
    fun riskColor(p: Palette, risk: com.mido.phonebridge.ai.Risk): Int = when (risk) {
        com.mido.phonebridge.ai.Risk.LOW -> p.okText
        com.mido.phonebridge.ai.Risk.MEDIUM -> p.cautionText
        com.mido.phonebridge.ai.Risk.HIGH -> p.cautionText
    }

    /**
     * The risk as a BACKGROUND WASH. `null` (no assessment yet) returns the untouched body
     * colour: an absent risk is not "safe", so it must not wear the green.
     */
    fun riskSurface(p: Palette, risk: com.mido.phonebridge.ai.Risk?): Int = when (risk) {
        com.mido.phonebridge.ai.Risk.LOW -> p.okSurface
        com.mido.phonebridge.ai.Risk.MEDIUM -> p.cautionSurface
        com.mido.phonebridge.ai.Risk.HIGH -> p.cautionSurface
        null -> p.body
    }

    // --- metrics ---

    const val RADIUS_CARD = Dimens.RADIUS_MODULE
    const val RADIUS_CONTROL = Dimens.RADIUS_KEY
    const val RADIUS_CHIP = Dimens.RADIUS_KNOB_THUMB

    /** Minimum touch target. Below this a tap is a coin flip. */
    const val MIN_TARGET_DP = Dimens.MIN_TARGET

    /** The smallest text size anywhere: nothing renders below 12sp. */
    const val TEXT_FLOOR_SP = 12f

    /** The gap between stacked rows. One constant, so every list breathes by the same amount. */
    const val ROW_GAP_DP = Dimens.SPACE_S

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    /** dp as a Float. */
    fun dpF(ctx: Context, v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
    )

    /**
     * Keep a screen clear of the system bars. The app is edge-to-edge (targetSdk 35), so content
     * draws under the status and navigation bars unless the root is padded by the window insets.
     * Pads TOP and BOTTOM only, adding to the root's existing padding. Returns [root] so a call
     * site can write `setContentView(Theme.inset(root))`.
     */
    fun <T : View> inset(root: T): T {
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, baseTop + bars.top, v.paddingRight, baseBottom + bars.bottom)
            insets
        }
        return root
    }

    /** The three top-level screens. Order is the order they appear in the bar. */
    enum class Tab(val label: String) {
        HOME("Home"),
        PLUGINS("Plugins"),
        SETTINGS("Settings"),
    }

    /**
     * The rocker switch (COMPONENTS 4). `onChange(!checked)` reads the live state, so a switch
     * repainted in place never sends a stale value.
     */
    fun rocker(
        ctx: Context,
        on: Boolean,
        label: String,
        pending: Boolean = false,
        onChange: (Boolean) -> Unit,
    ): com.mido.phonebridge.ui.kit.RockerSwitch = com.mido.phonebridge.ui.kit.RockerSwitch(ctx).apply {
        this.label = label
        setCheckedImmediately(on)
        this.pending = pending
        setOnClickListener { onChange(!checked) }
    }

    /** Vertical space. */
    fun spacer(ctx: Context, dp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, dp))
    }
}
