package com.mido.phonebridge.ui.kit

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * Tapjacking guard (COMPONENTS 24, D17). Touches in a gesture that another window fully obscured
 * never reach the children. Partial cover is allowed on purpose (D60).
 *
 * A cover can also appear in the middle of a gesture that started clear. Dropping that gesture's
 * remaining events would leave the child believing the finger is still down (a hold key would
 * then complete on its own timer), so the first obscured event of a live clear gesture sends the
 * children an ACTION_CANCEL before it is dropped.
 */
class ObscuredTouchGuard(ctx: Context) : FrameLayout(ctx) {
    var onObscuredTouch: (() -> Unit)? = null
    var onClearTouch: (() -> Unit)? = null

    /** A clear DOWN reached the children and its gesture has not ended yet. */
    private var isClearGestureLive = false
    private var hasReportedThisGesture = false
    private var isSendingCancel = false

    init {
        filterTouchesWhenObscured = true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) hasReportedThisGesture = false
        if (isObscured(event) && isClearGestureLive) cancelChildren(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
        if (isSendingCancel) return true
        val action = event.actionMasked
        if (isObscured(event)) {
            if (!hasReportedThisGesture) {
                hasReportedThisGesture = true
                onObscuredTouch?.invoke()
            }
            return false
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> { isClearGestureLive = true; onClearTouch?.invoke() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isClearGestureLive = false
        }
        return super.onFilterTouchEventForSecurity(event)
    }

    /** End the children's gesture: the rest of it is obscured and will be dropped. */
    private fun cancelChildren(event: MotionEvent) {
        isClearGestureLive = false
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        isSendingCancel = true
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            isSendingCancel = false
            cancel.recycle()
        }
    }

    companion object {
        fun isObscured(ev: MotionEvent): Boolean = (ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0

        const val BANNER_TEXT = "Another app is covering this screen — close it to continue."

        /** The warning shown while a cover is detected; the host toggles its visibility. */
        fun banner(ctx: Context): View {
            val p = Theme.palette(ctx)
            return Panel.create(ctx, Panel.Tone.CAUTION).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Led(ctx).apply { state = Led.State.CAUTION }, LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)))
                addView(
                    Type.text(ctx, BANNER_TEXT, TypeRole.BODY_STRONG, p.cautionText),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = ctx.dp(Dimens.SPACE_S) },
                )
                visibility = View.GONE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        }
    }
}
