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
 */
class ObscuredTouchGuard(ctx: Context) : FrameLayout(ctx) {
    var onObscuredTouch: (() -> Unit)? = null
    var onClearTouch: (() -> Unit)? = null

    init {
        filterTouchesWhenObscured = true
    }

    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
        if (isObscured(event)) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onObscuredTouch?.invoke()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onClearTouch?.invoke()
        return super.onFilterTouchEventForSecurity(event)
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
