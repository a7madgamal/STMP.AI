package com.mido.phonebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
 * The introduction: what STMP is, what it can do, how the gate decides, then how to connect.
 *
 * **Why this is a flow and not a scroll.** The previous screen explained only HOW to connect, and
 * a new install landed on Home facing a server switch and three unexplained mode radios. Purpose
 * is not a paragraph you append — it needs room, so it gets its own steps.
 *
 * **Why steps and not swipeable pages.** Step 2 and step 4 contain selectable monospace fields.
 * Horizontal swiping fights text selection: a drag to select would flip the page instead. Back and
 * Next are unambiguous, and they leave the gesture surface to the field that needs it.
 *
 * **Opened once.** `OnboardingPrefs` records that it has been seen, and it is written on ENTRY.
 * A human who opens this, reads one step and swipes the app away has been introduced; re-opening
 * on every launch because they never reached the last page would make the flag mean "completed a
 * tour" rather than "has been introduced", and the nagging version is the one people resent.
 *
 * **It writes nothing but that flag.** No setting is changed by walking through this. An
 * onboarding that silently altered the gate would be one you could not read and trust in the same
 * breath.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Marked HERE, not on finish. See the class comment: "has been introduced" is the fact
        // worth storing, and it is the one that cannot nag.
        OnboardingPrefs.markSeen(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OnboardingActivity, 20), Theme.dp(this@OnboardingActivity, 20),
                Theme.dp(this@OnboardingActivity, 20), Theme.dp(this@OnboardingActivity, 16)
            )
            background = Theme.sheet(Theme.BG)
        }

        root.addView(Theme.text(this, "STMP", 22f).apply {
            typeface = Theme.handFont(this@OnboardingActivity)
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

    private lateinit var nav: LinearLayout

    /**
     * Paint the current step and the rail.
     *
     * Everything is rebuilt, never patched: a step's content is not a superset of another's, so
     * mutating in place would mean reasoning about which views to remove. The whole set is small
     * and text-only, so rebuilding is cheaper than the bookkeeping.
     */
    private fun render() {
        body.removeAllViews()
        nav.removeAllViews()

        body.addView(
            Theme.text(
                this,
                "Step ${step + 1} of $STEP_COUNT",
                12f, Theme.ON_SURFACE_VARIANT
            )
        )

        when (step) {
            0 -> stepWhatThisIs()
            1 -> stepWhatItCanDo()
            2 -> stepHowTheGateDecides()
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
        // without reading any of this — the Plugins tab says what each thing does.
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
        body.addView(Theme.sectionHeader(this, "What this is"))

        body.addView(
            paragraph(
                "This phone is a server, and your agent is the client. It calls the phone; you " +
                    "approve each call here. Nothing about your accounts lives on the machine " +
                    "running the agent."
            )
        )

        body.addView(Theme.sectionHeader(this, "The trust boundary is the phone"))

        body.addView(
            paragraph(
                "Google tokens, API keys and SSH private keys live in a Keystore-wrapped vault on " +
                    "this device. They are never sent anywhere — not to your agent, not to a " +
                    "server. When a call needs a credential, the PHONE makes the request and " +
                    "returns the answer, so the secret never crosses the wire."
            )
        )

        body.addView(
            paragraph(
                "That is the whole design. An agent that has been prompt-injected, or a laptop " +
                    "that has been compromised, can still only ASK — it cannot approve itself, " +
                    "and it has nothing worth stealing."
            )
        )
    }

    // --- 2. what it can do -------------------------------------------------

    /**
     * The capability summary, grouped by MEANING rather than listed exhaustively.
     *
     * Deliberately curated, not generated from `PluginRegistry`. A page that listed thirteen
     * plugins with their real ids would be a wall of names on a screen meant to answer "should I
     * keep this app". The Plugins tab is the live truth and names every one; this is the shape of
     * the thing. When a plugin is added, the tab updates and this stays a summary — which is the
     * intended division rather than a drift.
     */
    private fun stepWhatItCanDo() {
        body.addView(Theme.sectionHeader(this, "What it can do"))

        for ((group, items) in GROUPS) {
            body.addView(
                Theme.text(this, group, 14f, Theme.ACCENT).apply {
                    typeface = Theme.handFont(this@OnboardingActivity)
                    setPadding(0, Theme.dp(this@OnboardingActivity, 12), 0, Theme.dp(this@OnboardingActivity, 2))
                }
            )
            body.addView(paragraph(items))
        }

        body.addView(
            Theme.text(
                this,
                "Every plugin can be switched off individually, and each row has a ? explaining " +
                    "exactly what it serves.",
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 12), 0, 0) }
        )
    }

    // --- 3. how the gate decides -------------------------------------------

    /**
     * The three modes and what each one COSTS.
     *
     * The descriptions come from `GlobalMode.title`/`blurb` — the same properties the Home gate
     * card renders. Copying them here would drift: nobody re-reads an onboarding paragraph when
     * they tweak a card label, so this page would quietly start describing a tradeoff the app no
     * longer offered.
     */
    private fun stepHowTheGateDecides() {
        body.addView(Theme.sectionHeader(this, "How the gate decides"))

        body.addView(
            paragraph(
                "One switch governs every call. It can only ever make the app ask MORE — there is " +
                    "no setting that lets a request through without a human, so the quietest it " +
                    "gets is a five-second card."
            )
        )

        for (mode in GlobalMode.entries) {
            body.addView(
                Theme.text(this, mode.title, 15f, Theme.ON_SURFACE).apply {
                    typeface = Theme.handFont(this@OnboardingActivity)
                    setPadding(0, Theme.dp(this@OnboardingActivity, 14), 0, 0)
                }
            )
            body.addView(
                Theme.text(this, mode.blurb, 13f, Theme.ON_SURFACE_VARIANT).apply {
                    setPadding(0, Theme.dp(this@OnboardingActivity, 2), 0, 0)
                }
            )
        }

        body.addView(
            Theme.text(
                this,
                "Under Whitelist you can also write per-plugin exceptions, so a route you trust " +
                    "stops asking. Everything else keeps asking.",
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 14), 0, 0) }
        )
    }

    // --- 4. connect your agent ---------------------------------------------

    private fun stepConnectYourAgent() {
        body.addView(Theme.sectionHeader(this, "Connect your agent"))

        body.addView(paragraph("Pick what you use, then copy the block below into it."))

        // --- pick your agent ---
        body.addView(
            agentCard(
                Agent.HERMES,
                "Hermes",
                "Installs the hermes-stmp plugin and points it at this phone. " +
                    "Thirteen tools, one per plugin class.",
            )
        )
        body.addView(
            agentCard(
                Agent.GENERIC,
                "Another agent",
                "Anything that can make an HTTP request and read a reply. Nothing to install.",
            )
        )

        when (agent) {
            Agent.HERMES -> renderHermes()
            Agent.GENERIC -> renderGeneric()
        }

        // --- shared caveats, true for both paths ---
        body.addView(Theme.sectionHeader(this, "Before it will work"))

        body.addView(
            paragraph(
                "Your agent needs to reach this phone. If both are on the same network that is " +
                    "enough; otherwise install Tailscale on both — it gives the phone an address " +
                    "that works from anywhere."
            )
        )
        body.addView(
            paragraph(
                "The first request from a new machine makes the phone ask you to name it. That " +
                    "happens once per machine."
            )
        )
    }

    /** The Hermes path: install, point at this phone, expect the naming refusal. */
    private fun renderHermes() {
        val base = baseUrl()

        body.addView(Theme.sectionHeader(this, "Run these where Hermes runs"))

        body.addView(
            Theme.text(
                this,
                "Not in Hermes — on the machine that hosts it. These are shell commands.",
                12f, Theme.ON_SURFACE_VARIANT
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

        body.addView(Theme.sectionHeader(this, "What happens next"))

        for (line in hermesSteps()) {
            body.addView(
                Theme.text(this, line, 13f, Theme.ON_SURFACE).apply {
                    setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8))
                }
            )
        }

        body.addView(
            Theme.text(
                this,
                "Read the plugin first: https://github.com/a7madgamal/hermes-stmp",
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 4), 0, 0) }
        )
    }

    /** The generic path. */
    private fun renderGeneric() {
        body.addView(Theme.sectionHeader(this, "Paste this into your agent"))

        val prompt = buildPrompt()
        body.addView(
            Theme.text(
                this,
                "Works with any agent that can make an HTTP request and read a reply.",
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8)) }
        )

        body.addView(monoField(prompt))
        body.addView(
            copyButton("COPY PROMPT", prompt),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(this@OnboardingActivity, 12) }
        )

        body.addView(Theme.sectionHeader(this, "What happens next"))

        for (line in steps()) {
            body.addView(
                Theme.text(this, line, 13f, Theme.ON_SURFACE).apply {
                    setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8))
                }
            )
        }
    }

    /** A paragraph with the standard gap beneath it. */
    private fun paragraph(text: String): TextView =
        Theme.text(this, text, 13f, Theme.ON_SURFACE).apply {
            setPadding(0, Theme.dp(this@OnboardingActivity, 6), 0, Theme.dp(this@OnboardingActivity, 6))
        }

    /**
     * One selectable agent, drawn as the same radio card the gate picker uses.
     *
     * Reusing `Theme.radioDot` rather than a `RadioGroup` is not laziness: this app draws every
     * control by hand, and a stock radio would be the one Material artefact on a paper screen.
     */
    private fun agentCard(choice: Agent, title: String, blurb: String): LinearLayout {
        val selected = agent == choice
        val card = LinearLayout(this).apply {
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
        card.layoutParams = LinearLayout.LayoutParams(
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
            addView(Theme.text(this@OnboardingActivity, title, 15f, Theme.ON_SURFACE).apply {
                typeface = Theme.handFont(this@OnboardingActivity)
            })
            addView(
                Theme.text(this@OnboardingActivity, blurb, 12f, Theme.ON_SURFACE_VARIANT)
                    .apply { setPadding(0, Theme.dp(this@OnboardingActivity, 2), 0, 0) }
            )
        }
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
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
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 13f
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
        "1. Start the server on the home screen — the address above is only real once it is running.",
        "2. Paste the prompt into your agent and send it.",
        "3. Approve the first request on the phone. If it is a naming prompt, name this machine.",
        "4. From then on, every call your agent makes asks you first — a tap for Whitelist or " +
            "Paranoid, a five-second card for Don't disturb.",
    )

    /**
     * The Hermes steps, which are a different shape from the generic ones.
     *
     * Step 3 is the important line: `caller_unverified` looks like a failure and is not. Left
     * unexplained, it is the point where people conclude the plugin is broken.
     */
    private fun hermesSteps(): List<String> = listOf(
        "1. Start the server on the home screen — the address in those commands is only real " +
            "once it is running.",
        "2. Run the two commands above on the machine that hosts Hermes.",
        "3. Call stmp_help. The first call is refused with caller_unverified — that is the " +
            "phone asking who this machine is, not a fault.",
        "4. Approve that prompt on the phone and name the machine, then retry. It is named once.",
        "5. From then on every gated call waits for you — a tap for Whitelist or Paranoid, a " +
            "five-second card for Don't disturb.",
    )

    private companion object {
        const val STEP_COUNT = 4

        /** Curated, not exhaustive — see [stepWhatItCanDo]. */
        val GROUPS: List<Pair<String, String>> = listOf(
            "Google" to "Gmail, Calendar and Drive — read, send, create, trash, upload. " +
                "The tokens stay in the phone's vault.",
            "Phone" to "Read and act on notifications, dismiss them, and search your contacts.",
            "Machine" to "Run shell commands over SSH from a key generated on the phone. It " +
                "never leaves this device.",
            "Voice" to "Speak text aloud, and play cues so a request can reach you when you " +
                "are not looking at the screen.",
            "Plumbing" to "API contract, logs, an audit trail and a self-report — so an agent " +
                "can diagnose itself without asking you to read a screen.",
        )
    }
}
