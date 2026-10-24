package com.mido.phonebridge.policy

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * The policy on disk: one small JSON file, read once and cached.
 *
 * Read policy is fail-hard. A file that cannot be parsed does not degrade into
 * "assume SECURE": the caller gets the exception and refuses to start the server.
 * Silently re-enabling a gate the user turned off, or silently running with a gate
 * they believe is off, are both worse than a bridge that will not serve.
 *
 * Writes are atomic (temp + rename) so a kill mid-write cannot leave a half file
 * that the next boot reads as corrupt.
 */
class PolicyStore(private val dir: File) {

    private val file: File get() = File(dir, FILE_NAME)

    @Volatile
    private var cached: Policy? = null

    /** Current policy. First call reads the file; later calls return the cache. */
    @Synchronized
    fun get(): Policy {
        cached?.let { return it }
        val f = file
        if (!f.exists()) {
            // No file is a real, valid state: a fresh install has never chosen.
            // This is the one place a default is correct, and it is written down
            // immediately so the "no choice" state is not re-derived every boot.
            val fresh = Policy(GlobalMode.SECURE)
            write(fresh)
            return fresh
        }
        val raw = try {
            f.readText()
        } catch (e: Exception) {
            throw PolicyException("policy file unreadable: ${e.message}")
        }
        val parsed = Policy.parse(raw)
        // The ONE place these can be logged: `Policy.parse` is pure (it has to be, or the JVM
        // suite cannot touch it — `android.util.Log` throws "not mocked" there). So the decision
        // travels as data and the side effect happens here, where Android exists.
        for (warning in parsed.preferenceWarnings) {
            com.mido.phonebridge.Debug.log("policy: $warning")
        }
        cached = parsed
        return parsed
    }

    /**
     * Persist a new policy. The cache is updated only after the bytes are safely on
     * disk — a failed write must not leave the process believing a change took
     * effect that a restart would undo.
     */
    @Synchronized
    fun set(next: Policy) {
        write(next)
        cached = next
    }

    private fun write(p: Policy) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw PolicyException("cannot create policy dir ${dir.absolutePath}")
        }
        val tmp = File(dir, "$FILE_NAME.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(p.toJson().toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                throw PolicyException("cannot replace ${file.absolutePath}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw if (e is PolicyException) e else PolicyException("policy write failed: ${e.message}")
        }
    }

    companion object {
        private const val FILE_NAME = "policy.json"

        @Volatile
        private var singleton: PolicyStore? = null

        /** Idempotent, same pattern as `AuditLog.from` / `AuthManager.init`. */
        @Synchronized
        fun from(ctx: Context): PolicyStore {
            singleton?.let { return it }
            val store = PolicyStore(File(ctx.filesDir, "policy"))
            singleton = store
            return store
        }
    }
}
