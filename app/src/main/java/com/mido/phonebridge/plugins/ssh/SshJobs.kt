package com.mido.phonebridge.plugins.ssh

import com.mido.phonebridge.core.PluginError
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory registry of detached SSH commands.
 *
 * **Why this exists.** A synchronous `/ssh/{name}` call is capped at 300s and, at the deadline,
 * the session is torn down — so any build, clone or agent run either fits or is killed with no exit
 * code. The workaround was a manual `nohup … & echo $!` on the far end followed by separate `tail`
 * calls: correct, but it makes every long job a small shell-programming exercise and leaves the
 * caller composing quoting by hand. This registry moves that bookkeeping into the app.
 *
 * **What it deliberately is NOT.** It does not survive the process: a job dies with the app, and
 * that is the honest contract — the alternative is pretending a restart preserved a live socket.
 * It also does not bypass the gate; each poll is an ordinary gated request, so polling costs a
 * decision exactly as a fresh command would.
 *
 * **Bounded on every axis**, because an unbounded job table inside a foreground service is how a
 * phone gets killed:
 *  - [MAX_JOBS] concurrent jobs, refused beyond that with `ssh_job_limit`;
 *  - a retained tail per job, never the whole stream (see [SshTail]);
 *  - finished jobs expire after [TTL_MS] so a forgotten poll cannot pin memory forever.
 */
object SshJobs {

    /** Concurrent detached jobs. Small on purpose: each one holds an open SSH session. */
    const val MAX_JOBS = 4

    /** How long a FINISHED job stays readable so its output can still be collected. */
    const val TTL_MS = 30 * 60 * 1000L

    /** One detached run. Mutated by its own thread, read by pollers. */
    class Job(
        val id: String,
        val name: String,
        val command: String,
        val startedAtMs: Long,
        private val tail: SshTail,
    ) {
        @Volatile var done: Boolean = false
        @Volatile var exitCode: Int = -1
        @Volatile var timedOut: Boolean = false
        @Volatile var error: String? = null
        @Volatile var finishedAtMs: Long = 0L

        /** The retained tail so far. Safe to call while the command is still running. */
        fun snapshot(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("command", command)
            .put("done", done)
            .put("exitCode", exitCode)
            .put("timedOut", timedOut)
            .put("truncated", tail.truncated)
            .put("outputBytes", tail.totalBytes())
            .put("startedAtMs", startedAtMs)
            .put("durationMs", (if (done) finishedAtMs else System.currentTimeMillis()) - startedAtMs)
            .put("stdout", tail.text())
            .put("error", error ?: JSONObject.NULL)
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val counter = AtomicInteger(0)

    /**
     * Start [command] in the background and return its job id immediately.
     *
     * The connection is resolved by the CALLER, before this is reached, so a bad name or a locked
     * vault still fails as a coded 4xx pre-gate rather than as a job that exists and then dies.
     */
    fun start(
        conn: SshConnection,
        name: String,
        command: String,
        timeoutMs: Long,
        trustFingerprint: String?,
        stdin: CharArray?,
    ): Job {
        if (command.isBlank()) throw PluginError("bad_request", "command must not be empty")
        purgeExpired()

        // Count only jobs still running: finished ones are just output waiting to be read, and
        // refusing a new job because four OLD results are uncollected would be the wrong limit.
        val running = jobs.values.count { !it.done }
        if (running >= MAX_JOBS) {
            throw PluginError(
                "ssh_job_limit",
                "$running detached job(s) still running (limit $MAX_JOBS). Poll or wait for one to finish."
            )
        }

        val id = "j${counter.incrementAndGet()}-${System.currentTimeMillis().toString(36)}"
        val tail = SshTail(SshExec.MAX_OUTPUT_BYTES)
        val job = Job(id, name, command, System.currentTimeMillis(), tail)
        jobs[id] = job

        // A plain thread, not a shared pool: the run blocks on socket I/O for up to timeoutMs, and
        // a bounded pool would let four long jobs starve every later one.
        Thread({
            try {
                val result = SshExec.run(
                    conn = conn,
                    command = command,
                    timeoutMs = timeoutMs,
                    trustFingerprint = trustFingerprint,
                    stdin = stdin,
                    sink = tail,
                )
                job.exitCode = result.exitCode
                job.timedOut = result.timedOut
            } catch (e: PluginError) {
                // Recorded on the job rather than thrown into a thread that nobody is reading.
                // A poller must be able to see WHY it failed, not just that it stopped.
                job.error = "${e.code}: ${e.message}"
            } catch (e: Exception) {
                job.error = "ssh_failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                // The password has served its purpose; wipe it before the thread ends.
                stdin?.fill('\u0000')
                job.finishedAtMs = System.currentTimeMillis()
                job.done = true
            }
        }, "ssh-job-$id").apply { isDaemon = true }.start()

        return job
    }

    /** A job by id, or a coded 404. Never a null the caller might treat as "no output". */
    fun get(id: String): Job {
        purgeExpired()
        return jobs[id] ?: throw PluginError("ssh_job_not_found", "no detached job '$id' (finished jobs expire after 30 min)")
    }

    /** Ids still held, newest first. Used by the poll route's error text and by tests. */
    fun ids(): List<String> = jobs.keys.sortedDescending()

    /** Drop finished jobs past their TTL. Called on every start and poll. */
    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        jobs.entries.removeIf { (_, j) -> j.done && j.finishedAtMs in 1..cutoff }
    }

    /** Test hook: forget everything. */
    fun clear() = jobs.clear()
}
