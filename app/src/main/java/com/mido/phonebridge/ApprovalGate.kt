package com.mido.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mido.phonebridge.policy.Rule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The security gate. Every HTTP request must call await() and get a human
 * approve/deny before anything is read or executed.
 *
 * A request posts a full-screen-intent notification. The ApprovalActivity
 * renders details and calls approve()/deny(). On timeout -> deny.
 */
object ApprovalGate {
    private const val TAG = "ApprovalGate"

    const val CHANNEL_ID = "approval"
    const val TIMEOUT_SECONDS = 60L

    /**
     * How a request left the gate. Returned, never thrown: the caller has to write
     * an audit record for every outcome, and an exception path is a place a record
     * gets forgotten. One call site today, and R2 (the `OFF` switch, removed in 0.12.0)
     * and R3 (`NOTIFY` has a different clock) both needed a gate that just hands back an
     * answer.
     */
    sealed class GateResult {
        /** The human approved (biometric included, when required). */
        data class Approved(
            val enqueuedAt: Long,
            val shownAt: Long,
            val decidedAt: Long,
            val queueDepth: Int
        ) : GateResult()

        /** The human tapped DENY. */
        data class Denied(
            val enqueuedAt: Long,
            val shownAt: Long,
            val decidedAt: Long,
            val queueDepth: Int
        ) : GateResult()

        /** Nobody decided inside the window. Automatically denied. */
        data class TimedOut(
            val enqueuedAt: Long,
            val shownAt: Long,
            val expiredAt: Long,
            val queueDepth: Int
        ) : GateResult()

        /**
         * The human approved AND typed the password this request needs.
         *
         * Distinct from [Approved] because the caller has one more thing to do: feed the
         * password to the command's stdin. It is carried on the result rather than read
         * from the pending request so the dispatcher can zero it at the single place that
         * consumes it — a value with two owners is a value that never gets wiped.
         */
        data class ApprovedWithPassword(
            val password: CharArray,
            val enqueuedAt: Long,
            val shownAt: Long,
            val decidedAt: Long,
            val queueDepth: Int
        ) : GateResult()

        /**
         * The human tapped the exception button: the request is approved AND an exception
         * was written for its pair.
         *
         * Distinct from [Approved] because the caller has one more job — persist the rule.
         * The decision and the policy change are ONE tap, so the write and the record
         * happen together at the single dispatch site, never in the UI.
         */
        data class ApprovedWithException(
            val rule: Rule,
            val enqueuedAt: Long,
            val shownAt: Long,
            val decidedAt: Long,
            val queueDepth: Int
        ) : GateResult()
    }

    /**
     * The state of this request's AI summary.
     *
     * The modal appears BEFORE the model answers (Mido, 2026-10-19: the buttons enable on
     * the fetch, not on a fixed timer), so "is there a summary yet" has to be readable
     * while the human is looking at the prompt. The Activity reads it live on its own tick,
     * the same way it reads [waitingBehindCurrent] — one live source rather than a value
     * pushed at build time, which would be stale the instant it mattered.
     */
    sealed class AiState {
        /** The fetch is running. Buttons stay disabled and the UI shows it is working. */
        object Loading : AiState()

        /** The model answered. Buttons enable now, and the clock starts. */
        class Ready(val summary: com.mido.phonebridge.ai.RequestSummary) : AiState()

        /**
         * The model is present but this call failed.
         *
         * The modal is UNLOCKED and the failure is shown (Mido, 2026-10-19: *"if AI fails
         * show error but unlock modal for user"*). This is not a weakening of fail-hard:
         * a MISSING model still refuses the whole bridge with `503` before a modal is ever
         * built. This is the transient case — one call failed on a model that is installed
         * and proven — and a human staring at dead buttons because of it is a dead end,
         * not a safety property. Nothing is cached, so the next request tries again.
         */
        class Failed(val detail: String) : AiState()

        /** The route declares `aiSummary = false`: there is nothing to wait for. */
        object NotNeeded : AiState()
    }

    /**
     * One labelled row in the modal: a short label and its value, plus an optional
     * style hint so a plugin never has to teach the Activity about itself. The
     * Activity renders rows and nothing else — it has no idea whether the data came
     * from a notification, Gmail, or Calendar.
     */
    class Row(
        val label: String,
        val value: String,
        val style: Style = Style.NORMAL
    ) {
        enum class Style {
            /** Ordinary value, body text colour. */
            NORMAL,
            /** Monospace + selectable — ids, addresses, raw targets. */
            MONO,
            /** Long free text (a message body, an event description): full, wrapped. */
            LONG,
            /** A consequence the human must not miss (mutating target). */
            WARN
        }
    }

    /**
     * A titled group of rows. Sections are the only structure the modal knows;
     * plugins compose them freely. A section with no rows must never be rendered
     * (an empty header is a defect).
     */
    class Section(
        val title: String,
        val rows: List<Row>
    )

    /**
     * The modal's exception offer: the rule to write for THIS request's pair, plus the
     * label for the button.
     *
     * Carrying the finished [rule] (rather than the pair, leaving the UI to assemble one)
     * is deliberate: the Activity then does no policy reasoning at all — it renders
     * [label] and hands [rule] back. Every decision about what the exception MEANS stays
     * on the gate/dispatch side, where it is unit-tested and audited.
     */
    class ExceptionOffer(val rule: Rule, val label: String)

    /**
     * Everything the modal needs to state, in human terms, exactly what is being
     * requested. Built from the RESOLVED action + live notification, never from
     * the raw request, so the modal can't understate what will happen.
     *
     * The gate's own chrome (requester, account, action label, countdown, buttons)
     * is fixed and identical on every route. Plugins contribute [sections] only.
     */
    class ApprovalView(
        val requester: String,
        val account: String,
        val actionLabel: String,
        val appLabel: String,
        val notifTitle: String,
        val notifSender: String,
        val notifText: String,
        val messageCount: Int,
        val replyText: String?,
        /** Other action buttons available on the same notification (labels). */
        val options: List<String> = emptyList(),
        /** Optional query filters the request carried (e.g. count/package). */
        val filters: Map<String, String> = emptyMap(),
        /**
         * Verbatim target fields for a mutating route (send/trash/modify), exactly
         * as sent, structured as labelled rows so each fact is readable on its own
         * line. Never reformatted or summarised: the human approves the real target,
         * and a crafted body must not look benign in the popup.
         *
         * Kept as a plain list of rows; [sections] is the general form and this
         * remains for callers that only need the target block.
         */
        val destructive: List<Pair<String, String>> = emptyList(),
        /**
         * The plugin-facing content: ordered sections of rows rendered by the same
         * component for every route. When empty, the Activity falls back to the
         * named fields above (notifications keep their current rendering).
         */
        val sections: List<Section> = emptyList(),
        /**
         * True when the modal must bind APPROVE to a biometric confirmation. Set by
         * the GATE, never decided by the Activity: the UI renders the flag, it does
         * not compute it — same principle as the rows. Currently TRUE for EVERY
         * request, reads included (biometric is part of the modal for now).
         */
        val requiresBiometric: Boolean = true,
        /**
         * The third-button offer, or null when the modal must not offer one.
         *
         * Decided by the GATE, rendered by the Activity — same split as
         * [requiresBiometric]. It is non-null only when the switch is `AUTOMATIC`,
         * because that is the only mode that reads the rule table: under a forced
         * `SECURE`/`NOTIFY` a written rule is inert, so offering the button there would
         * be offering a tap that changes nothing.
         */
        val exceptionOffer: ExceptionOffer? = null,
        /**
         * True when the modal must collect a password from the human.
         *
         * Decided by the GATE, rendered by the Activity — same split as
         * [requiresBiometric]. A route that needs a password is forced to `SECURE` by
         * the dispatcher, so this is never true on a `NOTIFY` card: there is no Activity
         * there and a five-second silence cannot type a password.
         */
        val needsPassword: Boolean = false,
        /** Seconds until auto-deny, for the countdown UI. */
        val timeoutSeconds: Long = TIMEOUT_SECONDS
        // NOTE: there are no AI fields here any more. The summary arrives AFTER this view is
        // built (Mido, 2026-10-19), so it cannot be part of it — a field that is always blank
        // by construction is a field that lies about what the view knows. The modal renders
        // the AI live from ApprovalGate.currentAiState() instead.
    )

    private val seq = AtomicLong(1000)

    /** A pending request waiting for the human. */
    class Pending(
        val requestId: Long,
        val latch: CountDownLatch,
        val detail: org.json.JSONObject? = null,
        val view: ApprovalView? = null,
        val remote: String = "",
        val endpoint: String = "",
        val summary: String = "",
        val account: String = "—",
        /** When the 60s clock started. Kept on the pending request (not stamped
         *  per-intent) so a re-show on return to the app reuses the ORIGINAL
         *  deadline rather than restarting a fresh window. */
        val createdAtMs: Long = System.currentTimeMillis(),
        /** When the popup actually appeared (the clock the human saw). */
        @Volatile var shownAtMs: Long = 0L,
        /** Requests already waiting behind this one when it enqueued. */
        val queueDepthAtEnqueue: Int = 0,
        /**
         * Facts for the AI fetch, or null when the route declares `aiSummary = false`.
         * Null is the ONLY reason there is no summary — it is not a failure signal.
         */
        val aiFacts: com.mido.phonebridge.ai.RequestFacts? = null,
        /** How long the fetch may take before the buttons enable regardless. */
        val aiFetchCapMs: Long = com.mido.phonebridge.ai.SummaryProvider.SECURE_BUDGET_MS,
        /**
         * Live summary state, read by the Activity each tick. Starts Loading when a fetch
         * is coming, and is set to NotNeeded at post time when it is not.
         */
        @Volatile var aiState: AiState = AiState.Loading,
        /**
         * When the decision buttons went live — the instant the human could actually
         * decide. 0 until then, which is the UI's signal to show the loading state instead
         * of a countdown.
         *
         * The 60s clock runs from HERE, not from [createdAtMs] (Mido, 2026-10-19: *"60 at
         * enable"*). Otherwise a slow model would spend the human's decision time before
         * they could use any of it.
         */
        @Volatile var enabledAtMs: Long = 0L,
        @Volatile var decidedAtMs: Long = 0L,
        @Volatile var approved: Boolean? = null,
        /**
         * Set when the human tapped the exception button: the exact rule to persist for
         * this request's pair. Carried on the pending request so [await] can hand it to
         * the caller, which is the ONLY place policy is written. Non-null implies
         * [approved] is true — the tap approves and writes in one gesture.
         */
        @Volatile var exceptionRule: Rule? = null,

        /**
         * True when this request cannot proceed without a password the human types.
         *
         * Distinct from [requiresBiometric]: a fingerprint proves presence, a password
         * proves knowledge, and this request needs the second one because sudo does.
         */
        val needsPassword: Boolean = false,

        /**
         * The typed password, or null.
         *
         * A `CharArray` because a `String` cannot be wiped: it would sit in the heap
         * until the collector got round to it. The Activity fills this on approve, the
         * dispatcher reads it once and zeroes it, and nothing else may touch it — it is
         * never logged, never audited and never sent over HTTP.
         */
        @Volatile var password: CharArray? = null
    ) {
        /**
         * The longest this request can legitimately take: the misclick guard, the fetch, then a
         * full window.
         *
         * Derived rather than hardcoded, so a fetch that is allowed to run long cannot
         * silently exceed the wait the server is doing on the other side of the latch.
         *
         * **The guard term is load-bearing, not padding.** The decision clock starts at
         * `enabledAtMs`, which is now at least `shownAtMs + MISCLICK_GUARD_MS`. Without adding
         * the guard here, a no-AI request would arm at +3s and then be given 60s of countdown —
         * 63s of legitimate life against a 60s latch. The caller would see a timeout at the exact
         * moment the human still had the guard left to decide, and the UI would show a window that
         * the server had already given up on.
         */
        fun maxWaitMs(): Long =
            ArmGuard.MISCLICK_GUARD_MS +
                if (aiFacts == null) TIMEOUT_SECONDS * 1000L
                else aiFetchCapMs + TIMEOUT_SECONDS * 1000L
    }

    @Volatile
    private var current: Pending? = null

    /**
     * FIFO queue of requests waiting for the human. The head is [current] (on
     * screen); the tail waits its turn. Sized by demand, no cap.
     */
    private val queue = java.util.ArrayDeque<Pending>()

    /**
     * Live count of requests waiting behind the one on screen. Read by the modal
     * on every countdown tick, so the label is correct the instant a follower
     * queues — never a value sampled once at enqueue time (that snapshot was 0
     * for a request that became head while the follower was still arriving, so
     * the label never appeared).
     */
    fun waitingBehindCurrent(): Int = synchronized(this) { (queue.size - 1).coerceAtLeast(0) }

    /** How many requests are waiting behind the one on screen (0 when none). */
    private fun waitingBehind(): Int = waitingBehindCurrent()

    /** Full detail JSON for the currently pending request, for the UI. */
    fun currentDetailJson(): String? = current?.detail?.toString(2)

    /** Structured WHAT/ON payload for the currently pending request, if any. */
    fun currentView(): ApprovalView? = current?.view

    /**
     * The live summary state for the request on screen.
     *
     * NotNeeded when nothing is pending, so a stale modal cannot show a loading state for
     * a request that is already gone.
     */
    fun currentAiState(): AiState = current?.aiState ?: AiState.NotNeeded

    /** When the on-screen request's buttons went live, or 0 while still loading. */
    fun currentEnabledAtMs(): Long = current?.enabledAtMs ?: 0L

    /**
     * Is [requestId] still the request on screen and undecided?
     *
     * The modal polls this so it can dismiss ITSELF when the gate gives up (the latch
     * expired, or the request was replaced). Without it a loading modal has no countdown
     * to run out and would sit there forever after the server had already answered.
     */
    fun isCurrent(requestId: Long): Boolean {
        val p = current ?: return false
        return p.requestId == requestId && p.approved == null
    }

    /**
     * Put the modal back on screen for the currently pending request. Called when
     * the human returns to the app (MainActivity.onResume). Why it is needed: the
     * approval runs in its OWN recents-hidden task (taskAffinity="",
     * excludeFromRecents=true), so returning via the launcher icon lands on
     * MainActivity — a different screen that knows nothing about the request — and
     * the modal would otherwise sit stranded until its window expires.
     *
     * Rebuilds the SAME intent (original id + original clock), so the countdown
     * continues where it left off. Returns false (no-op) when nothing is waiting,
     * or when the head request has ALREADY been decided and is merely awaiting
     * cleanup — never re-show a request that is already resolved.
     */
    fun relaunchCurrent(ctx: Context): Boolean {
        val p = current ?: return false
        if (p.approved != null) return false
        return try {
            ctx.startActivity(buildApprovalIntent(ctx, p))
            true
        } catch (e: Exception) {
            android.util.Log.w("ApprovalGate", "relaunch failed: ${e.message}")
            false
        }
    }

    /** Called by ApprovalActivity once the human decides. */
    fun resolve(requestId: Long, approved: Boolean): Boolean {
        val p = current ?: return false
        if (p.requestId != requestId) return false
        p.approved = approved
        p.decidedAtMs = System.currentTimeMillis()
        p.latch.countDown()
        return true
    }

    /**
     * Called by ApprovalActivity when the human taps the exception button: approve this
     * request AND write [rule] for its pair.
     *
     * The rule travels back on the pending request so the CALLER persists it — the
     * Activity renders and signals, it never writes policy. Same split as the modal rows
     * and [ApprovalView.requiresBiometric].
     */
    fun resolveWithException(requestId: Long, rule: Rule): Boolean {
        val p = current ?: return false
        if (p.requestId != requestId) return false
        p.exceptionRule = rule
        p.approved = true
        p.decidedAtMs = System.currentTimeMillis()
        p.latch.countDown()
        return true
    }

    /**
     * Called by ApprovalActivity when the human approves a request that needs a password.
     *
     * **The password is copied and the caller's array is wiped immediately.** The Activity
     * holds the chars only long enough to hand them over; keeping them there would leave a
     * second live copy in a finishing Activity's memory. The gate owns the copy from here
     * until [await] hands it to the dispatcher, which wipes it again after the handler runs.
     *
     * Refuses when the request does not actually need a password — a mismatch here would
     * mean the modal collected one for a request that never asked, and silently attaching
     * it would feed a secret to a command that does not use sudo.
     */
    fun resolveWithPassword(requestId: Long, password: CharArray): Boolean {
        val p = current ?: return false
        if (p.requestId != requestId) return false
        if (!p.needsPassword) return false
        p.password = password.copyOf()
        p.approved = true
        p.decidedAtMs = System.currentTimeMillis()
        p.latch.countDown()
        return true
    }

    /**
     * Block until the human approves/denies. Throws on deny/timeout.
     */
    fun await(
        ctx: Context,
        remote: String,
        endpoint: String,
        summary: String,
        detail: org.json.JSONObject?,
        account: String = "—",
        view: ApprovalView? = null,
        /**
         * Facts to summarise, or null for a route that declares `aiSummary = false`.
         *
         * The fetch happens HERE rather than in the caller, because the modal must be
         * posted before the model answers and only the gate knows when the request
         * actually becomes the one on screen.
         */
        aiFacts: com.mido.phonebridge.ai.RequestFacts? = null,
        aiFetchCapMs: Long = com.mido.phonebridge.ai.SummaryProvider.SECURE_BUDGET_MS,
        /**
         * True when this request cannot proceed without a password typed at the modal.
         *
         * Passed by the dispatcher, which forces such a request to `SECURE` — a `NOTIFY`
         * card has no Activity to collect a keystroke in.
         */
        needsPassword: Boolean = false
    ): GateResult {
        val id = seq.incrementAndGet()
        val behind = synchronized(this) { (queue.size).coerceAtLeast(0) }
        val pending = Pending(
            requestId = id,
            latch = CountDownLatch(1),
            detail = detail,
            view = view,
            remote = remote,
            endpoint = endpoint,
            summary = summary,
            account = account,
            queueDepthAtEnqueue = behind,
            aiFacts = aiFacts,
            aiFetchCapMs = aiFetchCapMs,
            needsPassword = needsPassword
        )

        // Enqueue, then wait for OUR turn. A second concurrent request no longer
        // dies with a fake 408 (the old one-slot behaviour threw TimeoutException,
        // which lied: it had not timed out, another popup was simply open). It now
        // waits behind the current one and gets its own popup in arrival order.
        val shouldPost: Boolean = synchronized(this) {
            queue.addLast(pending)
            if (current == null) {
                current = pending
                true
            } else {
                false
            }
        }
        // Only the request that became head posts. Everyone waits on their latch.
        try {
            if (shouldPost) {
                postApprovalNotification(ctx, pending)
            }
            val ok = pending.latch.await(pending.maxWaitMs(), TimeUnit.MILLISECONDS)
            val decidedAt = if (pending.decidedAtMs > 0) pending.decidedAtMs else System.currentTimeMillis()
            return when {
                !ok || pending.approved == null -> GateResult.TimedOut(
                    enqueuedAt = pending.createdAtMs,
                    shownAt = pending.shownAtMs,
                    expiredAt = decidedAt,
                    queueDepth = pending.queueDepthAtEnqueue
                )
                // The exception tap is checked FIRST: it also sets approved = true, so
                // testing `approved` first would silently downgrade it to a plain approval
                // and lose the rule the human asked for.
                // The password is checked BEFORE the plain approval for the same reason
                // the exception is: both also set `approved = true`, so testing that first
                // would silently downgrade the result and lose the secret the human typed.
                pending.approved == true && pending.password != null ->
                    GateResult.ApprovedWithPassword(
                        password = pending.password!!,
                        enqueuedAt = pending.createdAtMs,
                        shownAt = pending.shownAtMs,
                        decidedAt = decidedAt,
                        queueDepth = pending.queueDepthAtEnqueue
                    )
                pending.approved == true && pending.exceptionRule != null ->
                    GateResult.ApprovedWithException(
                        rule = pending.exceptionRule!!,
                        enqueuedAt = pending.createdAtMs,
                        shownAt = pending.shownAtMs,
                        decidedAt = decidedAt,
                        queueDepth = pending.queueDepthAtEnqueue
                    )
                pending.approved == true -> GateResult.Approved(
                    enqueuedAt = pending.createdAtMs,
                    shownAt = pending.shownAtMs,
                    decidedAt = decidedAt,
                    queueDepth = pending.queueDepthAtEnqueue
                )
                else -> GateResult.Denied(
                    enqueuedAt = pending.createdAtMs,
                    shownAt = pending.shownAtMs,
                    decidedAt = decidedAt,
                    queueDepth = pending.queueDepthAtEnqueue
                )
            }
        } finally {
            // Remove ourselves, promote the next waiter, and post ITS popup.
            val next: Pending? = synchronized(this) {
                queue.remove(pending)
                if (current?.requestId == pending.requestId) {
                    current = queue.peekFirst()
                }
                if (current != null && current!!.requestId != pending.requestId) current else null
            }
            cancelNotification(ctx, id)
            if (next != null) {
                postApprovalNotification(ctx, next)
            }
        }
    }

    private fun postApprovalNotification(
        ctx: Context,
        p: Pending
    ) {
        // The 60s clock and the audit's `shownAt` are the same instant: when the
        // human could first see this request. Stamped here, once, for the modal and
        // for the record.
        p.shownAtMs = p.createdAtMs
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk is 26, so NotificationChannel is always available.
        //
        // The channel carries the SECURE alert sound, and it is deliberately a
        // NOTIFICATION-class channel: it uses the default notification sound, so it
        // respects silent and DND (Mido's choice, option B, 2026-10-18). The
        // consequence was stated and accepted — on silent the request is unheard and
        // auto-denies at 60s; the full-screen prompt still turns the screen on, so
        // the visual path survives. Enable vibration on the channel too; the app also
        // fires an explicit haptic pattern (SoundAlerts.secureVibration) for a device
        // whose channel vibration is off.
        val ch = NotificationChannel(
            CHANNEL_ID, "Approval requests", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)

        // Haptic + audible nudge, both notification-class. The sound itself comes from
        // the channel above (posting the notification plays it); this adds the short
        // double-tap vibration the channel may not provide.
        com.mido.phonebridge.alert.SoundAlerts.secureVibration(ctx)

        // The paper-creasing sound as the modal ARRIVES — a separate moment from the stamp,
        // which fires on the decision (Mido, 2026-10-27: *"use this when modal appears"*).
        // Reported rather than swallowed: a missing sound must never cancel the prompt.
        try {
            com.mido.phonebridge.alert.SoundAlerts.modalAppear(ctx)
        } catch (e: com.mido.phonebridge.alert.SoundAlerts.SoundUnavailable) {
            android.util.Log.e("ApprovalGate", "MODAL SOUND FAILED: ${e.message}", e)
        }

        val intent = buildApprovalIntent(ctx, p)

        val pi = PendingIntent.getActivity(
            ctx,
            p.requestId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        @Suppress("DEPRECATION")
        val notif = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle("🔐 PhoneBridge approval required")
            .setContentText(p.summary)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)   // pop the activity immediately
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        nm.notify(p.requestId.toInt(), notif)

        // The summary fetch starts when this request becomes the one ON SCREEN — not at
        // enqueue. A queued request nobody can see yet has no business burning an
        // inference, and the human's clock has not started for it either.
        if (p.aiFacts == null) {
            p.aiState = AiState.NotNeeded
            // THE MISCLICK GUARD (Mido, 2026-10-21: *"when AI is disabled, we need the 3 seconds
            // protection again to avoid misclicking the dialog"*). With no summary to wait for,
            // this used to arm at `shownAtMs` — the instant the modal appeared. A tap already in
            // flight when the card arrives would land on APPROVE.
            //
            // `ArmGuard.armAt` rather than `shownAtMs + MISCLICK_GUARD_MS` inline: the AI path
            // below uses the same function, so the rule that decides when a human may agree to
            // something is
            // stated once. Applying it per-branch is how the two paths drift, and this project
            // has paid for exactly that (the audit flag honoured on one dispatch path only).
            p.enabledAtMs = ArmGuard.armAt(p.shownAtMs, p.shownAtMs)
        } else {
            startAiFetch(ctx, p)
        }

        // Belt-and-braces: also launch the approval activity directly. If the
        // OS honors the full-screen intent, this is a no-op (singleTop). If it
        // doesn't, this still puts the modal on screen instead of silently timing
        // out. On Android 10+ this background start is legal because the app holds
        // SYSTEM_ALERT_WINDOW (a listed background-activity-start exemption), and
        // the Full-screen popup toggle covers the FSI path on Android 14+.
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("ApprovalGate", "direct activity launch failed: ${e.message}")
        }
    }

    /**
     * Fetch the summary in the background, then unlock the modal.
     *
     * The modal is ALREADY on screen when this starts (Mido, 2026-10-19: the buttons enable
     * on the fetch, not on a fixed timer), so the human sees the real request immediately
     * instead of a blank wait.
     *
     * Two things happen when it finishes, and both matter:
     *  - **The buttons enable** ([Pending.enabledAtMs] is stamped). That is the only trigger
     *    now; the old flat arming delay is gone.
     *  - **The 60s clock starts** from that same instant, so a slow model never spends the
     *    human's decision time before they can act on it.
     *
     * A FAILURE also unlocks (see [AiState.Failed]): the modal states the error and lets the
     * human decide. That is not a weakening of fail-hard — a missing model still refuses the
     * whole bridge with 503 before any modal exists. This is one transient call failing on a
     * model that is installed and proven, and a human staring at dead buttons because of it
     * is a dead end rather than a safety property. Nothing is cached, so the next request
     * tries again.
     */
    private fun startAiFetch(ctx: Context, p: Pending) {
        val facts = p.aiFacts ?: return
        Thread({
            val result = try {
                com.mido.phonebridge.ai.AiGateway.summarize(ctx, facts, p.aiFetchCapMs)
            } catch (e: Exception) {
                Log.w(TAG, "summary fetch threw for id=${p.requestId}: ${e.message}")
                null
            }
            // The gate's own reason if it has one, else a plain statement. Never blank:
            // the modal has to say something, and "failed" with no cause is the dead end
            // this whole change exists to remove.
            val failureDetail = com.mido.phonebridge.ai.LiteRtLm.lastEngineError
                .ifBlank { "the model did not answer within ${p.aiFetchCapMs}ms" }
            p.aiState = if (result != null) AiState.Ready(result) else AiState.Failed(failureDetail)
            // Same guard, applied to the moment the summary landed. A summary takes ~1.2s, so the
            // floor usually wins and the guard costs ~1.8s; a slow one is unaffected because
            // `armAt` only ever pushes the time LATER, never earlier.
            val readyAt = System.currentTimeMillis()
            p.enabledAtMs = ArmGuard.armAt(p.shownAtMs, readyAt)
            com.mido.phonebridge.Debug.log(
                "approval[${p.requestId}] AI ${if (result != null) "ready" else "FAILED"} " +
                    "in ${readyAt - p.shownAtMs}ms; buttons live at " +
                    "${p.enabledAtMs - p.shownAtMs}ms; clock starts"
            )
        }, "approval-ai-${p.requestId}").apply { isDaemon = true }.start()
    }

    /**
     * The ONE place the modal's intent is built. Both the post path and
     * [relaunchCurrent] go through here, so every modal carries the request id and
     * the ORIGINAL clock (p.createdAtMs). A re-show can therefore never present a
     * fresh, reset 60s window.
     */
    private fun buildApprovalIntent(ctx: Context, p: Pending): Intent =
        Intent(ctx, ApprovalActivity::class.java).apply {
            putExtra(ApprovalActivity.EXTRA_ID, p.requestId)
            putExtra(ApprovalActivity.EXTRA_REMOTE, p.remote)
            putExtra(ApprovalActivity.EXTRA_ENDPOINT, p.endpoint)
            putExtra(ApprovalActivity.EXTRA_SUMMARY, p.summary)
            putExtra(ApprovalActivity.EXTRA_ACCOUNT, p.account)
            putExtra(ApprovalActivity.EXTRA_TIMEOUT_SECONDS, p.view?.timeoutSeconds ?: TIMEOUT_SECONDS)
            putExtra(ApprovalActivity.EXTRA_CREATED_AT, p.createdAtMs)
            putExtra(ApprovalActivity.EXTRA_WAITING_BEHIND, waitingBehindCurrent())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

    /** Whether the app may post full-screen intents (Android 14+). */
    fun canUseFullScreenIntent(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true    // not a thing before 34
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }

    private fun cancelNotification(ctx: Context, id: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id.toInt())
    }
}
