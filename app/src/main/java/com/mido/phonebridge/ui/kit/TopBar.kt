package com.mido.phonebridge.ui.kit

import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mido.phonebridge.R
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** The one top bar of every non-tab screen (COMPONENTS 10, D22). */
object TopBar {
    fun create(activity: ComponentActivity, title: String, action: View? = null): LinearLayout {
        val ctx = activity
        val p = Theme.palette(ctx)
        activity.title = title

        val back = FrameLayout(ctx).apply {
            val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.ink) }
            background = RippleDrawable(rippleColor(ctx), null, mask).also { it.radius = ctx.dp(20) }
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            installFocusRing(this, 24)
            setRole(this, "android.widget.Button")
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_kit_back)
                setColorFilter(p.ink, PorterDuff.Mode.SRC_IN)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ctx.dp(Dimens.ICON), ctx.dp(Dimens.ICON), Gravity.CENTER))
            setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        }
        val titleView = Type.text(ctx, title, TypeRole.TITLE, p.ink).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            ViewCompat.setAccessibilityHeading(this, true)
        }

        val line = GradientDrawable().apply { setColor(p.hairline) }
        val fill = GradientDrawable().apply { setColor(p.body) }
        val bg = LayerDrawable(arrayOf(fill, line)).apply {
            setLayerHeight(1, ctx.dp(1))
            setLayerGravity(1, Gravity.BOTTOM)
        }
        val baseTop = 0
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(back, LinearLayout.LayoutParams(ctx.dp(Dimens.MIN_TARGET), ctx.dp(Dimens.MIN_TARGET)).apply {
                marginStart = ctx.dp(4)
            })
            addView(titleView, LinearLayout.LayoutParams(0, ctx.dp(Dimens.TOP_BAR_H), 1f).apply {
                marginStart = ctx.dp(4)
            })
            if (action != null) {
                addView(action, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = ctx.dp(Dimens.SPACE_S) })
            }
            minimumHeight = ctx.dp(Dimens.TOP_BAR_H)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, baseTop + bars.top, v.paddingRight, v.paddingBottom)
                insets
            }
        }
    }
}
