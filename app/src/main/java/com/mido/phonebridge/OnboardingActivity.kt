package com.mido.phonebridge

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.core.OnboardingPrefs
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.blurb
import com.mido.phonebridge.policy.title
import com.mido.phonebridge.ui.OnboardingCopy
import com.mido.phonebridge.ui.SystemAccess
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.RadioMark
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.kit.Toasts
import com.mido.phonebridge.ui.kit.TopBar
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The introduction: what STMP is, what it can do, what it needs, how the gate decides, then how
 * to connect an agent.
 *
 * **Short by rule, not by taste.** The first version explained everything carefully and read as a
 * wall: three of four steps were 3–4 sentence paragraphs at 13sp, and step 1 said the same thing
 * three times. Mido, 2026-11-10: *"the onboarding is very text heavy. more concise and larger
 * text"*. So: every step is a headline plus at most three short lines, body is 16sp, and the
 * prose is roughly a third of what it was. The detail lives where it is actionable — the bulb on
 * each plugin, `/help` for an agent, the Settings tab for permission state.
 *
 * **Permissions are a step, not a footnote.** The app cannot work without four system accesses,
 * and previously the only place to find them was Settings → Permissions, three taps from an
 * explanation of why they matter. Mido, 2026-11-10: *"the onboarding must also help user give all
 * needed permissions like the draw and full screen one"*. Each row shows live state and opens the
 * exact Settings screen.
 *
 * **Skip is always available, and Next never blocks.** A permission the human declines is a
 * degraded app, not a broken one, and an introduction that refuses to advance until four system
 * toggles are flipped is a hostage negotiation. The rows are here so the choice is informed, not
 * compulsory — and the Settings tab keeps the same rows for later.
 *
 * **Only the `seen` flag is written.** No gate mode, no toggle, no permission request is issued
 * by walking through this: the rows open Settings and the human decides there.
 */
class OnboardingActivity : FragmentActivity() {

    /** Who is being set up. Only affects the LAST step's content. */
    private enum class Agent { HERMES, GENERIC }

    private var agent = Agent.GENERIC

    /** 0-based. The last step is [STEP_COUNT] - 1. */
    private var step = 0

    /** Rebuilt wholesale on every step change. */
    private lateinit var body: LinearLayout

    private lateinit var scroll: ScrollView

    private lateinit var footer: LinearLayout

    private fun dp(v: Int) = Theme.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Marked HERE, not on finish: a human who opens this and swipes away has been
        // introduced, and the flag that means "completed a tour" is the one that nags.
        OnboardingPrefs.markSeen(this)

        val p = Theme.palette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.body)
        }
        root.addView(TopBar.create(this, "Getting started"))

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Bottom padding is SPACE_XL so the last line scrolls fully clear of the footer.
            setPadding(dp(Dimens.GUTTER), dp(Dimens.SPACE_S), dp(Dimens.GUTTER), dp(Dimens.SPACE_XL))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(View(this).apply { setBackgroundColor(p.hairline) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // The footer is a SIBLING of the scroll, not an overlay: Next must never be the thing you
        // have to scroll to find, and it must never cover the last line of a step at large text.
        footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.body)
            setPadding(dp(Dimens.GUTTER), dp(Dimens.SPACE_M), dp(Dimens.GUTTER), dp(Dimens.SPACE_M))
        }
        BottomInset.apply(footer)
        root.addView(footer)

        // Survive rotation / process death: without this a turn of the phone sends step 3 back to 1.
        savedInstanceState?.let {
            step = it.getInt(STATE_STEP, 0).coerceIn(0, STEP_COUNT - 1)
            agent = Agent.entries.getOrElse(it.getInt(STATE_AGENT, agent.ordinal)) { Agent.GENERIC }
        }

        render()
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step)
        outState.putInt(STATE_AGENT, agent.ordinal)
    }

    /**
     * Coming back from a Settings screen must show the grant.
     *
     * The permission rows read live state, and the human leaves this Activity to grant them, so
     * without this the row they just satisfied would still say "turn on". Same failure, and the
     * same fix, as `OptionsFragment.onResume`.
     */
    override fun onResume() {
        super.onResume()
        // Guarded: `footer` is lateinit and assigned in onCreate. In the normal lifecycle onCreate
        // has already rendered once, so this repaints; the guard only covers an onResume that
        // arrives with no onCreate having built the views.
        if (::footer.isInitialized) render()
    }

    /**
     * Paint the current step and the footer.
     *
     * Everything is rebuilt, never patched: a step's content is not a superset of another's, so
     * mutating in place would mean reasoning about which views to remove. The set is small and
     * text-only, so rebuilding is cheaper than the bookkeeping.
     */
    private fun render() {
        val p = Theme.palette(this)
        body.removeAllViews()
        footer.removeAllViews()

        body.addView(
            Etched.label(this, "STEP ${step + 1} OF $STEP_COUNT").apply {
                ViewCompat.setAccessibilityHeading(this, false)
            }
        )

        val heading = when (step) {
            0 -> stepWhatThisIs(p)
            1 -> stepWhatItCanDo(p)
            2 -> stepWhatItNeeds(p)
            3 -> stepHowTheGateDecides(p)
            else -> stepConnectYourAgent(p)
        }
        ViewCompat.setAccessibilityPaneTitle(scroll, "Step ${step + 1} of $STEP_COUNT. $heading")

        renderFooter()

        // Always show the TOP of the new step. Carrying the scroll offset across would land the
        // reader mid-paragraph in a section they have not seen the heading of.
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun keyParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun renderFooter() {
        val last = step == STEP_COUNT - 1

        // Skip is present on every step that has something left to skip. A flow with no exit is
        // a wall, and the app is fully usable without reading any of this.
        if (!last) {
            footer.addView(
                KeyButton.create(
                    this, "SKIP", KeyButton.Kind.OUTLINE, KeyButton.Size.REGULAR,
                    describe = "Skip the introduction",
                ) { finish() },
                keyParams(),
            )
        }

        // Spacer: pushes the primary action to the right edge so Next sits where a thumb expects
        // it and does not move between steps of different text length.
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        if (step > 0) {
            footer.addView(
                KeyButton.create(
                    this, "BACK", KeyButton.Kind.PANEL, KeyButton.Size.REGULAR,
                    describe = "Previous step",
                ) { step--; render() },
                keyParams().apply { marginEnd = dp(Dimens.SPACE_S) },
            )
        }

        footer.addView(
            KeyButton.create(
                this, if (last) "DONE" else "NEXT", KeyButton.Kind.INK, KeyButton.Size.REGULAR,
                describe = if (last) "Finish the introduction" else "Next step",
            ) { if (last) finish() else { step++; render() } },
            keyParams(),
        )
    }

    // --- 1. what this is ---------------------------------------------------

    private fun stepWhatThisIs(p: Palette): String {
        val title = "Your phone runs the bridge"
        body.addView(heading(p, title))
        body.addView(line(p, "Your agent asks. You approve on the phone."))

        body.addView(
            Panel.create(this, Panel.Tone.PLAIN).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Dimens.SPACE_M)
                addView(Type.text(this@OnboardingActivity, "Your keys never leave here", TypeRole.ROW_LABEL, p.ink))
                addView(etchedBody(p, "Google tokens, API keys, SSH keys — they live in a vault on this device. Nothing is sent to the machine running your agent.", Dimens.SPACE_S))
                addView(etchedBody(p, "So a stolen laptop, or an agent tricked into obeying something else, has nothing to take and still has to ask you.", Dimens.SPACE_S))
            }
        )
        return title
    }

    // --- 2. what it can do -------------------------------------------------

    /**
     * Capabilities by MEANING, not by plugin id.
     *
     * Curated rather than generated: thirteen rows would be a wall on a screen meant to answer
     * "should I keep this app". The Plugins tab is the live truth and names every one.
     */
    private fun stepWhatItCanDo(p: Palette): String {
        val title = "What it can do"
        body.addView(heading(p, title))

        val panel = Panel.create(this, Panel.Tone.PLAIN)
        OnboardingCopy.GROUPS.forEachIndexed { i, (group, items) ->
            panel.addView(Etched.label(this, group).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = if (i == 0) 0 else dp(Dimens.SPACE_M)
            })
            panel.addView(Type.text(this, items, TypeRole.BODY, p.ink))
        }
        body.addView(panel)

        body.addView(
            Type.text(
                this,
                "Each one can be switched off. Tap its bulb on the Plugins tab to see what it does.",
                TypeRole.DETAIL, p.etched,
            ).apply { setPadding(0, dp(Dimens.SPACE_M), 0, 0) }
        )
        return title
    }

    // --- 3. what it needs --------------------------------------------------

    /**
     * The four system accesses, each a row that opens the screen which grants it.
     *
     * Order is by consequence, not by Android's own listing: without notification access the
     * agent is blind, so it goes first. Each row states what breaks if it stays off, because a
     * toggle with no consequence attached is a toggle people skip. Labels equal Settings' labels.
     */
    private fun stepWhatItNeeds(p: Palette): String {
        val title = "What it needs to work"
        body.addView(heading(p, title))
        body.addView(line(p, "Four things Android decides, not this app. Tap any one to grant it."))

        val panel = Panel.create(this, Panel.Tone.PLAIN).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Dimens.SPACE_M)
        }
        panel.addView(permissionRow(
            "Notification access",
            "Without it your agent cannot see anything on your phone.",
            granted = BridgeService.listenerRef != null,
            optional = false,
        ) { openNotifAccessSettings() })
        panel.addView(permissionRow(
            "Draw over apps",
            "Without it the approval card cannot appear over another app.",
            granted = SystemAccess.overlayGranted(this),
            optional = false,
        ) { SystemAccess.openOverlaySettings(this) })
        panel.addView(permissionRow(
            "Lock-screen popup",
            "Without it approvals miss you while the screen is locked.",
            granted = ApprovalGate.canUseFullScreenIntent(this),
            optional = true,
        ) { SystemAccess.openFullScreenIntentSettings(this) })
        panel.addView(permissionRow(
            "Battery optimization",
            "Without it Android may freeze the bridge while the screen sleeps.",
            granted = SystemAccess.batteryExempt(this),
            optional = false,
        ) { SystemAccess.requestBatteryExemption(this) })
        body.addView(panel)

        body.addView(
            Type.text(
                this,
                "You can change these later in the Settings tab, under Permissions.",
                TypeRole.DETAIL, p.etched,
            ).apply { setPadding(0, dp(Dimens.SPACE_M), 0, 0) }
        )
        return title
    }

    /**
     * One system access, with its live state.
     *
     * Tappable in BOTH states, deliberately. Sending the human to the same Settings screen even
     * when it already reads granted costs nothing, and a row that stops responding once satisfied
     * is a row that looks broken.
     */
    private fun permissionRow(
        label: String,
        why: String,
        granted: Boolean,
        optional: Boolean,
        onClick: () -> Unit,
    ): View {
        val (led, tone, value) = when {
            granted -> Triple(Led.State.OK, StatusRow.Tone.OK, "GRANTED")
            optional -> Triple(Led.State.OFF, StatusRow.Tone.NEUTRAL, "OPTIONAL")
            else -> Triple(Led.State.CAUTION, StatusRow.Tone.CAUTION, "NEEDED")
        }
        return StatusRow.create(this, label, value, tone, detail = why, led = led, onClick = onClick)
    }

    // --- 4. how the gate decides -------------------------------------------

    /**
     * The three modes and what each one COSTS.
     *
     * The descriptions come from `GlobalMode.title`/`blurb`, the same properties the Home gate
     * card renders. Copying them here would drift silently: nobody re-reads an onboarding
     * paragraph when they tweak a card label.
     */
    private fun stepHowTheGateDecides(p: Palette): String {
        val title = "How it asks you"
        body.addView(heading(p, title))
        body.addView(line(p, OnboardingCopy.GATE_INTRO))

        val panel = Panel.create(this, Panel.Tone.PLAIN).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Dimens.SPACE_M)
        }
        GlobalMode.entries.forEachIndexed { i, mode ->
            panel.addView(Type.text(this, mode.title, TypeRole.ROW_LABEL, p.ink).apply {
                if (i > 0) setPadding(0, dp(Dimens.SPACE_M), 0, 0)
            })
            panel.addView(Type.text(this, mode.blurb, TypeRole.DETAIL, p.etched).apply {
                setPadding(0, dp(Dimens.SPACE_2XS), 0, 0)
            })
        }
        body.addView(panel)
        return title
    }

    // --- 5. connect your agent ---------------------------------------------

    private fun stepConnectYourAgent(p: Palette): String {
        val title = "Connect your agent"
        body.addView(heading(p, title))
        body.addView(line(p, "Pick what you use, then copy the block into it."))

        body.addView(
            agentCard(
                p, Agent.HERMES, "Hermes",
                "Installs the plugin and points it at this phone. Thirteen tools.",
            )
        )
        body.addView(
            agentCard(
                p, Agent.GENERIC, "Another agent",
                "Anything that can make an HTTP request. Nothing to install.",
            )
        )

        val serverOff = BridgeService.bindAddress.isBlank()
        if (serverOff) {
            body.addView(
                Type.text(this, OnboardingCopy.SERVER_OFF_NOTE, TypeRole.DETAIL, p.cautionText).apply {
                    setPadding(0, dp(Dimens.SPACE_M), 0, 0)
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
            )
        }

        when (agent) {
            Agent.HERMES -> renderHermes(p)
            Agent.GENERIC -> renderGeneric(p)
        }

        body.addView(SectionHeader.create(this, "Before it will work"))
        body.addView(line(p, "Both machines must reach each other. Same network is enough; otherwise install Tailscale on both."))
        body.addView(line(p, "The first request from a new machine asks you to name it. Once per machine."))
        return title
    }

    /** The Hermes path: install, point at this phone, expect the naming refusal. */
    private fun renderHermes(p: Palette) {
        val base = baseUrl()

        body.addView(SectionHeader.create(this, "Run these where Hermes runs"))
        body.addView(
            Type.text(this, "On the machine hosting Hermes, not inside Hermes.", TypeRole.DETAIL, p.etched)
                .apply { setPadding(0, 0, 0, dp(Dimens.SPACE_S)) }
        )

        // The address is interpolated, so the second line cannot be pasted with a stale IP.
        val commands = OnboardingCopy.hermesCommands(base)
        body.addView(Readout.create(this, commands, Readout.Kind.COMMAND).root)
        body.addView(copyKey("COPY COMMANDS", "Copy the commands", commands, enabled = BridgeService.bindAddress.isNotBlank()), copyKeyParams())

        body.addView(SectionHeader.create(this, "Then"))
        for (line in OnboardingCopy.hermesSteps(serverOn = BridgeService.bindAddress.isNotBlank())) body.addView(stepLine(p, line))

        body.addView(
            Type.text(this, "Read the plugin first: https://github.com/a7madgamal/hermes-stmp", TypeRole.DETAIL, p.etched)
                .apply {
                    setTextIsSelectable(true)
                    setPadding(0, dp(Dimens.SPACE_XS), 0, 0)
                }
        )
    }

    /** The generic path. */
    private fun renderGeneric(p: Palette) {
        body.addView(SectionHeader.create(this, "Paste this into your agent"))

        // A bounded readout: it scrolls inside itself, so the prompt never pushes the page.
        val prompt = OnboardingCopy.buildPrompt(baseUrl())
        body.addView(Readout.create(this, prompt, Readout.Kind.BODY).root)
        body.addView(copyKey("COPY PROMPT", "Copy the prompt", prompt, enabled = BridgeService.bindAddress.isNotBlank()), copyKeyParams())

        body.addView(SectionHeader.create(this, "Then"))
        for (line in OnboardingCopy.steps(serverOn = BridgeService.bindAddress.isNotBlank())) body.addView(stepLine(p, line))
    }

    // --- shared building blocks --------------------------------------------

    private fun baseUrl() = OnboardingCopy.baseUrl(BridgeService.bindAddress, BridgeService.PORT)

    /** A step heading, large enough to be the thing you read first. */
    private fun heading(p: Palette, text: String): TextView =
        Type.text(this, text, TypeRole.TITLE, p.ink).apply {
            ViewCompat.setAccessibilityHeading(this, true)
            setPadding(0, dp(Dimens.SPACE_S), 0, dp(Dimens.SPACE_S))
        }

    /** One line of body copy. `ink`, not `etched`: grey body text was the hardest to read. */
    private fun line(p: Palette, text: String): TextView =
        Type.text(this, text, TypeRole.BODY, p.ink).apply {
            setPadding(0, dp(Dimens.SPACE_XS), 0, dp(Dimens.SPACE_XS))
        }

    private fun stepLine(p: Palette, text: String): TextView =
        Type.text(this, text, TypeRole.BODY, p.ink).apply { setPadding(0, 0, 0, dp(Dimens.SPACE_S)) }

    private fun etchedBody(p: Palette, text: String, top: Int): TextView =
        Type.text(this, text, TypeRole.BODY, p.etched).apply { setPadding(0, dp(top), 0, 0) }

    private fun copyKeyParams() = keyParams().apply { topMargin = dp(Dimens.SPACE_M) }

    private fun openNotifAccessSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            Toasts.show(this, "Couldn't open notification settings")
        } catch (e: SecurityException) {
            Toasts.show(this, "Couldn't open notification settings")
        }
    }

    /**
     * One selectable agent, drawn as a panel with a radio mark.
     *
     * A hand-drawn mark rather than a stock `RadioGroup`: this app draws every control from the
     * kit, and a stock radio would be the one Material artefact on an Instrument screen.
     */
    private fun agentCard(p: Palette, choice: Agent, title: String, blurb: String): View {
        val selected = agent == choice
        val card = Panel.create(
            this, Panel.Tone.PLAIN,
            onClick = if (selected) null else ({
                agent = choice
                // Rebuild AFTER this click finishes. The card is destroyed by `render()`, and
                // tearing down a view mid-dispatch is how a tap lands and nothing happens.
                footer.post { render() }
            }),
        )
        card.minimumHeight = dp(Dimens.MIN_TARGET)
        // Panel leaves a MODULE_GAP below; zero it so the gap between cards is the 8dp top margin.
        (card.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = dp(Dimens.SPACE_S)
            bottomMargin = 0
        }
        // Panel only focuses a card that has an onClick, and the selected card has none. Without
        // this TalkBack skips it and never says "selected".
        card.isFocusable = true

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(RadioMark.create(this, selected).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(Dimens.SPACE_M)
        })
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The card's contentDescription already carries both lines; don't read them twice.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(Type.text(this@OnboardingActivity, title, TypeRole.ROW_LABEL, p.ink))
            addView(Type.text(this@OnboardingActivity, blurb, TypeRole.DETAIL, p.etched).apply {
                setPadding(0, dp(Dimens.SPACE_2XS), 0, 0)
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        // TalkBack reads "Hermes, <blurb>, radio button, selected".
        ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = selected
                info.contentDescription = "$title, $blurb"
            }
        })
        return card
    }

    /**
     * Copy [value] to the clipboard.
     *
     * Takes the string rather than reading the readout back: the readout is rebuilt on every
     * pick, so a closure over it would copy whatever was in the PREVIOUS one after the agent
     * changed.
     */
    /** [enabled] is false while the bridge is off: the text holds a placeholder address nobody should paste. */
    private fun copyKey(label: String, describe: String, value: String, enabled: Boolean = true): View =
        KeyButton.create(this, label, KeyButton.Kind.INK, KeyButton.Size.REGULAR, describe = describe) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("STMP", value))
            Toasts.show(this, "Copied")
        }.apply { isEnabled = enabled }

    private companion object {
        const val STEP_COUNT = 5
        const val STATE_STEP = "step"
        const val STATE_AGENT = "agent"
    }
}
