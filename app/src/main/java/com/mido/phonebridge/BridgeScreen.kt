package com.mido.phonebridge

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.mido.phonebridge.ui.Theme

/**
 * Every top-level screen: a scrolling body with the bottom bar pinned under it.
 *
 * **Why a base class rather than three copies.** Three things must happen on EVERY screen, and
 * each one is a bug when a screen forgets it:
 *
 *  1. **System-bar insets.** Without them the first row renders under the status bar and the last
 *     one under the gesture bar. Applied to the BODY, never the root, so the bar can extend behind
 *     the navigation bar while content stays clear of it.
 *  2. **The bottom bar itself.** A screen that draws its own would drift in height and spacing.
 *  3. **`ApprovalGate.relaunchCurrent` in `onResume`.** This is the one that matters. An approval
 *     modal lives in its own task, so returning to the app lands on whichever screen was open.
 *     Before this class existed the call was in `MainActivity.onResume` alone — so sitting on any
 *     other screen while a request was pending left the prompt stranded off-screen until its
 *     window expired. Putting it here means a new screen cannot reintroduce that.
 *
 * **The bottom bar is a sibling of the body, not an overlay.** An overlay would need the body to
 * reserve space manually, and a screen that got the reservation wrong would have its last row
 * permanently hidden under the bar.
 */
abstract class BridgeScreen : AppCompatActivity(), ScreenHost {

    /** Which tab this screen is. Subclasses state it once; the bar cannot disagree. */
    protected abstract val tab: Theme.Tab

    /** Build the screen's scrolling content here. Called once, from `onCreate`. */
    protected abstract fun buildContent(container: LinearLayout)

    /**
     * Whether the base class should wrap [buildContent] in its own scroll view.
     *
     * True for a screen whose content is a plain column. False for one that already owns a scroll
     * view — Home does, because it must repaint its rows without rebuilding the scroll position,
     * and wrapping that in a second `ScrollView` would nest scrolling and break the drag.
     */
    protected open val ownsScrolling: Boolean get() = false

    /**
     * The body, exposed so a screen can keep a reference for its own repaints.
     *
     * Deliberately the CONTAINER rather than the whole screen: a screen that needs to repaint has
     * no business touching the bar, and rebuilding the bar under a press is exactly the kind of
     * tap-eating defect this codebase has already paid for.
     */
    protected lateinit var body: LinearLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.sheet(Theme.BG)
        }

        val center: View
        if (ownsScrolling) {
            // The subclass supplies the whole scrolling area; this class only allocates the
            // container it must fill so `body` is always valid.
            body = LinearLayout(this)
            center = body
        } else {
            val scroll = ScrollView(this).apply { isFillViewport = true }
            body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    Theme.dp(this@BridgeScreen, 20),
                    Theme.dp(this@BridgeScreen, 20),
                    Theme.dp(this@BridgeScreen, 20),
                    Theme.dp(this@BridgeScreen, 24),
                )
            }
            scroll.addView(body)
            center = scroll
        }

        // The body takes the remaining height and the bar sits under it at its natural size, so
        // a short screen still has its bar at the bottom rather than floating under the content.
        root.addView(center, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        // A non-tab screen (the plugin detail) still draws the bar, and tapping an unselected
        // tab must leave it for that tab's screen. It does so by asking the SHELL, not by
        // starting an Activity directly -- once the tabs are fragments there is no Activity per
        // tab to start.
        val bar = Theme.bottomNav(this, tab) { selected -> openTab(selected) }
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // Insets split by owner, which is the whole reason this is not `Theme.inset`:
        //
        //   TOP on the root   — the first row must clear the status bar.
        //   BOTTOM on the BAR — the bar must clear the gesture bar, and its background should
        //                       extend behind it so there is no unpainted strip.
        //
        // Putting both on one view is what `Theme.inset` does, and on this layout it would pad
        // the body's bottom for a bar that is already below it, leaving a dead gap.
        TopInset.apply(root)

        setContentView(root)

        buildContent(body)
    }

    /** Leave a non-tab screen for the tab that was tapped. */
    private fun openTab(tab: Theme.Tab) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(Tabs.EXTRA_TAB, tab.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    override fun onResume() {
        super.onResume()
        // See the class comment: this is the reason the base class exists.
        ApprovalGate.relaunchCurrent(this)
    }

    // --- ScreenHost -------------------------------------------------------------------------
    //
    // Straight delegations, so adopting the interface changes no behaviour. A screen that calls
    // `go(...)` gets exactly `startActivity(...)`. The point is not the indirection itself but
    // that the SCREEN no longer names an Activity API, so the host can later be something else.

    override fun go(intent: Intent) = startActivity(intent)

    override fun toast(message: String, duration: Int) =
        Toast.makeText(this, message, duration).show()

    override fun ui(block: () -> Unit) = runOnUiThread(block)

    override fun confirm(
        title: String,
        message: String,
        positive: String,
        negative: String,
        onNegative: (() -> Unit)?,
        onPositive: () -> Unit,
    ) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .show()
    }

    override fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun askPermission(permission: String, requestCode: Int) =
        requestPermissions(arrayOf(permission), requestCode)
}

/**
 * Top inset only, added to whatever padding the view already has.
 *
 * Split from [BottomInset] because the two are applied to DIFFERENT views on a screen that has a
 * bottom bar: the top padding belongs on the root, the bottom clearances on the scrolling body.
 * One combined helper (Theme.inset) can only pad one view with both, which double-pads exactly
 * where the bar is.
 */
object TopInset {
    fun apply(root: View) {
        val base = root.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, base + bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
    }
}

/** Bottom inset only — used on the nav bar so it sits above the gesture bar. */
object BottomInset {
    fun apply(view: View) {
        val base = view.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + bars.bottom)
            insets
        }
    }
}
