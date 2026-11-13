package com.mido.phonebridge.ui.kit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Selection
import android.text.Spanned
import android.text.SpannableStringBuilder
import androidx.appcompat.widget.AppCompatTextView
import com.mido.phonebridge.ui.SoftWrap
import kotlin.math.floor

/**
 * The text of a [Readout]. With [softWrap] on it wraps only at whitespace (see [SoftWrap]) and marks
 * every display-only break with a continuation glyph drawn in the right gutter.
 *
 * The breaks are inserted as `\n` into the displayed text and are remembered in [insertedAt], so:
 *  - the glyph is drawn, not typed: no font fallback can make it wider than a cell and wrap again;
 *  - a copy of the selection leaves the inserted breaks out, so what lands on the clipboard is the
 *    verbatim command, not the wrapped picture of it.
 */
class ReadoutText(ctx: Context) : AppCompatTextView(ctx) {

    /** Wrap at whitespace and mark soft wraps. Off: the platform wraps as it always did. */
    var softWrap: Boolean = false
        set(value) { field = value; columns = -1; requestLayout() }

    /** Colour of the continuation glyph. */
    var glyphColor: Int = 0

    /** Display indices of the `\n` characters this view inserted. */
    internal var insertedAt: IntArray = IntArray(0)
        private set

    private var source: CharSequence? = null
    private var columns = -1
    private var isRewrapping = false
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!isRewrapping) {
            source = text
            columns = -1
            insertedAt = IntArray(0)
        }
        super.setText(text, type)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (softWrap && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            rewrap(MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun rewrap(availablePx: Int) {
        val src = source ?: return
        val advance = paint.measureText("0")
        if (advance <= 0f || availablePx <= 0) return
        val cols = floor(availablePx / advance).toInt()
        if (cols == columns) return
        columns = cols
        val spanned = src as? Spanned
        val keep = spanned?.getSpans(0, src.length, DangerUnderlineSpan::class.java)
            ?.map { spanned.getSpanStart(it) until spanned.getSpanEnd(it) }
            .orEmpty()
        val cuts = SoftWrap.breaks(src, cols, keep)
        isRewrapping = true
        try {
            if (cuts.isEmpty()) {
                insertedAt = IntArray(0)
                super.setText(src, BufferType.SPANNABLE)
            } else {
                val wrapped = SpannableStringBuilder(src)
                // Back to front, so the earlier indices are still valid while inserting.
                for (i in cuts.indices.reversed()) wrapped.insert(cuts[i], "\n")
                insertedAt = IntArray(cuts.size) { cuts[it] + it }
                super.setText(wrapped, BufferType.SPANNABLE)
            }
        } finally {
            isRewrapping = false
        }
    }

    /** [text] as it was given, without the breaks this view added. */
    fun verbatimText(): CharSequence = source ?: text

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (insertedAt.isEmpty()) return
        glyphPaint.set(paint)
        glyphPaint.color = glyphColor
        val x = (width - totalPaddingRight).toFloat() - glyphPaint.measureText(SoftWrap.GLYPH)
        for (at in insertedAt) {
            if (at >= text.length) continue
            val y = totalPaddingTop + l.getLineBaseline(l.getLineForOffset(at)).toFloat()
            canvas.drawText(SoftWrap.GLYPH, x, y, glyphPaint)
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id != android.R.id.copy || insertedAt.isEmpty()) return super.onTextContextMenuItem(id)
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)
        if (from < 0 || to <= from) return super.onTextContextMenuItem(id)
        val skip = insertedAt.toHashSet()
        val picked = StringBuilder()
        for (i in from until to) if (i !in skip) picked.append(text[i])
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("command", picked.toString()))
        (text as? android.text.Spannable)?.let { Selection.setSelection(it, to) }
        return true
    }
}
