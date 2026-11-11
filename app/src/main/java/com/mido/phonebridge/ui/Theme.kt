package com.mido.phonebridge.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.BottomInset
import com.mido.phonebridge.R
import com.mido.phonebridge.ui.tokens.Bevel
import com.mido.phonebridge.ui.tokens.Colors
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.FontFace
import com.mido.phonebridge.ui.tokens.Fonts
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Palettes

/**
 * The app's single source of design truth.
 *
 * Before this file, every screen carried its own button helper (`coloredButton`,
 * `colored`, `smallButton`, `chip`, `small`) and its own raw hex literals — the same
 * colour copy-pasted up to nine times, `pill()` sizing its corners in PIXELS rather
 * than dp, and no press feedback anywhere because a `GradientDrawable` background
 * discards the ripple and the state list. That is what "looks like a hacky tool"
 * means in code: not one ugly value, but no single place that owns the values.
 *
 * Rules this file exists to enforce:
 *  - **Semantic, not decorative.** A colour says what a thing MEANS (`success`,
 *    `danger`, `accent`), never what it looks like. A screen that wants green asks
 *    for `success`, so restyling is one edit here, not a grep across seven files.
 *  - **dp, always.** Radii, padding and stroke widths are density-independent. The
 *    old `cornerRadius = radiusDp * 3` only looked right at ~3x density.
 *  - **Touch targets are ≥48dp.** A control you can miss is a control that denies a
 *    request by accident.
 *  - **Every button ripples.** Built on `MaterialButton` with a `backgroundTintList`,
 *    so the state list and the ripple survive — the thing a custom background kills.
 */
object Theme {

    // --- palette: one source (res/values*/colors.xml) read through Palettes ---
    //
    // The legacy role names below are getters over the current palette, so a call site keeps
    // compiling while its owner WP migrates it to `Theme.palette(ctx)`. They resolve from the
    // application context, so they work in services and overlay windows too.

    @Volatile private var app: android.app.Application? = null

    /** Called once by [com.mido.phonebridge.StmpApp]. */
    fun install(application: android.app.Application) { app = application }

    /** The palette for the app's current configuration. Throws if [install] has not run. */
    fun current(): Palette = Palettes.of(
        checkNotNull(app) { "Theme.install was not called; StmpApp must be the application class" }
    )

    /** The palette for [ctx]. New code reads this once per build. */
    fun palette(ctx: Context): Palette = Palettes.of(ctx)

    /** Screen background. */
    val BG: Int get() = current().body

    /** Modules, neutral keys, dialogs. */
    val SURFACE: Int get() = current().panel

    /** Chips, secondary buttons, inset blocks. */
    val SURFACE_VARIANT: Int get() = current().sunken

    /** Hairline borders and separators. */
    val BORDER: Int get() = current().hairline

    /**
     * Body text.
     *
     * **Every `EditText` must set this explicitly** (`setTextColor(ON_SURFACE)` and
     * `setHintTextColor(ON_SURFACE_VARIANT)`), so a typed value is never unreadable.
     */
    val ON_SURFACE: Int get() = current().ink

    /** Secondary text, captions. */
    val ON_SURFACE_VARIANT: Int get() = current().etched

    /** The primary action and brand colour: ink. Never equal to [DANGER]. */
    val ACCENT: Int get() = current().ink

    val SUCCESS: Int get() = current().okText
    val SUCCESS_SURFACE: Int get() = current().okSurface
    val WARNING: Int get() = current().cautionText
    val WARNING_SURFACE: Int get() = current().cautionSurface
    val DANGER: Int get() = current().cautionText
    val DANGER_SURFACE: Int get() = current().cautionSurface

    /**
     * How a RISK bucket looks, in one place.
     *
     * Three surfaces now say the same thing: the NOTIFY card's strip, the modal's risk line,
     * and — since Mido asked for it (2026-10-19: *"make the approve button color match the
     * risk label color"*) — the APPROVE button itself. A second copy of this mapping is how
     * the card and the modal end up disagreeing about what HIGH looks like, which is worse
     * than either being wrong on its own.
     *
     * Deliberately the app's existing semantic colours rather than a new palette: SUCCESS /
     * WARNING / DANGER already mean "fine / careful / stop" everywhere else on these screens,
     * and a risk badge that introduced a fourth green would just be decoration.
     */
    fun riskColor(risk: com.mido.phonebridge.ai.Risk): Int = when (risk) {
        com.mido.phonebridge.ai.Risk.LOW -> current().okText
        com.mido.phonebridge.ai.Risk.MEDIUM -> current().cautionText
        com.mido.phonebridge.ai.Risk.HIGH -> current().cautionText
    }

    /**
     * The risk as a BACKGROUND WASH, for surfaces that should carry it as a tint rather
     * than a mark (Mido, 2026-11-03: *"the level result shouldn't be a left border it should
     * affect the bg color with a light tint effect"*).
     *
     * The `_SURFACE` colours already exist for exactly this purpose — they are the muted
     * ink re-tints, chosen so a filled area still reads as paper with ink on it rather than
     * as a coloured panel. Reusing them keeps the tint consistent with the rest of the app
     * instead of introducing a second, paler palette that only this screen knows about.
     *
     * `null` (no assessment yet) returns [BG], the untouched sheet — an absent risk is not
     * "safe", so it must not wear the green.
     */
    fun riskSurface(risk: com.mido.phonebridge.ai.Risk?): Int = when (risk) {
        com.mido.phonebridge.ai.Risk.LOW -> current().okSurface
        com.mido.phonebridge.ai.Risk.MEDIUM -> current().cautionSurface
        com.mido.phonebridge.ai.Risk.HIGH -> current().cautionSurface
        null -> current().body
    }

    /** Text/icons on a filled coloured control. On paper the only filled controls are the
     *  stamp red and the ink-tinted semantic buttons, so this stays cream-white for legibility. */
    val ON_COLOR: Int get() = current().onInk

    // --- metrics ---

    const val RADIUS_CARD = Dimens.RADIUS_MODULE
    const val RADIUS_CONTROL = Dimens.RADIUS_KEY
    const val RADIUS_CHIP = Dimens.RADIUS_KNOB_THUMB

    /**
     * How far a drawn line wanders from true, in dp.
     *
     * 1.5dp reads as ink dragged by hand. Below ~1dp it is invisible at phone density; above ~3dp
     * a 40dp button starts looking like a doodle instead of a control.
     */
    const val SQUIGGLE_DP = 1.5f

    /**
     * The bottom bar's elevation, in dp.
     *
     * Real elevation rather than a painted gradient: the platform draws a blurred, genuinely
     * transparent shadow beneath the view. A gradient inside the view's own bounds can only ever
     * look like a grey rectangle sitting above the paper, which is exactly what it looked like.
     */
    const val BAR_ELEVATION_DP = 0f

    /** The pen width of a drawn outline, in dp. Hairline but not invisible. */
    const val SQUIGGLE_STROKE_DP = 1.2f

    /**
     * The edge weight on every torn surface, in dp.
     *
     * Derived from what a 6dp tear used to imply (`6 / 4`), now stated once so a caller that
     * chooses a smaller tear — the toggle, at 2dp — keeps the SAME visible line weight instead of
     * getting a fainter one by arithmetic accident.
     */
    const val CARD_STROKE_DP = 1.5f

    /** Minimum touch target. Below this a tap is a coin flip. */
    const val MIN_TARGET_DP = Dimens.MIN_TARGET

    /**
     * The type scale, as sp values.
     *
     * **Why these exist rather than literals.** Sizes had drifted across nine files to 9, 10, 11,
     * 12, 13, 14, 15, 16, 17 and 20sp with no rule, so the same kind of caption was 9sp on one
     * screen and 13sp on another — and the smallest ones were genuinely hard to read. Mido,
     * 2026-11-04: *"review all font sizes, in general I feel some can be hard to read so increase
     * the smallest font size then apply common typography rules"*.
     *
     * The rule, in one place:
     *
     *  - **12sp is the floor.** Nothing renders smaller, including the modal's badge and the AI
     *    status line, which were 9 and 10sp. A size nobody can read is not information.
     *  - **13sp** — captions and status detail under a row.
     *  - **14sp** — body text and row values.
     *  - **16sp** — row labels, control labels, tab labels.
     *  - **20sp** — screen titles only.
     *
     * Two steps between adjacent roles, which is the smallest gap that still reads as deliberate
     * hierarchy rather than an accident.
     */
    const val TEXT_FLOOR_SP = 12f
    const val TEXT_CAPTION_SP = 12f
    const val TEXT_DETAIL_SP = 13f
    const val TEXT_BODY_SP = 14f
    const val TEXT_LABEL_SP = 16f
    const val TEXT_TITLE_SP = 20f

    /** Ripple colour for controls on paper. A translucent ink tint, not the old white ripple. */
    private val RIPPLE: Int get() = current().ripple

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    /** The default text face (Geist Regular). Kept as a forwarder until the legacy callers migrate. */
    fun handFont(ctx: Context): Typeface = Fonts.get(ctx, FontFace.SANS_400)

    private fun radiusPx(ctx: Context, radiusDp: Int): Float = dp(ctx, radiusDp).toFloat()

    /**
     * Keep a screen clear of the system bars.
     *
     * **Every screen needs this, not just the modal.** The app is edge-to-edge (targetSdk 35),
     * so by default content draws UNDER the status bar and the navigation bar. Only
     * `ApprovalActivity` had handling; the other six screens did not, which is why content sat
     * under the clock and the buttons sat under the gesture bar (Mido, 2026-10-19:
     * *"no need for app ui to render under the notification bar"*).
     *
     * Applied as WINDOW INSETS, never a fixed dp: the bar height varies per device, per OEM and
     * per orientation, so a hardcoded number is wrong exactly where it matters.
     *
     * Pads TOP and BOTTOM only. Horizontal insets are handled by each screen's own padding, and
     * adding them here would double-pad. Bottom is included because a pinned control under the
     * gesture bar is unreachable.
     *
     * Returns [root] so a call site can write `setContentView(Theme.inset(root))`.
     */
    fun <T : View> inset(root: T): T {
        // Capture the existing padding and ADD to it rather than replacing: several screens
        // already pad their root for content breathing room, and clobbering that would make
        // every one of them flush to the edges.
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, baseTop + bars.top, v.paddingRight, baseBottom + bars.bottom)
            insets
        }
        return root
    }

    // --- bottom navigation ---

    /** The three top-level screens. Order is the order they appear in the bar. */
    enum class Tab(val label: String) {
        HOME("Home"),
        PLUGINS("Plugins"),
        SETTINGS("Settings"),
    }

    /**
     * The bottom bar.
     *
     * **Hand-built, not `BottomNavigationView`.** That component wants a menu resource and vector
     * icons, and this app has neither — every control in it is constructed in code and the visual
     * language is paper and ink, not Material. Fighting the component to fit would cost more than
     * these forty lines and would still look borrowed.
     *
     * **A `LinearLayout` of three equal weights**, so the bar cannot be a thing a screen forgets
     * to size: each label takes a third regardless of text length, and the tap target is the full
     * third.
     *
     * The selected tab is passed in, not stored. Each screen knows which tab it IS, which is a
     * fact that cannot go stale the way a shared "current tab" could.
     */
    /**
     * The bottom bar.
     *
     * **Built ONCE and repainted in place.** Mido, 2026-11-04: *"add animation or fade, switching
     * is too sharp"* — and the first attempt at that could not work, because the host called
     * `removeAllViews()` and built a brand-new bar on every tab change. An animation needs a
     * previous state to move FROM; a rebuilt bar has none, and the fade ran `1f -> 1f`.
     *
     * So this returns a [BottomBar] that owns its three tabs and exposes [BottomBar.select]. The
     * shell builds it once per Activity and calls `select` on a tab change.
     */
    fun bottomNav(ctx: Context, current: Tab, onSelect: (Tab) -> Unit): BottomBar {
        val bar = BottomBar(ctx, onSelect)
        for (tab in Tab.entries) {
            // `addTabView`, not `addView`: the bar has to know the parts to repaint them, and a
            // plain addView would leave its registry empty so select() would silently do nothing.
            bar.addTabView(
                tabView(ctx, tab, tab == current, onSelect),
                // EQUAL WEIGHTS across the full width. Without this the bar measures as
                // wrap-content, so the three tabs huddle at the left edge.
                LinearLayout.LayoutParams(0, dp(ctx, 52), 1f),
            )
        }

        // The bottom inset is applied to the BAR, not to its host: padding the host leaves the
        // paper stopping above the gesture bar, which reads as the bar floating with a margin.
        BottomInset.apply(bar)

        // Real elevation, with a RECTANGULAR outline. `elevation` is the only way to get a
        // genuinely transparent blurred shadow, and it needs an outline; a torn edge is CONCAVE
        // and `Outline.setConvexPath` throws on those, so the shadow follows a rectangle. A 2dp
        // tear under an 8dp blur still reads as torn.
        bar.elevation = dpF(ctx, BAR_ELEVATION_DP)
        bar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRect(0, 0, view.width, view.height)
            }
        }
        return bar
    }

    /**
     * The bar itself: three tabs whose selection can change without being rebuilt.
     *
     * Keeping the tab views alive is what makes the fade possible — the outgoing tab still exists
     * when the incoming one is selected, so the marker can visibly retreat rather than blink out.
     */
    class BottomBar(
        ctx: Context,
        private val onSelect: (Tab) -> Unit,
    ) : LinearLayout(ctx) {

        /** One entry per [Tab], in the order they were added. */
        private val tabs = mutableListOf<TabView>()

        init {
            orientation = LinearLayout.HORIZONTAL
            background = tornBar(ctx, BG)
            // No band above the paper: the shadow is real elevation, drawn OUTSIDE these bounds.
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 6))
        }

        /** Register a tab's parts so [select] can repaint them later. */
        internal fun addTabView(parts: TabView, params: LayoutParams) {
            tabs += parts
            addView(parts.column, params)
        }

        /**
         * Move the selection to [tab].
         *
         * `animate` is false on the first paint — a bar that fades in as the app opens is
         * animating something nobody did.
         */
        fun select(tab: Tab, animate: Boolean = true) {
            for (parts in tabs) {
                parts.setSelected(parts.tab == tab, animate)
            }
        }
    }

    /**
     * One tab's parts, kept alive so its selection can change without rebuilding it.
     *
     * A plain data holder rather than a custom ViewGroup: the column is the view Android lays out,
     * and wrapping it in another layer would add a level of nesting for no benefit.
     */
    internal class TabView(
        val tab: Tab,
        val column: LinearLayout,
        private val label: TextView,
        private val rule: View,
        private val accent: Int,
        private val variant: Int,
        private val bold: android.graphics.Typeface,
        private val normal: android.graphics.Typeface,
    ) {
        /**
         * Repaint for the selected state.
         *
         * **The rule's alpha is the animation.** Its colour is always [accent]; only opacity
         * moves, so nothing has to be re-created mid-transition. The OUTGOING tab fades its rule
         * away while the INCOMING one fades in, which is what makes the marker look like it
         * travelled instead of teleporting.
         */
        fun setSelected(selected: Boolean, animate: Boolean) {
            column.contentDescription =
                if (selected) "${tab.label}, selected" else tab.label
            label.setTextColor(if (selected) accent else variant)
            label.setTypeface(if (selected) bold else normal)

            val target = if (selected) 1f else 0f
            val labelTarget = if (selected) 1f else 0.72f
            if (animate) {
                rule.animate().alpha(target).setDuration(180L).start()
                label.animate().alpha(labelTarget).setDuration(180L).start()
            } else {
                rule.animate().cancel()
                label.animate().cancel()
                rule.alpha = target
                label.alpha = labelTarget
            }
            // Only the unselected tab is tappable, so tapping the current one does nothing.
            column.setOnClickListener(
                if (selected) null else android.view.View.OnClickListener { onSelectClicked() }
            )
        }

        /** Set by [tabView]; kept here so [setSelected] can rewire the listener each time. */
        internal var onSelectClicked: () -> Unit = {}
    }

    /**
     * One tab: a column holding the label and its underline, plus the parts [BottomBar] needs to
     * repaint it in place.
     *
     * The rule is a SIBLING of the label, never its background — a background cannot be narrower
     * than its view, and the label's view is a third of the screen wide, which is what made the
     * underline span a third of the bar with the word sitting under it.
     */
    private fun tabView(
        ctx: Context,
        tab: Tab,
        initiallySelected: Boolean,
        onSelect: (Tab) -> Unit,
    ): TabView {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }

        val label = TextView(ctx).apply {
            // ALL CAPS by request (Mido, 2026-11-04). The hand font is informal; caps give the
            // three words the weight of labels rather than of prose, which is what a tab bar is.
            text = tab.label.uppercase(java.util.Locale.US)
            textSize = 16f
            gravity = Gravity.CENTER
            // One line, always. "Settings" wrapped to "Setting / s" as a third of a narrow bar.
            isSingleLine = true
            maxLines = 1
            ellipsize = null
            setAutoSizeTextTypeUniformWithConfiguration(
                12, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
        column.addView(label)

        val rule = View(ctx)
        val ruleHeight = dp(ctx, 2)
        // Measured with the same face and at the SMALLEST size the label may take, so the rule
        // cannot come out wider than the word it underlines.
        val measure = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.get(ctx, FontFace.SANS_400)
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, TEXT_FLOOR_SP, ctx.resources.displayMetrics
            )
        }
        val ruleWidth = measure.measureText(tab.label.uppercase(java.util.Locale.US)).toInt() +
            dp(ctx, 12)
        column.addView(
            rule,
            LinearLayout.LayoutParams(ruleWidth.coerceAtLeast(dp(ctx, 16)), ruleHeight)
                .apply { topMargin = dp(ctx, 2) },
        )
        rule.background = GradientDrawable().apply {
            setColor(ACCENT)
            cornerRadius = dpF(ctx, 1f)
        }

        val parts = TabView(
            tab = tab,
            column = column,
            label = label,
            rule = rule,
            accent = ACCENT,
            variant = ON_SURFACE_VARIANT,
            bold = Fonts.get(ctx, FontFace.SANS_600),
            normal = Fonts.get(ctx, FontFace.SANS_400),
        )
        parts.onSelectClicked = { onSelect(tab) }
        // First paint: no animation, because nothing moved.
        parts.setSelected(initiallySelected, animate = false)
        return parts
    }

    // --- surfaces ---

    /**
     * THE ONE WAY TO PAINT A BACKGROUND. Use this for every screen root, card, block and
     * panel — `Theme.sheet(Theme.BG)` for a full sheet, `Theme.sheet(Theme.SURFACE, r)` for a
     * rounded card.
     *
     * **Do not call `setBackgroundColor` on a view.** That is a flat fill with no grain, and a
     * flat fill next to a textured one is exactly how the homepage ended up looking unchanged
     * while the code claimed paper. `GrainCoverageTest` scans the source and fails the build on
     * any raw background assignment outside this file, so this is enforced, not remembered.
     */
    fun sheet(color: Int, radiusPx: Float = 0f, squigglePx: Float = 0f): android.graphics.drawable.Drawable =
        GradientDrawable().apply {
            setColor(color)
            if (radiusPx > 0f) cornerRadius = radiusPx
        }

    /** Convenience: [sheet] sized in dp. */
    fun sheetDp(ctx: Context, color: Int, radiusDp: Int = 0): android.graphics.drawable.Drawable =
        if (radiusDp > 0) sheet(color, radiusPx(ctx, radiusDp)) else sheet(color)

    /** dp as a Float. */
    fun dpF(ctx: Context, v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
    )

    /** A bevelled module surface (card / block / badge). */
    fun surface(ctx: Context, color: Int, radiusDp: Int = RADIUS_CARD): android.graphics.drawable.Drawable =
        Bevel.module(ctx, fill = color, r = radiusDp)

    /** A filled surface with a hairline border. */
    fun outlined(
        ctx: Context,
        color: Int = SURFACE,
        radiusDp: Int = RADIUS_CARD,
        strokeColor: Int = BORDER,
        strokeDp: Int = 1
    ): android.graphics.drawable.Drawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx(ctx, radiusDp)
        setStroke(dp(ctx, strokeDp), strokeColor)
    }

    /** A filled surface that ripples when tapped (for tappable rows). */
    fun rippled(ctx: Context, color: Int, radiusDp: Int = RADIUS_CARD): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            surface(ctx, color, radiusDp),
            GradientDrawable().apply {
                setColor(current().ink)
                cornerRadius = radiusPx(ctx, radiusDp)
            },
        )

    /**
     * A short hand-inked rule that sits under a section label.
     *
     * Drawn as a [Drawable] that paints ONLY a thin strip at its own bottom edge — the
     * whole point. The first attempt used a `GradientDrawable` as the TextView's
     * background, which fills the entire view and turned every section header into a solid
     * salmon bar (caught on device 2026-10-26). A background drawable has no concept of
     * "just the bottom", so the shape has to be painted, not stretched.
     *
     * Two strokes, one slightly shorter and fainter, give the line the uneven edge of ink
     * rather than a printed hairline.
     */

    /**
     * A card whose own OUTLINE is torn paper — the note ripped off a pad.
     *
     * The first attempt (0.43.6) layered a 24dp `torn_edge` vector over a card surface.
     * That cannot work: a tile stretched to card size, drawn in paper colour on paper, is
     * invisible — it changed nothing on screen. A torn edge is a property of the SHAPE, so
     * this drawable paints a rounded body whose bottom edge is a deterministic jagged line.
     *
     * The tear pattern is a fixed array, not `Random`: a random tear would re-roll on every
     * redraw and the card would visibly crawl each time it repainted. Same shape, every frame.
     */
    private class TornPaper(
        private val fill: Int,
        private val edge: Int,
        private val cornerPx: Float,
        private val tearPx: Float,
        /**
         * Line thickness, INDEPENDENT of [tearPx].
         *
         * **They used to be welded together** (`strokeWidth = tearPx / 4f`), which was invisible
         * while only cards existed — they all tear 6dp, so they all got the same 1.5dp line. The
         * toggle tears 2dp to keep its proportions on a 31dp control, and silently got a 0.5dp
         * line: three times lighter than every card around it. Mido, 2026-11-04: *"toggle border
         * still not the same as cards, toooo light currently. match the fucking cards exactly"*.
         *
         * Depth and weight are separate decisions, so they are separate parameters now.
         */
        private val strokePx: Float,
    ) : android.graphics.drawable.Drawable() {

        private val path = android.graphics.Path()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val builtFor = android.graphics.Rect()
        private var built = false
        private var alphaMul = 255

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return
            if (!built || builtFor != b) {
                build(b)
                builtFor.set(b)
                built = true
            }

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = scaled(fill)
            canvas.drawPath(path, paint)

            // Grain, clipped to the SAME torn path — so the texture follows the tear instead of
            // stopping at a rectangle. This is the drawable the homepage's SETUP cards actually
            // use, which is why adding grain only to `surface()` changed nothing visible there.
            canvas.save()
            canvas.clipPath(path)
            canvas.drawPaint(PaperGrain.paint())
            canvas.restore()

            // A hairline on the torn edge so the paper reads as having thickness.
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = strokePx
            paint.color = scaled(edge)
            canvas.drawPath(path, paint)
        }

        private fun build(b: android.graphics.Rect) {
            // The SHARED torn silhouette. This used to hold its own copy of the pattern and the
            // path construction; the toggle needed the same effect and grew a second, noisier one,
            // which is exactly how "one hand" becomes "three slightly different hands". One
            // implementation now serves cards, the bar and the toggle.
            TornEdge.path(
                out = path,
                left = b.left.toFloat(),
                top = b.top.toFloat(),
                right = b.right.toFloat(),
                bottom = b.bottom.toFloat(),
                tearPx = tearPx,
                cornerPx = cornerPx,
            )
        }

        private fun scaled(color: Int): Int {
            val a = (android.graphics.Color.alpha(color) * alphaMul / 255f).toInt()
            return (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
        }

        override fun setAlpha(alpha: Int) {
            alphaMul = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }

        @Deprecated("Deprecated in the framework; required by the Drawable contract")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * The bottom bar's surface: paper with a torn TOP edge.
     *
     * **No shadow is painted here, and no outline is stroked.**
     *
     * It used to reserve a band at the top of its own bounds and paint a gradient into it, because
     * a view is clipped to its bounds and a shadow above the top edge would not appear. On a light
     * page that band read as a grey rectangle sitting above the paper — a hard-edged strip, not a
     * shadow — and stroking the path drew a thin grey line around all four sides.
     *
     * The fix is to stop imitating a shadow: the bar's View carries real `elevation`, which the
     * platform renders as a genuine blurred, transparent shadow outside the view's own drawing.
     * That needs an outline, which [TornTopBar] now supplies from the torn path, so the shadow
     * follows the ragged edge instead of a rectangle.
     */
    fun tornBar(
        ctx: Context,
        fill: Int = BG,
        tearDp: Int = 5,
    ): android.graphics.drawable.Drawable {
        val line = GradientDrawable().apply { setColor(current().hairline) }
        return LayerDrawable(arrayOf(sheet(fill), line)).apply {
            setLayerGravity(1, Gravity.TOP)
            setLayerHeight(1, dp(ctx, 1))
        }
    }

    /**
     * Paper with a torn top edge, and an outline that lets the platform draw the shadow.
     *
     * See [tornBar] for why nothing is stroked or banded here.
     */
    class TornTopBar(
        private val fill: Int,
        private val tearPx: Float,
    ) : android.graphics.drawable.Drawable() {

        private val path = android.graphics.Path()
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val builtFor = android.graphics.Rect()
        private var built = false

        /**
         * The same pattern [TornEdge] uses, referenced rather than copied.
         *
         * It cannot call `TornEdge.path` directly because that tears the BOTTOM edge and this bar
         * is pinned to the window's bottom — its ragged edge has to face upward. The shape is the
         * same; only the side it is applied to differs.
         */
        private val tearPattern = TornEdge.PATTERN

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return
            if (!built || builtFor != b) {
                build(b)
                builtFor.set(b)
                built = true
            }

            fillPaint.style = android.graphics.Paint.Style.FILL
            fillPaint.color = fill
            canvas.drawPath(path, fillPaint)

            // Grain inside the paper only, clipped to the torn shape so the texture follows the
            // ragged edge instead of stopping at a rectangle.
            canvas.save()
            canvas.clipPath(path)
            canvas.drawPaint(PaperGrain.paint())
            canvas.restore()
        }

        private fun build(b: android.graphics.Rect) {
            path.reset()
            val left = b.left.toFloat()
            val right = b.right.toFloat()
            // The tear rides the very top of the view. No band is reserved above it: the shadow
            // comes from elevation now, so the paper starts where the bar starts.
            val top = b.top.toFloat()
            val bottom = b.bottom.toFloat()
            if (top >= bottom) return

            // Torn top edge, left -> right, each step dipped DOWN from the baseline.
            val segments = tearPattern.size
            val span = right - left
            path.moveTo(left, top + tearPattern[0] * tearPx)
            for (i in 1 until segments) {
                val x = left + span * (i.toFloat() / segments)
                path.lineTo(x, top + tearPattern[i] * tearPx)
            }
            path.lineTo(right, top)

            // Square sides and bottom: the bar is pinned to the window's bottom edge.
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
            path.close()
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = cf
        }

        @Deprecated("Deprecated in the framework; required by the Drawable contract")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Formerly a torn card; now a flat bevelled module. [tearDp] is ignored. */
    fun tornCard(
        ctx: Context,
        color: Int = SURFACE,
        radiusDp: Int = RADIUS_CARD,
        tearDp: Int = 6,
    ): android.graphics.drawable.Drawable = Bevel.module(ctx, fill = color, r = radiusDp)

    // --- text ---

    /** A small all-caps section header in the handwritten face, with a drawn rule under it.
     *  The rule is a painted [SquiggleRule], not a background fill: a background has no concept
     *  of "just the bottom edge", and the first attempt at that turned every header into a solid
     *  salmon bar. */
    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        // 16sp (Mido, 2026-11-03: *"increase its font size a little"*). These are the only
        // structural landmarks on a page of body text, so at 14 they competed with the 15sp rows
        // underneath instead of out-ranking them.
        textSize = 16f
        letterSpacing = 0.12f
        typeface = Fonts.get(ctx, FontFace.SANS_600)
        setTextColor(ACCENT)
        // Bottom padding is the room the RULE lives in. Without it the wave has nowhere to go
        // inside the view and gets clipped into a straight line.
        setPadding(0, dp(ctx, 22), 0, dp(ctx, 10))
        // Real margin under the header, so the first row is not crowded against the rule
        // (Mido, 2026-11-03: *"add a little bit of bottom margin to the headers"*). Padding
        // cannot do this: it lives INSIDE the measured view, so the rule would sit 10dp above the
        // row and read as an underline of the first card rather than a heading over it.
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 6) }
    }

    /** A plain line of text. [mono] keeps the monospace face for log/code strings; the
     *  default face is the handwritten (STMP) face so body copy reads as a note. */
    fun text(
        ctx: Context,
        text: String,
        size: Float = TEXT_BODY_SP,
        color: Int = ON_SURFACE,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Fonts.get(ctx, if (mono) FontFace.MONO_400 else FontFace.SANS_400)
        setLineSpacing(0f, 1.15f)
    }

    // --- controls ---

    /** How a button should read. Chosen by MEANING, never by colour name. */
    enum class ButtonStyle { PRIMARY, NEUTRAL, SUCCESS, DANGER, GHOST }

    private fun styleColors(style: ButtonStyle): Pair<Int, Int> = when (style) {
        ButtonStyle.PRIMARY -> ACCENT to ON_COLOR
        ButtonStyle.NEUTRAL -> SURFACE to ON_SURFACE
        ButtonStyle.SUCCESS -> ACCENT to ON_COLOR
        ButtonStyle.DANGER -> Color.TRANSPARENT to ON_SURFACE
        ButtonStyle.GHOST -> Color.TRANSPARENT to ON_SURFACE
    }

    /**
     * The one button. Every screen uses this — there is no second variant.
     *
     * `backgroundTintList` (not `background`) is what preserves the ripple and the
     * disabled/pressed states; setting a drawable directly is what removed them before.
     */
    fun button(
        ctx: Context,
        label: String,
        style: ButtonStyle = ButtonStyle.NEUTRAL,
        small: Boolean = false,
        onClick: () -> Unit
    ): MaterialButton {
        val (bg, fg) = styleColors(style)
        val h = if (small) dp(ctx, 40) else dp(ctx, MIN_TARGET_DP)
        return MaterialButton(ctx).apply {
            text = label
            textSize = if (small) 12f else 14f
            isAllCaps = false
            typeface = Fonts.get(ctx, FontFace.SANS_500)
            minHeight = h
            minimumHeight = h
            setTextColor(fg)
            backgroundTintList = ColorStateList.valueOf(bg)
            rippleColor = ColorStateList.valueOf(RIPPLE)
            cornerRadius = dp(ctx, RADIUS_CONTROL)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(ctx, if (small) 12 else 20), 0, dp(ctx, if (small) 12 else 20), 0)
            if (style == ButtonStyle.GHOST || style == ButtonStyle.DANGER) {
                strokeWidth = dp(ctx, 1)
                strokeColor = ColorStateList.valueOf(ON_SURFACE)
            }
            foreground = null
            setOnClickListener { onClick() }
        }
    }

    /** A compact filter chip. Same machinery as [button], tighter. */
    fun chip(ctx: Context, label: String, selected: Boolean = false, onClick: () -> Unit): MaterialButton =
        button(ctx, label, if (selected) ButtonStyle.PRIMARY else ButtonStyle.NEUTRAL, small = true, onClick = onClick)

    /**
     * A round bulb badge that opens an explanation, sized to be tapped rather than aimed at.
     *
     * **Why a bulb and not a `?`.** A question mark on a row reads as "something is wrong" or
     * "unknown value" — it is the shape of a diagnostic, not of an offer to explain. A bulb in a
     * disc says "there is more to read here" before it is tapped (Mido, 2026-11-10).
     *
     * **The target is the BOX, not the paint.** A 30dp disc is easy to see and small to hit, so it
     * is centred inside a 44dp touch frame: minimumWidth/minimumHeight do nothing on a fixed-size
     * child, so the frame is what makes the affordance hittable. The disc carries the app's paper
     * grain and drawn edge through [surface], like every other bounded surface in the app.
     *
     * Its own click listener is deliberate — a clickable child wins the touch, so tapping the badge
     * never navigates the row underneath it.
     */
    fun bulbBadge(ctx: Context, describe: String, onClick: () -> Unit): FrameLayout {
        val touch = dp(ctx, 44)
        val disc = dp(ctx, 30)
        val holder = FrameLayout(ctx)
        holder.contentDescription = describe
        holder.isClickable = true
        holder.isFocusable = true
        holder.setOnClickListener { onClick() }
        holder.layoutParams = LinearLayout.LayoutParams(touch, touch).apply {
            marginEnd = dp(ctx, 8)
        }
        holder.addView(View(ctx).apply {
            background = surface(ctx, WARNING_SURFACE, 15)
        }, FrameLayout.LayoutParams(disc, disc, Gravity.CENTER))
        holder.addView(TextView(ctx).apply {
            text = "\uD83D\uDCA1"
            textSize = 14f
            gravity = Gravity.CENTER
            // No hand font and no ink colour: the glyph is drawn by the system emoji font, so the
            // typeface and colour are ignored. Kept explicit so a later pass does not wonder.
            setTextColor(ON_SURFACE)
        }, FrameLayout.LayoutParams(disc, disc, Gravity.CENTER))
        return holder
    }

    /**
     * A radio marker: a small ring, filled with the accent when selected.
     *
     * Lives here rather than in the screen because `GrainCoverageTest` forbids a bare
     * `GradientDrawable` outside the ui package — and rightly so, that rule is what keeps
     * surfaces from shipping flat. A 12dp marker cannot carry grain, so it belongs where the
     * sanctioned constructors live.
     */
    fun radioDot(ctx: Context, selected: Boolean): View {
        val size = dp(ctx, 16)
        val ring = dp(ctx, 2)
        return View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) {
                    // A solid accent disc: the stroke matches the fill so the two read as
                    // ONE shape. The old version stroked SURFACE at 3dp inside a 14dp
                    // circle, which left an 8dp dot — so selecting a radio made its marker
                    // SMALLER than the unselected one, changing shape as well as colour.
                    setColor(ACCENT)
                    setStroke(ring, ACCENT)
                } else {
                    // Identical outer diameter and ring weight; only the fill differs. That
                    // is the whole constraint: the marker must not change size or shape when
                    // selection moves, or the eye reads two different controls.
                    //
                    // The fill is SURFACE_VARIANT — the exact colour of an UNSELECTED card,
                    // which is the only card an unfilled marker ever sits on — rather than
                    // TRANSPARENT. Stating the colour means the hole can never read as
                    // whatever happens to be behind it.
                    //
                    // Checked the two call sites: both put the dot inside a card that is
                    // SURFACE_VARIANT when unselected and SURFACE when selected, and this
                    // branch only runs when unselected. So one constant is correct at both.
                    setColor(SURFACE_VARIANT)
                    setStroke(ring, current().outline)
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(ctx, 10)
                // The row is TOP-aligned so the marker sits beside the TITLE rather than
                // floating in the middle of a two-line card. This small nudge puts its
                // centre on that first line instead of level with the cap height.
                topMargin = dp(ctx, 2)
            }
        }
    }

    /**
     * An on/off switch, drawn as paper with a sliding knob.
     *
     * **Why this returns a custom View and not a styled `SwitchCompat`.** Material's switch is a
     * capsule with a circular thumb and a ripple. All three are wrong here: this app is paper and
     * ink, and a switch has to look cut from the same sheet as the cards. See [RuggedToggle].
     *
     * **State, not action.** The knob's position IS the answer — left off, right on. There is no
     * label to recompute, so the control cannot go stale and claim the server is stopped while it
     * is running.
     *
     * The callback fires ONLY on a tap. Callers must render from stored truth and let this be
     * driven by it; calling [RuggedToggle.setCheckedAnimated] from the callback would show the
     * tapped intent before it was real.
     */
    fun toggle(
        ctx: Context,
        on: Boolean,
        pending: Boolean = false,
        onChange: (Boolean) -> Unit,
    ): RuggedToggle = RuggedToggle(ctx).apply {
        // No animation on the first paint: a switch that slides as the screen opens is animating
        // something the human did not do.
        setCheckedImmediately(on)
        this.pending = pending
        setOnClickListener {
            // **Read the knob's CURRENT state, never the `on` captured at build time.**
            //
            // This closure used to send `!on`, where `on` was fixed when the switch was built. A
            // screen that repaints its switch in place — which is most of them, on purpose, to
            // avoid tearing down a view mid-press — therefore kept sending the ORIGINAL value
            // forever: after turning the setting on, every later tap sent `true` again, so it
            // could turn on and never off. Reported 2026-11-04: *"fingerprint for modals doesn't
            // turn off even after fingerprint reading, only turned on"*.
            //
            // `checked` is the live field the render drives, so this always inverts the real
            // current state no matter how many times the screen has repainted since.
            onChange(!checked)
        }
    }

    /**
     * A button whose own background fills with progress — the "downloading" control.
     *
     * **Why a button and not a separate bar.** The action and its feedback are the same
     * thing here: you tap to start the fetch, and the thing you tapped is what fills. A
     * detached `ProgressBar` under a status row says "something is happening"; a filling
     * button says "the thing you pressed is doing it", which is the whole difference
     * between a control and a readout (Mido, 2026-10-19: *"the loader should be button
     * scoped, using a bg color progress fill style"*).
     *
     * Built from a [MaterialButton] with its OWN background drawable rather than
     * `backgroundTintList`, because the fill has to be a two-tone layer (track + fill)
     * that `setLevel` can drain. That means the ripple is gone while loading — which is
     * correct: a control mid-download is not tappable, so press feedback would be a lie.
     * On completion the caller swaps back to [button], and the ripple returns with it.
     *
     * [progress] is 0..100 and drives the fill width directly. It is set from the real
     * byte count, never interpolated — a progress bar that animates on a timer is a
     * decoration, and this one has to be honest about a 474 MB transfer.
     */
    fun progressButton(
        ctx: Context,
        label: String,
        progress: Int,
        style: ButtonStyle = ButtonStyle.SUCCESS
    ): MaterialButton {
        val (bg, fg) = styleColors(style)
        val h = dp(ctx, MIN_TARGET_DP)
        val pct = progress.coerceIn(0, 100)
        val radius = radiusPx(ctx, RADIUS_CONTROL)
        // Track = the button's own colour, dimmed; fill = the solid colour. So the
        // control reads as itself, partially completed, rather than as a separate bar.
        val track = GradientDrawable().apply {
            setColor(withAlpha(bg, 0x33))
            cornerRadius = radius
        }
        val fill = ClipDrawable(
            GradientDrawable().apply {
                setColor(bg)
                cornerRadius = radius
            },
            Gravity.START,
            ClipDrawable.HORIZONTAL
        ).apply { level = pct * 100 }
        // A LAYER, not a tint: the fill has to be a second layer that `setLevel` drains,
        // and `backgroundTintList` can only recolour one. Setting `background` directly
        // would normally cost the ripple — which is correct here, because a control
        // mid-download is not tappable (see below) and press feedback would be a lie.
        // On completion the caller swaps back to [button] and the ripple returns with it.
        // Grain ON TOP of the two-tone track/fill: the progress button is the one control that
        // sets its own background, so this is the only place the texture can go.
        val layered = LayerDrawable(arrayOf(track, fill))
        return MaterialButton(ctx).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            typeface = Fonts.get(ctx, FontFace.SANS_500)
            minHeight = h
            minimumHeight = h
            setTextColor(fg)
            background = layered
            cornerRadius = dp(ctx, RADIUS_CONTROL)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(ctx, 20), 0, dp(ctx, 20), 0)
            // Not tappable while filling: the download is already running, and a second
            // writer on one `.part` file is a corrupt model (see ModelProvisioner).
            isEnabled = false
        }
    }

    /** [color] with its alpha replaced. For tinting a token without a second literal. */
    private fun withAlpha(color: Int, alpha: Int): Int = Colors.withAlpha(color, alpha)

    /** A status row: coloured dot + label + value, optionally tappable. */
    fun statusRow(
        ctx: Context,
        label: String,
        value: String,
        color: Int,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            background = outlined(ctx, SURFACE, RADIUS_CONTROL)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply {
                marginEnd = dp(ctx, 12)
            }
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(ctx).apply {
            text = label
            textSize = 15f
            setTextColor(ON_SURFACE)
        })
        body.addView(TextView(ctx).apply {
            text = value
            textSize = 13f
            setTextColor(ON_SURFACE_VARIANT)
        })
        row.addView(dot)
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (onClick != null) {
            row.addView(TextView(ctx).apply {
                text = "›"
                textSize = 20f
                setTextColor(ON_SURFACE_VARIANT)
            })
        }
        // A gap between stacked rows (Mido, 2026-10-19: "add margin around list items they
        // are touching"). Set HERE rather than at every call site: the spacing is a property
        // of the component, and a row that has to be spaced by its caller is a row that will
        // eventually be added somewhere without spacing. `addView` keeps these params when
        // they are already a `LinearLayout.LayoutParams`, which they are, so a vertical
        // parent honours this without the caller passing anything.
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, ROW_GAP_DP) }
        return row
    }

    /**
     * The gap between stacked rows. One constant, so every list in the app breathes by the
     * same amount — the alternative is a different number per screen, which is how a UI
     * drifts into looking assembled rather than designed.
     */
    const val ROW_GAP_DP = Dimens.SPACE_S

    /**
     * The rocker switch (COMPONENTS 4). Same callback contract as [toggle]: `onChange(!checked)`
     * reads the live state, so a switch repainted in place never sends a stale value.
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
