package com.mido.phonebridge

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.ui.Theme

/**
 * The app shell: one Activity, one bar, three fragments.
 *
 * **Why one Activity.** Each tab used to be its own Activity, so a tab switch was a *window*
 * transition — and a window transition carries the whole window, bar included, off and back on.
 * There was no way to keep the bar still from inside the window, because the window is what
 * animates. The two alternatives were worse: an overlay window needs `SYSTEM_ALERT_WINDOW` and
 * fails into "no tabs at all" when revoked, and hand-animating the body still leaves the bar in
 * the animated window.
 *
 * With one Activity there is no tab-switch transition to fight. The bar is a sibling of the
 * fragment container, created once, and the content swaps underneath it.
 *
 * **What belongs HERE rather than in a fragment.** Anything that is a fact about the APP, not
 * about a screen: OAuth config, the debug sink, opening the audit log, the once-per-process
 * auto-start guard, and the vault prompt. These used to live in the Home screen's `onCreate`,
 * which was correct when one screen meant one process. A fragment is created on every tab
 * switch, so leaving them there would run them again — and a manual STOP would be undone by
 * visiting the Plugins tab.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var barHost: LinearLayout

    /**
     * The visible tab, as a field rather than re-derived at save time.
     *
     * `findFragmentById` can return null while state is being saved, and "null means Home" would
     * restore the wrong tab after a rotation. A field cannot be null when a tab was shown.
     */
    private var currentTab: Theme.Tab = Theme.Tab.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appStartup()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.sheet(Theme.BG)
        }

        // The body takes the remaining height and the bar sits under it at its natural size, so
        // a short screen still has its bar at the bottom rather than floating under the content.
        container = FrameLayout(this).apply { id = CONTAINER_ID }
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // A stable host for the bar, so a tab change replaces one child instead of re-finding
        // the root and guessing which child the bar is.
        barHost = LinearLayout(this)
        root.addView(barHost)

        // Insets split by owner: top on the root so the first row clears the status bar, bottom
        // on the bar so its paper extends behind the gesture bar instead of leaving a strip.
        TopInset.apply(root)
        BottomInset.apply(barHost)

        setContentView(root)

        // Restore across a config change or process death, then honour a tab requested by a
        // caller (a notification tap, or a non-tab screen asking to leave).
        val wanted = savedInstanceState?.getString(STATE_TAB)
            ?: intent?.getStringExtra(Tabs.EXTRA_TAB)
        show(wanted?.let(::tabNamed) ?: Theme.Tab.HOME)
    }

    /**
     * Everything that is true ONCE per process.
     *
     * `autoStartAttempted` lives here, not on Home: Home is recreated on every tab switch, so a
     * guard there would let a deliberate STOP be undone by visiting Plugins and coming back.
     */
    private fun appStartup() {
        AuthManager.init(
            applicationContext,
            BridgeService.GOOGLE_CLIENT_ID,
            BridgeService.GOOGLE_SERVER_CLIENT_ID,
            BridgeService.GOOGLE_CLIENT_SECRET,
        )
        Debug.log("config loaded; clientId len=${AuthManager.clientId.length}")

        Debug.init(applicationContext)
        Debug.log("=== STMP ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) ===")
        Debug.log("app launched")

        // Open the audit log now rather than on first request: a store that cannot be created is
        // worth surfacing before anything needs to record into it. Idempotent.
        try {
            val store = AuditLog.from(applicationContext)
            Debug.log("audit log ready; ${store.headers().size} record(s) indexed")
        } catch (e: Exception) {
            AuditHealth.markDegraded("audit store unavailable: ${e.message}")
            Debug.log("AUDIT STORE FAILED: ${e.message}")
        }

        if (!autoStartAttempted && !BridgeService.serverUp) {
            autoStartAttempted = true
            maybeAutoStart()
        }

        // The dialog belongs to this Activity, but the lock state is drawn by a fragment that
        // was never told. Ask the visible one to redraw rather than relying on the 5s tick to
        // notice -- the tick would work, five seconds later, which reads as a dead toggle.
        VaultUnlock.ensureUnlocked(this) { visibleFragment()?.repaint() }
    }

    private fun maybeAutoStart() {
        val enabled = try {
            com.mido.phonebridge.policy.PolicyStore.from(applicationContext).get().autoStartOnBoot
        } catch (e: Exception) {
            // An unreadable policy must neither start nor skip silently: Settings reports it, and
            // guessing here would contradict what that screen says.
            Debug.log("auto-start skipped: policy unreadable (${e.message})")
            return
        }
        if (!enabled) {
            Debug.log("auto-start off; server stays down until START is tapped")
            return
        }
        Debug.log("auto-start: bringing the server up on app load")
        startService(Intent(this, BridgeService::class.java))
    }

    /**
     * Swap the visible fragment.
     *
     * `replace`, not `add`/`hide`. `hide` leaves a fragment `RESUMED`, so Home's 5s repaint tick
     * would keep firing while Plugins is on screen — an invisible timer rebuilding a screen
     * nobody is looking at, which is the same family of bug that ate taps for three releases.
     * Losing scroll position is the cheaper cost, and it is visible rather than silent.
     */
    private fun show(tab: Theme.Tab) {
        if (supportFragmentManager.isStateSaved) {
            // Committing after state is saved throws. This only happens while backgrounded, so
            // onCreate will restore the right tab when the Activity comes back.
            Debug.log("tab switch to $tab ignored: state already saved")
            return
        }
        currentTab = tab
        val fragment: Fragment = when (tab) {
            Theme.Tab.HOME -> HomeFragment()
            Theme.Tab.PLUGINS -> PluginsFragment()
            Theme.Tab.SETTINGS -> OptionsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, fragment)
            // No transition: the point of this whole change is that switching tabs does not
            // animate. A default cross-fade here would reintroduce exactly the movement the
            // migration exists to remove.
            .setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_NONE)
            .commitNow()
        rebuildBar(tab)
    }

    /**
     * Repaint the bar for a tab change.
     *
     * Only the SELECTED marker differs, so this replaces one child of a stable parent. It never
     * runs on a timer: a bar rebuilt under a press is the tap-eating defect this codebase has
     * already paid for twice.
     */
    private fun rebuildBar(tab: Theme.Tab) {
        barHost.removeAllViews()
        barHost.addView(Theme.bottomNav(this, tab) { selected -> show(selected) })
    }

    /** Whatever screen is on screen, for a repaint request that comes from the shell. */
    private fun visibleFragment(): BridgeFragment? =
        supportFragmentManager.findFragmentById(CONTAINER_ID) as? BridgeFragment

    /** An unknown tab name falls back to Home: a wrong tab is recoverable, a crash on launch is not. */
    private fun tabNamed(name: String): Theme.Tab? =
        Theme.Tab.entries.firstOrNull { it.name == name }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A tab requested while this Activity is already the top of its task.
        intent.getStringExtra(Tabs.EXTRA_TAB)?.let(::tabNamed)?.let(::show)
    }

    override fun onResume() {
        super.onResume()
        // An approval prompt lives in its own task, so returning to the app lands here. Without
        // this the modal stays stranded off-screen until its window expires.
        ApprovalGate.relaunchCurrent(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab.name)
    }

    private companion object {
        const val STATE_TAB = "tab"

        /**
         * A fixed id for the fragment container.
         *
         * `replace()` needs a real view id, and `generateViewId()` would produce a different one
         * after every recreate — so the fragment manager could not find the container it had
         * already populated.
         */
        const val CONTAINER_ID = 0x5E1A

        /** Once per process. A deliberate STOP must survive a tab switch. */
        var autoStartAttempted = false
    }
}
