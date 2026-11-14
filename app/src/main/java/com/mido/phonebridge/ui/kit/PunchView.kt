package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.annotation.VisibleForTesting
import com.mido.phonebridge.alert.SoundAlerts
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Colors
import com.mido.phonebridge.ui.tokens.FontFace
import com.mido.phonebridge.ui.tokens.Motion
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The verdict punch (COMPONENTS 21), replacing the old stamp drawing. Everything is a function of
 * the clock since [stamp], drawn in `onDraw`, so it also works in an overlay window (TOKENS 9.2).
 */
class PunchView(context: Context, private val scrimAlpha: Int = 0x66) : View(context) {

    enum class Verdict(val word: String) { APPROVED("APPROVED"), DENIED("DENIED") }

    var onFinished: (() -> Unit)? = null

    /** Optional second line, e.g. "No. 413 - 14:03:27". Empty draws nothing. */
    var meta: String = ""

    /** When true the punch stays drawn after settling and [onFinished] fires at the settle time. */
    var holdAfterSettle: Boolean = false

    /** The view that jolts on impact. */
    var joltTarget: View? = null

    /** Plays the stamp thud. Tests replace it. */
    @VisibleForTesting
    internal var soundHook: (Context) -> Unit = { SoundAlerts.stamp(it) }

    private var verdict: Verdict? = null
    private var startAt = -1L
    private var armed = false
    private var soundDone = false
    private var finished = false
    private val reduced get() = Motion.reduced(context)

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val word = typePaint(context, TypeRole.PUNCH).apply { textAlign = Paint.Align.CENTER }
    private val metaPaint = typePaint(context, TypeRole.LABEL, FontFace.MONO_600)
        .apply { textAlign = Paint.Align.CENTER }
    private val frame = RectF()

    private val impact = Runnable { impactNow() }
    private val end = Runnable { finish() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun stamp(verdict: Verdict) {
        this.verdict = verdict
        armed = true
        soundDone = false
        finished = false
        startAt = -1L
        if (width > 0) begin()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (armed && startAt < 0 && w > 0) begin()
    }

    private fun begin() {
        startAt = SystemClock.uptimeMillis()
        removeCallbacks(impact)
        removeCallbacks(end)
        val settled = Motion.PUNCH_SETTLED_MS
        if (reduced) {
            impactNow()
            postDelayed(end, settled)
        } else {
            postDelayed(impact, Motion.PUNCH_DROP_MS)
            postDelayed(end, if (holdAfterSettle) settled else settled + Motion.PUNCH_FADE_MS)
        }
        invalidate()
    }

    private fun impactNow() {
        if (soundDone) return
        soundDone = true
        try {
            soundHook(context)
        } catch (e: SoundAlerts.SoundUnavailable) {
            Log.e("PunchView", "stamp sound unavailable: ${e.message}", e)
        }
        verdict?.let { announceForAccessibility(it.word) }
    }

    private fun finish() {
        if (finished) return
        finished = true
        joltTarget?.translationY = 0f
        onFinished?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(impact)
        removeCallbacks(end)
        joltTarget?.translationY = 0f
    }

    /** Elapsed ms since the punch began, or -1 before it has a size. */
    private fun elapsed(): Long = if (startAt < 0) -1L else SystemClock.uptimeMillis() - startAt

    /** Jolt offset in dp at [t] ms after the start (COMPONENTS 21.3). */
    internal fun joltDp(t: Long): Float {
        val s = t - Motion.PUNCH_DROP_MS
        if (reduced || s < 0 || s >= Motion.PUNCH_JOLT_MS) return 0f
        val f = s.toFloat() / Motion.PUNCH_JOLT_MS
        return when {
            f < 0.35f -> 3f * (f / 0.35f)
            f < 0.65f -> 3f + (-1f - 3f) * ((f - 0.35f) / 0.30f)
            else -> -1f * (1f - (f - 0.65f) / 0.35f)
        }
    }

    private companion object {
        const val MIN_WIDTH_DP = 220f
        const val FRAME_INSET_DP = 6f
        const val TEXT_PAD_DP = 12f
        const val SCREEN_MARGIN_DP = 12f
        const val TOP_PAD_DP = 14f
        const val BOTTOM_PAD_DP = 12f
        const val META_GAP_DP = 8f
    }

    override fun onDraw(canvas: Canvas) {
        val v = verdict ?: return
        val t = elapsed()
        if (t < 0) return
        val p = Theme.palette(context)
        val settled = Motion.PUNCH_SETTLED_MS
        val fade = if (holdAfterSettle || reduced || t < settled) 1f
        else (1f - (t - settled).toFloat() / Motion.PUNCH_FADE_MS).coerceIn(0f, 1f)

        // Scrim
        scrim.color = Colors.withAlpha(p.scrim, (scrimAlpha * fade).toInt())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)

        val drop = Motion.PUNCH_DROP_MS
        val translate: Float
        val scale: Float
        val alpha: Float
        if (reduced) {
            translate = 0f; scale = 1f; alpha = 1f
        } else if (t < drop) {
            val k = Motion.EASE_ACCELERATE.getInterpolation(t.toFloat() / drop)
            translate = -context.dpf(70f) * (1f - k)
            scale = 1.35f + (0.95f - 1.35f) * k
            alpha = (t / 90f).coerceIn(0f, 1f)
        } else {
            translate = 0f
            scale = Motion.springAt(
                (t - drop) / 1000f, 0.95f, 1f, Motion.SPRING_PUNCH_STIFFNESS, Motion.SPRING_PUNCH_DAMPING,
            )
            alpha = 1f
        }
        joltTarget?.translationY = context.dpf(joltDp(t))

        val color = if (v == Verdict.APPROVED) p.okGlyph else p.ink
        val cx = width / 2f
        val cy = height * 0.38f + translate
        val hasMeta = meta.isNotEmpty()
        // The stamp is sized from the text it has to hold. At large font scales "APPROVED" and the
        // entry line are wider than the old fixed 220 dp frame and ran over its border (R1). The
        // frame grows with the text; past the screen width the whole stamp scales down to fit.
        val edge = context.dpf(FRAME_INSET_DP)
        val pad = context.dpf(TEXT_PAD_DP)
        val textW = maxOf(word.measureText(v.word), if (hasMeta) metaPaint.measureText(meta) else 0f)
        val w = maxOf(context.dpf(MIN_WIDTH_DP), textW + 2 * (edge + pad))
        val fit = minOf(1f, (width - 2 * context.dpf(SCREEN_MARGIN_DP)) / w)
        val wordH = word.descent() - word.ascent()
        val metaH = if (hasMeta) metaPaint.descent() - metaPaint.ascent() else 0f
        val topPad = context.dpf(TOP_PAD_DP)
        val gap = if (hasMeta) context.dpf(META_GAP_DP) else 0f
        val h = 2 * edge + topPad + wordH + gap + metaH + context.dpf(BOTTOM_PAD_DP)
        canvas.save()
        canvas.rotate(-4f, cx, cy)
        canvas.scale(scale * fit, scale * fit, cx, cy)
        frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val a = (alpha * fade * 255).toInt()
        fill.color = Colors.withAlpha(p.panel, (0xEB * alpha * fade).toInt())
        canvas.drawRoundRect(frame, context.dpf(12f), context.dpf(12f), fill)
        stroke.color = Colors.withAlpha(color, a)
        stroke.strokeWidth = context.dpf(3f)
        canvas.drawRoundRect(frame, context.dpf(12f), context.dpf(12f), stroke)
        frame.inset(edge, edge)
        stroke.strokeWidth = context.dpf(1.5f)
        canvas.drawRoundRect(frame, context.dpf(7f), context.dpf(7f), stroke)
        word.color = Colors.withAlpha(color, a)
        val wordBase = frame.top + topPad - word.ascent()
        canvas.drawText(v.word, cx, wordBase, word)
        if (hasMeta) {
            metaPaint.color = Colors.withAlpha(color, a)
            canvas.drawText(meta, cx, wordBase + word.descent() + gap - metaPaint.ascent(), metaPaint)
        }
        canvas.restore()
        if (!finished) postInvalidateOnAnimation()
    }
}
