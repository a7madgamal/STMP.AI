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
         * We believe we are running, but a real connect says otherwise.
         *
         * **This state exists because the app once painted green while every caller got
         * `Connection refused`** (2026-10-25). `serverUp` records that `start()` succeeded
         * once; it is never re-verified, so it cannot distinguish "serving" from "bound to an
         * address that stopped routing". A green label that no caller can reach is worse than
         * a red one, because it stops anyone looking.
         *
         * Checked BEFORE the healthy/attention pair, since both of those assume `serverUp`
         * means reachable.
         */
        UNRESPONSIVE,

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
     * @param alive a REAL liveness result: the listener's socket is open, its accept thread is
     *   running, and a TCP connect to the bound address succeeded. Not the same thing as
     *   [serverUp], and that distinction is the point — see [State.UNRESPONSIVE].
     * @param listenerOk the notification listener is connected.
     * @param notifOk `POST_NOTIFICATIONS` is granted.
     * @param lastError `BridgeService.lastError` — set by every refusal path, cleared on a
     *   successful bind, and cleared by the button before a retry so a stale reason cannot
     *   outlive its cause.
     */
    fun state(
        serverUp: Boolean,
        alive: Boolean,
        listenerOk: Boolean,
        notifOk: Boolean,
        lastError: String,
    ): State = when {
        // UNRESPONSIVE first: everything below assumes serverUp implies reachable.
        serverUp && !alive -> State.UNRESPONSIVE
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
        // Deliberately not "Running": that word is the lie this state replaces.
        State.UNRESPONSIVE -> "● Not answering"
        State.FAILED -> "● Could not start"
        State.STOPPED -> "● Stopped"
    }

    /**
     * The hero title while a start or stop is still settling.
     *
     * Without this the screen contradicts the switch: a tap that asks to start paints nothing
     * new, because `serverUp` is still false, so for up to [HomeFragment.PENDING_WINDOW_MS] the
     * hero reads "● Stopped" and "Switch the server on to begin" directly under a switch that is
     * already on. The screen is not wrong about the state — the state genuinely has not changed
     * yet — it is wrong about what it is doing about it.
     *
     * `starting` is the direction of the tap, not the current state. The rocker renders the STORED
     * state and is never flipped optimistically, so the pending direction is the opposite of
     * whatever the switch is drawing.
     */
    fun pendingTitle(starting: Boolean): String =
        if (starting) "● Starting" else "● Stopping"

    /** The hero's supporting line while a start or stop is still settling. See [pendingTitle]. */
    fun pendingDetail(starting: Boolean): String =
        if (starting) {
            "Opening the port now — this clears itself when the bridge answers."
        } else {
            "Closing the port now — this clears itself when the bridge stops."
        }

    /**
     * Whether a start or stop tap is still settling.
     *
     * Pure, and extracted from the fragment so the rules can be tested without a phone. Three
     * conditions end the pending look, and each one is a distinct way it used to get stuck:
     *
     * - the bridge reached the state that was asked for ([up] == [want]) — the normal case;
     * - the [PENDING_WINDOW] closed — a start that never lands must not say "Starting" forever;
     * - a start FAILED, which is detectable because `startBridge` clears `lastError` before it
     *   tries: an error present afterwards is new, and belongs on the screen at once rather than
     *   hiding behind a "Starting" that will never finish.
     *
     * `want == null` is "no tap yet", and `since <= 0` is "no tap this session". Both mean not
     * pending: a pending look with no tap behind it is a claim about an action nobody took.
     */
    fun isPending(want: Boolean?, up: Boolean, since: Long, now: Long, lastError: String?): Boolean {
        if (want == null || since <= 0L || up == want) return false
        if (now - since >= PENDING_WINDOW) return false
        // A refused start ends the look immediately. A refused STOP has no error of its own, so
        // this only ever shortens a start.
        return !(want && !lastError.isNullOrBlank())
    }

    /** How long a start or stop tap may show as settling. Matches the fragment's own window. */
    const val PENDING_WINDOW = 3_000L

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
        bindAddress: String,
        port: Int,
    ): String = when (state) {
        State.FAILED -> lastError
        // Name the address: "not answering" is only actionable if you know where it should be
        // answering, and a stale tailnet address is the likeliest cause.
        State.UNRESPONSIVE ->
            "Not answering on ${bindAddress.ifBlank { "?" }}:$port — retrying automatically."
        // A running server that also carries an error is not possible (a successful bind
        // clears it), but if that state ever appears, the error is still the more useful
        // line to show than the address. Fail toward information.
        State.RUNNING_HEALTHY, State.RUNNING_ATTENTION ->
            // The EMAIL is deliberately absent (Mido, 2026-11-03: *"move email from home, it
            // belongs to the google plugin"*). It is a fact about the ACCOUNT, not about the
            // server, and Home answers one question — is the bridge running and where. The
            // Google plugin reports the address on its own row, which is the screen that can also
            // change it.
            if (lastError.isNotBlank()) lastError
            else "${bindAddress.ifBlank { "?" }}:$port"
        // No button by that name exists: the control is a switch now.
        State.STOPPED -> "Switch the server on to begin"
    }

    /** One-line summary for the Setup checklist row, kept in step with the hero. */
    fun checklistValue(state: State, bindAddress: String, port: Int): String = when (state) {
        State.RUNNING_HEALTHY, State.RUNNING_ATTENTION ->
            "running on ${bindAddress.ifBlank { "?" }}:$port"
        // "stopped" beside a red "Could not start" reads like a contradiction.
        State.FAILED -> "not running — see the error above"
        // Never "running" here: the row would contradict the hero directly above it.
        State.UNRESPONSIVE -> "not answering — see above"
        State.STOPPED -> "stopped"
    }
}
