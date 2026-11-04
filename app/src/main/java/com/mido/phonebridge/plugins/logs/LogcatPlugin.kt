package com.mido.phonebridge.plugins.logs

import android.content.Context
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * `GET /logs` — this app's own logcat, over HTTP.
 *
 * **Own process only.** Since Android 4.1 an app may read only the lines its own UID
 * produced, with no permission, so `logcat` run in-process returns exactly that
 * subset. Reading system or other apps' logs needs `READ_LOGS`, which is
 * signature-level and not grantable — do not ask for it.
 *
 * This is the route for a DRAWN UI. The `NOTIFY` overlay logs its own lifecycle
 * because appearance cannot be inferred from a response body, so "why did the card
 * flash" is answerable here and nowhere else.
 *
 * Both bounds are validated BEFORE the gate, so probing the limit costs no popup:
 * a read the human is asked to approve should never be one that can only fail.
 */
class LogcatPlugin(private val ctx: Context) : Plugin {

    override val id = "logs"

    

    /**

     * **Required. Not a preference.**

     *

     * `logs` is the same argument as `debug`: it is read-only diagnosis. A bridge that will not serve is diagnosed through this route, so it cannot be behind a switch that a caller has no way to throw.

     */

    override val displayName = "Logs"

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/logs", "Read this app's own logcat", Verb.READ,
            // The reply IS the log, so storing it would copy the log into the log on
            // every call — the same self-referential growth `/debug` opts out of.
            auditResponseBody = false,
            auditRequestBody = false,
            // No AI: this route exists to explain a broken bridge, so it must not
            // depend on the model, the thing most likely to be broken. This is also
            // what exempts it from the model-readiness gate.
            aiSummary = false,
            validate = { req -> parseLines(req.query["lines"]) }
        ) { req -> read(parseLines(req.query["lines"]), req.query["filter"]) }
    )

    override fun status(): PluginStatus =
        PluginStatus("Logs", PluginStatus.State.OK, "GET /logs?lines=$DEFAULT_LINES")

    /**
     * Validate `?lines`, refusing rather than clamping.
     *
     * A silent clamp was the old behaviour and it lied: `lines=5000` quietly returned
     * 500, so a caller could not tell a truncated answer from a complete one. Out of
     * range is `bad_lines` — a 400, pre-gate, and noise.
     */
    private fun parseLines(raw: String?): Int {
        if (raw == null) return DEFAULT_LINES
        val n = raw.toIntOrNull()
            ?: throw PluginError("bad_lines", "lines must be a number, got '$raw'")
        if (n < 1) throw PluginError("bad_lines", "lines must be at least 1, got $n")
        if (n > MAX_LINES) throw PluginError("bad_lines", "lines must be at most $MAX_LINES, got $n")
        return n
    }

    private fun read(lines: Int, filter: String?): Resp {
        val output = try {
            dumpLogcat(lines)
        } catch (e: Exception) {
            // A real failure, never "there were no logs": an empty log is a successful
            // read of nothing, and the two must not collapse.
            return Resp.error(500, "logcat_failed", e.message ?: "could not run logcat")
        }

        val all = output.split('\n').filter { it.isNotBlank() }
        val kept = if (filter.isNullOrBlank()) all else all.filter { it.contains(filter) }

        return Resp.ok(
            JSONObject()
                .put("lines", kept.size)
                .put("returned", kept.size)
                .put("totalCaptured", all.size)
                .put("filter", filter ?: JSONObject.NULL)
                // True when the READ hit the character cap, not when the filter hid
                // lines — a filtered-down answer is complete for its query.
                .put("truncated", output.length >= MAX_CHARS)
                .put("log", kept.joinToString("\n"))
        )
    }

    /**
     * Run `logcat -d` and read at most [MAX_CHARS].
     *
     * The cap is enforced DURING the read rather than after, so a pathological log
     * cannot be buffered into the heap first and then trimmed. The wait is bounded
     * too: a `logcat` that hangs must not hold a server thread forever.
     */
    private fun dumpLogcat(lines: Int): String {
        val pb = ProcessBuilder("logcat", "-d", "-t", lines.toString(), "-v", "time")
        pb.redirectErrorStream(true)
        val process = pb.start()

        val text = process.inputStream.bufferedReader().use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (sb.length < MAX_CHARS) {
                val read = reader.read(buf)
                if (read <= 0) break
                sb.append(buf, 0, read)
            }
            sb.toString()
        }

        if (!process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        return text
    }

    private companion object {
        const val DEFAULT_LINES = 200
        const val MAX_LINES = 500
        const val MAX_CHARS = 256 * 1024
        const val WAIT_SECONDS = 5L
    }
}
