package com.mido.phonebridge

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mido.phonebridge.core.SecretVault
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Block a request until the vault is open, by asking the human on the phone.
 *
 * **The shape mirrors `ApprovalGate`, deliberately.** A bridge request runs on a server thread and
 * blocks on a latch; the human's answer arrives on the main thread through an Activity. Reusing
 * that structure means one mental model for both prompts instead of two.
 *
 * **What it never does: approve anything.** `awaitUnlock` returns only whether the vault opened.
 * The approval is a separate `ApprovalGate.await`, so a scan cannot be read as consent — the
 * caller still has to be shown the target and approved. That separation is the whole point.
 */
object VaultUnlockGate {

    private const val TAG = "VaultUnlockGate"

    const val EXTRA_ID = "vault_id"
    const val EXTRA_ENDPOINT = "vault_endpoint"
    const val EXTRA_REMOTE = "vault_remote"
    const val EXTRA_REASON = "vault_reason"

    /** How long the human has to pass the prompt. Matches the approval window. */
    const val WINDOW_SECONDS = 60L

    private val seq = AtomicLong()

    private class Pending(val id: Long, val latch: CountDownLatch)

    private val lock = Any()

    @Volatile
    private var current: Pending? = null

    /**
     * Wait for the vault to be open, prompting if it is shut.
     *
     * Returns true only when `SecretVault.isUnlocked()` is genuinely true afterwards — not merely
     * because a callback fired. A resolved-but-still-locked result would send a request onward to
     * fail at the vault again, one layer deeper and harder to read.
     */
    fun awaitUnlock(ctx: Context, endpoint: String, remote: String): Boolean {
        if (SecretVault.isUnlocked()) return true

        // COALESCE. Two requests needing the vault at once must not stack two biometric prompts;
        // the second waits on the first and then re-reads the truth.
        val existing = synchronized(lock) { current }
        if (existing != null) {
            existing.latch.await(WINDOW_SECONDS + 5L, TimeUnit.SECONDS)
            return SecretVault.isUnlocked()
        }

        val id = seq.incrementAndGet()
        val pending = Pending(id, CountDownLatch(1))
        synchronized(lock) {
            // Re-check under the lock: another thread may have opened it between the early
            // return above and here, and prompting for an already-open vault is noise.
            if (SecretVault.isUnlocked()) return true
            current = pending
        }

        return try {
            ctx.startActivity(
                Intent(ctx, VaultUnlockActivity::class.java).apply {
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_ENDPOINT, endpoint)
                    putExtra(EXTRA_REMOTE, remote)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                },
            )
            pending.latch.await(WINDOW_SECONDS + 5L, TimeUnit.SECONDS)
            SecretVault.isUnlocked()
        } catch (e: Exception) {
            // No Activity, or starting one was refused. Say so in the log and let the caller
            // refuse with a code — never pretend the vault opened.
            Log.w(TAG, "could not raise the unlock prompt: ${e.message}")
            false
        } finally {
            synchronized(lock) { if (current?.id == id) current = null }
        }
    }

    /** Called by [VaultUnlockActivity] once, whichever way it ended. */
    fun resolve(id: Long, unlocked: Boolean): Boolean {
        val pending = synchronized(lock) {
            current?.takeIf { it.id == id }
        } ?: return false
        if (unlocked && !SecretVault.isUnlocked()) {
            // The callback claimed success but the vault disagrees. Refuse rather than report a
            // state the rest of the app cannot use.
            Log.w(TAG, "unlock reported success but the vault is still shut")
        }
        pending.latch.countDown()
        return true
    }
}
