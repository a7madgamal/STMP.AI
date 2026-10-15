package com.mido.phonebridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotifListenerService : NotificationListenerService() {
    private val tag = "NotifListener"

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(tag, "listener connected")
        NotifStore.seed(this)
        BridgeService.listenerRef = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(tag, "listener disconnected")
        BridgeService.listenerRef = null
    }

    override fun onDestroy() {
        BridgeService.listenerRef = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // Ignore our own notifications (the approval prompt).
        if (sbn.packageName == packageName) return
        // Ignore ongoing/foreground-service noise? keep everything for now.
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            NotifStore.put(sbn)
            return
        }
        NotifStore.put(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        NotifStore.remove(sbn)
    }
}
