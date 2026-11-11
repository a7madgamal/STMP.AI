package com.mido.phonebridge

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The last uncaught crash, kept where a human can read it.
 *
 * **Why this exists.** A crash report with no stack is unanswerable: logcat's retained buffer is
 * a few hundred lines, and this app logs enough per notification that a crash trace rotates out
 * within minutes. Reported 2026-11-11 as *"still failing and asking me to reset app data after
 * crash"* — the app had already restarted, the buffer had already moved past the trace, and the
 * only evidence left was the symptom.
 *
 * So the trace is written to a file the moment it happens, and `/debug` reports it. That turns
 * "it crashed again" into a named file and line on the next request.
 *
 * Deliberately last-write-wins rather than a growing list: one crash is what the next fix needs,
 * and an unbounded file on a device that is crash-looping would become its own problem.
 */
object Crash {

    private const val FILE = "last-crash.txt"

    @Volatile
    private var installed = false

    /**
     * Install the handler. Idempotent; call once, as early as possible.
     *
     * The previous handler is ALWAYS chained: swallowing the exception would leave Android
     * believing the process survived, which hides the crash from the system and turns a clean
     * failure into a hung app.
     */
    fun install(ctx: Context) {
        if (installed) return
        installed = true

        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, FILE).writeText(
                    Evidence.header(context = app) + trace
                )
                // Also into the ring buffer, so it appears in the on-screen log immediately.
                Debug.log("CRASH ${error.javaClass.name}: ${error.message}")
            } catch (t: Throwable) {
                // Never let bookkeeping replace the real failure.
                android.util.Log.e("PhoneBridge", "could not record crash: ${t.message}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last recorded crash, or null when the app has not crashed since install. */
    fun last(ctx: Context): String? = Evidence.read(ctx, FILE)
}

/**
 * The header every self-report carries, and the rule that keeps old reports out of the UI.
 *
 * **Why a timestamp alone is not enough.** The first version wrote the stack and nothing else,
 * so after an upgrade the app kept showing a crash that had already been fixed. Reported
 * 2026-11-11, immediately after the real fix landed: *"works now / but i see this"* — the trace
 * was from the PREVIOUS build, and the report gave no way to tell. A diagnostic surface that
 * cries wolf after every fix is worse than none, because it trains the reader to ignore it.
 *
 * So each record states when it happened and which build wrote it, and [read] returns null when
 * the record predates the installed package. That comparison is a fact the platform keeps
 * (`lastUpdateTime`), not something this code has to infer.
 */
internal object Evidence {

    fun header(context: Context): String = buildString {
        append("at=").append(System.currentTimeMillis()).append('\n')
        append("version=").append(com.mido.phonebridge.BuildConfig.VERSION_NAME)
            .append(" (").append(com.mido.phonebridge.BuildConfig.VERSION_CODE).append(")\n")
        append("thread=").append(Thread.currentThread().name).append('\n')
    }

    /**
     * The record, or null when it is absent or was written by a build that is no longer installed.
     *
     * A stale file is DELETED, not merely hidden, so it cannot resurface on the next render or
     * be pasted into a bug report as if it were current.
     */
    fun read(ctx: Context, name: String): String? {
        val app = ctx.applicationContext
        val file = File(app.filesDir, name)
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return null
        }
        val writtenAt = text.lineSequence()
            .firstOrNull { it.startsWith("at=") }
            ?.removePrefix("at=")?.trim()?.toLongOrNull()
        val installedAt = try {
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        if (writtenAt != null && installedAt > 0L && writtenAt < installedAt) {
            android.util.Log.i(
                "PhoneBridge",
                "$name predates this install (${writtenAt} < $installedAt); discarding",
            )
            file.delete()
            return null
        }
        return text
    }
}
