package com.mido.phonebridge.ui.kit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Selection
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.text.Spanned
import android.text.SpannableStringBuilder
import androidx.appcompat.widget.AppCompatTextView
import com.mido.phonebridge.ui.SoftWrap
import com.mido.phonebridge.ui.UntrustedText

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
        set(value) {
            field = value
            wrappedFor = null
            // Inserted breaks make each line its own paragraph; pin the direction so a line that starts
            // inside an RTL run is not re-ordered and the glyph (always drawn right) stays in its gutter.
            if (value) {
                textDirection = TEXT_DIRECTION_LTR
                // TEXT_START follows the pinned LTR text direction; VIEW_START would follow an RTL
                // layout direction and right-align the lines under the glyph.
                textAlignment = TEXT_ALIGNMENT_TEXT_START
            }
            requestLayout()
        }

    /** Colour of the continuation glyph. */
    var glyphColor: Int = 0

    /** Colour of the "text did not fit" marker; the caution ink of the readout. */
    var cautionColor: Int = 0

    /**
     * True when the text still does not fit after every shrink: a line wider than the view, or a
     * wrap the platform added by itself. It is never left silent: a marker is drawn and the
     * accessibility description says so.
     */
    internal var didNotFit: Boolean = false
        private set

    /** The shrink steps tried before giving up; a test lowers it to reach the failure state. */
    internal var maxShrinks: Int = MAX_SHRINKS

    init {
        // A selection here can leave only through Copy (which drops the inserted breaks). Share,
        // Process text, Cut and the rest would hand on the wrapped picture with its `\n`s.
        customSelectionActionModeCallback = CopyOnlyCallback
    }

    /** Display indices of the `\n` characters this view inserted. */
    internal var insertedAt: IntArray = IntArray(0)
        private set

    /** Parallel to [insertedAt]: true where the break cuts a token (no space there), drawn with another glyph. */
    internal var insertedHard: BooleanArray = BooleanArray(0)
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
            insertedHard = BooleanArray(0)
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
        for (attempt in 0..maxShrinks) {
            rewrap(available, factor)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (!overflows(available)) {
                setFitFailed(false)
                return
            }
            factor *= SHRINK_STEP
        }
        setFitFailed(true)
    }

    private fun setFitFailed(failed: Boolean) {
        if (didNotFit == failed) return
        didNotFit = failed
        contentDescription = if (failed) "$DID_NOT_FIT_DESCRIPTION. ${UntrustedText.escape(verbatimText())}" else null
    }

    private fun overflows(availablePx: Int): Boolean {
        if (hasForeignWrap()) return true
        val l = layout ?: return false
        return (0 until l.lineCount).any { l.getLineWidth(it) > availablePx + FIT_TOLERANCE_PX }
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
        val glyphWidth = maxOf(paint.measureText(SoftWrap.GLYPH), paint.measureText(SoftWrap.GLYPH_HARD))
        val spanned = src as? Spanned
        val keep = spanned?.getSpans(0, src.length, DangerUnderlineSpan::class.java)
            ?.map { spanned.getSpanStart(it) until spanned.getSpanEnd(it) }
            .orEmpty()
        val cuts = SoftWrap.breaks(src, advances, availablePx * factor, glyphWidth, keep)
        isRewrapping = true
        try {
            if (cuts.isEmpty()) {
                insertedAt = IntArray(0)
                insertedHard = BooleanArray(0)
                super.setText(src, BufferType.SPANNABLE)
            } else {
                val wrapped = SpannableStringBuilder(src)
                // Back to front, so the earlier indices are still valid while inserting.
                for (i in cuts.indices.reversed()) wrapped.insert(cuts[i], "\n")
                insertedAt = IntArray(cuts.size) { cuts[it] + it }
                insertedHard = BooleanArray(cuts.size) { SoftWrap.isHardBreak(src, cuts[it]) }
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
        const val FIT_TOLERANCE_PX = 1f
        const val DID_NOT_FIT_DESCRIPTION = "Text did not fit and may be cut off"

        /** Drawn at the end of the last line when the text did not fit. */
        const val DID_NOT_FIT_MARK = "\u2026"

        private val ALLOWED_MENU_IDS = setOf(android.R.id.copy, android.R.id.selectAll)

        /** Keeps Copy and Select all, drops every other item (Share, Process text, Cut, Paste, Replace...). */
        object CopyOnlyCallback : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { prune(menu); return true }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { prune(menu); return true }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit

            private fun prune(menu: Menu) {
                // Several items can share an id (Process text items are all NONE): remove by id from the
                // front, so the first match is always the item at [i] and nothing is skipped.
                var i = 0
                while (i < menu.size()) {
                    val id = menu.getItem(i).itemId
                    if (id in ALLOWED_MENU_IDS) i++ else menu.removeItem(id)
                }
            }
        }
    }

    /** [text] as it was given, without the breaks this view added. */
    fun verbatimText(): CharSequence = source ?: text

    /**
     * No drag-and-drop of a selection: it would carry the inserted `\n`s out of the view. The
     * platform starts that drag from a long press on the selected text (and `startDragAndDrop` is
     * final), so a long press first drops the selection: it then selects the word under the finger
     * instead of picking the old selection up.
     */
    override fun performLongClick(): Boolean {
        (text as? android.text.Spannable)?.let { if (hasSelection()) Selection.removeSelection(it) }
        return super.performLongClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (didNotFit) {
            glyphPaint.set(paint)
            glyphPaint.color = cautionColor
            val markX = (width - totalPaddingRight).toFloat() - glyphPaint.measureText(DID_NOT_FIT_MARK)
            val markY = totalPaddingTop + l.getLineBaseline(l.lineCount - 1).toFloat()
            canvas.drawText(DID_NOT_FIT_MARK, markX, markY, glyphPaint)
        }
        if (insertedAt.isEmpty()) return
        glyphPaint.set(paint)
        glyphPaint.color = glyphColor
        val right = (width - totalPaddingRight).toFloat()
        for (i in insertedAt.indices) {
            val at = insertedAt[i]
            if (at >= text.length) continue
            val glyph = if (insertedHard[i]) SoftWrap.GLYPH_HARD else SoftWrap.GLYPH
            val x = right - glyphPaint.measureText(glyph)
            val y = totalPaddingTop + l.getLineBaseline(l.getLineForOffset(at)).toFloat()
            canvas.drawText(glyph, x, y, glyphPaint)
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        // Anything but Copy and Select all (Share, Cut, Paste, Process text) would hand on the picture.
        if (id !in ALLOWED_MENU_IDS) return true
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
