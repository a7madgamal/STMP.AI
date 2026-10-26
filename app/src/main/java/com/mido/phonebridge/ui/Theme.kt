package com.mido.phonebridge.ui

import android.content.Context
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.R

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

    // --- palette (warm paper + ink: one stamp accent, three semantic states) ---
    //
    // STMP rebrand (2026-10-26). The old palette was a cold, near-black dark UI with a
    // single blue accent. The new identity is "ink on paper": a warm cream paper base,
    // near-black brown ink for text, and one deep stamp-red accent that is the ONLY
    // saturated hit of colour on the screen. Success/warning/danger keep their meaning
    // but are re-tinted as ink analogues (green ink, amber ink, red ink) so the risk
    // semantics the approval flow depends on survive unchanged.

    /** Paper background: warm cream, not white, not grey. */
    const val BG = 0xFFF5EEDF.toInt()

    /** Raised paper: slightly darker kraft, like a card sitting on the desk. */
    const val SURFACE = 0xFFEDE3CB.toInt()

    /** Secondary paper: chips, secondary buttons, inset blocks. */
    const val SURFACE_VARIANT = 0xFFE3D6B8.toInt()

    /** Ink line: hairline borders, drawn separators. */
    const val BORDER = 0xFFC9B98F.toInt()

    /** Primary ink: near-black brown, never pure black. */
    const val ON_SURFACE = 0xFF2B2318.toInt()

    /** Faded ink: secondary text, captions. */
    const val ON_SURFACE_VARIANT = 0xFF7A6B52.toInt()

    /** The one stamp accent: deep rubber-stamp red. */
    const val ACCENT = 0xFFB23A2F.toInt()

    // Ink re-tints for the existing semantic states. Deliberately muted so they read as
    // ink, not as neon over paper.
    const val SUCCESS = 0xFF2F7D4F.toInt()
    const val SUCCESS_SURFACE = 0xFFDCEBD7.toInt()
    const val WARNING = 0xFFB07A1F.toInt()
    const val WARNING_SURFACE = 0xFFF0E4C8.toInt()
    const val DANGER = 0xFFB23A2F.toInt()
    const val DANGER_SURFACE = 0xFFF1D9D0.toInt()

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
        com.mido.phonebridge.ai.Risk.LOW -> SUCCESS
        com.mido.phonebridge.ai.Risk.MEDIUM -> WARNING
        com.mido.phonebridge.ai.Risk.HIGH -> DANGER
    }

    /** Text/icons on a filled coloured control. On paper the only filled controls are the
     *  stamp red and the ink-tinted semantic buttons, so this stays cream-white for legibility. */
    const val ON_COLOR = 0xFFFBF6EA.toInt()

    // --- metrics ---

    const val RADIUS_CARD = 12
    const val RADIUS_CONTROL = 10
    const val RADIUS_CHIP = 8

    /** Minimum touch target. Below this a tap is a coin flip. */
    const val MIN_TARGET_DP = 48

    /** Ripple colour for controls on paper. A translucent ink tint, not the old white ripple. */
    private const val RIPPLE = 0x332B2318

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    /**
     * The STMP handwritten face, loaded once. Cached because `ResourcesCompat.getFont`
     * re-inflates and multi-font documents would otherwise be re-read on every TextView;
     * a cache keeps the cost to a single font load per process.
     *
     * Never null in practice (the font ships with the APK), but a missing resource must
     * fail loudly rather than silently fall back to a system face that would quietly undo
     * the whole handwritten look. See [handFont].
     */
    @Volatile private var handleeCache: Typeface? = null

    /** The display face for headings and labels. [Handlee] under the SIL OFL 1.1. */
    fun handFont(ctx: Context): Typeface = handleeCache ?: synchronized(this) {
        handleeCache ?: run {
            val f = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.handlee)
                ?: error("STMP font missing: R.font.handlee not found in APK")
            handleeCache = f
            f
        }
    }

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

    // --- surfaces ---

    /** A filled, rounded surface (card / block / badge). */
    fun surface(ctx: Context, color: Int, radiusDp: Int = RADIUS_CARD): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx(ctx, radiusDp)
        }

    /** A filled surface with a hairline border — the standard card look. */
    fun outlined(
        ctx: Context,
        color: Int = SURFACE,
        radiusDp: Int = RADIUS_CARD,
        strokeColor: Int = BORDER,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx(ctx, radiusDp)
        setStroke(dp(ctx, strokeDp), strokeColor)
    }

    /** A filled surface that ripples when tapped (for tappable rows). */
    fun rippled(ctx: Context, color: Int, radiusDp: Int = RADIUS_CARD): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            surface(ctx, color, radiusDp),
            null
        )

    // --- text ---

    /** A small all-caps section header, in the handwritten face to read as a label
     *  scribbled above a group of controls. */
    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.12f
        typeface = handFont(ctx)
        setTextColor(ACCENT)
        setPadding(0, dp(ctx, 20), 0, dp(ctx, 6))
    }

    /** A plain line of text. [mono] keeps the monospace face for log/code strings; the
     *  default face is the handwritten (STMP) face so body copy reads as a note. */
    fun text(
        ctx: Context,
        text: String,
        size: Float = 15f,
        color: Int = ON_SURFACE,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (mono) android.graphics.Typeface.MONOSPACE else handFont(ctx)
        setLineSpacing(0f, 1.15f)
    }

    // --- controls ---

    /** How a button should read. Chosen by MEANING, never by colour name. */
    enum class ButtonStyle { PRIMARY, NEUTRAL, SUCCESS, DANGER, GHOST }

    private fun styleColors(style: ButtonStyle): Pair<Int, Int> = when (style) {
        ButtonStyle.PRIMARY -> ACCENT to ON_COLOR
        ButtonStyle.NEUTRAL -> SURFACE_VARIANT to ON_SURFACE
        ButtonStyle.SUCCESS -> SUCCESS to ON_COLOR
        ButtonStyle.DANGER -> DANGER to ON_COLOR
        ButtonStyle.GHOST -> Color.TRANSPARENT to ACCENT
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
            textSize = if (small) 13f else 15f
            isAllCaps = false
            typeface = handFont(ctx)
            minHeight = h
            minimumHeight = h
            setTextColor(fg)
            backgroundTintList = ColorStateList.valueOf(bg)
            rippleColor = ColorStateList.valueOf(RIPPLE)
            cornerRadius = dp(ctx, RADIUS_CONTROL)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(ctx, if (small) 12 else 20), 0, dp(ctx, if (small) 12 else 20), 0)
            if (style == ButtonStyle.GHOST) {
                strokeWidth = dp(ctx, 1)
                strokeColor = ColorStateList.valueOf(ACCENT)
            }
            setOnClickListener { onClick() }
        }
    }

    /** A compact filter chip. Same machinery as [button], tighter. */
    fun chip(ctx: Context, label: String, selected: Boolean = false, onClick: () -> Unit): MaterialButton =
        button(ctx, label, if (selected) ButtonStyle.PRIMARY else ButtonStyle.NEUTRAL, small = true, onClick = onClick)

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
        val layered = LayerDrawable(arrayOf(track, fill))
        return MaterialButton(ctx).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            typeface = handFont(ctx)
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
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

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
    const val ROW_GAP_DP = 8

    /** Vertical space. */
    fun spacer(ctx: Context, dp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, dp))
    }
}
