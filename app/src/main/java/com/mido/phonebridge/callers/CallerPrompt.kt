package com.mido.phonebridge.callers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mido.phonebridge.CallerActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Asks the human to name an unverified caller, and blocks the request until they answer.
 *
 * **This is the third gate, and the only one that runs before routing.** The first two ask
 * "should this request run?"; this one asks "should this machine be allowed to ask at all?".
 * That is why it sits ahead of `PluginRegistry.resolve` — an unnamed address must not be able to
 * map the surface by watching which routes exist.
 *
 * **A full-screen intent, like the SECURE modal.** Consistent with the app's only other blocking
 * prompt, and the same failure mode: no screen, no answer, so the request is refused. A quiet
 * notification was rejected because the caller would stay refused until someone happened to tap
 * it, which reads as a broken bridge rather than a pending decision.
 *
 * **The answer is remembered.** A verified caller is named and allowed from then on; a denied one
 * is refused SILENTLY on every later call with no prompt. Without that record the only way to
 * refuse a stranger would be to refuse them again on every knock.
 */
object CallerPrompt {

    /** What the human decided. Never "still unverified" — that is the absence of an answer. */
    sealed class Result {
        /** Named and allowed. */
        object Named : Result()

        /** Blocked. Recorded, so the next call is refused without a prompt. */
        object Blocked : Result()

        /** Nothing could be shown: screen off, locked, or the activity could not start. */
        object Unavailable : Result()

        /** Shown, and nobody answered inside the window. */
        object Timeout : Result()
    }

    /**
     * How long a caller is made to wait for a human.
     *
     * Longer than the SECURE modal's 60s on purpose: the human has to think of a NAME, and the
     * caller has nothing invested yet — this is its first ever call. 90s is still bounded, and a
     * timeout is a clean refusal that can simply be retried.
     */
    const val WINDOW_SECONDS = 90L

    private const val CHANNEL_ID = "caller_prompt"

    /**
     * Serialise prompts: two strangers at once must not race two full-screen intents.
     *
     * A [ReentrantLock] rather than `Any()` because this needs `newCondition()` — Kotlin's `Any`
     * has no `wait`/`notifyAll`, and the monitor methods on `Object` are not exposed.
     */
    private val lock = ReentrantLock()
    private val slotFreed = lock.newCondition()

    @Volatile
    private var pending: Pending? = null

    private class Pending(
        val ip: String,
        val latch: CountDownLatch,
        val result: AtomicReference<Result?> = AtomicReference(null),
    )

    /**
     * Ask the human, blocking the calling thread until they answer or the window expires.
     *
     * **Never returns `Named` without a name.** The decision and the write happen in
     * [CallerActivity]; this method only waits, then re-reads the registry so the answer it
     * reports is the one that is actually on disk rather than one it was told about.
     */
    fun ask(ctx: Context, remote: String): Result {
        val p = Pending(remote, CountDownLatch(1))
        lock.lock()
        try {
            // One prompt at a time. A second caller waits for the first to be decided — the same
            // discipline the approval gate uses, and for the same reason: two full-screen
            // intents racing would show the human two questions with no way to tell them apart.
            while (pending != null) {
                try {
                    slotFreed.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Result.Unavailable
                }
            }
            pending = p
        } finally {
            lock.unlock()
        }

        try {
            if (!post(ctx, p)) {
                com.mido.phonebridge.Debug.log("caller prompt unavailable for $remote")
                return Result.Unavailable
            }
            val answered = p.latch.await(WINDOW_SECONDS + ESCAPE_SECONDS, TimeUnit.SECONDS)
            if (!answered) {
                com.mido.phonebridge.Debug.log("caller prompt timed out for $remote")
                return Result.Timeout
            }
            // Re-read the registry rather than trusting the reported result: the write is what
            // makes an address allowed, so the state on disk is the only honest answer.
            return when (val stored = CallerStore.from(ctx).stateOf(remote)) {
                is CallerState.Verified -> Result.Named
                CallerState.Denied -> Result.Blocked
                null -> Result.Timeout
            }
        } finally {
            lock.lock()
            try {
                pending = null
                slotFreed.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * Resolve the waiting request. Called by [CallerActivity] once it has written the decision.
     *
     * A no-op when nothing is pending, so a stale activity finishing late cannot answer a
     * question that is no longer being asked.
     */
    fun resolve(result: Result) {
        lock.lock()
        val p = try { pending } finally { lock.unlock() }
        if (p == null) return
        p.result.compareAndSet(null, result)
        p.latch.countDown()
    }

    /** The address currently being asked about, for the activity to render. */
    fun pendingIp(): String? {
        lock.lock()
        return try { pending?.ip } finally { lock.unlock() }
    }

    private fun post(ctx: Context, p: Pending): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Caller prompts", NotificationManager.IMPORTANCE_HIGH)
                    .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
            )
        }
        val intent = Intent(ctx, CallerActivity::class.java).apply {
            putExtra(CallerActivity.EXTRA_IP, p.ip)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = PendingIntent.getActivity(
            ctx, p.ip.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Unknown caller")
            .setContentText("${p.ip} wants to reach this phone")
            .setCategory(Notification.CATEGORY_CALL)
            // No `setPriority`: it is deprecated, and it would be redundant anyway — the channel
            // above already carries IMPORTANCE_HIGH, which is the modern way to say the same
            // thing. Two places to set one thing is how they end up disagreeing.
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        return try {
            nm.notify(p.ip.hashCode(), n)
            true
        } catch (e: Exception) {
            com.mido.phonebridge.Debug.log("caller prompt post failed: ${e.message}")
            false
        }
    }

    /** A little past the window before the wall drops, so a late answer still lands. */
    private const val ESCAPE_SECONDS = 5L
}
