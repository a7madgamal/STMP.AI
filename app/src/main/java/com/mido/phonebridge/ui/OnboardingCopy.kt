package com.mido.phonebridge.ui

/**
 * The introduction's text, kept apart from the Activity so a test can read it.
 *
 * Pure strings and builders. Nothing here touches Android, so the copy rules (no jargon, no
 * hard-wrapped prompt, one placeholder) are checked by plain JUnit.
 */
internal object OnboardingCopy {

    /** Curated, not exhaustive: the Plugins tab is the live truth and names every plugin. */
    val GROUPS: List<Pair<String, String>> = listOf(
        "Google" to "Gmail, Calendar and Drive. Read, send, schedule, upload.",
        "Phone" to "Notifications and contacts.",
        "Machine" to "Shell commands over SSH, key made on the phone.",
        "Voice" to "Speak text aloud, play a cue to reach you.",
        "Diagnostics" to "Your request log and the phone's logs, so it can diagnose itself.",
    )

    const val SERVER_OFF_NOTE =
        "STMP isn't on yet, so the address below is a placeholder. Turn it on from Home, then copy."

    /**
     * The real address, or a clearly marked fallback when the server is not up yet. "http://:8787"
     * would look like a typo; the placeholder tells the human what to do.
     */
    fun baseUrl(bindAddress: String, port: Int): String =
        if (bindAddress.isBlank()) "http://<start STMP first>:$port" else "http://$bindAddress:$port"

    /** The address is interpolated, so the second line cannot be pasted with a stale IP. */
    fun hermesCommands(base: String): String =
        "hermes plugins install stmp --enable --yes-deps\n" +
            "hermes config set plugins.entries.stmp.settings.base_url $base"

    /**
     * The generic prompt.
     *
     * Only judgment and a pointer: where to look (`/help`), what a refusal means (never retry a
     * decline), and how to check the gate works (`/probe`, where a denial is a pass). Every route
     * and error is described by the endpoint, so naming them here would be the second copy that
     * drifts. Each paragraph and bullet is ONE line: hard wrapping shows as ragged lines in the
     * readout and in the agent.
     */
    fun buildPrompt(base: String): String = listOf(
        "You can control my Android phone through STMP, an HTTP bridge at $base",
        "",
        "First call:",
        "  GET $base/help",
        "",
        "It returns the whole API as JSON, plus a \"markdown\" field describing it in prose. Read the markdown. " +
            "Note the contractVersion — if you ever see a higher one, re-read /help and treat it as current.",
        "",
        "Then work from that. Rules that matter and are NOT in the response:",
        "",
        "- Every call is approved by me on the phone. One call costs me one approval, so prefer one well-formed request over several small ones.",
        "- A 403 with error \"denied\" means I declined. Do not retry it, do not rephrase it, and do not route around it. Tell me what you were trying to do and stop.",
        "- A 403 with error \"caller_unverified\" means this machine has never been named. Tell me to check the phone; I will name it and you can retry once.",
        "- A 403 with error \"caller_denied\" means I blocked this machine. Stop.",
        "- 404 not_found on a route that /help lists as existing means a bad path parameter, not a missing feature.",
        "- Never send a credential. Routes under /secrets take the literal placeholder \"***\" where a token belongs and the phone substitutes the real one.",
        "",
        "Finally, confirm the gate reaches me:",
        "  GET $base/probe",
        "",
        "A 200 means a request went through the approval gate and reached the handler. " +
            "A 403 \"denied\" ALSO means it works — I declined and the gate honoured it. " +
            "Either way the gate is alive; only a timeout or a 5xx is a real failure.",
    ).joinToString("\n")

    fun steps(): List<String> = listOf(
        "1. Turn STMP on at the top of Home.",
        "2. Paste the prompt into your agent and send it.",
        "3. Approve the first request. Name this machine if asked.",
        "4. From then on, every call asks you first.",
    )

    /**
     * The Hermes steps, a different shape from the generic ones. Step 3 is the important line:
     * `caller_unverified` looks like a failure and is not. Left unexplained, it is the point where
     * people conclude the plugin is broken.
     */
    fun hermesSteps(): List<String> = listOf(
        "1. Turn STMP on at the top of Home.",
        "2. Run the two commands above.",
        "3. Call stmp_help. It is refused with caller_unverified. That is the phone asking who this machine is, not a fault.",
        "4. Name this machine on the phone, then retry. You do this once.",
        "5. From then on, every call asks you first.",
    )
}
