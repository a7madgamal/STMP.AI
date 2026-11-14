package com.mido.phonebridge.core

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import com.mido.phonebridge.BridgeService.Companion.CHANNEL_ID
import com.mido.phonebridge.R
import com.mido.phonebridge.BridgeService.Companion.STATUS_CHANNEL_ID
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.ui.Theme

/**
 * The three system notifications, built in one place (D23, D51).
 *
 * Each owner still creates its own channel and builds its own PendingIntents; this only assembles
 * the [Notification]. All three use the platform builder, so there is no new dependency.
 */
object StmpNotifications {

    const val ACTION_STOP = "com.mido.phonebridge.action.STOP_BRIDGE"

    /** Same id as `CallerPrompt`'s private channel; `StmpNotificationsTest` keeps them equal. */
    const val CALLER_CHANNEL_ID = "caller_prompt"

    const val CALLER_TITLE = "New device wants to connect"

    /** True when [intent] is the "Stop bridge" action. */
    fun isStop(intent: android.content.Intent?): Boolean = intent?.action == ACTION_STOP

    /** The ongoing notification: address, port and gate mode, with Open and Stop bridge. */
    fun running(
        ctx: Context, address: String?, port: Int, mode: GlobalMode?,
        open: PendingIntent, stop: PendingIntent,
    ): Notification {
        val icon = Icon.createWithResource(ctx, R.drawable.ic_stat_stmp)
        val public = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stmp)
            .setContentTitle(ForegroundNotice.PUBLIC_TITLE)
            .build()
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stmp)
            .setContentTitle(ForegroundNotice.title(address, port))
            .setContentText(ForegroundNotice.text(address, mode))
            .setColor(Theme.palette(ctx).ledOk)
            .setColorized(false)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Open", open).build())
            .addAction(Notification.Action.Builder(icon, "Stop bridge", stop).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
    }

    /** The "STMP could not start" reminder. */
    fun status(
        ctx: Context, kind: BridgeStatusNotice.Kind, attempt: Int, maxAttempts: Int,
        open: PendingIntent,
    ): Notification =
        Notification.Builder(ctx, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stmp)
            .setContentTitle(BridgeStatusNotice.title(kind))
            .setContentText(BridgeStatusNotice.text(kind, attempt, maxAttempts))
            .setColor(Theme.palette(ctx).ledCaution)
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(open)
            .setOngoing(BridgeStatusNotice.ongoing(kind))
            .setAutoCancel(BridgeStatusNotice.autoCancel(kind))
            .build()

    /** The full-screen "new device" prompt. */
    fun callerPrompt(ctx: Context, ip: String, fullScreen: PendingIntent): Notification {
        val public = Notification.Builder(ctx, CALLER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stmp)
            .setContentTitle(CALLER_TITLE)
            .build()
        return Notification.Builder(ctx, CALLER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stmp)
            .setContentTitle(CALLER_TITLE)
            .setContentText("$ip asked to reach STMP. Name it or block it.")
            .setColor(Theme.palette(ctx).ledCaution)
            .setCategory(Notification.CATEGORY_CALL)
            // No `setPriority`: the channel's IMPORTANCE_HIGH already says it.
            .setFullScreenIntent(fullScreen, true)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
    }
}
