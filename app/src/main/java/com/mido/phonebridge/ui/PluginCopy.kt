package com.mido.phonebridge.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Plain-English descriptions and example prompts, per plugin.
 *
 * **Why this is hand-written when the technical help was generated.** The generated version listed
 * routes, methods and permission ids — accurate, and useless to anyone who is not the person who
 * wrote the plugin. Mido, 2026-11-10: *"friendly not a fucking api documentation. a non developer
 * simple description of the features and example prompts to explain"*.
 *
 * The trade is real and worth stating: this copy can drift from what a plugin serves. It is kept
 * short and about PURPOSE rather than mechanism, because purpose changes rarely and routes change
 * often. The `?` dialog is for deciding whether to switch something on; `/help` remains the
 * accurate contract for anyone who needs one.
 *
 * Example prompts are written the way a human talks to an agent, not the way an API is called.
 * They are the part that makes a feature click: "read my last email" explains Gmail faster than
 * any list of endpoints.
 */
object PluginCopy {

    /** What a plugin is FOR, plus things a person might actually ask for. */
    data class Info(
        val blurb: String,
        val examples: List<String> = emptyList(),
    )

    private val BY_ID: Map<String, Info> = mapOf(
        "notifications" to Info(
            "Lets your agent see what is on your phone's screen — messages, emails, alerts — " +
                "and act on them. It can read a notification, press one of its buttons, or " +
                "dismiss it.",
            listOf(
                "What are my unread messages?",
                "Dismiss that WhatsApp notification.",
                "Reply \"on my way\" to the last message from Sara.",
            ),
        ),
        "google" to Info(
            "Your Google account on this phone. Signing in here is what the Gmail, Calendar and " +
                "Drive plugins use — and the tokens stay in the phone, never on the machine " +
                "running your agent.",
            listOf("Which Google account is connected?"),
        ),
        "gmail" to Info(
            "Read, search and send email. Your agent can summarise an inbox, open a message, " +
                "archive or trash it, or write a reply.",
            listOf(
                "Any important email today?",
                "Archive everything from that newsletter.",
                "Send a short reply saying I'll look at it tomorrow.",
            ),
        ),
        "calendar" to Info(
            "See and change your calendar. Your agent can list what is coming up, add an event, " +
                "move one, or delete it.",
            listOf(
                "What's on my calendar tomorrow?",
                "Add lunch with Karim on Thursday at 1pm.",
                "Push my 3pm meeting back an hour.",
            ),
        ),
        "drive" to Info(
            "Browse and manage Google Drive. Your agent can find a file, download it to the " +
                "phone, upload something, or tidy things into folders.",
            listOf(
                "Find the invoice PDF from last month.",
                "Upload the file I just sent you to Drive.",
                "Move everything in my root folder into an Archive folder.",
            ),
        ),
        "contacts" to Info(
            "Search your phone's contacts by name, number or email. Read-only — it can look " +
                "people up but never change them.",
            listOf(
                "What's Sara's number?",
                "Find everyone with an @company.com email.",
            ),
        ),
        "ssh" to Info(
            "Run commands on a server from this phone. The SSH key is generated here and never " +
                "leaves the device, so the server trusts the phone rather than your laptop.",
            listOf(
                "Check how much disk is left on the server.",
                "Restart the web service.",
                "Tail the last 50 lines of the error log.",
            ),
        ),
        "secrets" to Info(
            "API keys that stay on this phone. Your agent can ask the phone to call a service " +
                "using the key, but it never sees the key itself — so a compromised machine has " +
                "nothing to steal.",
            listOf(
                "What secrets are stored?",
                "Add the todoist task \"buy milk\".",
            ),
        ),
        "tts" to Info(
            "Makes the phone speak out loud. Useful for getting your attention, or having a long " +
                "answer read to you while your hands are busy.",
            listOf(
                "Read me the summary out loud.",
                "Say that I'm on my way.",
            ),
        ),
        "audio" to Info(
            "Plays a short cue — a chime or an alarm — so your agent can reach you when you are " +
                "not looking at the screen.",
            listOf("Play the alert sound so I notice."),
        ),
        "logs" to Info(
            "The app's own log. Your agent can read it to work out why something failed, without " +
                "asking you to describe a screen you cannot see.",
            listOf("Why did that last call fail? Show me the recent errors."),
        ),
        "debug" to Info(
            "A health report about the phone: battery, storage, permissions, which plugins are " +
                "on, and what recently happened. The first thing to check when something feels " +
                "wrong.",
            listOf("Is the phone healthy? Anything need my attention?"),
        ),
        "help" to Info(
            "The live manual. Your agent reads this to learn every route and error code, so it " +
                "does not have to be told upfront.",
            listOf("What can you do on my phone?"),
        ),
        "probe" to Info(
            "A test call that proves you are reachable. Your agent uses it before a long run: if " +
                "it fails, nobody is at the phone and the run should stop instead of stacking " +
                "prompts nobody will answer.",
            listOf("Check whether I'm at my phone before you start."),
        ),
    )

    /**
     * Copy for [id], or a neutral fallback.
     *
     * The fallback exists so a newly registered plugin still opens something readable. It says
     * what it can honestly say — the name — instead of pretending to know more.
     */
    fun forId(id: String, displayName: String): Info =
        BY_ID[id] ?: Info(
            "$displayName is one of the app's plugins. Open it to see the routes it serves.",
            emptyList(),
        )

    /** The message when a plugin is switched off. Shared so the wording cannot vary by screen. */
    const val OFF_NOTE: String =
        "Switched off. Nothing is broken — its routes stay registered and simply answer that " +
            "the plugin is disabled, so no call starts failing with a 404."

    /** How the status sentence should read, for the caller to pick a colour from. */
    enum class LineTone { OK, NEUTRAL, CAUTION }

    data class StatusLine(val text: String, val tone: LineTone)

    /**
     * What the help dialog says about a plugin's state, from the plugin's own report.
     *
     * **Never "ready" unless the plugin said so.** This used to print "On, and ready." whenever
     * the stored switch was on, so the one screen a person opens to ask "why is this not working"
     * claimed everything was fine — for a plugin with no secret saved, or missing the permission
     * its own `status()` reports. [status] null means nobody asked, which is stated as itself.
     */
    fun statusLine(enabled: Boolean, status: com.mido.phonebridge.core.PluginStatus?): StatusLine {
        if (!enabled) return StatusLine(OFF_NOTE, LineTone.NEUTRAL)
        if (status == null) return StatusLine("On. Its state was not read.", LineTone.NEUTRAL)
        val detail = status.detail.trim()
        fun withDetail(lead: String): String = when {
            detail.isEmpty() -> lead
            detail.last() in ".!?" -> "$lead $detail"
            else -> "$lead $detail."
        }
        return when (status.state) {
            com.mido.phonebridge.core.PluginStatus.State.OK ->
                StatusLine(withDetail("On, and ready."), LineTone.OK)
            com.mido.phonebridge.core.PluginStatus.State.NEEDS_ATTENTION ->
                StatusLine(withDetail("On, but it needs action."), LineTone.CAUTION)
            com.mido.phonebridge.core.PluginStatus.State.ERROR ->
                StatusLine(withDetail("On, but it hit an error."), LineTone.CAUTION)
            // On, but reporting OFF internally: measurably misconfigured, not switched off by the
            // human. Naming it "not set up" keeps the two states apart.
            com.mido.phonebridge.core.PluginStatus.State.OFF ->
                StatusLine(withDetail("On, but not set up yet."), LineTone.NEUTRAL)
        }
    }
}
