package com.mido.phonebridge.ui

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.mido.phonebridge.Debug
import com.mido.phonebridge.Fingerprint
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.ui.kit.Toasts

/**
 * The "fingerprint for approvals" setting (D66): one change flow shared by Settings and, from
 * WP12, Home. A fingerprint is asked for in both directions, the switch never moves on the tap,
 * and a phone that cannot read a fingerprint is forced back to off.
 */
object FingerprintSetting {

    /**
     * Ask for the fingerprint, and only then store [want]. [onDone] runs on every path so the
     * caller repaints from what is stored; [onError] receives a message when nothing was written
     * because of a fault (it defaults to a toast).
     */
    fun request(
        fragment: Fragment,
        want: Boolean,
        source: String = "settings",
        onError: ((String) -> Unit)? = null,
        onDone: () -> Unit,
    ) {
        val activity: FragmentActivity = fragment.requireActivity()
        val report: (String) -> Unit = onError ?: { Toasts.show(activity, it) }
        when (val status = Fingerprint.status(activity)) {
            is Fingerprint.Status.Unusable -> {
                // Unreachable while the row is disabled; a stray call never writes a setting this
                // phone cannot honour.
                report(status.reason)
                onDone()
            }

            Fingerprint.Status.Usable -> Fingerprint.confirm(
                activity = activity,
                title = if (want) "Require fingerprint for approvals" else "Stop requiring fingerprint",
                subtitle = "Confirm it's you",
                onConfirmed = { store(activity, want, source, report); onDone() },
                onFailed = { why ->
                    Debug.log("fingerprint setting unchanged: $why")
                    onDone()
                },
            )
        }
    }

    private fun store(ctx: Context, want: Boolean, source: String, report: (String) -> Unit) {
        val store = PolicyStore.from(ctx)
        val current = try {
            store.get()
        } catch (e: PolicyException) {
            report("The policy file is unreadable: ${e.message}")
            return
        }
        // The value the human confirmed, not a flip: a flip would fight the repaint that puts the
        // switch back after a cancelled prompt.
        val next = current.withFingerprintForModals(want)
        try {
            store.set(next)
        } catch (e: Exception) {
            report("Could not save: ${e.message}")
            return
        }
        try {
            AuditLog.from(ctx).append(PolicyAudit.record(next.global, next.global, source))
        } catch (e: Exception) {
            Debug.log("audit record failed for fingerprint setting: ${e.message}")
        }
    }

    /**
     * Turn the setting off because this phone cannot honour it. With it on and no usable
     * biometric the approval screen could never enable APPROVE, so every secure request would be
     * unapprovable. Writes nothing when it is already off.
     */
    fun forceOff(ctx: Context, reason: String, source: String) {
        val store = PolicyStore.from(ctx)
        val current = try {
            store.get()
        } catch (e: PolicyException) {
            Debug.log("cannot force fingerprint off: policy unreadable (${e.message})")
            return
        }
        if (!current.fingerprintForModals) return
        val next = current.withFingerprintForModals(false)
        try {
            store.set(next)
            Debug.log("fingerprint for approvals forced OFF: $reason")
            AuditLog.from(ctx).append(PolicyAudit.record(next.global, next.global, source))
        } catch (e: Exception) {
            Debug.log("could not force fingerprint off: ${e.message}")
        }
    }
}
