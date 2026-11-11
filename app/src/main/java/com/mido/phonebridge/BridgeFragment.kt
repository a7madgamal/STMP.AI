package com.mido.phonebridge

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mido.phonebridge.ui.OverflowScrollView
import com.mido.phonebridge.ui.Theme

/**
 * Every top-level screen, as a fragment.
 *
 * **Why a fragment and not an Activity.** A tab switch used to be a cross-activity transition, so
 * the window — bar included — was animated off and back on. The bar could not be kept still from
 * inside the window, because the window is what moved. As a fragment the content swaps inside one
 * window and the bar is never recreated.
 *
 * **What this class still guarantees**, unchanged from the `BridgeScreen` it replaces, because
 * each of these is a bug when a screen forgets it:
 *
 *  1. **System-bar insets**, split so the top clears the status bar and the bottom clears the
 *     gesture bar under the bar.
 *  2. **The bottom bar** is drawn by the shell, once, not by each screen.
 *  3. **`ApprovalGate.relaunchCurrent`** on resume — a modal lives in its own task, so returning
 *     to the app must re-show a prompt that would otherwise stay stranded off-screen.
 *
 * **`ctx` exists because a fragment is not a Context.** Every screen built its views with `this`,
 * which was true when `this` was an Activity. `requireContext()` is the replacement, and it fails
 * loudly if the fragment is detached rather than handing back a stale reference.
 */
abstract class BridgeFragment : Fragment(), ScreenHost {

    /** Which tab this screen is. Subclasses state it once; the bar cannot disagree. */
    protected abstract val tab: Theme.Tab

    /** Build the screen's scrolling content here. Called once, from `onCreateView`. */
    protected abstract fun buildContent(container: LinearLayout)

    /**
     * True for a screen whose content is a plain column; false for one that already owns a
     * scroll view. Home owns its own, because it repaints rows without rebuilding scroll position
     * and nesting two `ScrollView`s would break the drag.
     */
    protected open val ownsScrolling: Boolean get() = false

    /**
     * The body, exposed for repaints.
     *
     * Deliberately the CONTAINER rather than the whole screen: a screen that repaints has no
     * business touching the bar, and rebuilding the bar under a press is the tap-eating defect
     * this codebase has already paid for twice.
     */
    protected lateinit var body: LinearLayout

    /**
     * The activity as a Context.
     *
     * `requireContext()` rather than a cached field: caching survives detach and would let a
     * screen build views against a dead activity — the leak that outlives the screen.
     */
    protected val ctx get() = requireContext()

    /**
     * The hosting activity, for the few calls that genuinely need an Activity rather than a
     * Context — `setClass`, `startService`. Narrow on purpose: most code wants [ctx].
     *
     * Named `hostActivity` because `Fragment` already declares `getActivity()`; a property called
     * `activity` would be an accidental override rather than a compile error, which is the worse
     * of the two failures.
     */
    protected val hostActivity get() = requireActivity()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val center: View
        if (ownsScrolling) {
            body = LinearLayout(ctx)
            center = body
        } else {
            val scroll = OverflowScrollView(ctx)
            body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    Theme.dp(ctx, 20),
                    Theme.dp(ctx, 20),
                    Theme.dp(ctx, 20),
                    Theme.dp(ctx, 24),
                )
            }
            scroll.setContent(body)
            center = scroll
        }

        root.addView(center, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        buildContent(body)
        return root
    }

    /**
     * Ask the visible screen to repaint itself.
     *
     * Needed because the shell can trigger a state change the screen cannot observe — unlocking
     * the vault happens in a dialog owned by the Activity, and the screen that shows the lock
     * state is a fragment that was not told. Default is a no-op: a screen with nothing to redraw
     * should not be forced to write an empty override.
     */
    open fun repaint() { }

    override fun onResume() {
        super.onResume()
        // See the class comment: an approval prompt must not stay stranded off-screen.
        ApprovalGate.relaunchCurrent(requireContext())
    }

    // --- ScreenHost ---------------------------------------------------------------------------

    override fun go(intent: Intent) = startActivity(intent)

    override fun toast(message: String, duration: Int) =
        Toast.makeText(ctx, message, duration).show()

    override fun ui(block: () -> Unit) = requireActivity().runOnUiThread(block)

    override fun confirm(
        title: String,
        message: String,
        positive: String,
        negative: String,
        onNegative: (() -> Unit)?,
        onPositive: () -> Unit,
    ) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(negative) { _, _ -> onNegative?.invoke() }
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .show()
    }

    override fun hasPermission(permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Ask for a runtime permission.
     *
     * **On the `DEPRECATION` suppression.** `Fragment.requestPermissions` is deprecated in favour
     * of `registerForActivityResult`, which returns a launcher that must be created during
     * initialisation rather than on demand. This screen asks at the moment of a tap — "start the
     * server" — so a launcher field would have to be declared on every screen that might ask,
     * including ones that never do.
     *
     * The deprecated call still WORKS and still routes the result to the fragment's own
     * `onRequestPermissionsResult`, which is where Home already handles `REQ_POST_NOTIF`.
     * Switching to the Activity Result API is a change to that flow, not to this interface, so it
     * does not belong in a host-swap commit.
     */
    @Suppress("DEPRECATION")
    override fun askPermission(permission: String, requestCode: Int) =
        requestPermissions(arrayOf(permission), requestCode)
}
