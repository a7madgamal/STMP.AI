package com.mido.phonebridge.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * A scrollable body with a real, non-overlay cue when its final content is off-screen.
 *
 * The cue is a sibling below the viewport, not a badge painted over the last line. The scroll
 * view fades its bottom edge only while overflow exists; the compact text/arrow always names
 * the same fact for sighted users and accessibility services. A viewport shorter than its
 * content therefore announces itself rather than looking like a complete page.
 */
class OverflowScrollView @JvmOverloads constructor(
    context: Context,
    private val guidance: String = "More below ↓",
) : LinearLayout(context) {

    val scrollView: ScrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        ViewCompat.setNestedScrollingEnabled(this, true)
    }

    private val cue: TextView = Theme.text(context, guidance, Theme.TEXT_CAPTION_SP).apply {
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, Theme.dp(context, 6), 0, Theme.dp(context, 4))
        contentDescription = guidance.replace('↓', ' ')
    }

    private var offBottom = false

    init {
        orientation = VERTICAL
        addView(scrollView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(cue, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateCue() }
        scrollView.viewTreeObserver.addOnGlobalLayoutListener { updateCue() }
        scrollView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCue() }
        updateCue()
    }

    private fun updateCue() {
        val next = scrollView.canScrollVertically(1)
        cue.visibility = if (next) View.VISIBLE else View.GONE
        scrollView.isVerticalFadingEdgeEnabled = next
        scrollView.setFadingEdgeLength(if (next) Theme.dp(context, 18) else 0)
        if (next == offBottom) return
        offBottom = next
        announceForAccessibility(if (next) guidance.replace('↓', ' ') else "End of content")
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateCue()
    }

    /** Add exactly one content root; Android's ScrollView owns no more than one child. */
    fun setContent(content: View) {
        require(scrollView.childCount == 0) { "OverflowScrollView content was set twice" }
        scrollView.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        scrollView.post { updateCue() }
    }

    fun offBottomForTest(): Boolean = offBottom
}
