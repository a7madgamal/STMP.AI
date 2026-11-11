package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.ui.Theme
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
        val s = maxOf(tokenStart, start)
        val e = minOf(tokenEnd, end)
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

    fun create(ctx: Context, content: CharSequence, kind: Kind, prompt: String? = null): Handle {
        val p = Theme.palette(ctx)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var promptView: TextView? = null
        if (prompt != null) {
            promptView = Type.text(ctx, prompt, TypeRole.READOUT, p.readoutMuted).apply {
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
        val text = Type.text(ctx, content, role, color).apply {
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
                Kind.COMMAND, Kind.LOG -> (h * 0.4f).toInt()
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
            addView(column)
            layoutParams = matchWrap().apply { bottomMargin = ctx.dp(Dimens.MODULE_GAP) }
        }
        return Handle(scroll, text, promptView)
    }

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
