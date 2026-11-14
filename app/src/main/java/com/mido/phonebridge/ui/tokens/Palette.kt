package com.mido.phonebridge.ui.tokens

import android.content.Context
import androidx.annotation.ColorInt

/**
 * Every colour role the app may use.
 *
 * **The values are the original paper palette**, kept in one object so a screen can be written
 * against roles rather than literals. The Instrument kit's structure is kept; only its colours
 * were wrong for this app — grey where the design is warm cream, orange where it is stamp red.
 *
 * A role is a MEANING, not a colour: `signal` is "this will destroy something", and it happens
 * to be a deep ink red on paper. That separation is the part worth having, because it is what
 * lets a future theme change happen in one file without editing thirty screens.
 */
data class Palette(
    @ColorInt val body: Int,
    @ColorInt val panel: Int,
    @ColorInt val sunken: Int,
    @ColorInt val hairline: Int,
    @ColorInt val ink: Int,
    @ColorInt val etched: Int,
    @ColorInt val onInk: Int,
    @ColorInt val signal: Int,
    @ColorInt val onSignal: Int,
    @ColorInt val okText: Int,
    @ColorInt val okSurface: Int,
    @ColorInt val cautionText: Int,
    @ColorInt val cautionSurface: Int,
    @ColorInt val signalText: Int,
    @ColorInt val signalSurface: Int,
    @ColorInt val ripple: Int,
)

/**
 * The paper palette.
 *
 * There is exactly one, and it is light. Paper has no night mode: the identity is ink on a warm
 * sheet, and a dark version of it is a different design rather than the same one dimmed. If a
 * dark theme is ever wanted it is a second palette object, chosen deliberately — not the system's
 * `uiMode` silently picking one, which is how a build once opened near-black on a phone that
 * happened to be in dark mode.
 */
object Palettes {
    private val PAPER = Palette(
        body = 0xFFF5EEDF.toInt(),
        panel = 0xFFEDE3CB.toInt(),
        sunken = 0xFFE3D6B8.toInt(),
        hairline = 0xFF8F805F.toInt(),
        ink = 0xFF2B2318.toInt(),
        etched = 0xFF67563C.toInt(),
        onInk = 0xFFFBF6EA.toInt(),
        signal = 0xFFB23A2F.toInt(),
        onSignal = 0xFFFBF6EA.toInt(),
        okText = 0xFF27673F.toInt(),
        okSurface = 0xFFDCEBD7.toInt(),
        cautionText = 0xFF745410.toInt(),
        cautionSurface = 0xFFF0E4C8.toInt(),
        signalText = 0xFF94372D.toInt(),
        signalSurface = 0xFFF1D9D0.toInt(),
        ripple = 0x332B2318,
    )

    /** The palette for [ctx]. Currently always paper, whatever the device is set to. */
    @Suppress("UNUSED_PARAMETER")
    fun of(ctx: Context): Palette = PAPER

    @androidx.annotation.VisibleForTesting
    fun clearCacheForTest() = Unit
}
