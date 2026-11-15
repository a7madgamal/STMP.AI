package com.mido.phonebridge.ai

/**
 * The deterministic risk floor under a request: what the app knows WITHOUT asking a model.
 *
 * **Why this exists.** APPROVE's colour came only from the AI's assessment. Three
 * consequences, all wrong:
 *  - While the model was still loading, `applyRisk(null)` painted APPROVE `SUCCESS` — green,
 *    the app's "this is fine" colour, on the one button that runs a command.
 *  - With the model switched off or failed, a `chown -R` as root kept that green.
 *  - A small model replying "low" about `rm -rf /` painted it green too.
 *
 * Meanwhile the readout in that same modal already underlined `chown -R` from `DangerTokens`,
 * so the card said "look at this" and the button said "safe". Those two disagreeing is the bug.
 *
 * **The model may RAISE the risk, never lower it.** Same shape as the gate's own rule: the
 * model is advisory, a deterministic check is the floor. [combine] is the only place the two
 * meet, so no call site can accidentally let the model win.
 *
 * Pure: takes a command string, returns a level. No Android, no Context, no I/O.
 */
object RiskFloor {

    /**
     * Tokens that change the machine rather than read it, and that a person cannot undo by
     * looking at them again. Deliberately a SUBSET of `DangerTokens`, not the same list:
     *
     *  - `DangerTokens` is written to make a human LOOK (it underlines `eval`, `kill -9`, a
     *    write redirect). Underlining those is a nudge; painting APPROVE red is a claim about
     *    consequence, so the bar is higher.
     *  - A red button on anything with `>` or `eval` in it would fire on half of all real
     *    commands, and a warning that fires on everything is one that is ignored.
     *
     * Every entry here is a token that destroys or reassigns data, changes privilege, or takes
     * the machine down.
     */
    private val RED: List<Regex> = listOf(
        // Recursive ownership/permission change: the one in the report, and the classic
        // "I fixed it by making /home unwritable" incident.
        Regex("""\b(?:chown|chmod)\s+(?:-[A-Za-z]*R[A-Za-z]*|--recursive)\b"""),
        // Recursive force delete.
        Regex("""\brm\s+(?:-[A-Za-z]*(?:[rR].*f|f.*[rR])[A-Za-z]*|--(?:recursive|force)\b)"""),
        // Filesystem creation over a device: destroys whatever was there.
        Regex("""\bmkfs(?:\.\w+)?\b"""),
        // Raw device write.
        Regex("""\bdd\b[^\n;|&]*\bof=/dev/"""),
        // Privilege escalation. Amber on its own would train people to approve it; every
        // sudo here is a command running as another user, which is the thing to notice.
        Regex("""(?:(?<=/)|(?<![\w.\-/]))(?:sudo|doas)\b"""),
        // Taking the machine down.
        Regex("""\b(?:shutdown|reboot|poweroff|halt)\b\s"""),
        // Rewriting published history.
        Regex("""\bgit\s+(?:push\s+(?:-f|--force)|reset\s+--hard)\b"""),
        // Download-and-execute: the canonical remote-code path.
        Regex("""\|\s*(?:sudo\s+)?(?:ba|z|da|k)?sh\b"""),
    )

    /**
     * Anything else `DangerTokens` flags: worth a second look, not worth a red button.
     * Used to lift APPROVE to at least amber when the model is silent or says low.
     */
    private val AMBER: List<Regex> = listOf(
        Regex("""\beval\b"""),
        Regex("""\bkill(?:all)?\s+-9\b"""),
        Regex("""\bgit\s+clean\s+-[A-Za-z]*f\b"""),
        Regex(""":\(\)\s*\{"""),
    )

    /**
     * The floor for one command, or null when the command carries no signal at all.
     *
     * Null means "no deterministic opinion" — NOT "safe". A caller that paints null green is
     * making the same mistake this object exists to stop, so the modal treats null as
     * [Risk.LOW] only because a plain `ls` deserves green; the important case is that a
     * *dangerous* command can never return null or LOW.
     */
    fun of(command: String): Risk? {
        val text = command.trim()
        if (text.isEmpty()) return null
        if (RED.any { it.containsMatchIn(text) }) return Risk.HIGH
        if (AMBER.any { it.containsMatchIn(text) }) return Risk.MEDIUM
        return null
    }

    /**
     * The risk to paint: the HIGHER of the model's answer and the floor.
     *
     * **This is the only place the two are compared**, so no screen can let the model win by
     * accident. A model that says LOW about `chown -R /home` is overruled by the floor; a model
     * that says HIGH about something the floor reads as ordinary still wins, because the model
     * can see facts a pattern cannot.
     */
    fun combine(model: Risk?, floor: Risk?): Risk? = when {
        model == null -> floor
        floor == null -> model
        // Ordinal order is LOW < MEDIUM < HIGH, declared that way in `Risk`.
        else -> if (model.ordinal >= floor.ordinal) model else floor
    }
}
