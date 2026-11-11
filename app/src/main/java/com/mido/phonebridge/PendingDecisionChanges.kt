package com.mido.phonebridge

import android.os.Handler
import android.os.Looper

/**
 * Read-only notifications about queue membership changes.
 *
 * The approval gates retain sole ownership of their queues and resolvers. Only immutable
 * snapshots cross this boundary, and every consumer runs on Android's main thread. Publications
 * are coalesced so an older worker-thread snapshot cannot overwrite a newer queue state.
 */
object PendingDecisionChanges {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<MainActivity, (List<PendingDecisionSnapshot>) -> Unit>()
    private var generation = 0L

    fun attach(activity: MainActivity, listener: (List<PendingDecisionSnapshot>) -> Unit) {
        synchronized(lock) { listeners[activity] = listener }
    }

    fun detach(activity: MainActivity) {
        synchronized(lock) { listeners.remove(activity) }
    }

    /** Called only after the owning gate's queue mutation has completed. */
    fun publish(pending: List<PendingDecisionSnapshot>) {
        val token: Long
        val targets: List<(List<PendingDecisionSnapshot>) -> Unit>
        synchronized(lock) {
            generation += 1
            token = generation
            targets = listeners.values.toList()
        }
        main.post {
            if (synchronized(lock) { generation != token }) return@post
            targets.forEach { it(pending) }
        }
    }
}
