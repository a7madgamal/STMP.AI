package com.mido.phonebridge.plugins.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mido.phonebridge.R

/**
 * Ask the human to choose the folder — once, not once per request.
 *
 * **The grant is asked for when a request needs it.** There is no setup screen and no prompt on
 * first launch: a route that finds no grant refuses with `503 folder_not_granted` and this posts a
 * notification whose tap opens the system picker. The HTTP call does not wait for the picker,
 * because a human browsing a filesystem is unbounded time and often on another app's screen.
 *
 * **De-duplicated, because a polling caller would otherwise fill the tray.** A client retrying every
 * few seconds would produce one notification per retry, and a tray full of identical prompts is a
 * notification nobody reads. Same notification id every time, so the platform replaces rather than
 * stacks.
 *
 * The notification is also the explanation: it says which folder and why, so a human who never
 * intended to use this plugin can decline by ignoring it.
 */
object FolderGrant {

    private const val CHANNEL_ID = "files_grant"

    /** One id: a second ask replaces the first instead of stacking. */
    private const val NOTIFICATION_ID = 7919

    fun askOnce(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Folder access",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        // The picker lives in the app: an Activity has to launch it, because SAF requires a
        // foreground activity for the result. The notification is only the door.
        val intent = Intent(ctx, FilesGrantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stmp)
            .setContentTitle("Choose a folder for STMP")
            .setContentText("Tap to grant file access to ${FilesPlugin.FOLDER}")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // A notification that cannot post must not turn a refusal into a crash: the 503 the
            // caller already received is the answer, and this is only the nudge.
            com.mido.phonebridge.Debug.log("folder-grant ask failed: ${e.javaClass.simpleName}")
        }
    }

    /** Clear the nudge once a folder has been granted. */
    fun dismiss(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }
}
