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
import com.mido.phonebridge.ui.Theme

/**
 * "How do I use this?" — the one screen that explains STMP to someone who just installed it.
 *
 * **The deliverable is a copyable prompt, not documentation.** STMP has no UI to speak of: the
 * phone is a server and the agent is the client. So the useful artifact is a paragraph a human
 * pastes into whatever agent they already use — Claude, Cursor, OpenClaw, a shell — and which
 * makes that agent productive without installing anything.
 *
 * **It contains no API documentation.** Everything mechanical (routes, verbs, error meanings) is
 * served live by `GET /help`, so restating it here would be a second copy that rots. The prompt
 * only tells the agent where to look and how to behave once it has looked — which is judgment,
 * and judgment is the one thing `/help` deliberately does not carry.
 *
 * **The address is read from the running service**, not typed. A hardcoded IP here would be wrong
 * on any other phone, and on this one it changes between Wi-Fi and Tailscale.
 *
 * **The agent picker changes WORDS ONLY.** Picking Hermes does not configure anything, does not
 * register anything, and does not change what the bridge serves. It swaps the text this screen
 * hands you. That separation is deliberate: an onboarding screen that quietly wrote settings
 * would be a screen you could not read and trust at the same time.
 */
class OnboardingActivity : FragmentActivity() {

    /**
     * Who is being set up.
     *
     * `HERMES` exists because that path is not a paragraph of judgment — it is a plugin install
     * and a config write, then a naming step that looks like an error and is not. A generic
     * prompt would leave the human at `caller_unverified` with no idea it was expected.
     */
    private enum class Agent { HERMES, GENERIC }

    private var agent = Agent.GENERIC

    /** Rebuilt on every pick. Holds the picker AND the content below it. */
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OnboardingActivity, 20), Theme.dp(this@OnboardingActivity, 20),
                Theme.dp(this@OnboardingActivity, 20), Theme.dp(this@OnboardingActivity, 32)
            )
            background = Theme.sheet(Theme.BG)
        }

        root.addView(Theme.text(this, "GET STARTED", 22f).apply {
            typeface = Theme.handFont(this@OnboardingActivity)
        })

        root.addView(
            Theme.text(
                this,
                "This phone is a server. Your agent calls it; you approve each call on the " +
                    "phone. There is nothing to configure inside the app beyond starting the " +
                    "server — everything else happens in your agent.",
                14f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 10), 0, 0) }
        )

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)

        render()

        setContentView(Theme.inset(ScrollView(this).apply { addView(root) }))
    }

    /**
     * Paint the picker and everything under it.
     *
     * Called from a `post { }` when a card is tapped, never inline. The card that was tapped is
     * destroyed by this method, and tearing a view down while its own click is still being
     * dispatched is the tap-eating defect `HomeFragment` documents at length — the tap lands and
     * nothing happens.
     */
    private fun render() {
        body.removeAllViews()

        // --- pick your agent ---
        body.addView(Theme.sectionHeader(this, "Which agent?"))

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
                "Anything that can make an HTTP request and read a reply. " +
                    "Nothing to install.",
            )
        )

        when (agent) {
            Agent.HERMES -> renderHermes()
            Agent.GENERIC -> renderGeneric()
        }

        // --- shared caveats, true for both paths ---
        body.addView(Theme.sectionHeader(this, "Before it will work"))

        body.addView(
            Theme.text(
                this,
                "Your agent needs to reach this phone. If both are on the same network that is " +
                    "enough; otherwise install Tailscale on both — it gives the phone an address " +
                    "that works from anywhere.",
                13f, Theme.ON_SURFACE_VARIANT
            )
        )
        body.addView(
            Theme.text(
                this,
                "The first request from a new machine makes the phone ask you to name it. " +
                    "That happens once per machine.",
                13f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 8), 0, 0) }
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

        // The address is interpolated, so the second line cannot be pasted with a stale IP. The
        // README shows `http://192.168.1.20:8787`; here it is this phone's real bind address.
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

    /** The generic path: unchanged from before the picker existed. */
    private fun renderGeneric() {
        body.addView(Theme.sectionHeader(this, "Paste this into your agent"))

        val prompt = buildPrompt()
        body.addView(
            Theme.text(
                this,
                "Works with any agent that can make an HTTP request and read a reply. " +
                    "Nothing to install.",
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
                body.post { render() }
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
     * drifts — the same mistake `respond()` made by enumerating its keys.
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
        "4. From then on, every call your agent makes asks you first — a tap for SECURE, a " +
            "five-second card for NOTIFY.",
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
        "5. From then on every gated call waits for you: a tap for SECURE, a five-second card " +
            "for NOTIFY.",
    )
}
