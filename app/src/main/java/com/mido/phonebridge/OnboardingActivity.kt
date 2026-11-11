package com.mido.phonebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.core.OnboardingPrefs
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.blurb
import com.mido.phonebridge.policy.title
import com.mido.phonebridge.ui.Theme

/**
 * The introduction: what STMP is, what it can do, what it needs, how the gate decides, then how
 * to connect an agent.
 *
 * **Short by rule, not by taste.** The first version explained everything carefully and read as a
 * wall: three of four steps were 3–4 sentence paragraphs at 13sp, and step 1 said the same thing
 * three times. Mido, 2026-11-10: *"the onboarding is very text heavy. more concise and larger
 * text"*. So: every step is a headline plus at most three short lines, body is 16sp, and the
 * prose is roughly a third of what it was. The detail lives where it is actionable — the `?` on
 * each plugin, `/help` for an agent, Settings → Permissions for state.
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
 * compulsory — and Settings → Permissions keeps the same rows for later.
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

    private lateinit var nav: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Marked HERE, not on finish: a human who opens this and swipes away has been
        // introduced, and the flag that means "completed a tour" is the one that nags.
        OnboardingPrefs.markSeen(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OnboardingActivity, 20), Theme.dp(this@OnboardingActivity, 20),
                Theme.dp(this@OnboardingActivity, 20), Theme.dp(this@OnboardingActivity, 16)
            )
            background = Theme.sheet(Theme.BG)
        }

        root.addView(Theme.text(this, "STMP", 26f).apply {
            typeface = Theme.bodyFont(this@OnboardingActivity)
        })

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // The nav rail is OUTSIDE the scroll: Next must never be the thing you have to scroll to
        // find, which is exactly how a long introduction becomes one nobody finishes.
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dp(this@OnboardingActivity, 12), 0, 0)
        }
        root.addView(nav)

        render()
        setContentView(root)
    }

    /**
     * Coming back from a Settings screen must show the grant.
     *
     * The permission rows read live state, and the human leaves this Activity to grant them — so
     * without this the row they just satisfied would still say "turn on". Same failure, and the
     * same fix, as `OptionsFragment.onResume`.
     */
    override fun onResume() {
        super.onResume()
        // Guarded: onResume runs before the first render() has built the rail.
        if (::nav.isInitialized) render()
    }

    /**
     * Paint the current step and the rail.
     *
     * Everything is rebuilt, never patched: a step's content is not a superset of another's, so
     * mutating in place would mean reasoning about which views to remove. The set is small and
     * text-only, so rebuilding is cheaper than the bookkeeping.
     */
    private fun render() {
        body.removeAllViews()
        nav.removeAllViews()

        body.addView(
            Theme.text(
                this,
                "${step + 1} / $STEP_COUNT",
                13f, Theme.ON_SURFACE_VARIANT
            )
        )

        when (step) {
            0 -> stepWhatThisIs()
            1 -> stepWhatItCanDo()
            2 -> stepWhatItNeeds()
            3 -> stepHowTheGateDecides()
            else -> stepConnectYourAgent()
        }

        renderNav()

        // Always show the TOP of the new step. Carrying the scroll offset across would land the
        // reader mid-paragraph in a section they have not seen the heading of.
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun renderNav() {
        val last = step == STEP_COUNT - 1

        // Skip is always present. A flow with no exit is a wall, and the app is fully usable
        // without reading any of this.
        nav.addView(
            Theme.button(this, if (last) "CLOSE" else "SKIP", Theme.ButtonStyle.GHOST, small = true) {
                finish()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Spacer: pushes the primary action to the right edge so Next sits where a thumb expects
        // it and does not move between steps of different text length.
        nav.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))

        if (step > 0) {
            nav.addView(
                Theme.button(this, "BACK", Theme.ButtonStyle.NEUTRAL, small = true) {
                    step--
                    render()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Theme.dp(this@OnboardingActivity, 8) }
            )
        }

        nav.addView(
            Theme.button(
                this,
                if (last) "DONE" else "NEXT",
                Theme.ButtonStyle.PRIMARY,
                small = true,
            ) {
                if (last) finish() else { step++; render() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    // --- 1. what this is ---------------------------------------------------

    private fun stepWhatThisIs() {
        body.addView(heading("Your phone is the server"))
        body.addView(line("Your agent asks. You approve on the phone."))

        body.addView(
            card {
                addView(
                    Theme.text(this@OnboardingActivity, "Your keys never leave here", 18f, Theme.ON_SURFACE).apply {
                        typeface = Theme.bodyFont(this@OnboardingActivity)
                    }
                )
                addView(
                    Theme.text(
                        this@OnboardingActivity,
                        "Google tokens, API keys, SSH keys — they live in a vault on this device. Nothing is sent to the machine running your agent.",
                        16f, Theme.ON_SURFACE_VARIANT,
                    ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 6), 0, 0) }
                )
                addView(
                    Theme.text(
                        this@OnboardingActivity,
                        "So a stolen laptop, or an agent tricked into obeying something else, has nothing to take and still has to ask you.",
                        16f, Theme.ON_SURFACE_VARIANT,
                    ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 8), 0, 0) }
                )
            }
        )
    }

    // --- 2. what it can do -------------------------------------------------

    /**
     * Capabilities by MEANING, not by plugin id.
     *
     * Curated rather than generated: thirteen rows would be a wall on a screen meant to answer
     * "should I keep this app". The Plugins tab is the live truth and names every one.
     */
    private fun stepWhatItCanDo() {
        body.addView(heading("What it can do"))

        for ((group, items) in GROUPS) {
            body.addView(
                Theme.text(this, group, 17f, Theme.ACCENT).apply {
                    typeface = Theme.bodyFont(this@OnboardingActivity)
                    setPadding(0, Theme.dp(this@OnboardingActivity, 16), 0, Theme.dp(this@OnboardingActivity, 2))
                }
            )
            body.addView(line(items))
        }

        body.addView(
            Theme.text(
                this,
                "Each one can be switched off, and its ? explains what it does.",
                14f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 16), 0, 0) }
        )
    }

    // --- 3. what it needs --------------------------------------------------

    /**
     * The four system accesses, each a row that opens the screen which grants it.
     *
     * Order is by consequence, not by Android's own listing: without notification access the
     * agent is blind, so it goes first. Each row states what breaks if it stays off — a toggle
     * with no consequence attached is a toggle people skip.
     */
    private fun stepWhatItNeeds() {
        body.addView(heading("What it needs to work"))

        body.addView(line("Four things Android decides, not this app. Tap any one to grant it."))

        permissionRow(
            "Notification access",
            "Without it your agent cannot see anything on your phone.",
            granted = BridgeService.listenerRef != null,
            optional = false,
        ) { openNotifAccessSettings() }

        permissionRow(
            "Draw over other apps",
            "Without it the approval card cannot appear over another app.",
            granted = com.mido.phonebridge.ui.SystemAccess.overlayGranted(this),
            optional = false,
        ) { com.mido.phonebridge.ui.SystemAccess.openOverlaySettings(this) }

        permissionRow(
            "Lock-screen popup",
            "Without it approvals miss you while the screen is locked.",
            granted = ApprovalGate.canUseFullScreenIntent(this),
            optional = true,
        ) { com.mido.phonebridge.ui.SystemAccess.openFullScreenIntentSettings(this) }

        permissionRow(
            "Battery optimization",
            "Without it Android may freeze the server while the screen sleeps.",
            granted = com.mido.phonebridge.ui.SystemAccess.batteryExempt(this),
            optional = false,
        ) { com.mido.phonebridge.ui.SystemAccess.requestBatteryExemption(this) }

        body.addView(
            Theme.text(
                this,
                "You can change these later in Settings → Permissions.",
                14f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 16), 0, 0) }
        )
    }

    /**
     * One system access, with its live state.
     *
     * Tappable in BOTH states, deliberately. Sending the human to the same Settings screen even
     * when it already reads granted costs nothing, and a row that stops responding once satisfied
     * is a row that looks broken.
     */
    private fun permissionRow(
        title: String,
        why: String,
        granted: Boolean,
        optional: Boolean,
        onClick: () -> Unit,
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OnboardingActivity, 14), Theme.dp(this@OnboardingActivity, 12),
                Theme.dp(this@OnboardingActivity, 14), Theme.dp(this@OnboardingActivity, 12),
            )
            background = Theme.outlined(
                this@OnboardingActivity,
                Theme.SURFACE,
                Theme.RADIUS_CONTROL,
                strokeColor = if (granted) Theme.SUCCESS else Theme.BORDER,
                strokeDp = if (granted) 2 else 1,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(this@OnboardingActivity, 10) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        row.addView(
            Theme.text(this, title, 17f, Theme.ON_SURFACE).apply {
                typeface = Theme.bodyFont(this@OnboardingActivity)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Theme.text(
                this,
                when {
                    granted -> "on"
                    optional -> "optional"
                    else -> "turn on"
                },
                14f,
                if (granted) Theme.SUCCESS else Theme.WARNING,
            )
        )
        card.addView(row)

        card.addView(
            Theme.text(this, why, Theme.TEXT_BODY_SP, Theme.ON_SURFACE_VARIANT).apply {
                setPadding(0, Theme.dp(this@OnboardingActivity, 4), 0, 0)
            }
        )
        body.addView(card)
    }

    // --- 4. how the gate decides -------------------------------------------

    /**
     * The three modes and what each one COSTS.
     *
     * The descriptions come from `GlobalMode.title`/`blurb` — the same properties the Home gate
     * card renders. Copying them here would drift silently: nobody re-reads an onboarding
     * paragraph when they tweak a card label.
     */
    private fun stepHowTheGateDecides() {
        body.addView(heading("How it asks you"))
        body.addView(line("One switch for everything. It can only make the app ask more, never less."))

        for (mode in GlobalMode.entries) {
            body.addView(
                Theme.text(this, mode.title, 17f, Theme.ON_SURFACE).apply {
                    typeface = Theme.bodyFont(this@OnboardingActivity)
                    setPadding(0, Theme.dp(this@OnboardingActivity, 14), 0, 0)
                }
            )
            body.addView(
                Theme.text(this, mode.blurb, Theme.TEXT_BODY_SP, Theme.ON_SURFACE_VARIANT).apply {
                    setPadding(0, Theme.dp(this@OnboardingActivity, 2), 0, 0)
                }
            )
        }
    }

    // --- 5. connect your agent ---------------------------------------------

    private fun stepConnectYourAgent() {
        body.addView(heading("Connect your agent"))
        body.addView(line("Pick what you use, then copy the block into it."))

        body.addView(
            agentCard(
                Agent.HERMES,
                "Hermes",
                "Installs the plugin and points it at this phone. Thirteen tools.",
            )
        )
        body.addView(
            agentCard(
                Agent.GENERIC,
                "Another agent",
                "Anything that can make an HTTP request. Nothing to install.",
            )
        )

        when (agent) {
            Agent.HERMES -> renderHermes()
            Agent.GENERIC -> renderGeneric()
        }

        body.addView(Theme.sectionHeader(this, "Before it will work"))
        body.addView(line("Both machines must reach each other. Same network is enough; otherwise install Tailscale on both."))
        body.addView(line("The first request from a new machine asks you to name it. Once per machine."))
    }

    /** The Hermes path: install, point at this phone, expect the naming refusal. */
    private fun renderHermes() {
        val base = baseUrl()

        body.addView(Theme.sectionHeader(this, "Run these where Hermes runs"))
        body.addView(
            Theme.text(
                this,
                "On the machine hosting Hermes — not in Hermes.",
                14f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8)) }
        )

        // The address is interpolated, so the second line cannot be pasted with a stale IP.
        val commands = """
            hermes plugins install a7madgamal/hermes-stmp --enable
            hermes config set plugins.entries.stmp.settings.base_url $base
        """.trimIndent()

        body.addView(monoField(commands))
        body.addView(
            copyButton("COPY COMMANDS", commands),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(this@OnboardingActivity, 12) }
        )

        body.addView(Theme.sectionHeader(this, "Then"))

        for (line in hermesSteps()) {
            body.addView(
                Theme.text(this, line, 16f, Theme.ON_SURFACE).apply {
                    setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8))
                }
            )
        }

        body.addView(
            Theme.text(
                this,
                "Read the plugin first: https://github.com/a7madgamal/hermes-stmp",
                14f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 4), 0, 0) }
        )
    }

    /** The generic path. */
    private fun renderGeneric() {
        body.addView(Theme.sectionHeader(this, "Paste this into your agent"))

        val prompt = buildPrompt()
        body.addView(monoField(prompt))
        body.addView(
            copyButton("COPY PROMPT", prompt),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(this@OnboardingActivity, 12) }
        )

        body.addView(Theme.sectionHeader(this, "Then"))

        for (line in steps()) {
            body.addView(
                Theme.text(this, line, 16f, Theme.ON_SURFACE).apply {
                    setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8))
                }
            )
        }
    }

    // --- shared building blocks --------------------------------------------

    /** A step heading, large enough to be the thing you read first. */
    private fun heading(text: String): TextView =
        Theme.text(this, text, 24f, Theme.ON_SURFACE).apply {
            typeface = Theme.bodyFont(this@OnboardingActivity)
            setPadding(0, Theme.dp(this@OnboardingActivity, 10), 0, Theme.dp(this@OnboardingActivity, 6))
        }

    /** One line of body copy at reading size. */
    private fun line(text: String): TextView =
        Theme.text(this, text, 16f, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, Theme.dp(this@OnboardingActivity, 4), 0, Theme.dp(this@OnboardingActivity, 4))
        }

    /** A paper card holding a few related lines. */
    private fun card(content: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OnboardingActivity, 14), Theme.dp(this@OnboardingActivity, 12),
                Theme.dp(this@OnboardingActivity, 14), Theme.dp(this@OnboardingActivity, 12),
            )
            background = Theme.outlined(
                this@OnboardingActivity, Theme.SURFACE_VARIANT, Theme.RADIUS_CARD,
            )
            content()
        }
        c.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(this@OnboardingActivity, 10) }
        return c
    }

    private fun openNotifAccessSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            Toast.makeText(this, "could not open notification settings", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * One selectable agent, drawn as the same radio card the gate picker uses.
     *
     * Reusing `Theme.radioDot` rather than a `RadioGroup` is not laziness: this app draws every
     * control by hand, and a stock radio would be the one Material artefact on a paper screen.
     */
    private fun agentCard(choice: Agent, title: String, blurb: String): LinearLayout {
        val selected = agent == choice
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OnboardingActivity, 14), Theme.dp(this@OnboardingActivity, 10),
                Theme.dp(this@OnboardingActivity, 14), Theme.dp(this@OnboardingActivity, 10),
            )
            background = Theme.outlined(
                this@OnboardingActivity,
                if (selected) Theme.SURFACE else Theme.SURFACE_VARIANT,
                Theme.RADIUS_CONTROL,
                strokeColor = if (selected) Theme.ACCENT else Theme.BORDER,
                strokeDp = if (selected) 2 else 1,
            )
            isClickable = true
            isFocusable = true
            if (!selected) setOnClickListener {
                agent = choice
                // Rebuild AFTER this click finishes. The card is destroyed by `render()`, and
                // tearing down a view mid-dispatch is how a tap lands and nothing happens.
                nav.post { render() }
            }
        }
        cardLayout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(this@OnboardingActivity, 8) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
        }
        row.addView(Theme.radioDot(this, selected))

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Theme.text(this@OnboardingActivity, title, 17f, Theme.ON_SURFACE).apply {
                typeface = Theme.bodyFont(this@OnboardingActivity)
            })
            addView(
                Theme.text(this@OnboardingActivity, blurb, Theme.TEXT_BODY_SP, Theme.ON_SURFACE_VARIANT)
                    .apply { setPadding(0, Theme.dp(this@OnboardingActivity, 2), 0, 0) }
            )
        }
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cardLayout.addView(row)
        return cardLayout
    }

    /**
     * A selectable, monospace, read-only block.
     *
     * An `EditText` rather than a `TextView` because the value must be selectable: the human may
     * want to fix the address by hand (a proxy, a different interface), and a value you cannot
     * select is a value you retype from a screenshot.
     */
    private fun monoField(value: String): EditText = EditText(this).apply {
        setText(value)
        isSingleLine = false
        isFocusable = true
        isClickable = true
        setTextColor(Theme.ON_SURFACE)
        setHintTextColor(Theme.ON_SURFACE_VARIANT)
        highlightColor = Theme.ACCENT
        typeface = Theme.monoFont(this@OnboardingActivity)
        textSize = Theme.TEXT_BODY_SP
        background = Theme.outlined(this@OnboardingActivity, Theme.SURFACE, Theme.RADIUS_CARD)
        setPadding(
            Theme.dp(this@OnboardingActivity, 12), Theme.dp(this@OnboardingActivity, 12),
            Theme.dp(this@OnboardingActivity, 12), Theme.dp(this@OnboardingActivity, 12)
        )
        setEnabled(true)
    }

    /**
     * Copy [value] to the clipboard.
     *
     * Takes the string rather than reading the field back: the field is rebuilt on every pick,
     * so a closure over it would copy whatever happened to be in the PREVIOUS field after the
     * agent changed.
     */
    private fun copyButton(label: String, value: String) =
        Theme.button(this, label, Theme.ButtonStyle.PRIMARY) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("STMP", value))
            Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show()
        }

    /**
     * The generic prompt.
     *
     * Only judgment and a pointer: where to look (`/help`), what a refusal means (never retry a
     * decline), and how to check the gate works (`/probe`, where a denial is a pass). Every route
     * and error is described by the endpoint, so naming them here would be the second copy that
     * drifts.
     */
    private fun buildPrompt(): String {
        val base = baseUrl()
        return """
            You can control my Android phone through STMP, an HTTP bridge at $base

            First call:
              GET $base/help

            It returns the whole API as JSON, plus a "markdown" field describing it in
            prose. Read the markdown. Note the contractVersion — if you ever see a higher
            one, re-read /help and treat it as current.

            Then work from that. Rules that matter and are NOT in the response:

            - Every call is approved by me on the phone. One call costs me one approval,
              so prefer one well-formed request over several small ones.
            - A 403 with error "denied" means I declined. Do not retry it, do not rephrase
              it, and do not route around it. Tell me what you were trying to do and stop.
            - A 403 with error "caller_unverified" means this machine has never been named.
              Tell me to check the phone; I will name it and you can retry once.
            - A 403 with error "caller_denied" means I blocked this machine. Stop.
            - 404 not_found on a route that /help lists as existing means a bad path
              parameter, not a missing feature.
            - Never send a credential. Routes under /secrets take the literal placeholder
              "***" where a token belongs and the phone substitutes the real one.

            Finally, confirm the gate reaches me:
              GET $base/probe

            A 200 means a request went through the approval gate and reached the handler.
            A 403 "denied" ALSO means it works — I declined and the gate honoured it.
            Either way the gate is alive; only a timeout or a 5xx is a real failure.
        """.trimIndent()
    }

    /** The real address, or a clearly-marked fallback when the server is not up yet. */
    private fun baseUrl(): String {
        val host = BridgeService.bindAddress
        return if (host.isBlank()) {
            // Not started yet. Saying "http://:8787" would look like a typo; saying so is
            // actionable — the human just has to start the server.
            "http://<start the server first>:${BridgeService.PORT}"
        } else {
            "http://$host:${BridgeService.PORT}"
        }
    }

    private fun steps(): List<String> = listOf(
        "1. Start the server on the home screen.",
        "2. Paste the prompt into your agent and send it.",
        "3. Approve the first request. Name this machine if asked.",
        "4. From then on, every call asks you first.",
    )

    /**
     * The Hermes steps, which are a different shape from the generic ones.
     *
     * Step 2 is the important line: `caller_unverified` looks like a failure and is not. Left
     * unexplained, it is the point where people conclude the plugin is broken.
     */
    private fun hermesSteps(): List<String> = listOf(
        "1. Start the server on the home screen.",
        "2. Run the two commands above.",
        "3. Call stmp_help. It is refused with caller_unverified — that is the phone asking who this machine is, not a fault.",
        "4. Approve it on the phone and retry. Named once.",
        "5. From then on, every call asks you first.",
    )

    private companion object {
        const val STEP_COUNT = 5

        /** Curated, not exhaustive — see [stepWhatItCanDo]. */
        val GROUPS: List<Pair<String, String>> = listOf(
            "Google" to "Gmail, Calendar and Drive. Read, send, schedule, upload.",
            "Phone" to "Notifications and contacts.",
            "Machine" to "Shell commands over SSH, key made on the phone.",
            "Voice" to "Speak text aloud, play a cue to reach you.",
            "Plumbing" to "Logs, audit trail, self-report — so it can diagnose itself.",
        )
    }
}
