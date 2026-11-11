package com.mido.phonebridge.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.HapticEvent
import com.mido.phonebridge.ui.tokens.Haptics
import com.mido.phonebridge.ui.tokens.Motion
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The approve key for requests that execute or delete (COMPONENTS 9, D12, D40): hold for
 * [Motion.HOLD_TO_APPROVE_MS]. A plain double-tap in TalkBack does nothing; the "Approve"
 * action performs the completed hold.
 */
class HoldKey(ctx: Context) : View(ctx) {

    var armed: Boolean = true
        set(value) { field = value; if (!value) abortHold(); applyArmed(); refreshDescription() }

    /** Only used for the description while unarmed. */
    var secondsUntilArmed: Int = 0
        set(value) { field = value; refreshDescription() }

    var onHeld: (() -> Unit)? = null
    var onCancelled: (() -> Unit)? = null

    /** 0..1 progress right now (clock-driven while holding). */
    val progress: Float get() = currentProgress(SystemClock.uptimeMillis())

    private var holdStart = -1L
    private var rest = 0f
    private var completed = false
    private var pressed = false
    private var returning: SpringAnimation? = null
    private val ticks = ArrayList<Runnable>()
    private val confirm = Runnable { complete() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(Dimens.HOLD_RING_STROKE.toFloat())
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = typePaint(ctx, TypeRole.KEY).apply { textAlign = Paint.Align.CENTER }
    private val arc = RectF()

    private val restProperty = object : FloatPropertyCompat<HoldKey>("rest") {
        override fun getValue(obj: HoldKey) = obj.rest
        override fun setValue(obj: HoldKey, value: Float) { obj.rest = value; obj.invalidate() }
    }

    init {
        isFocusable = true
        isClickable = false
        isLongClickable = false
        refreshDescription()
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.roleDescription = "Hold button"
                info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
                info.isClickable = false
                if (armed && !completed) {
                    info.isLongClickable = true
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, "Approve",
                        ),
                    )
                }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
                    if (!armed || completed) return false
                    complete()
                    return true
                }
                if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) return false
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    private fun refreshDescription() {
        contentDescription = "Approve. Hold for 1.2 seconds, or use the Approve action." +
            if (!armed && secondsUntilArmed > 0) ", available in $secondsUntilArmed seconds" else ""
    }

    private fun applyArmed() {
        alpha = if (armed) 1f else 0.35f
        val filter = if (armed) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        if (filter == null) setLayerType(LAYER_TYPE_NONE, null)
        else setLayerType(LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = filter })
    }

    private fun currentProgress(now: Long): Float {
        if (completed) return 1f
        if (holdStart >= 0) return ((now - holdStart).toFloat() / Motion.HOLD_TO_APPROVE_MS).coerceIn(0f, 1f)
        return rest
    }

    /** Return to idle so the key can be held again (after a cancelled or declined approval). */
    fun reset() {
        cancelTimers()
        returning?.cancel()
        holdStart = -1L
        rest = 0f
        completed = false
        pressed = false
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) abortHold()
    }

    /** Drop a hold in progress without announcing a release: the key was disarmed or disabled. */
    private fun abortHold() {
        cancelTimers()
        returning?.cancel()
        if (completed) return
        holdStart = -1L
        pressed = false
        rest = 0f
        invalidate()
    }

    private fun beginHold() {
        if (!armed || !isEnabled || completed || holdStart >= 0) return
        returning?.cancel()
        holdStart = SystemClock.uptimeMillis()
        pressed = true
        for (i in 1..3) {
            val r = Runnable { Haptics.perform(this, HapticEvent.HOLD_TICK) }
            ticks.add(r)
            postDelayed(r, Motion.HOLD_TICK_EVERY_MS * i)
        }
        postDelayed(confirm, Motion.HOLD_TO_APPROVE_MS)
        invalidate()
    }

    private fun cancelTimers() {
        ticks.forEach { removeCallbacks(it) }
        ticks.clear()
        removeCallbacks(confirm)
    }

    private fun cancelHold() {
        if (holdStart < 0 || completed) return
        cancelTimers()
        val from = currentProgress(SystemClock.uptimeMillis())
        holdStart = -1L
        pressed = false
        rest = from
        Haptics.perform(this, HapticEvent.HOLD_CANCEL)
        announceForAccessibility("Released early. Not approved.")
        if (Motion.reduced(context) || !isAttachedToWindow) {
            rest = 0f
        } else {
            returning = SpringAnimation(this, restProperty, 0f).apply {
                spring = SpringForce(0f).apply {
                    stiffness = Motion.SPRING_HOLD_RETURN_STIFFNESS
                    dampingRatio = Motion.SPRING_HOLD_RETURN_DAMPING
                }
                setMinimumVisibleChange(0.002f)
                start()
            }
        }
        invalidate()
        onCancelled?.invoke()
    }

    private fun complete() {
        if (completed || !armed || !isEnabled) return
        cancelTimers()
        completed = true
        holdStart = -1L
        pressed = false
        Haptics.perform(this, HapticEvent.HOLD_CONFIRM)
        invalidate()
        onHeld?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelHold()
            return true
        }
        if (!isEnabled || !armed || completed) return true
        when (action) {
            MotionEvent.ACTION_DOWN -> beginHold()
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration_slop()
                if (event.x < -slop || event.y < -slop || event.x > width + slop || event.y > height + slop) cancelHold()
            }
        }
        return true
    }

    private fun ViewConfiguration_slop(): Int = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isActivationKey(keyCode)) {
            if (event.repeatCount == 0) beginHold()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isActivationKey(keyCode)) { cancelHold(); return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun isActivationKey(code: Int) = code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(Dimens.HOLD_KEY)
        setMeasuredDimension(s, s)
    }

    /** The window lost focus (shade, dialog, another app): a hold in progress is released. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelHold()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTimers()
        returning?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette(context)
        val cx = width / 2f
        val cy = height / 2f
        val stroke = ring.strokeWidth
        val radius = (width - stroke) / 2f
        ring.color = p.holdTrack
        canvas.drawCircle(cx, cy, radius, ring)
        val prog = currentProgress(SystemClock.uptimeMillis())
        if (prog > 0f) {
            ring.color = p.okGlyph
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(arc, -90f, 360f * prog, false, ring)
        }
        // Disc: lip, highlight, face. Reduced motion skips the press offset.
        val down = if (pressed && !Motion.reduced(context)) context.dpf(2f) else 0f
        val scale = if (pressed && !Motion.reduced(context)) 0.97f else 1f
        val disc = context.dpf(Dimens.HOLD_DISC.toFloat()) / 2f * scale
        canvas.save()
        canvas.translate(0f, down)
        fill.color = p.keyLipInk
        canvas.drawCircle(cx, cy + context.dpf(3f) - down / 2, disc, fill)
        fill.color = p.inkHighlight
        canvas.drawCircle(cx, cy, disc, fill)
        fill.color = p.ink
        canvas.drawCircle(cx, cy + context.dpf(1f), disc - context.dpf(0.5f), fill)
        text.color = p.onInk
        val base = cy - (text.ascent() + text.descent()) / 2f
        canvas.drawText("HOLD", cx, base, text)
        canvas.restore()
        if (holdStart >= 0 && !completed) postInvalidateOnAnimation()
    }
}
