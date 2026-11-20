package com.mido.phonebridge

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny ring-buffer logger rendered on-screen so we can debug without adb.
 */
object Debug {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Redaction kill-switch, for debugging only. When false, raw values reach
     * the on-screen log and logcat. Flip back to true before any real use.
     */
    @Volatile
    var redactEnabled: Boolean = true

    fun init(ctx: Context) { /* no-op placeholder for future file logging */ }

    /**
     * **A repeated identical line is coalesced, not appended.**
     *
     * The buffer is 200 entries and a drawn surface can log many times a second, so one chatty
     * line evicts every other line — and a ring that only contains the noisiest thing in the app
     * cannot answer "why did the login fail", which is what it exists for. Rather than rely on
     * every caller to behave (it did not: a per-frame `onDraw` line emptied the ring), the log
     * itself refuses to let one message occupy it. The repeat count is shown, so a storm is
     * still visible as a storm instead of being hidden.
     */
    @Synchronized
    fun log(msg: String) {
        val safe = if (redactEnabled) redact(msg) else msg
        val stamp = fmt.format(Date())
        val last = lines.lastOrNull()
        val lastBody = last?.substringAfter("  ", "")
        if (lastBody != null && lastBody == safe) {
            repeats++
            lines.removeLast()
            lines.addLast("$stamp  $safe  (×${repeats + 1})")
            android.util.Log.i("STMP", safe)
            return
        }
        repeats = 0
        val line = "$stamp  $safe"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("STMP", safe)
    }

    /**
     * How many times the message currently at the tail has repeated.
     *
     * Reset by any different line, so it counts a CONSECUTIVE run rather than a total. Anything
     * else would make two unrelated bursts of the same text look like one.
     */
    private var repeats = 0

    @Synchronized
    fun dump(): String = lines.joinToString("\n").ifBlank { "(no log yet)" }

    /**
     * S13: scrub anything token/secret-shaped before it reaches logcat or the
     * on-screen buffer. Deliberately blunt — a false positive costs a little
     * readability, a false negative leaks a credential.
     */
    private fun redact(s: String): String {
        var out = s
        out = out.replace(Regex("ya29\\.[A-Za-z0-9._\\-]+"), "ya29.[REDACTED]")
        out = out.replace(Regex("1//[A-Za-z0-9._\\-]{10,}"), "1//[REDACTED]")
        out = out.replace(Regex("GOCSPX-[A-Za-z0-9._\\-]+"), "GOCSPX-[REDACTED]")
        out = out.replace(Regex("4/0[A-Za-z0-9._\\-]{10,}"), "4/0[REDACTED]")
        out = out.replace(
            Regex("(?i)(refresh_token|access_token|client_secret|server_auth_code|code)\\s*[=:]\\s*[A-Za-z0-9._/\\-]{8,}"),
            "$1=[REDACTED]"
        )
        out = out.replace(
            Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{10,}"),
            "Bearer [REDACTED]"
        )
        return out
    }
}
