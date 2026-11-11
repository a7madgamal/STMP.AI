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
                    "at=${System.currentTimeMillis()}\nthread=${thread.name}\n$trace"
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
    fun last(ctx: Context): String? = try {
        File(ctx.applicationContext.filesDir, FILE).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        null
    }
}
