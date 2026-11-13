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
        set(value) { field = value; wrappedFor = null; requestLayout() }

    /** Colour of the continuation glyph. */
    var glyphColor: Int = 0

    /** Display indices of the `\n` characters this view inserted. */
    internal var insertedAt: IntArray = IntArray(0)
        private set

    private var source: CharSequence? = null
    private var wrappedFor: Key? = null
    private var isRewrapping = false
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!isRewrapping) {
            source = text
            wrappedFor = null
            insertedAt = IntArray(0)
        }
        super.setText(text, type)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!softWrap || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val available = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
        // The advances are measured, so a line SoftWrap calls full really is. Shaping can still make
        // a run wider than the sum of its chars, so the layout is checked: if the platform added a
        // wrap of its own (a line count that is not logical lines + our breaks), the budget shrinks
        // and the text is wrapped again. A silent platform wrap is never left on screen.
        var factor = 1f
        for (attempt in 0..MAX_SHRINKS) {
            rewrap(available, factor)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (!hasForeignWrap()) return
            factor *= SHRINK_STEP
        }
    }

    private fun hasForeignWrap(): Boolean {
        val l = layout ?: return false
        val logical = text.count { it == '\n' } + 1
        return l.lineCount != logical
    }

    private fun rewrap(availablePx: Int, factor: Float) {
        val src = source ?: return
        if (availablePx <= 0) return
        val key = Key(availablePx, factor, paint.textSize)
        if (key == wrappedFor) return
        wrappedFor = key
        val advances = FloatArray(src.length)
        paint.getTextWidths(src, 0, src.length, advances)
        val glyphWidth = paint.measureText(SoftWrap.GLYPH)
        val spanned = src as? Spanned
        val keep = spanned?.getSpans(0, src.length, DangerUnderlineSpan::class.java)
            ?.map { spanned.getSpanStart(it) until spanned.getSpanEnd(it) }
            .orEmpty()
        val cuts = SoftWrap.breaks(src, advances, availablePx * factor, glyphWidth, keep)
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

    private data class Key(val availablePx: Int, val factor: Float, val textSize: Float)

    private companion object {
        const val MAX_SHRINKS = 6
        const val SHRINK_STEP = 0.85f
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
