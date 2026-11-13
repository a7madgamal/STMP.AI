package com.mido.phonebridge.ui.tokens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity

/** Drawable recipes: depth is one highlight line and one shade line, never a drop shadow. */
object Bevel {
    enum class Kind { PANEL, INK, SIGNAL, OUTLINE }

    private fun px(ctx: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()

    private fun rect(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    private fun layers(ctx: Context, parts: List<Drawable>, insets: List<IntArray>): LayerDrawable {
        val d = LayerDrawable(parts.toTypedArray())
        insets.forEachIndexed { i, v -> d.setLayerInset(i, px(ctx, v[0].toFloat()), px(ctx, v[1].toFloat()), px(ctx, v[2].toFloat()), px(ctx, v[3].toFloat())) }
        return d
    }

    /** A module: shade, highlight, then the fill. */
    fun module(ctx: Context, fill: Int = Palettes.of(ctx).panel, r: Int = Dimens.RADIUS_MODULE): Drawable {
        val p = Palettes.of(ctx)
        val rp = px(ctx, r.toFloat()).toFloat()
        return layers(
            ctx,
            listOf(rect(p.bevelShade, rp), rect(p.bevelHighlight, rp), rect(fill, rp)),
            listOf(intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 0, 1), intArrayOf(0, 1, 0, 1)),
        )
    }

    private fun keyFace(ctx: Context, kind: Kind, pressed: Boolean): Drawable {
        val p = Palettes.of(ctx)
        val rp = px(ctx, Dimens.RADIUS_KEY.toFloat()).toFloat()
        if (kind == Kind.OUTLINE) {
            val d = GradientDrawable().apply {
                cornerRadius = rp
                setColor(if (pressed) p.ripple else Color.TRANSPARENT)
                setStroke(px(ctx, Dimens.OUTLINE_STROKE_DP), p.ink)
            }
            return d
        }
        val (lip, hi, face) = when (kind) {
            Kind.INK -> Triple(p.keyLipInk, p.inkHighlight, p.ink)
            Kind.SIGNAL -> Triple(p.keyLipSignal, p.inkHighlight, p.signal)
            else -> Triple(p.keyLip, p.bevelHighlight, p.panel)
        }
        val insets = if (pressed) {
            listOf(intArrayOf(0, 2, 0, 0), intArrayOf(0, 2, 0, 0), intArrayOf(0, 3, 0, 0))
        } else {
            listOf(intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 0, 2), intArrayOf(0, 1, 0, 2))
        }
        val faceLayer = rect(face, rp)
        // Dark PANEL keys: the face is the panel colour, so without an edge the key vanishes. A
        // 1dp outline (4.9:1 on the panel) gives WCAG 1.4.11's 3:1 for the control's boundary.
        if (kind == Kind.PANEL && p.isDark) faceLayer.setStroke(px(ctx, 1f), p.outline)
        return layers(ctx, listOf(rect(lip, rp), rect(hi, rp), faceLayer), insets)
    }

    /**
     * A key: pressed variant moves the face down 2dp. A disabled key looks like the unpressed one;
     * the dimming is the key view's own 38 % alpha (KeyButton), so the label dims with the face.
     */
    fun key(ctx: Context, kind: Kind, pressed: Boolean = false): Drawable {
        if (pressed) return keyFace(ctx, kind, true)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), keyFace(ctx, kind, true))
            addState(intArrayOf(), keyFace(ctx, kind, false))
        }
    }

    /** The recessed readout: highlight rim, dark body, inner top shadow. */
    fun readout(ctx: Context, r: Int = Dimens.RADIUS_READOUT): Drawable {
        val p = Palettes.of(ctx)
        val rp = px(ctx, r.toFloat()).toFloat()
        val shadow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.readoutShadow, Color.TRANSPARENT),
        ).apply { cornerRadii = floatArrayOf(rp, rp, rp, rp, 0f, 0f, 0f, 0f) }
        val d = layers(
            ctx,
            listOf(rect(p.bevelHighlight, rp), rect(p.readout, rp), shadow),
            listOf(intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 0, 1), intArrayOf(0, 0, 0, 1)),
        )
        d.setLayerHeight(2, px(ctx, 6f))
        d.setLayerGravity(2, Gravity.TOP)
        return d
    }

    /** A sunken track (knob, rocker): outlined fill with a soft top shade. */
    fun sunkenTrack(ctx: Context, fill: Int, r: Int): Drawable {
        val p = Palettes.of(ctx)
        val rp = px(ctx, r.toFloat()).toFloat()
        val base = rect(fill, rp).apply { setStroke(px(ctx, 1f), p.outline) }
        val shade = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Colors.withAlpha(p.readoutShadow, if (p.isDark) 0x80 else 0x47), Color.TRANSPARENT),
        ).apply { cornerRadii = floatArrayOf(rp, rp, rp, rp, 0f, 0f, 0f, 0f) }
        val d = layers(
            ctx, listOf(base, shade),
            listOf(intArrayOf(0, 0, 0, 0), intArrayOf(1, 1, 1, 0)),
        )
        d.setLayerHeight(1, px(ctx, 3f))
        d.setLayerGravity(1, Gravity.TOP)
        return d
    }
}
