package com.mido.phonebridge.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Bevel
import com.mido.phonebridge.ui.tokens.Colors
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** The physical key (COMPONENTS 8). Replaces Theme.button and Theme.progressButton. */
object KeyButton {
    enum class Kind { INK, PANEL, SIGNAL, OUTLINE }
    enum class Size { REGULAR, LARGE, SMALL }

    /** A key described as data, for rows that build their own keys (StatusRow, ActionRow). */
    data class Spec(val label: String, val describe: String, val onClick: () -> Unit)

    /** The MaterialButton behind every key; keeps the armed state the gate needs. */
    internal class KeyView(ctx: Context) : MaterialButton(ctx) {
        var armed = true
        var secondsUntilArmed = 0
        var baseDescription: String? = null
        var onClickAction: (() -> Unit)? = null
        var percent = -1
        var dropped = false
    }

    fun create(
        ctx: Context,
        label: String,
        kind: Kind = Kind.PANEL,
        size: Size = Size.REGULAR,
        describe: String? = null,
        onClick: () -> Unit,
    ): MaterialButton {
        val button = build(ctx, label, kind, size)
        button.baseDescription = describe
        if (describe != null) button.contentDescription = describe
        button.onClickAction = onClick
        button.setOnClickListener { if (button.armed) button.onClickAction?.invoke() }
        return button
    }

    /** A download key: the fill is clipped to [percent]; not clickable while in progress. */
    fun progress(ctx: Context, label: String, percent: Int): MaterialButton {
        val p = Theme.palette(ctx)
        val pct = percent.coerceIn(0, 100)
        val button = build(ctx, label, Kind.INK, Size.REGULAR)
        val r = ctx.dpf(Dimens.RADIUS_KEY.toFloat())
        val track = GradientDrawable().apply { setColor(Colors.withAlpha(p.ink, 0x33)); cornerRadius = r }
        val fill = ClipDrawable(
            GradientDrawable().apply { setColor(p.ink); cornerRadius = r },
            Gravity.START, ClipDrawable.HORIZONTAL,
        ).apply { level = pct * 100 }
        button.background = LayerDrawable(arrayOf(track, fill))
        button.setTextColor(if (pct >= 50) p.onInk else p.ink)
        button.percent = pct
        button.isClickable = false
        button.contentDescription = "$label, $pct percent"
        return button
    }

    /** COMPONENTS 8.3 "armed = false". Safe to call every tick; no-op when unchanged. */
    fun setArmed(button: MaterialButton, armed: Boolean, secondsUntilArmed: Int) {
        val key = button as? KeyView ?: return
        if (key.armed == armed && (armed || key.secondsUntilArmed == secondsUntilArmed)) return
        key.armed = armed
        key.secondsUntilArmed = if (armed) 0 else secondsUntilArmed
        key.alpha = if (armed) 1f else DISABLED_ALPHA_F
        val base = key.baseDescription ?: key.text.toString()
        key.contentDescription = if (armed) key.baseDescription else
            "$base, available in $secondsUntilArmed ${if (secondsUntilArmed == 1) "second" else "seconds"}"
    }

    /** Mapping used by owning WPs when they migrate a Theme.button call. */
    fun kindFor(old: Theme.ButtonStyle): Kind = when (old) {
        Theme.ButtonStyle.PRIMARY, Theme.ButtonStyle.SUCCESS -> Kind.INK
        Theme.ButtonStyle.NEUTRAL -> Kind.PANEL
        Theme.ButtonStyle.DANGER, Theme.ButtonStyle.GHOST -> Kind.OUTLINE
    }

    private fun build(ctx: Context, label: String, kind: Kind, size: Size): KeyView {
        val p = Theme.palette(ctx)
        val bevelKind = when (kind) {
            Kind.INK -> Bevel.Kind.INK
            Kind.PANEL -> Bevel.Kind.PANEL
            Kind.SIGNAL -> Bevel.Kind.SIGNAL
            Kind.OUTLINE -> Bevel.Kind.OUTLINE
        }
        val labelColor = when (kind) {
            Kind.INK -> p.onInk
            Kind.SIGNAL -> p.onSignal
            else -> p.ink
        }
        val touchH = when (size) {
            Size.REGULAR -> Dimens.KEY_HEIGHT
            Size.LARGE -> Dimens.KEY_HEIGHT_LARGE
            Size.SMALL -> Dimens.MIN_TARGET
        }
        val padH = if (size == Size.SMALL) 12 else 20
        return KeyView(ctx).apply {
            text = label
            Type.apply(this, TypeRole.KEY)
            setTextColor(labelColor)
            maxLines = 2
            ellipsize = null
            gravity = Gravity.CENTER
            minWidth = ctx.dp(64)
            minimumWidth = ctx.dp(64)
            minHeight = ctx.dp(touchH)
            minimumHeight = ctx.dp(touchH)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = null
            rippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
            stateListAnimator = null
            elevation = 0f
            val face = Bevel.key(ctx, bevelKind)
            val inset = if (size == Size.SMALL) ctx.dp((Dimens.MIN_TARGET - Dimens.KEY_SMALL_VISUAL) / 2) else 0
            background = if (size == Size.SMALL) InsetDrawable(face, 0, inset, 0, inset) else face
            // After the background: View.setBackground copies the drawable's padding, which for
            // the InsetDrawable is (0, inset, 0, inset). Keep the vertical inset so a two-line
            // label stays inside the visible face, and restore the side padding.
            setPadding(ctx.dp(padH), inset, ctx.dp(padH), inset)
            installFocusRing(this, Dimens.RADIUS_KEY)
            setOnTouchListener(::onKeyTouch)
        }
    }

    private const val PRESS_DROP_DP = 2

    /** Moves the label with the pressed face, relative to the resting padding (never absolute). */
    private fun dropLabel(v: View, down: Boolean) {
        val key = v as? KeyView ?: return
        if (key.dropped == down) return
        val d = v.context.dp(PRESS_DROP_DP) * if (down) 1 else -1
        key.dropped = down
        v.setPadding(v.paddingLeft, v.paddingTop + d, v.paddingRight, v.paddingBottom - d)
    }

    private fun onKeyTouch(v: View, ev: MotionEvent): Boolean {
        if (!v.isEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The face drops 2dp (Bevel pressed layers); the label follows by padding.
                dropLabel(v, down = true)
                Haptics.perform(v, HapticEvent.KEY_DOWN)
            }
            MotionEvent.ACTION_UP -> {
                dropLabel(v, down = false)
                if (ev.x in 0f..v.width.toFloat() && ev.y in 0f..v.height.toFloat()) {
                    Haptics.perform(v, HapticEvent.KEY_UP)
                }
            }
            MotionEvent.ACTION_CANCEL -> dropLabel(v, down = false)
        }
        return false
    }
}
