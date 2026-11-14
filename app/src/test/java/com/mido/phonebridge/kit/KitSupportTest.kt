package com.mido.phonebridge.kit

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mido.phonebridge.debug.KitHostActivity
import com.mido.phonebridge.ui.tokens.Haptics
import org.junit.After
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Shared pieces of the component behaviour tests (TESTING 6.5). */
abstract class KitTest {

    protected val haptics = ArrayList<Int>()

    protected fun host(): KitHostActivity {
        Haptics.sink = { _, constant -> haptics.add(constant) }
        return Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
    }

    @After
    fun clearSink() { Haptics.sink = null }

    /** Advance the main looper's clock by [ms] and run everything that comes due. */
    protected fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Adds [view] to the host column at a fixed width and lets the framework lay it out. */
    protected fun <T : View> KitHostActivity.place(view: T, widthDp: Int = 371, heightDp: Int? = null): T {
        val d = resources.displayMetrics.density
        val h = if (heightDp == null) ViewGroup.LayoutParams.WRAP_CONTENT else (heightDp * d).toInt()
        column.addView(view, LinearLayout.LayoutParams((widthDp * d).toInt(), h))
        idle()
        return view
    }

    protected fun touch(view: View, action: Int, x: Float = 5f, y: Float = 5f, downTime: Long = SystemClock.uptimeMillis(), flags: Int = 0): Boolean {
        // `MotionEvent.setFlags` is not available under Robolectric 4.14.1 at sdk 33 (TESTING 6.5
        // "Verify first"), so the flags travel in the public full-argument `obtain` overload.
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags,
        )
        val handled = view.dispatchTouchEvent(ev)
        ev.recycle()
        return handled
    }
}

fun View.allViews(): List<View> =
    listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).allViews() } } ?: emptyList())
