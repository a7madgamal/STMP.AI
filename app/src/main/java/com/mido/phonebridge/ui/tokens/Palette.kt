package com.mido.phonebridge.ui.tokens

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.mido.phonebridge.R

/** Every colour role the app may use. Built only by [Palettes] from the `stmp_*` resources. */
data class Palette(
    val isDark: Boolean,
    @ColorInt val body: Int,
    @ColorInt val panel: Int,
    @ColorInt val sunken: Int,
    @ColorInt val readout: Int,
    @ColorInt val hairline: Int,
    @ColorInt val outline: Int,
    @ColorInt val ink: Int,
    @ColorInt val etched: Int,
    @ColorInt val onInk: Int,
    @ColorInt val signal: Int,
    @ColorInt val onSignal: Int,
    @ColorInt val readoutText: Int,
    @ColorInt val readoutCommand: Int,
    @ColorInt val readoutMuted: Int,
    @ColorInt val readoutDanger: Int,
    @ColorInt val readoutString: Int,
    @ColorInt val okText: Int,
    @ColorInt val cautionText: Int,
    @ColorInt val signalText: Int,
    @ColorInt val okGlyph: Int,
    @ColorInt val cautionGlyph: Int,
    @ColorInt val signalGlyph: Int,
    @ColorInt val okSurface: Int,
    @ColorInt val cautionSurface: Int,
    @ColorInt val signalSurface: Int,
    @ColorInt val ledOk: Int,
    @ColorInt val ledCaution: Int,
    @ColorInt val ledSignal: Int,
    @ColorInt val ledOff: Int,
    @ColorInt val bevelHighlight: Int,
    @ColorInt val bevelShade: Int,
    @ColorInt val inkHighlight: Int,
    @ColorInt val keyLip: Int,
    @ColorInt val keyLipInk: Int,
    @ColorInt val keyLipSignal: Int,
    @ColorInt val readoutShadow: Int,
    @ColorInt val rockerTrack: Int,
    @ColorInt val rockerCapOff: Int,
    @ColorInt val rockerOffText: Int,
    @ColorInt val holdTrack: Int,
    @ColorInt val tapePaper: Int,
    @ColorInt val scrim: Int,
    @ColorInt val focus: Int,
    @ColorInt val ripple: Int,
)

object Palettes {
    @Volatile private var light: Palette? = null
    @Volatile private var dark: Palette? = null

    /** The palette for [ctx]'s current night mode. Safe from any thread and any Context. */
    fun of(ctx: Context): Palette {
        val night = isNight(ctx)
        val cached = if (night) dark else light
        if (cached != null) return cached
        return synchronized(this) {
            val again = if (night) dark else light
            again ?: load(ctx, night).also { if (night) dark = it else light = it }
        }
    }

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    @androidx.annotation.VisibleForTesting
    fun clearCacheForTest() = synchronized(this) { light = null; dark = null }

    private fun load(ctx: Context, night: Boolean): Palette {
        fun c(id: Int) = ContextCompat.getColor(ctx, id)
        return Palette(
            isDark = night,
            body = c(R.color.stmp_body),
            panel = c(R.color.stmp_panel),
            sunken = c(R.color.stmp_sunken),
            readout = c(R.color.stmp_readout),
            hairline = c(R.color.stmp_hairline),
            outline = c(R.color.stmp_outline),
            ink = c(R.color.stmp_ink),
            etched = c(R.color.stmp_etched),
            onInk = c(R.color.stmp_on_ink),
            signal = c(R.color.stmp_signal),
            onSignal = c(R.color.stmp_on_signal),
            readoutText = c(R.color.stmp_readout_text),
            readoutCommand = c(R.color.stmp_readout_command),
            readoutMuted = c(R.color.stmp_readout_muted),
            readoutDanger = c(R.color.stmp_readout_danger),
            readoutString = c(R.color.stmp_readout_string),
            okText = c(R.color.stmp_ok_text),
            cautionText = c(R.color.stmp_caution_text),
            signalText = c(R.color.stmp_signal_text),
            okGlyph = c(R.color.stmp_ok_glyph),
            cautionGlyph = c(R.color.stmp_caution_glyph),
            signalGlyph = c(R.color.stmp_signal_glyph),
            okSurface = c(R.color.stmp_ok_surface),
            cautionSurface = c(R.color.stmp_caution_surface),
            signalSurface = c(R.color.stmp_signal_surface),
            ledOk = c(R.color.stmp_led_ok),
            ledCaution = c(R.color.stmp_led_caution),
            ledSignal = c(R.color.stmp_led_signal),
            ledOff = c(R.color.stmp_led_off),
            bevelHighlight = c(R.color.stmp_bevel_highlight),
            bevelShade = c(R.color.stmp_bevel_shade),
            inkHighlight = c(R.color.stmp_ink_highlight),
            keyLip = c(R.color.stmp_key_lip),
            keyLipInk = c(R.color.stmp_key_lip_ink),
            keyLipSignal = c(R.color.stmp_key_lip_signal),
            readoutShadow = c(R.color.stmp_readout_shadow),
            rockerTrack = c(R.color.stmp_rocker_track),
            rockerCapOff = c(R.color.stmp_rocker_cap_off),
            rockerOffText = c(R.color.stmp_rocker_off_text),
            holdTrack = c(R.color.stmp_hold_track),
            tapePaper = c(R.color.stmp_tape_paper),
            scrim = c(R.color.stmp_scrim),
            focus = c(R.color.stmp_focus),
            ripple = c(R.color.stmp_ripple),
        )
    }
}

object Colors {
    /** [color] with its alpha byte replaced by [alpha] (0..255). */
    @ColorInt fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
