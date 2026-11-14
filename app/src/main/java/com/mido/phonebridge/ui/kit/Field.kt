package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Bevel
import com.mido.phonebridge.ui.tokens.Colors
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** One themed single-line text field with an etched label and an error line (COMPONENTS 25). */
object Field {

    enum class Kind { TEXT, URL, NUMBER, SECRET }

    class Handle(val root: View, val edit: EditText) {
        internal var errorView: TextView? = null
        internal var applyError: (String?) -> Unit = {}
        internal var label: View? = null
        fun setError(text: String?) = applyError(text)
        internal var applyEnabled: (Boolean) -> Unit = {}
        fun setEnabled(enabled: Boolean) {
            edit.isEnabled = enabled
            applyEnabled(enabled)
        }
    }

    fun create(
        ctx: Context,
        label: String,
        value: String,
        hint: String,
        kind: Kind = Kind.TEXT,
        autofill: Boolean = false,
        onChange: ((String) -> Unit)? = null,
    ): Handle {
        val p = Theme.palette(ctx)
        val labelView = Etched.label(ctx, label)
        val edit = FieldEditText(ctx).apply {
            id = View.generateViewId()
            setText(value)
            // The EditText keeps the FULL hint, so TalkBack, accessibility events and autofill all get
            // it. An EditText has a key listener, so TextView.ellipsize never applies; the cut is made
            // when the hint is drawn (see FieldEditText), never in the hint itself.
            this.hint = hint
            hintDrawColor = p.etched
            setTextColor(p.ink)
            setHintTextColor(android.graphics.Color.TRANSPARENT)
            Type.apply(this, if (kind == Kind.TEXT) TypeRole.BODY else TypeRole.MONO_BODY)
            setHorizontallyScrolling(false)
            setSingleLine(true)
            minHeight = ctx.dp(Dimens.MIN_TARGET)
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            isSaveEnabled = false
            importantForAutofill = if (autofill) View.IMPORTANT_FOR_AUTOFILL_YES else View.IMPORTANT_FOR_AUTOFILL_NO
            highlightColor = Colors.withAlpha(p.ink, 0x33)
            inputType = when (kind) {
                Kind.TEXT -> InputType.TYPE_CLASS_TEXT
                Kind.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                Kind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                Kind.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            if (kind == Kind.SECRET) {
                transformationMethod = PasswordTransformationMethod.getInstance()
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                isSaveFromParentEnabled = false
            }
            Etched.labelFor(labelView, this)
        }
        val errorView = Type.text(ctx, "", TypeRole.CAPTION, p.cautionText).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val handle = Handle(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = matchWrap().apply { bottomMargin = ctx.dp(Dimens.SPACE_S) }
                addView(labelView)
                addView(edit, matchWrap())
                addView(errorView, matchWrap().apply { topMargin = ctx.dp(6) })
            },
            edit,
        )
        handle.errorView = errorView
        handle.label = labelView
        fun track(error: Boolean, focused: Boolean) {
            if (!edit.isEnabled) {
                // Not editable: a flat box with a dashed edge and no inner shade, so it reads as
                // locked rather than as an empty field showing its placeholder.
                edit.background = GradientDrawable().apply {
                    setColor(p.panel)
                    setStroke(ctx.dp(1), p.outline, ctx.dpf(4f), ctx.dpf(3f))
                    cornerRadius = ctx.dpf(Dimens.RADIUS_READOUT.toFloat())
                }
                return
            }
            val bg = Bevel.sunkenTrack(ctx, p.sunken, Dimens.RADIUS_READOUT)
            edit.background = bg
            if (error || focused) {
                val ring = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(ctx.dp(2), if (error) p.cautionText else p.ink)
                    cornerRadius = ctx.dpf(Dimens.RADIUS_READOUT.toFloat())
                }
                edit.background = android.graphics.drawable.LayerDrawable(arrayOf(bg, ring))
            }
        }
        var hasError = false
        handle.applyEnabled = { enabled ->
            edit.alpha = if (enabled) 1f else DISABLED_ALPHA_F
            track(hasError, edit.isFocused)
        }
        track(false, false)
        edit.setOnFocusChangeListener { _, focused -> track(hasError, focused) }
        handle.applyError = { text ->
            hasError = text != null
            errorView.text = text.orEmpty()
            errorView.visibility = if (text == null) View.GONE else View.VISIBLE
            track(hasError, edit.isFocused)
        }
        if (onChange != null) {
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
            })
        }
        return handle
    }
}

/**
 * An EditText whose empty-state hint is drawn here, cut with a real ellipsis to the inner width and in
 * the sans face. The inherited hint is kept whole but painted transparent, so it is never replaced.
 */
internal class FieldEditText(ctx: Context) : androidx.appcompat.widget.AppCompatEditText(ctx) {
    var hintDrawColor: Int = 0

    /** What is drawn while the field is empty: the hint, cut to fit. */
    var drawnHint: CharSequence = ""
        private set

    private val hintPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val full = hint?.toString().orEmpty()
        val inner = w - compoundPaddingLeft - compoundPaddingRight
        drawnHint = if (inner > 0) fitHint(context, full, inner, paint).toString() else full
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!text.isNullOrEmpty() || drawnHint.isEmpty()) return
        hintPaint.set(paint)
        hintPaint.typeface = com.mido.phonebridge.ui.tokens.Fonts.get(context, TypeRole.BODY.face)
        hintPaint.color = hintDrawColor
        val baseline = getLineBounds(0, null)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val x = if (rtl) (width - compoundPaddingRight).toFloat() else compoundPaddingLeft.toFloat()
        hintPaint.textAlign = if (rtl) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
        canvas.drawText(drawnHint.toString(), x + scrollX, baseline.toFloat(), hintPaint)
    }
}

private fun sansHint(ctx: Context, hint: String): CharSequence {
    val face = com.mido.phonebridge.ui.tokens.Fonts.get(ctx, TypeRole.BODY.face)
    return android.text.SpannableString(hint).apply {
        setSpan(object : android.text.style.MetricAffectingSpan() {
            override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = face }
            override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = face }
        }, 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private const val ELLIPSIS = "…"

/** [hint] in the sans face, cut with an ellipsis so it is never wider than [inner] pixels. */
internal fun fitHint(ctx: Context, hint: String, inner: Int, base: android.text.TextPaint): CharSequence {
    if (inner <= 0) return sansHint(ctx, hint)
    val paint = android.text.TextPaint(base).apply {
        typeface = com.mido.phonebridge.ui.tokens.Fonts.get(ctx, TypeRole.BODY.face)
    }
    if (paint.measureText(hint) <= inner) return sansHint(ctx, hint)
    // Longest prefix that, with the ellipsis after it, fits.
    var keep = hint.length
    while (keep > 0 && paint.measureText(hint.substring(0, keep).trimEnd() + ELLIPSIS) > inner) keep--
    return sansHint(ctx, hint.substring(0, keep).trimEnd() + ELLIPSIS)
}
