package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.view.View
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.UntrustedText
import com.mido.phonebridge.ui.tokens.Bevel
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * Paints a 2dp bar under the baseline of one dangerous token (D11). It never replaces or reorders
 * a glyph. The span covers exactly `[tokenStart, tokenEnd)` of the text it is attached to.
 */
class DangerUnderlineSpan(
    private val color: Int,
    private val thicknessPx: Float,
    private val tokenStart: Int,
    private val tokenEnd: Int,
) : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int,
    ) {
        // Display-only line breaks are inserted after the span was made, so a Spanned text knows
        // where the token really is now; the constructor range is the fallback.
        val spanned = text as? Spanned
        val tokenFrom = spanned?.getSpanStart(this)?.takeIf { it >= 0 } ?: tokenStart
        val tokenTo = spanned?.getSpanEnd(this)?.takeIf { it >= 0 } ?: tokenEnd
        val s = maxOf(tokenFrom, start)
        var e = minOf(tokenTo, end)
        // Never underline the space a soft wrap (or the end of the line) leaves behind the token.
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return
        val x0 = left + paint.measureText(text, start, s)
        val x1 = left + paint.measureText(text, start, e)
        val saved = paint.color
        paint.color = color
        val y = baseline + thicknessPx * 0.5f
        canvas.drawRect(x0, y, x1, y + thicknessPx, paint)
        paint.color = saved
    }
}

/** The dark recessed display for verbatim commands, payloads, ports and logs (COMPONENTS 2). */
object Readout {
    enum class Kind { COMMAND, BODY, DISPLAY, LOG }

    class Handle(val root: View, val text: TextView, val prompt: TextView?)

    /** A log line starts with `HH:MM:SS` and two spaces; a wrapped line lines up under the message. */
    private const val LOG_STAMP = "00:00:00  "

    /** [text] with every line after the first row of each log entry indented by [hang] pixels. */
    internal fun hangingIndent(text: CharSequence, hang: Float): CharSequence {
        val out = android.text.SpannableStringBuilder(text)
        var start = 0
        while (start <= out.length) {
            val nl = out.indexOf('\n', start).let { if (it < 0) out.length else it }
            if (nl > start) {
                out.setSpan(
                    android.text.style.LeadingMarginSpan.Standard(0, hang.toInt()), start, nl,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            start = nl + 1
        }
        return out
    }

    /**
     * @param softWrap wrap only at whitespace and mark each soft wrap with a continuation glyph
     *   (see [com.mido.phonebridge.ui.SoftWrap]). On by default for a command, where a wrap read
     *   as a space would change what the human approves; the gate also turns it on for its rows.
     */
    fun create(
        ctx: Context,
        content: CharSequence,
        kind: Kind,
        prompt: String? = null,
        softWrap: Boolean = kind == Kind.COMMAND,
    ): Handle {
        val p = Theme.palette(ctx)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var promptView: TextView? = null
        if (prompt != null) {
            promptView = Type.text(ctx, UntrustedText.mark(prompt, p.readoutDanger), TypeRole.READOUT, p.readoutMuted).apply {
                setTextIsSelectable(false)
            }
            column.addView(promptView, matchWrap())
        }
        val role = when (kind) {
            Kind.COMMAND -> TypeRole.COMMAND
            Kind.BODY -> TypeRole.READOUT
            Kind.DISPLAY -> TypeRole.DISPLAY
            Kind.LOG -> TypeRole.MONO_BODY
        }
        val color = if (kind == Kind.COMMAND) p.readoutCommand else p.readoutText
        // Text a caller sent is shown as sent: hidden characters become visible tokens, and a source
        // wrap mark can never pass for one the readout inserted (FIX4).
        val shown = UntrustedText.mark(content, p.readoutDanger, escapeWrapGlyphs = softWrap)
        val text = ReadoutText(ctx).apply {
            Type.apply(this, role)
            setTextColor(color)
            glyphColor = p.readoutMuted
            cautionColor = p.readoutDanger
            this.softWrap = softWrap
            setText(if (kind == Kind.LOG) hangingIndent(shown, paint.measureText(LOG_STAMP)) else shown)
            setTextIsSelectable(true)
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            ellipsize = null
            maxLines = Int.MAX_VALUE
            setHorizontallyScrolling(false)
            // Link-free and non-ellipsized: the characters shown are the characters copied.
        }
        column.addView(text, matchWrap())

        val scroll = MaxHeightScrollView(ctx).apply {
            background = Bevel.readout(ctx, Dimens.RADIUS_READOUT)
            fadeColor = p.readout
            val h = ctx.resources.displayMetrics.heightPixels
            maxHeightPx = when (kind) {
                Kind.COMMAND -> (h * 0.4f).toInt()
                // A log is scrolled to its end: a height of whole lines keeps the top line from
                // being cut through when it is.
                Kind.LOG -> {
                    val pad = ctx.dp(12) * 2
                    val lines = ((h * 0.4f).toInt() - pad) / text.lineHeight
                    lines * text.lineHeight + pad
                }
                Kind.BODY -> (h * 0.5f).toInt()
                Kind.DISPLAY -> Int.MAX_VALUE
            }
            if (kind == Kind.COMMAND) {
                setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            } else {
                setPadding(ctx.dp(13), ctx.dp(12), ctx.dp(13), ctx.dp(12))
            }
            clipToPadding = false
            isFillViewport = false
            layoutParams = matchWrap().apply { bottomMargin = ctx.dp(Dimens.MODULE_GAP) }
        }
        // A readout that is capped in height must say what it hides (R1: never hide part of a
        // command without a visible "+N more lines"). The band is an overlay child of the scrolled
        // content, moved to the visible bottom as it scrolls, so the readout stays one scroll view
        // (callers cast it) and nothing under the band is out of reach.
        val more = Type.text(ctx, "", TypeRole.CAPTION, p.readoutText).apply {
            visibility = View.GONE
            setBackgroundColor(p.readout)
            setPadding(ctx.dp(14), ctx.dp(MORE_PAD_DP), ctx.dp(14), ctx.dp(MORE_PAD_DP))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val content = FrameLayout(ctx).apply {
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(more, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        }
        scroll.addView(content)
        val refresh = Runnable { updateMore(scroll, content, column, text, more) }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> refresh.run() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh.run() }
        text.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh.run() }
        return Handle(scroll, text, promptView)
    }

    private const val MORE_PAD_DP = 6

    /** The affordance under a capped readout. Plain words, so it is JVM-testable. */
    fun moreLines(hidden: Int): String =
        "+$hidden more ${if (hidden == 1) "line" else "lines"} \u00B7 scroll to read"

    /**
     * Show "+N more lines" over the bottom of the visible part while lines lie below it, else hide
     * it. N counts the lines that are not fully visible above the band.
     */
    private fun updateMore(scroll: MaxHeightScrollView, content: View, column: View, text: TextView, more: TextView) {
        val layout = text.layout
        val bandHeight = if (more.height > 0) more.height else more.measuredHeight
        // The band's height is unknown until it has been laid out once; assume one caption line.
        val reserve = if (bandHeight > 0) bandHeight else more.context.dp(MORE_BAND_FALLBACK_DP)
        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingTop - reserve
        val textTop = column.top + text.top + text.totalPaddingTop
        var shown = 0
        if (layout != null) {
            while (shown < layout.lineCount && textTop + layout.getLineBottom(shown) <= visibleBottom) shown++
        }
        val hidden = if (layout == null || !scroll.hasMoreBelow) 0 else layout.lineCount - shown
        if (hidden <= 0) {
            if (more.visibility != View.GONE) more.visibility = View.GONE
            return
        }
        val line = moreLines(hidden)
        if (more.text.toString() != line) more.text = line
        if (more.visibility != View.VISIBLE) more.visibility = View.VISIBLE
        // Lay the band out now: this runs inside a layout pass, whose deferred second pass may not
        // have happened by the time the frame is drawn.
        val width = content.width
        if (width > 0) {
            more.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            more.layout(0, 0, width, more.measuredHeight)
        }
        more.translationY = (scroll.scrollY + scroll.height - scroll.paddingTop - reserve).toFloat()
    }

    private const val MORE_BAND_FALLBACK_DP = 28

    /**
     * [text] with the given character ranges marked as dangerous: readoutDanger ink plus the
     * underline bar. Characters and their order are untouched.
     */
    fun withDanger(ctx: Context, text: String, ranges: List<IntRange>): Spannable {
        val p = Theme.palette(ctx)
        val out = SpannableString(text)
        val thick = ctx.dpf(1f)
        for (r in ranges) {
            val s = r.first
            val e = r.last + 1
            out.setSpan(ForegroundColorSpan(p.readoutDanger), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(
                DangerUnderlineSpan(p.signal, thick * 2f, s, e),
                s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return out
    }
}
