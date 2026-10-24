package com.mido.phonebridge.ui

/**
 * What the home screen's hero card says about the server.
 *
 * **Why this is a pure object and not five lines inside `MainActivity.render()`.** It was
 * exactly that, and it was wrong in a way nobody could see: the branch order checked
 * `!serverUp` BEFORE `lastError`, so a start that failed was indistinguishable from a start
 * that had never been attempted. `BridgeService` stops itself when it cannot bind, which sets
 * `serverUp=false` while `lastError` holds the reason — the perfect input for the bug. The
 * user tapped START, the button did nothing visible, and the reason it refused was sitting in
 * a field the renderer never reached. Mido, 2026-10-20: *"on server fail user should see clear
 * error"*.
 *
 * The lesson is not "order the branches carefully". It is that a precedence rule which decides
 * what a human sees belongs where a test can fail on it, and this one had no test at all. The
 * project has paid for this shape before (`ResponseBody.classify`, `HostAllowlist`): a decision
 * made inline in the view or I/O path is a decision nobody can exercise, so the wrong branch is
 * the one that ships. Here the rule is three lines and one `when`, and `ServerStatusTest` pins
 * every ordering it depends on.
 */
object ServerStatus {

    /**
     * The hero card's state. Ordered by precedence as written: the first matching case wins.
     */
    enum class State {
        /** Server up, listener bound, notification permission granted. */
        RUNNING_HEALTHY,

        /** Server up, but something it needs is missing — worth naming, not an error. */
        RUNNING_ATTENTION,

        /**
         * Start was attempted and refused. **Checked before [STOPPED]** — a stopped server
         * that carries an error is a failure, not an idle state, and this precedence is the
         * whole reason this object exists.
         */
        FAILED,

        /** Never started, or deliberately stopped. The only genuinely idle state. */
        STOPPED,
    }

    /**
     * Resolve the state.
     *
     * @param serverUp `BridgeService.serverUp` — true only after a successful bind.
     * @param listenerOk the notification listener is connected.
     * @param notifOk `POST_NOTIFICATIONS` is granted.
     * @param lastError `BridgeService.lastError` — set by every refusal path, cleared on a
     *   successful bind, and cleared by the button before a retry so a stale reason cannot
     *   outlive its cause.
     */
    fun state(serverUp: Boolean, listenerOk: Boolean, notifOk: Boolean, lastError: String): State =
        when {
            serverUp && listenerOk && notifOk -> State.RUNNING_HEALTHY
            serverUp -> State.RUNNING_ATTENTION
            // FAILED before STOPPED. Moving these two lines re-introduces the shipped bug.
            lastError.isNotBlank() -> State.FAILED
            else -> State.STOPPED
        }

    /** The hero title for a state. */
    fun title(state: State): String = when (state) {
        State.RUNNING_HEALTHY -> "● Running"
        State.RUNNING_ATTENTION -> "● Running — needs attention"
        State.FAILED -> "● Could not start"
        State.STOPPED -> "● Stopped"
    }

    /**
     * The hero's supporting line.
     *
     * On [State.FAILED] this is the error VERBATIM — the reason a start was refused is the
     * single most useful string on the screen, and paraphrasing it here would mean two places
     * to keep in step. Every other state gets a status line instead.
     */
    fun detail(
        state: State,
        lastError: String,
        email: String?,
        bindAddress: String,
        port: Int,
    ): String = when (state) {
        State.FAILED -> lastError
        // A running server that also carries an error is not possible (a successful bind
        // clears it), but if that state ever appears, the error is still the more useful
        // line to show than the account email. Fail toward information.
        State.RUNNING_HEALTHY, State.RUNNING_ATTENTION ->
            if (lastError.isNotBlank()) lastError
            else if (email != null) "$email · ${bindAddress.ifBlank { "?" }}:$port"
            else "Not signed in to Google"
        State.STOPPED -> "Tap START SERVER to begin"
    }

    /** One-line summary for the Setup checklist row, kept in step with the hero. */
    fun checklistValue(state: State, bindAddress: String, port: Int): String = when (state) {
        State.RUNNING_HEALTHY, State.RUNNING_ATTENTION ->
            "running on ${bindAddress.ifBlank { "?" }}:$port"
        // "stopped" beside a red "Could not start" reads like a contradiction.
        State.FAILED -> "not running — see the error above"
        State.STOPPED -> "stopped"
    }
}
