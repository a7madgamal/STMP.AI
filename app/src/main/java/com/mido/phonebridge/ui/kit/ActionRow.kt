package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * A list row that carries text plus buttons (COMPONENTS 14). Inline when there is room at normal
 * text size, otherwise the keys drop under the text and wrap onto further lines, with the one
 * destructive key last and at least 16dp from every other key.
 */
class ActionRow(
    ctx: Context,
    private val title: String,
    detail: String?,
    actions: List<KeyButton.Spec>,
    destructive: KeyButton.Spec?,
    led: Led.State? = null,
) : ViewGroup(ctx) {

    private companion object {
        const val LED_DP = 16
        const val LED_GAP_DP = 10
    }

    private val content: LinearLayout
    private val keys: List<MaterialButton>
    private val destructiveKey: MaterialButton?

    /** True after a measure pass that chose the side-by-side layout (read by tests). */
    var isInline: Boolean = false
        private set

    private class Placement(val view: View, var x: Int = 0, var y: Int = 0)
    private val placements = ArrayList<Placement>()
    private var contentX = 0
    private var contentY = 0

    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(1f)
        color = Theme.palette(ctx).hairline
        pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(4f), ctx.dpf(3f)), 0f)
    }

    init {
        setWillNotDraw(false)
        val p = Theme.palette(ctx)
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val titleView = Type.text(ctx, title, TypeRole.ROW_LABEL, p.ink)
            // With a status light the text hangs under the title, as in StatusRow, and the light
            // sits on the title's first line.
            val textInset = if (led != null) ctx.dp(LED_DP) + ctx.dp(LED_GAP_DP) else 0
            if (led != null) {
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Led(ctx).apply { state = led }, LinearLayout.LayoutParams(ctx.dp(LED_DP), ctx.dp(LED_DP)).apply {
                        marginEnd = ctx.dp(LED_GAP_DP)
                        topMargin = (titleView.lineHeight - ctx.dp(LED_DP)).coerceAtLeast(0) / 2
                    })
                    addView(titleView, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, matchWrap())
            } else {
                addView(titleView, matchWrap())
            }
            if (!detail.isNullOrEmpty()) {
                addView(Type.text(ctx, detail, TypeRole.DETAIL, p.etched), matchWrap().apply {
                    topMargin = ctx.dp(2)
                    marginStart = textInset
                })
            }
            contentDescription = if (detail.isNullOrEmpty()) title else "$title, $detail"
            androidx.core.view.ViewCompat.setScreenReaderFocusable(this, true)
            isFocusable = true
        }
        addView(content)
        fun key(spec: KeyButton.Spec, kind: KeyButton.Kind) = KeyButton.create(
            ctx, spec.label, kind, KeyButton.Size.SMALL,
            describe = spec.describe.ifBlank { "${spec.label} $title" }, onClick = spec.onClick,
        )
        keys = actions.map { key(it, KeyButton.Kind.PANEL) }
        destructiveKey = destructive?.let { key(it, KeyButton.Kind.OUTLINE) }
        keys.forEach { addView(it) }
        destructiveKey?.let { addView(it) }
    }

    override fun generateLayoutParams(attrs: android.util.AttributeSet?) = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private val ordered: List<MaterialButton> get() = keys + listOfNotNull(destructiveKey)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ctx = context
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val padV = ctx.dp(10)
        val gap = ctx.dp(Dimens.TARGET_GAP)
        val bigGap = ctx.dp(Dimens.DESTRUCTIVE_GAP)
        placements.clear()

        ordered.forEach {
            it.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
        fun gapBefore(i: Int): Int = when {
            i == 0 -> 0
            ordered[i] === destructiveKey -> bigGap
            else -> gap
        }
        val actionsWidth = ordered.indices.sumOf { ordered[it].measuredWidth + gapBefore(it) }
        val actionsHeight = ordered.maxOfOrNull { it.measuredHeight } ?: 0
        val contentGap = ctx.dp(Dimens.SPACE_M)
        val inlineContentW = width - actionsWidth - contentGap
        val inline = ordered.isEmpty() ||
            (resources.configuration.fontScale <= 1.15f && inlineContentW >= width / 2)
        isInline = inline

        if (inline) {
            content.measure(
                MeasureSpec.makeMeasureSpec(if (ordered.isEmpty()) width else inlineContentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val inner = maxOf(content.measuredHeight, actionsHeight)
            contentX = 0
            contentY = padV + (inner - content.measuredHeight) / 2
            var x = width
            for (i in ordered.indices.reversed()) {
                x -= ordered[i].measuredWidth
                placements.add(Placement(ordered[i], x, padV + (inner - ordered[i].measuredHeight) / 2))
                x -= gapBefore(i)
            }
            setMeasuredDimension(width, inner + padV * 2)
            return
        }

        content.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        contentX = 0
        contentY = padV
        var y = padV + content.measuredHeight + gap
        // Greedy lines, end-aligned. The destructive key starts its own line when it does not fit
        // after the others, with a 16dp gap above it.
        val lines = ArrayList<MutableList<MaterialButton>>()
        var current = ArrayList<MaterialButton>()
        var used = 0
        for (k in keys) {
            val need = k.measuredWidth + if (current.isEmpty()) 0 else gap
            if (used + need > width && current.isNotEmpty()) {
                lines.add(current); current = ArrayList(); used = 0
            }
            used += k.measuredWidth + if (current.isEmpty()) 0 else gap
            current.add(k)
        }
        var destructiveOnOwnLine = false
        if (destructiveKey != null) {
            val need = destructiveKey.measuredWidth + if (current.isEmpty()) 0 else bigGap
            // Alone on its line whenever the others already wrapped, so a key on an earlier line
            // is never closer than DESTRUCTIVE_GAP to it.
            if ((used + need > width || lines.isNotEmpty()) && current.isNotEmpty()) {
                lines.add(current); current = ArrayList(); used = 0
                destructiveOnOwnLine = true
            }
            current.add(destructiveKey)
        }
        if (current.isNotEmpty()) lines.add(current)
        lines.forEachIndexed { li, line ->
            val lineH = line.maxOf { it.measuredHeight }
            val isDestructiveLine = destructiveOnOwnLine && li == lines.lastIndex
            if (li > 0) y += if (isDestructiveLine) bigGap else gap
            var lineW = 0
            line.forEachIndexed { i, k ->
                lineW += k.measuredWidth + if (i == 0) 0 else (if (k === destructiveKey) bigGap else gap)
            }
            var x = width - lineW
            line.forEachIndexed { i, k ->
                if (i > 0) x += if (k === destructiveKey) bigGap else gap
                placements.add(Placement(k, x, y + (lineH - k.measuredHeight) / 2))
                x += k.measuredWidth
            }
            y += lineH
        }
        setMeasuredDimension(width, y + padV)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(contentX, contentY, contentX + content.measuredWidth, contentY + content.measuredHeight)
        placements.forEach { it.view.layout(it.x, it.y, it.x + it.view.measuredWidth, it.y + it.view.measuredHeight) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if ((parent as? ViewGroup)?.indexOfChild(this).let { it != null && it > 0 }) {
            canvas.drawLine(0f, 0f, width.toFloat(), 0f, rulePaint)
        }
    }
}
