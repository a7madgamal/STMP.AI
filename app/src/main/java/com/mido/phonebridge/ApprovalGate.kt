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

    class DeniedException : Exception("denied")
    class TimeoutException : Exception("timeout")

    private val seq = AtomicLong(1000)

    /** A pending request waiting for the human. */
    class Pending(
        val requestId: Long,
        val latch: CountDownLatch,
        val detail: org.json.JSONObject? = null,
        @Volatile var approved: Boolean? = null
    )

    @Volatile
    private var current: Pending? = null

    /** Full detail JSON for the currently pending request, for the UI. */
    fun currentDetailJson(): String? = current?.detail?.toString(2)

    /** Called by ApprovalActivity once the human decides. */
    fun resolve(requestId: Long, approved: Boolean): Boolean {
        val p = current ?: return false
        if (p.requestId != requestId) return false
        p.approved = approved
        p.latch.countDown()
        return true
    }

    /**
     * Block until the human approves/denies. Throws on deny/timeout.
     */
    @Throws(DeniedException::class, TimeoutException::class)
    fun await(
        ctx: Context,
        remote: String,
        endpoint: String,
        summary: String,
        detail: org.json.JSONObject?
    ) {
        // Only one approval at a time for v0: if another is pending, fail fast.
        synchronized(this) {
            if (current != null) {
                throw TimeoutException().also {
                    Log.w(TAG, "another approval already pending")
                }
            }
        }

        val id = seq.incrementAndGet()
        val pending = Pending(id, CountDownLatch(1), detail)
        synchronized(this) { current = pending }

        try {
            postApprovalNotification(ctx, id, remote, endpoint, summary)
            val ok = pending.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!ok) throw TimeoutException()
            when (pending.approved) {
                true -> return
                false -> throw DeniedException()
                null -> throw TimeoutException()
            }
        } finally {
            synchronized(this) { current = null }
            cancelNotification(ctx, id)
        }
    }

    private fun postApprovalNotification(
        ctx: Context,
        id: Long,
        remote: String,
        endpoint: String,
        summary: String
    ) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk is 26, so NotificationChannel is always available.
        val ch = NotificationChannel(
            CHANNEL_ID, "Approval requests", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)

        val intent = Intent(ctx, ApprovalActivity::class.java).apply {
            putExtra(ApprovalActivity.EXTRA_ID, id)
            putExtra(ApprovalActivity.EXTRA_REMOTE, remote)
            putExtra(ApprovalActivity.EXTRA_ENDPOINT, endpoint)
            putExtra(ApprovalActivity.EXTRA_SUMMARY, summary)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val pi = PendingIntent.getActivity(
            ctx,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        @Suppress("DEPRECATION")
        val notif = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle("🔐 PhoneBridge approval required")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)   // pop the activity immediately
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        nm.notify(id.toInt(), notif)

        // Belt-and-braces: also launch the approval activity directly. If the
        // OS honors the full-screen intent, this is a no-op (singleTop). If it
        // doesn't (e.g. USE_FULL_SCREEN_INTENT not granted), this still puts the
        // modal on screen instead of silently timing out.
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("ApprovalGate", "direct activity launch failed: ${e.message}")
        }
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
