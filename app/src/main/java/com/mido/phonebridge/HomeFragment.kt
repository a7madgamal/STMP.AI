package com.mido.phonebridge

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ui.ServerStatus
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.blurb
import com.mido.phonebridge.policy.title
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.ui.Theme

class HomeFragment : BridgeFragment(), BridgeControl {

    companion object {
        const val REQ_POST_NOTIF = 1001

        /**
         * How often the hero re-renders while the activity is resumed.
         *
         * 5s is a compromise: fast enough that a dead server cannot stay green through a
         * glance at the screen, slow enough that the repaint is invisible. It reads a CACHED
         * probe result, so the cost is a field read — the real probe runs in the service on its
         * own 60s schedule, off the main thread.
         */
        const val UI_TICK_MS = 5_000L

        /**
         * How long the knob shows a pending look after a tap.
         *
         * Long enough to cover a bind on a slow device, short enough that a start which silently
         * failed does not leave the knob looking busy forever. After this the knob simply reflects
         * whatever the service says, including "still down".
         */
        const val PENDING_WINDOW_MS = 3_000L
    }

    /**
     * The server switch.
     *
     * A toggle rather than a button: the old control had to say "START SERVER" or "STOP SERVER"
     * and be recomputed on every render, so a stale label could claim the server was stopped
     * while it was running. The knob's POSITION is the state, so it cannot disagree with itself.
     */
    private lateinit var serverToggle: com.mido.phonebridge.ui.RuggedToggle

    /**
     * When the last start/stop tap was made, so the knob can show a PENDING look while the
     * service is still binding.
     *
     * The server is a live service, not a stored value: starting takes a moment and can fail. The
     * knob therefore must not slide on the tap — it would be showing intent, and would slide back
     * on a failure. It stays where the last known state put it and takes on the pending colour
     * until `render()` sees the real value.
     */
    private var pendingSince: Long = 0L
    private lateinit var auditBanner: TextView

    /**
     * The update section's container.
     *
     * **This exists because the section was built once and never again.** `updateBlock()` was
     * called from `buildContent()` only, while `render()` updates Home's other views IN PLACE —
     * so tapping CHECK NOW set `checking = true`, called `render()`, and nothing on screen
     * changed. The button looked dead because nothing ever redrew it (reported 2026-11-04:
     * *"clicking check now still does nothing"*).
     *
     * A container that `render()` empties and refills is the smallest fix that makes the section
     * behave like the rest of the screen.
     */
    private lateinit var updatesHost: LinearLayout
    private lateinit var gateCard: LinearLayout
    private lateinit var gateTitle: TextView
    private lateinit var gateSub: TextView
    private lateinit var gateButtons: LinearLayout

    /**
     * The column holding the mode row and the rules button.
     *
     * A separate field from [gateButtons] because the two are different KINDS of row: the modes
     * share width equally, the rules button takes a full row of its own. Putting them in one
     * horizontal parent is what made the modes collapse to nothing.
     */
    private lateinit var gateRows: LinearLayout
    private lateinit var gateFingerprint: com.mido.phonebridge.ui.RuggedToggle
    private lateinit var gateFingerprintWhy: TextView
    private lateinit var heroCard: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView

    /**
     * Work to run when this screen comes back to the front.
     *
     * The install-permission dialog sends the human to Android settings and promises the install
     * will carry on when they return. This is what keeps that promise.
     */
    private var pendingAfterResume: (() -> Unit)? = null

    /**
     * Guards the load prompt so returning to the app (onResume) never re-prompts on
     * its own. One prompt per process — which is exactly the session.
     */

    /**
     * Drives the while-resumed repaint. See [onResume].
     *
     * Main looper because it touches views, and a plain `Handler()` would do — named explicitly
     * so the threading is obvious to a reader rather than inferred from construction order.
     */
    private val uiTickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * What the checklist looked like at the last repaint.
     *
     * **This field exists because a tap was being eaten.** Reported 2026-11-02: *"nothing changed
     * no modal"*. The device log had the answer — a tap logged `pointer 0` and NO `pointer 1`,
     * a gesture cancelled before it could become a click. The cause was `uiTick`: every 5 seconds
     * `render()` rebuilds rows, so a press
     * held for longer than the interval had its View destroyed underneath the finger and the click
     * never fired. Pressing a row is exactly the kind of touch that lasts a second or two, so the
     * interaction was lost often enough to look like "the button does nothing".
     *
     * Worse, the bug is self-concealing: nothing crashed, nothing logged, the screen looked
     * identical because it had been rebuilt with the same content. Two releases of dialog copy
     * were written against a tap that never reached them.
     *
     * So the tick now repaints ONLY when something it displays actually changed. The rows keep
     * their identity between ticks, which is what lets a press survive to become a click.
     */
    private var lastRenderSignature: String? = null

    /** Guards the unrecoverable-vault reset dialog so it does not stack. */

    override val tab = Theme.Tab.HOME

    /**
     * Home's rows: hero, start/stop, the setup checklist, account, tools, audit, debug.
     *
     * Called by [BridgeScreen] with the container already inside the bar layout. Everything here
     * is built once; `render()` updates in place afterwards, which is what keeps a press from
     * being destroyed underneath the finger by the 5s tick.
     */
    override fun buildContent(container: LinearLayout) {
        // --- 1. HERO: the one thing you need to know ---
        heroCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 14),
                Theme.dp(ctx, 16), Theme.dp(ctx, 14))
            background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        heroTitle = TextView(ctx).apply {
            textSize = 20f
            typeface = Theme.handFont(ctx)
            setTextColor(Theme.ON_SURFACE)
        }
        heroSub = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        }

        // The stamp mark, beside the status rather than above it.
        //
        // `WRAP_CONTENT` + `adjustViewBounds`, the same treatment the approval modal gives it:
        // the mark is roughly 2:1, so forcing it into a square box letterboxes it into a smear.
        // Height drives the size and the width follows.
        val mark = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.logo_mark)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "STMP"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Theme.dp(ctx, 44)
            ).apply { marginEnd = Theme.dp(ctx, 12) }
        }
        val heroText = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(heroTitle)
            addView(heroSub)
        }
        // **The knob sits IN the header row, top right.**
        //
        // Mido, 2026-11-04: *"server toggle still in its own row, it should be top right aligned
        // so the header gets compacted"*. It was below the title in a row of its own, which cost
        // an entire line of vertical space on the one card that is meant to be read at a glance.
        //
        // TOP-aligned rather than centred: the text block is two lines tall, and a centred knob
        // drifts to the vertical middle of the card, away from the title it belongs to.
        val heroRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
            addView(mark)
            addView(heroText, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
        }

        // --- 2. THE CONTROL: inside the hero, because it is the answer's other half ---
        //
        // "Is the server up?" and "turn it up" are one subject. As a separate row below, the
        // switch read as another item in a list; inside the card it belongs to the status it
        // changes.
        //
        // No "Server" label. Mido, 2026-11-04: *"remove the word server in header, just put the
        // toggle on the right and make the header shorter by removing the server label"*.
        //
        // The word was doing no work: the card already says STMP and whether the bridge is up, so
        // the switch sitting under that sentence has exactly one possible meaning. A lone knob
        // hard right also makes the row shorter, which is the point — the hero is meant to be read
        // at a glance, not parsed.
        serverToggle = Theme.toggle(
            ctx,
            on = BridgeService.serverUp,
            pending = pendingSince > 0L,
        ) { want ->
            // Record the tap and POST everything else.
            //
            // **Not `render()` inline.** This handler is inside the hero card, and the panel it
            // repaints sits in the same card — doing that work during touch dispatch is the same
            // class of fault that made CHECK NOW dead and the channel switch refuse to flip.
            // Posting defers it to the next loop, after the press has been released.
            //
            // The knob is still NOT flipped here: `render()` moves it once `BridgeService.serverUp`
            // actually changes, so a failed stop cannot leave it showing a state that is not true.
            pendingSince = android.os.SystemClock.elapsedRealtime()
            serverToggle.pending = true
            uiTickHandler.post {
                if (want) {
                    Debug.log("server start requested")
                    startBridge()
                } else {
                    Debug.log("stop requested")
                    stopBridge()
                }
                render()
            }
            // The service binds asynchronously; a re-render after a beat picks up the result
            // without waiting for the 5s tick. The tick covers the case where it takes longer.
            uiTickHandler.postDelayed({ render() }, 600)
        }
        heroRow.addView(serverToggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = Theme.dp(ctx, 8) })
        heroCard.addView(heroRow)
        container.addView(heroCard)

        // --- GET STARTED ---
        //
        // **Directly under the hero, above the gate.** Mido, 2026-11-10: *"move get started
        // up"*. It was below the approval-gate card, which meant a fresh install met three mode
        // radios and an exception table before it met the explanation of what the app is for.
        // The hero answers "is it running", this answers "what do I do next" — those are the two
        // first questions, and they belong next to each other.
        //
        // Originally moved here from Settings → Tools (Mido, 2026-11-04: *"move get started to
        // home screen above updates"*). It is the first thing a new install needs and the thing
        // an agent's human reaches for when a call is refused with no obvious fix — both are
        // read-at-a-glance jobs, not settings.
        container.addView(Theme.sectionHeader(ctx, "Get started"))
        container.addView(
            // **"HOW TO USE THIS" named the door, not the room.** Mido, 2026-11-10: *"getting
            // started section is not obvious"* — the button promised instructions and delivered
            // one prompt. It now opens a flow that explains what the app IS first, and the label
            // says so.
            Theme.button(ctx, "WHAT IS STMP?", Theme.ButtonStyle.NEUTRAL) {
                go(Intent(ctx, OnboardingActivity::class.java))
            },
        )

        // **The account section moved to the Google plugin** (Mido, 2026-11-03: *"move the
        // account part to the google plugin"*). Signing in is a precondition the Google plugins
        // share, so it belongs with them: Plugins → Google account holds the email, the token
        // state and the sign-in button.

        // --- 3b. GATE SWITCH: the one control that decides whether anything is
        // asked of a human at all. Above the plugins, below Setup, because it is
        // the second question after "is it running?" — "is it still asking me?".
        container.addView(Theme.sectionHeader(ctx, "Approval gate"))
        gateCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 12),
                Theme.dp(ctx, 14), Theme.dp(ctx, 12))
            background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        gateTitle = TextView(ctx).apply {
            textSize = 17f
            typeface = Theme.handFont(ctx)
            setTextColor(Theme.ON_SURFACE)
        }
        gateSub = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        }
        // **Vertical, because the modes are radio cards now.**
        //
        // Mido, 2026-11-04: *"convert approval gate 3 options into the same radio cards"*. Cards
        // need height and a full row each, and three across a phone would have been three
        // unreadable chips — the same problem the mode row already had before it became a row of
        // three cramped labels.
        gateButtons = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        // A COLUMN holding the mode cards.
        //
        // It used to also hold EDIT RULES on its own row. That button has moved INTO the
        // whitelist card, where it belongs: only WHITELIST reads the rule table, and being a
        // child of a card that `renderGate` clears wholesale makes the old "5 EDIT RULES
        // buttons" stacking bug structurally impossible rather than avoided by care.
        //
        // It used to be the horizontal row itself, and the rules button was added to the SAME
        // row with MATCH_PARENT. In a horizontal LinearLayout that gives the rules button all the
        // width and shrinks the three weighted modes to zero — so the card showed only WHITELIST
        // and the other two vanished. Reported 2026-11-04: *"approval gate is fucking empty only
        // whitelist"*.
        gateRows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Theme.dp(ctx, 10), 0, 0)
            addView(gateButtons)
        }
        gateCard.addView(gateTitle)
        gateCard.addView(gateSub)
        gateCard.addView(gateRows)

        // The fingerprint toggle lives HERE, beside the mode picker, not in Settings.
        //
        // They are one subject: the mode decides WHEN you are asked, this decides HOW you prove
        // it. Split across two screens, neither half can be reasoned about — you could read
        // "AUTOMATIC" and never learn that a SECURE request will ask for a fingerprint.
        val fpRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 8dp, not 12: the card already pads its own bottom by 12, so the gap under this row
            // was 24dp — the "too much bottom padding" reported 2026-11-04. The row only needs
            // enough to separate the knob from the rules button above it.
            setPadding(0, Theme.dp(ctx, 8), 0, 0)
        }
        // A switch, not a button. Mido, 2026-11-04: *"make fingerprint for modals a toggle not a
        // button"*. The old control was a button whose LABEL carried the state, so the state had
        // to be re-rendered into text on every pass and a stale render could claim the wrong
        // thing. A knob's position cannot go stale.
        val fpHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fpHeader.addView(
            Theme.text(ctx, "Fingerprint for modals", 15f, Theme.ON_SURFACE).apply {
                typeface = Theme.handFont(ctx)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        gateFingerprint = Theme.toggle(
            ctx,
            on = try { PolicyStore.from(requireContext()).get().fingerprintForModals }
                 catch (e: Exception) { false },
        ) { want ->
            // **A fingerprint is required to flip this switch.** Mido, 2026-11-04: *"flipping the
            // fingerprint toggle should only work after requiring the fingerprint"*.
            //
            // Both directions ask. Turning it OFF is the more dangerous change — it removes the
            // proof of presence from every future approval — so it is not the one direction to
            // leave unguarded.
            requestFingerprintChange(want)
        }
        fpHeader.addView(gateFingerprint)
        fpRow.addView(fpHeader)
        gateFingerprintWhy = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            // **No padding and no height until it has something to say.**
            //
            // It is empty in the normal case (the description was removed on request), and an
            // empty TextView that still reserves 6dp of top padding plus a line of height is
            // invisible space under the toggle. Visibility is driven by `render`, which turns it
            // GONE when there is no text and the reason otherwise.
            setPadding(0, 0, 0, 0)
            visibility = TextView.GONE
        }
        fpRow.addView(gateFingerprintWhy)
        gateCard.addView(fpRow)

        container.addView(gateCard)

        // --- UPDATES ---
        //
        // On Home, not Settings. Mido, 2026-11-04: *"move the update section to the home page"*.
        // An available update is news about the app you are looking at, not a preference — and a
        // preference buried one tab away is one nobody finds. The row stays silent when there is
        // nothing to say.
        container.addView(Theme.sectionHeader(ctx, "Updates"))
        updatesHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(updatesHost)

        // **Filled here, at build time.**
        //
        // This host used to be created empty and only ever filled by `refreshUpdateSection()`,
        // which is only called from the CHECK NOW handler. So a fresh screen showed the "Updates"
        // header with NOTHING under it until you tapped a button — and since the button was
        // inside the missing block, there was nothing to tap. Reported 2026-11-04: *"the update
        // section completely empty just a header"*.
        //
        // The section is now correct on first paint and is refreshed afterwards by posted
        // rebuilds, which is the only model that keeps its controls alive under a tap.
        updatesHost.addView(updateBlock())

        // The plugin list used to live here and made Home a wall: twelve status cards between
        // the server control and the tools, pushing "is the bridge running" off the first
        // screen. It moved to the Plugins tab, where capability inventory belongs.

        // Tools and Debug moved to Settings (Mido, 2026-11-03: *"rethink each button screen"*).
        // They are destinations and diagnostics, not live state — and REQUEST LOG / SSH were
        // sitting on Home purely because there was nowhere else to put them.

        // **The audit banner keeps its home here, and it must be CREATED here.**
        //
        // This is the row that crashed 0.43.90 on open: `render()` has always painted it, but
        // removing the Debug block took the construction with it and left the `lateinit` field
        // unset. Home is the launch screen, so every open threw
        // `UninitializedPropertyAccessException` before anything else could run.
        //
        // The lesson is narrower than "remember to clean up": a `lateinit` field is a promise the
        // compiler cannot check. `render()` and `buildContent` are far enough apart that nothing
        // links them, so deleting one side compiles cleanly and fails on the device.
        auditBanner = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Theme.ON_COLOR)
            background = Theme.surface(ctx, Theme.DANGER_SURFACE, Theme.RADIUS_CONTROL)
            setPadding(
                Theme.dp(ctx, 12), Theme.dp(ctx, 10),
                Theme.dp(ctx, 12), Theme.dp(ctx, 10)
            )
            visibility = TextView.GONE
        }
        container.addView(auditBanner)
    }


    /**
     * Start the bridge on app load if it is not already running.
     *
     * Mido, 2026-10-19: *"start server on app load automatically"*. The whole point of the
     * app is the server, so requiring a tap to reach the normal state is a step that only
     * ever gets in the way.
     *
     * Two rules keep it from becoming a trap:
     *  - **Once per process.** [autoStartAttempted] means a manual STOP stays stopped; a
     *    restart on every onResume would make STOP impossible to keep.
     *  - **Never over a running server.** `serverUp` covers the case where the service was
     *    already started (or survived), so this cannot double-start or disturb it.
     *
     * **And a third: the preference is honoured.** "Auto-start on boot" is ONE toggle meaning
     * "the server starts itself", so it gates this entry point too. A setting whose OFF state
     * still started the server every time the app opened would be a toggle that does not mean
     * what it says. The service still starts on an explicit tap — that is a human asking.
     */

    /**
     * The app lock: the load prompt IS the vault unlock (plan D9). One scan does both
     * jobs, because the prompt carries a CryptoObject bound to the vault key — so the
     * finger that says "you're you" also unwraps the data key.
     *
     * Fails closed: a declined/failed prompt leaves the vault locked. The app still
     * renders (the bridge is a separate process concern and keeps running), but every
     * secret operation will answer `secret_locked` until the human unlocks.
     */
 
    /**
     * The 0.14.x vault is dead (missing wrap IV). Offer a reset, once per process, and
     * be honest that the stored secrets cannot be recovered — a silent fresh vault would
     * look like success while quietly discarding a credential.
     */
 
    override fun onResume() {
        super.onResume()
        render()
        // The update section is not part of `render()` (rebuilding it there could tear a control
        // down under a press), so returning to this screen has to ask for it explicitly — that is
        // what makes an install-return or a background check show up.
        if (::updatesHost.isInitialized) refreshUpdateSection()

        // The install-permission dialog promises the install resumes on return. Keep it.
        // Posted, not called inline: the pending action may open another dialog or start a
        // download, both of which want a settled frame first.
        pendingAfterResume?.let { pending ->
            pendingAfterResume = null
            uiTickHandler.post(pending)
        }
        // Re-render while visible, so the hero cannot go stale under the human's eyes.
        //
        // **This is the other half of the 2026-10-25 bug.** `render()` used to run only on
        // `onResume` and two one-shot 600ms callbacks, so a server that died while the screen
        // was open kept its green label until the activity was left and re-entered. A green
        // label nobody re-checks is how "the app says running" and "callers get refused" were
        // both true at once.
        //
        // The tick reads a CACHED probe result (`isVerifiedAlive`), never a fresh connect — the
        // service's watchdog owns the probing, because a connect here would be network I/O on
        // the main thread. So this is cheap: a field read plus a re-render every few seconds.
        uiTickHandler.removeCallbacksAndMessages(null)
        uiTickHandler.postDelayed(uiTick, UI_TICK_MS)
        // `relaunchCurrent` is handled by BridgeFragment.onResume for every screen, so it is
        // deliberately NOT repeated here: one owner, or a new screen silently loses it.
    }

    override fun onPause() {
        super.onPause()
        // No ticking in the background: it would repaint a screen nobody is looking at, and a
        // resumed activity re-arms it immediately.
        uiTickHandler.removeCallbacksAndMessages(null)
    }

    /**
     * A cheap fingerprint of everything the checklist renders.
     *
     * Deliberately primitives and short strings only — no view construction, no disk, no network.
     * It is compared once every 5 seconds, so it must cost less than the repaint it is avoiding.
     *
     * `progress?.percent` is included because the model download must visibly advance; without it
     * a download would freeze at whatever value was on screen when the tick started skipping.
     */
    private fun renderSignature(): String = buildString(96) {
        append(BridgeService.serverUp).append('|')
        append(BridgeService.isVerifiedAlive()).append('|')
        append(BridgeService.lastError).append('|')
        append(BridgeService.bindAddress).append('|')
        append(BridgeService.listenerRef != null).append('|')
        append(hasNotifPermission()).append('|')
        // `ctx`, NOT `this`: inside buildString the implicit receiver is the
        // StringBuilder, so a bare `this` would be the wrong object entirely.
        append(runCatching { PolicyStore.from(requireContext()).get().fingerprintForModals }
            .getOrDefault(false)).append('|')
        append(ApprovalGate.canUseFullScreenIntent(ctx)).append('|')
        append(android.provider.Settings.canDrawOverlays(ctx)).append('|')
        append(com.mido.phonebridge.ui.SystemAccess.batteryExempt(ctx)).append('|')
        append(SecretVault.isUnlocked()).append('|')
        append(com.mido.phonebridge.ai.AiGateway.enabled(ctx)).append('|')
        append(com.mido.phonebridge.ai.ModelProvisioner.running).append('|')
        append(com.mido.phonebridge.ai.ModelProvisioner.progress?.percent ?: -1).append('|')
        append(com.mido.phonebridge.ai.ModelRegistry.readiness(ctx).ready).append('|')
        append(AuthManager.isLoggedIn(ctx)).append('|')
        append(AuthManager.accountEmail ?: "").append('|')
        append(AuditHealth.reason ?: "").append('|')
        // **The update check is part of what this screen displays.** Without these two, a check
        // that finished while Home was visible changed nothing the signature could see, so the
        // tick skipped the repaint and the card stayed on "checking…" until the screen was left
        // and re-entered. Mido, 2026-11-10: *"the checking status right after updating is stuck
        // forever"*.
        //
        // `lastOutcome?.javaClass` rather than the outcome itself: this only needs to notice that
        // the value CHANGED, and the class name is a cheap stand-in that does not allocate.
        append(com.mido.phonebridge.core.Updater.checking).append('|')
        append(com.mido.phonebridge.core.Updater.lastOutcome?.javaClass?.simpleName ?: "").append('|')
    }

    /**
     * Repaints on a timer while the activity is resumed. See [onResume].
     *
     * **Repaints only on a real change.** The unconditional `render()` here rebuilt every row
     * every 5 seconds, which destroyed the View under any press that outlasted the interval and
     * cancelled the click (see [lastRenderSignature] for the evidence). A tap on a checklist row
     * is a 1–2 second gesture, so this ate them routinely while the screen looked unchanged.
     *
     * The alternative — keeping the rebuild and shortening the interval — would make it worse.
     * Skipping unchanged repaints is the only version where a press can complete.
     */
    private val uiTick = object : Runnable {
        override fun run() {
            val signature = renderSignature()
            if (signature != lastRenderSignature) {
                lastRenderSignature = signature
                render()
            }
            uiTickHandler.postDelayed(this, UI_TICK_MS)
        }
    }

    // --- rendering ---

    /**
     * Repaint everything, INCLUDING the update section.
     *
     * `render()` deliberately does not touch that section — rebuilding it there could tear a
     * control down under a finger, which is what made CHECK NOW look dead. But that means the
     * one caller that asks "redraw yourself" (the shell, after a background check completes)
     * would redraw everything except the news it was asking about.
     */
    override fun repaint() {
        render()
        if (::updatesHost.isInitialized) refreshUpdateSection()
    }

    private fun render() {
        // Baseline the tick's fingerprint. Every render() path — tap, permission result, service
        // callback — re-baselines here, so the 5-second tick cannot immediately undo a repaint
        // that was explicitly requested.
        lastRenderSignature = renderSignature()

        // **The update section is NOT rebuilt here, on purpose.**
        //
        // Every change to it — a check, a channel flip, an install — goes through
        // `refreshUpdateSection`, which rebuilds it POSTED so the control that was tapped is not
        // destroyed while its own click is still being dispatched.
        //
        // Rebuilding it from `render()` would undo that: `render()` runs on the 5-second tick and
        // on every server-state change, so a rebuild here could tear the channel switch down
        // mid-press. That is the tap-eating defect this file documents at length, and it is
        // exactly what made the switch stop flipping.

        // --- 1. HERO: one line that answers "is it working?" ---
        val listenerOk = BridgeService.listenerRef != null
        val notifOk = hasNotifPermission()
        // A failed start is its own state, and it must be checked BEFORE the stopped state.
        // The precedence lives in `ServerStatus` (pure, unit-tested) rather than here: this
        // ordering used to be inline, so nothing could fail when it was wrong. The old order
        // checked `!serverUp` first, which made the reason a start failed unreachable — the
        // service stops itself when it cannot bind, setting `serverUp=false` while `lastError`
        // held the cause. Mido, 2026-10-20: *"on server fail user should see clear error"*.
        val state = ServerStatus.state(
            serverUp = BridgeService.serverUp,
            // The REAL check, not the flag. `serverUp` only records that start() once
            // succeeded; this records that a socket connected. See ServerStatus.UNRESPONSIVE.
            alive = BridgeService.isVerifiedAlive(),
            listenerOk = listenerOk,
            notifOk = notifOk,
            lastError = BridgeService.lastError,
        )
        val failed = state == ServerStatus.State.FAILED ||
            state == ServerStatus.State.UNRESPONSIVE

        // The state colour is applied ONCE, here. The old code set a background per
        // branch and then overwrote it two lines later, so the hero card was always
        // the neutral grey regardless of state.
        val heroSurface = when (state) {
            ServerStatus.State.RUNNING_HEALTHY -> Theme.SUCCESS_SURFACE
            ServerStatus.State.RUNNING_ATTENTION -> Theme.WARNING_SURFACE
            ServerStatus.State.UNRESPONSIVE -> Theme.DANGER_SURFACE
            ServerStatus.State.FAILED -> Theme.DANGER_SURFACE
            ServerStatus.State.STOPPED -> Theme.SURFACE
        }
        heroTitle.text = ServerStatus.title(state)
        heroTitle.setTextColor(
            when (state) {
                ServerStatus.State.RUNNING_HEALTHY -> Theme.SUCCESS
                ServerStatus.State.RUNNING_ATTENTION -> Theme.WARNING
                ServerStatus.State.UNRESPONSIVE -> Theme.DANGER
                ServerStatus.State.FAILED -> Theme.DANGER
                ServerStatus.State.STOPPED -> Theme.ON_SURFACE_VARIANT
            }
        )
        heroCard.background = Theme.surface(ctx, heroSurface, Theme.RADIUS_CARD)
        // No email here (Mido, 2026-11-03: *"move email from home, it belongs to the google
        // plugin"*). The hero answers "is the bridge running, and where" -- who is signed in is a
        // fact about the ACCOUNT, and it lives on the screen that can change it.
        heroSub.text = ServerStatus.detail(
            state = state,
            lastError = BridgeService.lastError,
            bindAddress = BridgeService.bindAddress,
            port = BridgeService.PORT,
        )
        // The reason is the one thing on this screen that must be read, so it gets the
        // danger colour rather than the muted body colour it used to share with log lines.
        heroSub.setTextColor(if (failed) Theme.DANGER else Theme.ON_SURFACE_VARIANT)

        // --- 3. CHECKLIST: compact, each row says what to do when broken ---
        // Start/Stop colour rule (user's standing rule): grey = idle/stopped,
        // green = running, and NEVER red for a normal stopped state. The button
        // is not green just because it is tappable — green means the server is up.
        // Tint, never `setBackgroundColor`: assigning a background drawable to a
        // MaterialButton replaces the ripple/state-list machinery. `backgroundTintList`
        // recolours the same component and keeps the feedback.
        // The knob renders the STORED state, never the tap. `pendingSince` only suppresses the
        // "settled" look for a moment so a slow bind does not flick the knob back and forth.
        val up = BridgeService.serverUp
        // **Settled as soon as the real state matches what was asked for.**
        //
        // The old rule was "pending for 3 seconds, then sync". That left the knob showing its
        // PREVIOUS position — and, because the callback inverts `checked`, still reporting the
        // previous state — for the whole window. After a stop it kept saying "on" for three
        // seconds and every tap sent "off" again, which read as the switch being dead (reported
        // 2026-11-04: *"stopping the server left toggle unresponsive"*).
        //
        // So: clear the pending look the moment `serverUp` agrees with the knob, and fall back to
        // the timeout only for a start that has not landed yet.
        val settled = serverToggle.checked == up
        val windowOpen = pendingSince > 0L &&
            android.os.SystemClock.elapsedRealtime() - pendingSince < PENDING_WINDOW_MS
        if (windowOpen && !settled) {
            // Still waiting for the service to answer. The knob stays where the last real state
            // put it; only the colour says "working".
            serverToggle.pending = true
        } else {
            pendingSince = 0L
            serverToggle.pending = false
            if (serverToggle.checked != up) serverToggle.setCheckedAnimated(up)
        }

        // The status checklist moved to Settings > Permissions. Home now answers one question
        // — is the bridge running — and offers one control. Everything else is a setting.

        renderGate()

        // A gate you cannot audit must be visible without opening the log. This stays on Home
        // even though the log row moved to Settings: a state where every request is being
        // REFUSED is not a setting, it is the answer to "why is nothing working".
        val degraded = AuditHealth.reason
        if (degraded != null) {
            auditBanner.visibility = TextView.VISIBLE
            auditBanner.text = "⚠ AUDIT DEGRADED — requests are being refused\n$degraded"
        } else {
            auditBanner.visibility = TextView.GONE
        }
    }

    /**
     * The gate switch. `SECURE` is the resting state; `NOTIFY` is the quiet one.
     *
     * There is no auto-approve position (removed in 0.12.0), so this screen cannot
     * show a state where requests run without a human. The switch only chooses how
     * loudly they ask — never whether they ask at all.
     */
    private fun renderGate() {
        val current = try {
            PolicyStore.from(requireContext()).get().global
        } catch (e: PolicyException) {
            // An unreadable policy is not a quiet fallback to SECURE: say it here, and
            // BridgeService refuses to serve on the same condition. This screen must
            // never show a switch position the engine is not actually honouring.
            gateCard.background = Theme.surface(ctx, Theme.DANGER_SURFACE, Theme.RADIUS_CARD)
            gateTitle.text = "⚠ POLICY UNREADABLE"
            gateSub.text = e.message ?: "policy could not be read"
            gateTitle.setTextColor(Theme.DANGER)
            gateSub.setTextColor(Theme.ON_SURFACE_VARIANT)
            gateButtons.removeAllViews()
            // Hidden rather than left showing the last known position: an unreadable policy means
            // the app does not know what it is enforcing, so a switch position here would be a
            // claim it cannot support.
            gateFingerprint.visibility = TextView.GONE
            gateFingerprintWhy.visibility = TextView.GONE
            return
        }

        when (current) {
            // Only AUTOMATIC reads the rules. The other two force one mode on every
            // request, and the card says which so the home screen never shows a switch
            // position without showing what it implies.
            GlobalMode.WHITELIST -> {
                gateCard.background = Theme.surface(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "WHITELIST"
                gateSub.text = rulesSummary()
            }
            GlobalMode.PARANOID -> {
                gateCard.background = Theme.surface(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "PARANOID"
                gateSub.text = GlobalMode.PARANOID.blurb
            }
            GlobalMode.DONT_DISTURB -> {
                // Amber, not red: the gate still exists, it is just quiet and fast.
                // Calling it red would make the real panic switch less loud by
                // comparison, which is the failure that matters.
                gateCard.background = Theme.surface(ctx, Theme.WARNING_SURFACE, Theme.RADIUS_CARD)
                gateTitle.text = "⚠ DON'T DISTURB"
                gateSub.text = GlobalMode.DONT_DISTURB.blurb
            }
        }
        gateTitle.setTextColor(Theme.ON_SURFACE)
        gateSub.setTextColor(Theme.ON_SURFACE_VARIANT)

        // Rebuilt wholesale on every pass: `removeAllViews` then re-add is consistent, which is
        // the property the EDIT RULES button lacked when it stacked up. That button is built in
        // `buildContent` and never touched here.
        gateButtons.removeAllViews()
        for (m in GlobalMode.values()) {
            gateButtons.addView(gateRadioCard(m, m == current))
        }

        // **A way into the rule table from the card that shows it.**
        //
        // Mido, 2026-11-04: *"approval gate should have a button to edit the rules"*. The card
        // already SUMMARISES the rules ("3 rules · 2 quiet") but the only route to change one was
        // Settings → Tools → Exceptions, three taps away from the thing being described. A summary
        // you cannot act on is a label, not a control.
        //
        // **On its own row, not beside the modes.** Mido, 2026-11-04: *"approval gate is too
        // crowded, move the rules button under"*. In the modes row it took a quarter of the width
        // from three choices that need to read as one set, which made all four labels cramped.
        // Underneath, the modes get the full width and the rules button reads as the separate verb
        // it is.
        // **Built ONCE, in `buildContent`, and never re-added here.**
        //
        // This was inside `renderGate()`, and `renderGate` runs on every repaint while only
        // `gateButtons` was cleared — so each pass appended another EDIT RULES. Reported
        // 2026-11-04: *"2 edit rules buttons"*, then *"they keep appearing now 5"*. A view that is
        // only ever ADDED must be created where the layout is created, not where it is refreshed.

        // The fingerprint control, rendered from the STORED value rather than from what we
        // assume: a write that silently failed must not leave the screen claiming otherwise.
        val policy = try {
            PolicyStore.from(requireContext()).get()
        } catch (e: PolicyException) {
            // Unreachable in practice — the early return above already handled it — but a
            // second read that throws must not crash a screen the user is looking at.
            gateFingerprint.visibility = TextView.GONE
            gateFingerprintWhy.visibility = TextView.GONE
            return
        }
        gateFingerprint.visibility = TextView.VISIBLE
        // The explanation line is GONE unless it has text: it is normally empty (the description
        // was removed on request), and a visible empty TextView reserves a line of height under
        // the toggle — the extra bottom space reported 2026-11-04.
        gateFingerprintWhy.visibility = TextView.GONE

        // **Can this phone even ask for a fingerprint?**
        //
        // Mido, 2026-11-04: *"if no fingerprint feature available then it should be disabled and
        // off stating the reason"*. The check is the same one the modal and the vault use, so the
        // switch cannot claim a capability the approval screen would refuse.
        when (val status = Fingerprint.status(hostActivity)) {
            is Fingerprint.Status.Unusable -> {
                // Forced OFF, not merely greyed out. With the policy ON and no usable biometric,
                // `ApprovalActivity` sets `biometricBlocked` and APPROVE never enables — so every
                // SECURE request would become unapprovable, and the cause would be buried inside
                // the modal. Writing the policy off keeps SECURE working on a plain tap.
                forceFingerprintOff(status.reason)
                gateFingerprint.setCheckedImmediately(false)
                gateFingerprint.isEnabled = false
                gateFingerprintWhy.text = status.reason
                gateFingerprintWhy.setTextColor(Theme.WARNING)
                gateFingerprintWhy.visibility = TextView.VISIBLE
                gateFingerprintWhy.setPadding(0, Theme.dp(ctx, 6), 0, 0)
            }

            Fingerprint.Status.Usable -> {
                val fp = policy.fingerprintForModals
                gateFingerprint.isEnabled = true
                // `setCheckedAnimated` only moves when the value actually differs, so a repaint
                // that changed nothing cannot make the knob twitch.
                if (gateFingerprint.checked != fp) gateFingerprint.setCheckedAnimated(fp)
                // **No explanatory sentence.**
                //
                // Mido, 2026-11-04: *"remove the desc saying A SECURE request...."*. It restated
                // what the knob beside it already says, in two variants that both began with the
                // same four words — the kind of text that gets read once and then becomes noise.
                // A hidden `when` branch keeps the view's visibility logic intact.
                gateFingerprintWhy.setTextColor(Theme.ON_SURFACE_VARIANT)
                gateFingerprintWhy.text = ""
            }
        }
    }

    /**
     * Flip the fingerprint-for-modals preference.
     *
     * Audited as POLICY_CHANGED, exactly as the Settings screen used to do: a fingerprint
     * requirement DISAPPEARING is the kind of change a later reader wants dated.
     */
    /**
     * Ask for the fingerprint, and only then change the setting.
     *
     * The knob does NOT move on the tap. `toggleFingerprintForModals` writes, and the next
     * `render()` drives the knob from what was written — so a cancelled prompt leaves it exactly
     * where it was, which is the truth.
     */
    private fun requestFingerprintChange(want: Boolean) {
        when (val status = Fingerprint.status(hostActivity)) {
            is Fingerprint.Status.Unusable -> {
                // Unreachable through the UI while the knob is disabled, but a stray call must
                // never write a setting this phone cannot honour.
                toast(status.reason)
                render()
            }

            Fingerprint.Status.Usable -> Fingerprint.confirm(
                activity = hostActivity,
                title = if (want) {
                    "Require fingerprint for modals"
                } else {
                    "Stop requiring fingerprint"
                },
                subtitle = "Confirm it's you",
                onConfirmed = { toggleFingerprintForModals(want) },
                onFailed = { why ->
                    // Cancelled or failed: nothing changes. Repainting puts the knob back in case
                    // the press itself moved it.
                    Debug.log("fingerprint toggle unchanged: $why")
                    render()
                },
            )
        }
    }

    /**
     * Turn the setting off because this phone cannot honour it.
     *
     * **Not cosmetic.** With the policy ON and no usable biometric, `ApprovalActivity` sets
     * `biometricBlocked` and APPROVE never enables — so every SECURE request becomes
     * unapprovable, with the cause buried in the modal. Forcing the policy off keeps SECURE
     * working on a plain tap instead of silently breaking the bridge.
     */
    private fun forceFingerprintOff(reason: String) {
        val store = PolicyStore.from(requireContext())
        val current = try {
            store.get()
        } catch (e: PolicyException) {
            Debug.log("cannot force fingerprint off: policy unreadable (${e.message})")
            return
        }
        if (!current.fingerprintForModals) return
        val next = current.withFingerprintForModals(false)
        try {
            store.set(next)
            Debug.log("fingerprint for modals forced OFF: $reason")
            AuditLog.from(requireContext()).append(
                com.mido.phonebridge.audit.PolicyAudit.record(next.global, next.global, "home")
            )
        } catch (e: Exception) {
            Debug.log("could not force fingerprint off: ${e.message}")
        }
    }

    private fun toggleFingerprintForModals(want: Boolean) {
        val store = PolicyStore.from(requireContext())
        val current = try {
            store.get()
        } catch (e: PolicyException) {
            toast("policy unreadable: ${e.message}")
            return
        }
        // The value the human confirmed, not a flip. A flip would fight the render that is trying
        // to put the knob back after a cancelled prompt.
        val next = current.withFingerprintForModals(want)
        try {
            store.set(next)
        } catch (e: Exception) {
            toast("could not save: ${e.message}")
            return
        }
        try {
            AuditLog.from(requireContext()).append(
                com.mido.phonebridge.audit.PolicyAudit.record(next.global, next.global, "home")
            )
        } catch (e: Exception) {
            Debug.log("audit record failed for fingerprint toggle: ${e.message}")
        }
        render()
    }

    /**
     * Apply a switch change. `NOTIFY` gets one confirmation, because it is the only
     * value where doing nothing approves a request — the single place in the app where
     * silence means YES, so it gets one sentence saying so.
     */
    private fun requestGate(next: GlobalMode) {
        val store = PolicyStore.from(requireContext())
        val current = try {
            store.get().global
        } catch (e: PolicyException) {
            toast("policy unreadable: ${e.message}")
            return
        }
        if (next == current) return

        if (next == GlobalMode.DONT_DISTURB) {
            // Not a red-alert confirmation: NOTIFY still asks, just quietly. But it is
            // the value where silence means YES, so it gets one sentence saying so.
            confirm(
                title = "Use overlay prompts?",
                message = "Every request shows a small toast for 5 seconds. Doing nothing " +
                    "approves it. Tap ✕ to reject.\n\nYour custom rules are ignored " +
                    "while this is selected.",
                positive = "Use NOTIFY",
                negative = "Cancel",
            ) { applyGate(store, current, next) }
        } else {
            applyGate(store, current, next)
        }
    }

    /**
     * One line of truth about the exception table, for the WHITELIST card.
     *
     * **"Per-pair" is gone.** It named the IMPLEMENTATION (a rule is one plugin × verb
     * pair) instead of the thing the human manages, which is an exception to the
     * ask-everything default. The screen itself was renamed to EXCEPTIONS on 2026-10-19 for
     * exactly this reason; these three strings were missed and kept the old vocabulary, so
     * the card and the button it opens described the same idea with different words.
     *
     * The wording now says what the mode DOES: exceptions decide, and with none set every
     * request asks.
     */
    private fun rulesSummary(): String = try {
        val p = PolicyStore.from(requireContext()).get()
        val n = p.rules.size
        when (n) {
            0 -> "Exceptions decide. None set yet, so everything asks for your stamp."
            1 -> "Exceptions decide. 1 exception set."
            else -> "Exceptions decide. $n exceptions set."
        }
    } catch (e: PolicyException) {
        "exceptions unreadable: ${e.message}"
    }

    /**
     * Start the model download and keep the home screen in step with it.
     *
     * The download is owned by [ModelProvisioner], not this Activity: it must survive a
     * rotation or a trip to another app, and the progress it reports is read back by
     * [render] so returning to the screen shows the live state rather than a stale one.
     */
    /**
     * Ask before re-fetching a model that is already present and verified.
     *
     * A 474 MB download is not something to start on a stray tap, and the model is
     * already working — so the default is to do nothing and the human has to mean it.
     */
 
 
    private fun applyGate(store: PolicyStore, before: GlobalMode, after: GlobalMode) {
        try {
            // Carry EVERYTHING across. `Policy(after)` alone would silently delete every
            // per-pair decision on a switch flip — you would turn NOTIFY back to AUTOMATIC and
            // find your rules gone, with nothing in the UI saying why. The same trap now applies
            // to the two preferences, so the wrap is explicit rather than positional: `Policy`
            // has four fields and a positional call is one edit away from dropping the wrong two.
            val existing = store.get()
            store.set(existing.withGlobal(after))
        } catch (e: Exception) {
            toast("could not save policy: ${e.message}")
            return
        }
        // Audit the change. A policy record that cannot be written is a failure the
        // home screen must show, not a change that quietly took effect unrecorded.
        try {
            AuditLog.from(requireContext()).append(PolicyAudit.record(before, after, "settings"))
        } catch (e: Exception) {
            AuditHealth.markDegraded("policy change not recorded: ${e.message}")
        }
        render()
    }

 
    /**
     * A compact destination chip for the "Tools" row. The four loose nav buttons used to
     * each be a full-width NEUTRAL button — visually identical to every other button, which
     * is what made the bottom of the home screen read as "labels mixed together". A tool is
     * a DESTINATION, not live state, so it lives on the Settings screen with the others
     * rather than a full-width row of its own.
     */
    /** One full-width row in the vertical tools list. */
 
    // --- actions ---

    private fun hasNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(ScreenHost.POST_NOTIFICATIONS)
    }

 
    /**
     * Doze and app standby suspend background work; Samsung adds app-sleep on top. This
     * exemption is what keeps the server reachable when the screen has been off for a
     * while. It is not a battery-saver bypass — it just says "do not freeze this app".
     */
 
    /**
     * The system dialog that grants the exemption. The direct request intent is the one
     * that actually shows a prompt; the settings list is the fallback for OEM builds that
     * do not honour it (the user then toggles the app manually).
     */
 
    private fun openNotifAccessSettings() {
        try {
            go(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Debug.log("opened notification-access settings")
        } catch (e: Exception) {
            Debug.log("cannot open notif settings: ${e.message}")
        }
    }

    /**
     * Ask for READ_CONTACTS.
     *
     * Separate from [requestNotifPermission] rather than a generic "request everything" call, and
     * deliberately NOT asked at launch: a contacts prompt on first open, with no visible reason,
     * is the kind of thing people deny reflexively — and a denial is sticky enough that the app
     * then needs Settings to recover. Tapping the row that says "Contacts search" is a request the
     * human understands.
     */
    /** Whether the contacts prompt has EVER been shown, so "not asked" is distinguishable. */
 
    /**
     * App details, where a permanently-denied permission can actually be re-granted.
     *
     * Not the "app permissions" deep link: that one varies by OEM and Samsung's S22 build lands
     * on a page without the contacts toggle in several firmware revisions. App details always
     * shows Permissions.
     */
 
    /**
     * The one place that explains the contacts permission, and the only launcher.
     *
     * [deniedJustNow] changes the copy from "here is what you need" to "that was declined" — the
     * outcome has to arrive somewhere the user is already looking, which is this dialog rather
     * than a Toast that disappears over the row it is describing.
     */
 
 
    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Debug.log("requesting POST_NOTIFICATIONS")
            askPermission(ScreenHost.POST_NOTIFICATIONS, REQ_POST_NOTIF)
        } else {
            Debug.log("POST_NOTIFICATIONS not needed on this API level")
        }
    }

    /**
     * **On the `DEPRECATION` suppression.** `Fragment.onRequestPermissionsResult` is deprecated
     * alongside `requestPermissions`, which [BridgeFragment] documents: the Activity Result API
     * needs a launcher registered at initialisation, and this screen asks at the moment of the
     * tap that needs the permission. The deprecated pair still work and still agree with each
     * other, so suppressing here keeps the ask and the result on the same mechanism rather than
     * half-migrating. Moving both to Activity Result is its own change.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Debug.log("POST_NOTIFICATIONS result: ${if (granted) "granted" else "DENIED"}")
            render()
        }
    }

 
    private fun startGoogleLogin() {
        if (BridgeService.GOOGLE_CLIENT_ID.isBlank()) {
            Debug.log("cannot login: GOOGLE_CLIENT_ID is empty (build not configured)")
            render()
            return
        }
        Debug.log("login tapped; client id len=${BridgeService.GOOGLE_CLIENT_ID.length}")
        go(Intent(ctx, GoogleLoginActivity::class.java))
    }

    override fun startBridge() {
        Debug.log("server start requested")
        // Clear the previous failure so a retry cannot display a stale reason: the service
        // re-sets this on the way out if it fails again, and a successful bind clears it.
        // Without this, tapping START after fixing the cause would still show the old error
        // for the ~1s between the tap and the bind.
        BridgeService.lastError = ""
        if (!hasNotifPermission()) {
            Debug.log("no POST_NOTIFICATIONS yet — requesting first")
            requestNotifPermission()
        }
        val i = Intent(ctx, BridgeService::class.java).apply {
            setClass(ctx, BridgeService::class.java)
        }
        try {
            // minSdk is 26, so startForegroundService is always available.
            ctx.startForegroundService(i)
            Debug.log("startForegroundService dispatched")
        } catch (e: Exception) {
            Debug.log("FAILED to dispatch service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Stop the service.
     *
     * The intent is built here rather than in the screen so the screen never names a concrete
     * Service class — the same reason [startBridge] wraps `startForegroundService`.
     */
    override fun stopBridge() {
        Debug.log("stop requested")
        ctx.stopService(Intent(ctx, BridgeService::class.java))
    }

    /**
     * The update rows.
     *
     * **Built from `Updater`'s last OUTCOME, never from a stored flag.** A cached "up to date" is
     * a claim about a question that was answered once, and it stops being true silently. The row
     * therefore distinguishes three states — current, available, and could-not-ask — because the
     * third one is the one that matters while the repository is private.
     */
    private fun updateBlock(): android.view.View {
        // **ONE card, not five stacked pieces.** Mido, 2026-11-04: *"make updates a single
        // group"*. It used to be a status row, a bordered channel row, a button and two loose
        // captions — five surfaces that happened to sit near each other. One torn card matches
        // the rest of Home, where a subject is a card and a card is one subject.
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(ctx, 14), Theme.dp(ctx, 12),
                Theme.dp(ctx, 14), Theme.dp(ctx, 12),
            )
            background = Theme.tornCard(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }

        val outcome = com.mido.phonebridge.core.Updater.lastOutcome
        val checking = com.mido.phonebridge.core.Updater.checking
        val channel = com.mido.phonebridge.core.UpdatePrefs.channel(requireContext())

        // **GREEN when an update exists.** This row used `Theme.ACCENT`, which is `0xFFB23A2F`
        // — byte-identical to `Theme.DANGER`. The one piece of good news this screen can deliver
        // was painted in the colour used for errors. Mido, 2026-11-04: *"a red dot next to an
        // exciting update is the last thing a user needs to see"*. (This fix was lost once when
        // the section moved screens; it is stated here so the next move does not lose it again.)
        val (line, colour) = when {
            checking -> "checking…" to Theme.ON_SURFACE_VARIANT
            outcome is com.mido.phonebridge.core.Updater.Outcome.Available ->
                "v${outcome.version} is ready to install" +
                    (if (outcome.isBeta) " · beta" else "") to Theme.SUCCESS
            outcome is com.mido.phonebridge.core.Updater.Outcome.UpToDate ->
                "up to date — v${BuildConfig.VERSION_NAME}" to Theme.ON_SURFACE_VARIANT
            outcome is com.mido.phonebridge.core.Updater.Outcome.Unavailable ->
                "cannot check: ${outcome.reason}" to Theme.WARNING
            else -> "not checked yet" to Theme.ON_SURFACE_VARIANT
        }

        // Status line: what is true right now.
        //
        // **One line, not two.** "checking…" and "asking GitHub…" were saying the same thing on
        // consecutive rows — Mido, 2026-11-04: *"asking github and checking... are redundant"*.
        // The spinner rides beside the status word instead: it adds the one thing the word cannot
        // say (that something is actively happening) without repeating it.
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        if (checking) {
            statusRow.addView(android.widget.ProgressBar(ctx).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(
                    Theme.dp(ctx, 14), Theme.dp(ctx, 14)
                ).apply { marginEnd = Theme.dp(ctx, 8) }
            })
        }
        statusRow.addView(Theme.text(ctx, line, 14f, colour))
        card.addView(statusRow)

        // **What you are running, always.** The version previously appeared only in the
        // "up to date" line — so the moment an update existed, or the check had not run, the
        // number vanished. That is the one fact a person needs to answer "am I on the build I
        // just installed?", and it should not depend on the outcome of a network call.
        card.addView(
            Theme.text(
                ctx,
                "installed: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                13f,
                Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, Theme.dp(ctx, 4), 0, 0) }
        )

        // The channel, inside the same card. A switch and its label, no nested border.
        val channelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dp(ctx, 10), 0, 0)
        }
        channelRow.addView(
            Theme.text(
                ctx,
                if (channel == com.mido.phonebridge.core.UpdatePrefs.Channel.BETA) {
                    "Beta channel — every push"
                } else {
                    "Stable channel — promoted only"
                },
                13f,
                Theme.ON_SURFACE_VARIANT,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        channelRow.addView(
            Theme.toggle(ctx, channel == com.mido.phonebridge.core.UpdatePrefs.Channel.BETA) { want ->
                com.mido.phonebridge.core.UpdatePrefs.setChannel(
                    requireContext(),
                    if (want) com.mido.phonebridge.core.UpdatePrefs.Channel.BETA
                    else com.mido.phonebridge.core.UpdatePrefs.Channel.STABLE,
                )
                // The channel changes what counts as an update, so the old answer is void.
                com.mido.phonebridge.core.Updater.lastOutcome = null
                // NOT `render()`: it would tear down this switch while its click is still being
                // dispatched, which is exactly why the knob never moved.
                checkForUpdates()
            }
        )
        card.addView(channelRow)

        // The action. One button, never two: an available update replaces "check" with "update",
        // because re-checking is not what you want when you already know.
        if (outcome is com.mido.phonebridge.core.Updater.Outcome.Available) {
            val notes = com.mido.phonebridge.core.Updater.shortNotes(outcome.notes)
            if (notes.isNotEmpty()) {
                card.addView(
                    Theme.text(ctx, notes, 12f, Theme.ON_SURFACE_VARIANT).apply {
                        setPadding(0, Theme.dp(ctx, 8), 0, 0)
                    }
                )
            }
            // **A loader from the tap until the app restarts, or until CANCEL.**
            //
            // Mido, 2026-11-04: *"clicking update should immediately show a loader until the app is
            // actually restarted or user canceling"*. Before this, the whole download-verify-install
            // ran behind a toast that vanished after two seconds, so for most of a minute the
            // screen looked like the tap had been ignored.
            //
            // The state lives on `Updater`, not on this fragment: the section is rebuilt on every
            // repaint, so a local flag would be wiped the first time anything else refreshed.
            if (com.mido.phonebridge.core.Updater.installing) {
                val busy = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, Theme.dp(ctx, 10), 0, 0)
                }
                busy.addView(android.widget.ProgressBar(ctx).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(
                        Theme.dp(ctx, 18), Theme.dp(ctx, 18)
                    ).apply { marginEnd = Theme.dp(ctx, 10) }
                })
                busy.addView(
                    Theme.text(
                        ctx,
                        "Installing v${outcome.version}… the app restarts when it finishes",
                        12f, Theme.ON_SURFACE_VARIANT,
                    ),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                card.addView(busy)

                // CANCEL, offered while the download is still running. Once Android's own
                // confirmation dialog is up, the system owns the decision and this button
                // disappears from the next repaint.
                card.addView(
                    Theme.button(ctx, "CANCEL", Theme.ButtonStyle.NEUTRAL, small = true) {
                        com.mido.phonebridge.core.Updater.installCancelled = true
                        com.mido.phonebridge.core.Updater.installing = false
                        com.mido.phonebridge.core.Updater.installingVersion = null
                        Debug.log("update: cancelled by the human")
                        refreshUpdateSection()
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(ctx, 8) },
                )
            } else {
                card.addView(
                    Theme.button(
                        ctx, "UPDATE TO v${outcome.version}", Theme.ButtonStyle.SUCCESS,
                    ) { installUpdate(outcome) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(ctx, 10) },
                )
            }
        } else {
            val check = Theme.button(
                ctx, if (checking) "CHECKING…" else "CHECK NOW", Theme.ButtonStyle.NEUTRAL,
            ) { checkForUpdates() }
            // Genuinely disabled: a second tap while a check runs would race the first result.
            check.isEnabled = !checking
            card.addView(check, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(ctx, 10) })
        }

        // When we last actually asked. From the persisted timestamp, so it is honest across a
        // restart; coarse on purpose, because a tick for "3 minutes" is not worth an alarm.
        val lastCheck = com.mido.phonebridge.core.UpdatePrefs.lastCheckAt(requireContext())
        if (lastCheck > 0L) {
            card.addView(
                Theme.text(ctx, "last checked ${agoText(lastCheck)}", 12f, Theme.ON_SURFACE_VARIANT)
                    .apply { setPadding(0, Theme.dp(ctx, 8), 0, 0) }
            )
        }

        // The unreachable case says what will actually make it work.
        //
        // **It used to blame a private repository, and that stopped being true the day the
        // project was made public.** A note that names the wrong cause is worse than no note: it
        // sends someone to check a setting that is already correct. What a human can act on is
        // "your network", "wait", or "get it from the releases page" — so that is what it says.
        if (outcome is com.mido.phonebridge.core.Updater.Outcome.Unavailable) {
            card.addView(
                Theme.text(
                    ctx,
                    "This is a network or GitHub-side problem, not a setting. The button below " +
                        "opens the releases page in your browser, which is the same APK.",
                    12f, Theme.ON_SURFACE_VARIANT,
                ).apply { setPadding(0, Theme.dp(ctx, 8), 0, 0) }
            )
        }

        // --- the way out when this mechanism fails ---
        //
        // Mido, 2026-11-04: *"in case updates mechanism go wrong, add a small button for github
        // releases page directly"*.
        //
        // Deliberately ALWAYS shown, not only when a check failed. The cases it rescues include
        // the ones the app cannot detect: a release published without an APK, a version tag this
        // build's comparison misjudges, an install the system refuses, a download that verifies
        // here and will not install there. Those all report something other than "unavailable",
        // so a fallback that only appeared on failure would be missing exactly when it is needed.
        //
        // Small and GHOST so it never competes with CHECK NOW: it is a door, not an action.
        // A GHOST door when things work, a real action when they do not.
        //
        // The button exists in both states (the failures it rescues include ones this app cannot
        // detect), but when the check just FAILED it is the only thing on the card that can
        // actually move — so it stops being a quiet link and becomes the primary control.
        val checkFailed = outcome is com.mido.phonebridge.core.Updater.Outcome.Unavailable
        card.addView(
            Theme.button(
                ctx,
                if (checkFailed) "GET IT FROM GITHUB" else "OPEN RELEASES PAGE",
                if (checkFailed) Theme.ButtonStyle.SUCCESS else Theme.ButtonStyle.GHOST,
                small = !checkFailed,
            ) {
                // Handed to the browser rather than fetched: this is the escape hatch, so it must
                // not depend on any code in this app that could be the thing that is broken.
                val url = "https://github.com/${com.mido.phonebridge.core.Updater.REPO_OWNER}" +
                    "/${com.mido.phonebridge.core.Updater.REPO_NAME}/releases"
                try {
                    go(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Exception) {
                    // No browser installed. Rare, but the one case where a dead button would look
                    // like the update system failing again.
                    Debug.log("update: cannot open releases page: ${e.message}")
                    toast("no browser to open $url")
                }
            },
            LinearLayout.LayoutParams(
                if (checkFailed) LinearLayout.LayoutParams.MATCH_PARENT
                else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(ctx, 10) },
        )
        return card
    }


    /** Check on a worker, then repaint. The main thread is never blocked on this. */
    /**
     * Rebuild ONLY the update section, posted off the current call stack.
     *
     * **Why posted, and why not `render()`.** The update controls live INSIDE `updatesHost`, so
     * rebuilding it from their own click handler removes the view whose click is still being
     * dispatched — `removeAllViews()` mid-dispatch. That is why the channel switch did not even
     * flip (reported 2026-11-04) and why CHECK NOW appeared to do nothing: the tap tore down its
     * own target before the handler finished.
     *
     * Posting defers the rebuild to the next loop, after touch dispatch has completed and the
     * pressed state has been released. Rebuilding only this section also means the rest of Home
     * is not disturbed — the hero, the gate and the audit banner keep their identity, so a press
     * anywhere else cannot be eaten by somebody toggling a channel.
     */
    private fun refreshUpdateSection() {
        val host = updatesHost
        host.post {
            if (!::updatesHost.isInitialized) return@post
            host.removeAllViews()
            host.addView(updateBlock())
        }
    }

    private fun checkForUpdates() {
        com.mido.phonebridge.core.Updater.lastOutcome = null
        com.mido.phonebridge.core.Updater.checking = true
        refreshUpdateSection()

        // **The context is taken HERE, on the main thread, before the worker starts.**
        //
        // `requireContext()` inside the thread throws `IllegalStateException` when the fragment
        // is detached — which is exactly what happens around an update: the install completes,
        // the activity is torn down, and a check started in that window dies before `check()` is
        // ever entered. `check()` clears `checking` in its own `finally`, so a throw BEFORE it
        // leaves the flag stuck at `true` forever and the card reads "checking…" until the app is
        // killed. Mido, 2026-11-10: *"the checking status right after updating is stuck forever"*.
        //
        // The application context does not belong to the fragment, so capturing it early removes
        // the whole failure rather than catching it.
        val appContext = requireContext().applicationContext

        Thread({
            try {
                val outcome = com.mido.phonebridge.core.Updater.check(appContext)
                Debug.log("update check: ${outcome.javaClass.simpleName}")
                ui { refreshUpdateSection() }
            } finally {
                // **Belt and braces.** If anything above throws before `check()` sets its own
                // `finally`, the flag must still come down: a spinner that never stops is worse
                // than a check that failed, because it hides the CHECK NOW button too.
                com.mido.phonebridge.core.Updater.checking = false
                ui { refreshUpdateSection() }
            }
        }, "update-check").apply { isDaemon = true }.start()
    }


    /**
     * Download, verify, then hand to the installer.
     *
     * **Verification is not optional.** A digest mismatch deletes the file and refuses; an
     * update that cannot be checked against what was published is exactly the thing that should
     * not be installed.
     */
    private fun installUpdate(update: com.mido.phonebridge.core.Updater.Outcome.Available) {
        // **Answered BEFORE the 42 MB download, not after it.**
        //
        // Android requires the human to allow installs for this app once. Discovering that after
        // fetching and verifying a whole APK means the only thing left to show is an error about
        // a download that was never going to be usable — and that is exactly what happened on
        // 2026-11-04, as an unreadable toast.
        if (!com.mido.phonebridge.core.ApkInstaller.canInstall(requireContext())) {
            showInstallPermissionDialog { installUpdate(update) }
            return
        }

        // **Set BEFORE the worker starts**, so the loader is on screen at the next frame rather
        // than after the first network call returns.
        com.mido.phonebridge.core.Updater.installCancelled = false
        com.mido.phonebridge.core.Updater.installingVersion = update.version
        com.mido.phonebridge.core.Updater.installing = true
        refreshUpdateSection()

        Thread({
            try {
                val got = com.mido.phonebridge.core.Updater.download(requireContext().applicationContext, update) { _ -> }
                // A cancel during the download means the bytes are not wanted. Delete them and
                // stop: installing a version the human withdrew while it downloaded is the one
                // thing a cancel button must actually prevent.
                if (com.mido.phonebridge.core.Updater.installCancelled) {
                    got.file.delete()
                    Debug.log("update: cancelled mid-download; file removed")
                    return@Thread
                }
                val expected = update.sha256
                if (expected != null) {
                    val normalized = expected.trim().lowercase().substringAfterLast(" ")
                    if (!got.sha256.equals(normalized, ignoreCase = true)) {
                        got.file.delete()
                        Debug.log("update: digest mismatch, refusing")
                        ui {
                            com.mido.phonebridge.core.Updater.installing = false
                            com.mido.phonebridge.core.Updater.installingVersion = null
                            toast("download was corrupted — refusing to install")
                            refreshUpdateSection()
                        }
                        return@Thread
                    }
                    Debug.log("update: digest verified")
                } else {
                    Debug.log("update: no published digest; installing unverified")
                }
                ui {
                    com.mido.phonebridge.core.ApkInstaller.install(requireContext(), got.file) { result ->
                        ui {
                            when (result) {
                                is com.mido.phonebridge.core.ApkInstaller.Result.ConfirmationShown ->
                                    toast("confirm the install in the dialog")
                                // The permission was revoked between the pre-check and the
                                // commit, or a managed device forbids it. Same dialog either way:
                                // the fix is a settings toggle, and it has a button.
                                is com.mido.phonebridge.core.ApkInstaller.Result.NeedsPermission ->
                                    showInstallPermissionDialog { installUpdate(update) }
                                is com.mido.phonebridge.core.ApkInstaller.Result.Refused -> {
                                    // No install is happening, so the loader must stop: a
                                    // spinner that outlives its work is the exact failure this
                                    // change exists to remove.
                                    com.mido.phonebridge.core.Updater.installing = false
                                    com.mido.phonebridge.core.Updater.installingVersion = null
                                    showProblemDialog("Update could not be installed", result.reason)
                                }
                            }
                            render()
                        }
                    }
                }
            } catch (e: Exception) {
                Debug.log("update: download failed: ${e.message}")
                ui {
                    com.mido.phonebridge.core.Updater.installing = false
                    com.mido.phonebridge.core.Updater.installingVersion = null
                    toast("download failed: ${e.message ?: e.javaClass.simpleName}")
                    refreshUpdateSection()
                }
            }
        }, "update-download").apply { isDaemon = true }.start()
    }


    /**
     * The "allow installs for this app" dialog.
     *
     * **A dialog, not a toast.** Mido, 2026-11-04: *"I didn't like that the instructions were a
     * small popup I couldn't even read. make this case a clear dialog and as easy as possible to
     * follow. if we can have a button to go to the setting page needed that would be great"*.
     *
     * A toast cannot be re-read, cannot be acted on, and disappears while the human is still
     * squinting at it. This explains WHY the permission is wanted, names the exact switch, and
     * carries a button that opens that switch's page directly instead of describing the route.
     *
     * `retry` is invoked after the human returns, so granting the permission continues the
     * install they already tapped rather than making them find it again.
     */
    private fun showInstallPermissionDialog(retry: () -> Unit) {
        confirm(
            title = "Allow STMP to install updates",
            message = "Android needs your permission once before it will let an app install its " +
                "own update.\n\n" +
                "Tap Open settings, then turn on " +
                "\"Allow from this source\". Come back here and the install carries on " +
                "by itself.\n\n" +
                "This does not let anything else install on your phone — the permission is " +
                "only for STMP.",
            positive = "Open settings",
            negative = "Not now",
        ) {
            // Remembered BEFORE settings opens: the human may grant the permission and come
            // straight back, and the resume has to know what it was in the middle of.
            pendingAfterResume = retry
            if (!com.mido.phonebridge.core.ApkInstaller.openInstallSettings(requireContext())) {
                pendingAfterResume = null
                // Neither settings page could be opened. Saying so beats launching nothing and
                // leaving the dialog looking broken.
                showProblemDialog(
                    "Cannot open settings",
                    "This build of Android would not open the install-permission page. Go to " +
                        "Settings → Apps → STMP → Install unknown apps and allow it there.",
                )
            } else {
                Debug.log("update: opened install permission settings")
            }
        }
    }


    /** A plain, readable failure dialog. Long text belongs in a dialog, never a toast. */
    private fun showProblemDialog(title: String, message: String) {
        confirm(
            title = title,
            message = message,
            positive = "OK",
            negative = "Close",
        ) { }
    }

    /**
     * A short, human "how long ago", for the last-checked line.
     *
     * Deliberately coarse: "just now", "5 min ago", "yesterday". A precise timestamp on an
     * update check implies a precision nobody needs, and a live-updating string would need a tick
     * this row has no business owning.
     */
    private fun agoText(at: Long): String {
        val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 60L * 24L -> "${minutes / 60L} h ago"
            else -> "${minutes / (60L * 24L)} d ago"
        }
    }

    /**
     * One gate mode as a large radio card.
     *
     * Mido, 2026-11-04: *"convert approval gate 3 options into the same radio cards"* — the same
     * shape as the AI backend picker, so the two choice screens in this app read identically.
     *
     * Each card states what the mode DOES, not just its name. "WHITELIST" alone is a label; the
     * consequence is what the human is choosing between, and it was previously only visible in the
     * card title AFTER selection.
     */
    private fun gateRadioCard(mode: GlobalMode, selected: Boolean): LinearLayout {
        // Shared with the onboarding flow, so the two can never describe different tradeoffs.
        val title = mode.title
        val blurb = mode.blurb

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(ctx, 14), Theme.dp(ctx, 10),
                Theme.dp(ctx, 14), Theme.dp(ctx, 10),
            )
            background = Theme.outlined(
                ctx,
                if (selected) Theme.SURFACE else Theme.SURFACE_VARIANT,
                Theme.RADIUS_CONTROL,
                strokeColor = if (selected) Theme.ACCENT else Theme.BORDER,
                strokeDp = if (selected) 2 else 1,
            )
            isClickable = true
            isFocusable = true
            // Tapping anywhere selects it. The tap goes through `requestGate`, so NOTIFY keeps its
            // confirmation — the card is a bigger target, not a faster path past the warning.
            if (!selected) setOnClickListener { requestGate(mode) }
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(ctx, 8) }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // **TOP, not CENTER_VERTICAL.** Each card is a title plus a two-line blurb;
            // centring the marker put it level with the blurb's last line, so it read as
            // belonging to nothing in particular. At the top it sits beside the title, which
            // is the thing being chosen.
            gravity = android.view.Gravity.TOP
        }
        row.addView(Theme.radioDot(ctx, selected))

        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                Theme.text(
                    ctx,
                    if (mode == GlobalMode.DONT_DISTURB) "⚠ $title" else title,
                    15f,
                    if (mode == GlobalMode.DONT_DISTURB) Theme.WARNING else Theme.ON_SURFACE,
                ).apply { typeface = Theme.handFont(ctx) }
            )
            addView(
                Theme.text(ctx, blurb, 12f, Theme.ON_SURFACE_VARIANT)
                    .apply { setPadding(0, Theme.dp(ctx, 2), 0, 0) }
            )
        }
        row.addView(
            text,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(row)

        // The rule count only makes sense under WHITELIST — the other two ignore the table.
        if (selected && mode == GlobalMode.WHITELIST) {
            card.addView(
                Theme.text(ctx, rulesSummary(), 12f, Theme.ON_SURFACE_VARIANT)
                    .apply { setPadding(0, Theme.dp(ctx, 6), 0, 0) }
            )
        }

        // **The rules door lives INSIDE the whitelist card**, because that is the only mode
        // that reads the table: PARANOID asks about everything and DON'T DISTURB resolves by
        // silence, so a rule written under either is inert.
        //
        // Greyed rather than hidden when this card is not selected. A control that vanishes
        // teaches nothing; a greyed one says "select this card and I open". An Android view
        // with `isEnabled = false` does not consume touch either, so a tap here falls through
        // to the card's own listener and selects WHITELIST — the useful response, not a dead
        // zone.
        //
        // This also retired a long-standing bug: the button used to be built ONCE in
        // `buildContent` and re-added on every repaint, which stacked up to five copies. Here
        // it is a child of a card that `renderGate` clears wholesale, so duplication is
        // structurally impossible rather than prevented by a comment.
        if (mode == GlobalMode.WHITELIST) {
            card.addView(
                Theme.button(
                    ctx,
                    // **"EDIT RULES" was the last "rules" in this card.** The summary above it
                    // now says "Exceptions decide", and a button that names a different noun
                    // than the line it sits under is the same confusion the rename set out to
                    // remove. It opens ExceptionsActivity and it edits exceptions.
                    "EDIT EXCEPTIONS",
                    Theme.ButtonStyle.NEUTRAL,
                    small = true,
                ) {
                    go(Intent(ctx, ExceptionsActivity::class.java))
                }.apply {
                    isEnabled = selected
                    // The button's own colours come from the Material theme; dimming at the
                    // view level keeps the disabled state honest without a second style.
                    alpha = if (selected) 1f else 0.45f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = Theme.dp(ctx, 8)
                    // Aligned with the text column, not the marker: it is a child of the
                    // card, not of the row.
                    marginStart = Theme.dp(ctx, 26)
                },
            )
        }
        return card
    }
}
