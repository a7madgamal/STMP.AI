package com.mido.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
        /** Seconds until auto-deny, for the countdown UI. */
        val timeoutSeconds: Long = TIMEOUT_SECONDS,
        /**
         * The AI's plain-language one-liner, or blank when no summary is available.
         * Display-only: it never changes what is approved, only how clearly it is
         * stated. Blank is a normal state (no model, cold start, over budget) and the
         * modal simply omits the block.
         */
        val aiDescription: String = "",
        /**
         * The AI's coarse risk bucket, or null when unavailable. Rendered as a badge
         * and (on the NOTIFY card) a tint. It gates NOTHING — see the display-only
         * decision (Mido, 2026-10-18).
         */
        val aiRisk: com.mido.phonebridge.ai.Risk? = null,
        /** One short phrase explaining the risk bucket. */
        val aiReason: String = ""
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
        @Volatile var decidedAtMs: Long = 0L,
        @Volatile var approved: Boolean? = null
    )

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
     * Block until the human approves/denies. Throws on deny/timeout.
     */
    fun await(
        ctx: Context,
        remote: String,
        endpoint: String,
        summary: String,
        detail: org.json.JSONObject?,
        account: String = "—",
        view: ApprovalView? = null
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
            queueDepthAtEnqueue = behind
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
            val ok = pending.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val decidedAt = if (pending.decidedAtMs > 0) pending.decidedAtMs else System.currentTimeMillis()
            return when {
                !ok || pending.approved == null -> GateResult.TimedOut(
                    enqueuedAt = pending.createdAtMs,
                    shownAt = pending.shownAtMs,
                    expiredAt = decidedAt,
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
