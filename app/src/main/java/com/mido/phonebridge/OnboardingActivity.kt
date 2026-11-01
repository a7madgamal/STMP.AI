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
 */
class OnboardingActivity : FragmentActivity() {

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

        // --- the prompt ---
        root.addView(Theme.sectionHeader(this, "Paste this into your agent"))

        val prompt = buildPrompt()
        root.addView(
            Theme.text(
                this,
                "Works with any agent that can make an HTTP request and read a reply. " +
                    "Nothing to install.",
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8)) }
        )

        // Read-only and selectable rather than a plain TextView: the human may want to fix the
        // address by hand (a different interface, a proxy), and a value you cannot select is a
        // value you have to retype from a screenshot.
        val field = EditText(this).apply {
            setText(prompt)
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
        root.addView(field)

        root.addView(
            Theme.button(this, "COPY PROMPT", Theme.ButtonStyle.PRIMARY) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("STMP prompt", field.text.toString()))
                Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(this@OnboardingActivity, 12) }
        )

        // --- what happens next ---
        root.addView(Theme.sectionHeader(this, "What happens next"))

        for (line in steps()) {
            root.addView(
                Theme.text(this, line, 13f, Theme.ON_SURFACE).apply {
                    setPadding(0, 0, 0, Theme.dp(this@OnboardingActivity, 8))
                }
            )
        }

        // --- the honest caveat ---
        root.addView(Theme.sectionHeader(this, "Before it will work"))

        root.addView(
            Theme.text(
                this,
                "Your agent needs to reach this phone. If both are on the same network that is " +
                    "enough; otherwise install Tailscale on both — it gives the phone an address " +
                    "that works from anywhere.",
                13f, Theme.ON_SURFACE_VARIANT
            )
        )
        root.addView(
            Theme.text(
                this,
                "The first request from a new machine makes the phone ask you to name it. " +
                    "That happens once per machine.",
                13f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(this@OnboardingActivity, 8), 0, 0) }
        )

        setContentView(Theme.inset(ScrollView(this).apply { addView(root) }))
    }

    /**
     * The prompt itself.
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
}
