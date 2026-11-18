package com.mido.phonebridge.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/**
 * Draws its characters with **zero advance**: they stay in the string, they are simply not painted.
 *
 * **Why a span and not a substring.** The leading space after a `;` break is real — it is in the
 * command that gets sent, so removing it would change the text a human approved. But drawing it
 * puts a phantom indent on every continuation line, which reads as though the command were two
 * commands. Android's text model has no "skip these glyphs" flag; the one primitive that collapses
 * drawn width without touching the characters is a `ReplacementSpan` whose `getSize` returns zero.
 *
 * **String fidelity is the invariant, and this span does not threaten it.** Every character of the
 * input is still in the buffer, in order, at exactly its original offset — so the offset maths in
 * [ShellCode] (colour spans, danger underlines) is unchanged. A zero advance moves nothing.
 *
 * **This is a DRAWING concern only.** It must never be applied to, or confused with, the command a
 * caller sends: the render function receives a copy precisely so the original can stay untouched.
 * If a future change wants the indent gone from the STRING, that is a different decision about the
 * command itself, and it is not this class.
 */
class ZeroWidthSpan : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        // The characters occupy no horizontal space at all. Font metrics are deliberately NOT
        // touched: this span only ever covers whitespace, and a whitespace run cannot change the
        // line's ascent or descent.
        return 0
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        // Nothing. The characters exist in the text and are simply not painted.
    }
}
