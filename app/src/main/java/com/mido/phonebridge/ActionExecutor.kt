package com.mido.phonebridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Central place that decides/executes notification actions *after* approval.
 *
 * FAIL LOUD: every path either returns a success result or throws BridgeError.
 * There is deliberately NO fallback mechanism.
 */
class BridgeError(val code: String, message: String) : Exception(message)

object ActionExecutor {
    private const val TAG = "ActionExecutor"

    /**
     * Resolve the action to fire, WITHOUT firing it. Used both to render the
     * approval modal (so it shows the *real* resolved action) and to execute.
     *
     * FAIL LOUD: a missing notification or an unknown action throws a coded
     * BridgeError; there is no silent fallback.
     */
    @Throws(BridgeError::class)
    fun resolve(ctx: Context, key: String, actionRef: Any): Notification.Action {
        val sbn = NotifStore.get(key)
            ?: throw BridgeError("notification_gone", "no live notification with id=$key")
        val actions = sbn.notification.actions ?: emptyArray()
        if (actions.isEmpty()) {
            throw BridgeError("unsupported_action", "notification has no actions")
        }
        return resolveAction(actions, actionRef)
            ?: throw BridgeError(
                "unsupported_action",
                "no action matching '$actionRef' (available: ${
                    actions.mapIndexed { i, a -> "$i:'${a.title}'" }
                })"
            )
    }

    @Throws(BridgeError::class)
    fun performAction(ctx: Context, key: String, actionRef: Any, replyText: String?) {
        val sbn = NotifStore.get(key)
            ?: throw BridgeError("notification_gone", "no live notification with id=$key")
        val action = resolve(ctx, key, actionRef)

        val remoteInputs = action.remoteInputs
        if (remoteInputs != null && remoteInputs.isNotEmpty()) {
            if (replyText == null) {
                throw BridgeError(
                    "missing_reply_text",
                    "action '${action.title}' requires replyText"
                )
            }
            val intent = Intent()
            val bundle = android.os.Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            android.app.RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            try {
                action.actionIntent.send(ctx, 0, intent)
            } catch (e: PendingIntent.CanceledException) {
                throw BridgeError("action_failed", "PendingIntent canceled: ${e.message}")
            }
            Log.i(TAG, "sent remote-input action '${action.title}' to $key")
            return
        }

        // Plain action button: re-fire its PendingIntent directly.
        try {
            action.actionIntent.send()
        } catch (e: PendingIntent.CanceledException) {
            throw BridgeError("action_failed", "PendingIntent canceled: ${e.message}")
        }
        Log.i(TAG, "fired action '${action.title}' on $key")
    }

    private fun resolveAction(
        actions: Array<Notification.Action>,
        ref: Any
    ): Notification.Action? {
        if (ref is Int) {
            return actions.getOrNull(ref)
        }
        val s = ref.toString()
        s.toIntOrNull()?.let { idx -> return actions.getOrNull(idx) }
        return actions.firstOrNull { it.title?.toString() == s }
    }

    @Throws(BridgeError::class)
    fun dismiss(key: String): Boolean {
        val svc = BridgeService.listenerRef
            ?: throw BridgeError("listener_unavailable", "NotificationListenerService not connected")
        val sbn = NotifStore.get(key)
            ?: throw BridgeError("notification_gone", "no live notification with id=$key")
        if (!sbn.isClearable) {
            throw BridgeError("not_clearable", "notification is ongoing and cannot be dismissed")
        }
        svc.cancelNotification(key)
        return true
    }
}
