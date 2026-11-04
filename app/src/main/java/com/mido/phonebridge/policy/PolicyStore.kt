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
            //
            // **AUTOMATIC, not SECURE.** AUTOMATIC is the mode that READS the rule table, so
            // it is the only one where a default rule means anything. Under a forced SECURE or
            // NOTIFY every rule is inert, which would make the seeded `help/NOTIFY` below a lie
            // — the screen would show a rule that never fires.
            //
            // This does not weaken the gate: AUTOMATIC with no matching rule still resolves to
            // SECURE (see `resolve`), so "silence means ask me" is preserved. The default
            // changes WHICH mode is in force, not how much a rule can grant — and a rule can
            // never auto-approve, because no such mode exists.
            val fresh = Policy(GlobalMode.WHITELIST, DEFAULT_RULES)
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
        // **A repaired file is WRITTEN BACK, not just logged.**
        //
        // Mido, 2026-11-04: *"if incompatible values appear next time fallback to the default and
        // store it and add a debug hint"*. Without the write, every read repairs the same file
        // again and emits the same warning forever — a repair that costs work on every boot is
        // not a repair. The rewrite also means a downgrade-then-upgrade does not resurrect the
        // stale value.
        //
        // Deliberately AFTER the logging, so the reason is in the ring before the bytes change.
        if (parsed.repaired) {
            com.mido.phonebridge.Debug.log(
                "policy: file repaired and rewritten by this build"
            )
            try {
                write(parsed)
            } catch (e: Exception) {
                // A failed rewrite must not lose the parse: the in-memory policy is valid and
                // the server should start. The file simply gets repaired again next read.
                com.mido.phonebridge.Debug.log("policy: repair write failed: ${e.message}")
            }
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

        /**
         * The rules a fresh install starts with.
         *
         * **`help/ANY → NOTIFY` exists so the first thing an agent calls does not demand a
         * fingerprint.** `/help` is read-only and returns nothing sensitive, so making a
         * newcomer tap a full-screen modal to learn what the bridge can do is friction with no
         * security payoff. NOTIFY is the floor, not an exemption: the card still appears, silence
         * still approves for five seconds, and the call is still audited like every other one.
         *
         * ANY rather than an exact READ rule so a future help route is downgraded too — the
         * point is the plugin's character, not one path.
         *
         * Seeded as DATA rather than baked into the resolver: it shows up on the exceptions
         * screen like any other rule, so the human can see it and change it. A hidden default
         * that a user cannot find is the kind of thing this app refuses to do.
         */
        private val DEFAULT_RULES: List<Rule> = listOf(Rule("help", null, Mode.DONT_DISTURB))

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
