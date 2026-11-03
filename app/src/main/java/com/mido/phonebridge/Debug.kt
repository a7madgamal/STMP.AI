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

    @Synchronized
    fun log(msg: String) {
        val safe = if (redactEnabled) redact(msg) else msg
        val line = "${fmt.format(Date())}  $safe"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("PhoneBridge", safe)
    }

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
