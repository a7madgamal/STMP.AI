package com.mido.phonebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects a FROZEN main thread and records what it was doing.
 *
 * **Why this exists.** A crash handler is useless for the failure this app actually has. Reported
 * 2026-11-11: *"hangs again"* — the screen stops responding and `8787` refuses, so there is no
 * exception, no FATAL line, and nothing to read. A frozen UI thread is a silent failure by
 * construction: the app never gets to report anything because the thread that would report it is
 * the thread that is stuck.
 *
 * The instrument is a heartbeat. A daemon thread posts a token to the main looper once a second
 * and waits for it to run. If the token does not run within [STALL_MS], the main thread is not
 * processing messages, which is the definition of the hang. Its stack is captured from OUTSIDE
 * that thread — the only way to see what a stuck thread is doing — and written to a file that
 * `GET /debug` reports as `lastStall`.
 *
 * Deliberately cheap: one Handler post per second and one volatile compare. It runs only while
 * the process lives, and it never touches the main thread's own work.
 */
object Stall {

    private const val FILE = "last-stall.txt"

    /** How long the main thread may ignore a heartbeat before it counts as frozen. */
    private const val STALL_MS = 3_000L

    private const val CHECK_INTERVAL_MS = 1_000L

    /** One report per stall, so a permanent freeze does not rewrite the file forever. */
    @Volatile
    private var reported = false

    @Volatile
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val main = Looper.getMainLooper()
        val handler = Handler(main)

        Thread({
            while (true) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    val postedAt = System.currentTimeMillis()
                    // AtomicLong, not a `@Volatile` local: that annotation only applies to
                    // properties, so a captured local cannot be volatile in Kotlin.
                    val ranAt = AtomicLong(0L)
                    handler.post { ranAt.set(System.currentTimeMillis()) }

                    Thread.sleep(STALL_MS)

                    if (ranAt.get() == 0L) {
                        if (!reported) {
                            reported = true
                            record(app, main, postedAt)
                        }
                    } else {
                        // Recovered (or never stalled): allow the next stall to be recorded.
                        reported = false
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    android.util.Log.e("PhoneBridge", "stall watch failed: ${t.message}")
                }
            }
        }, "stall-watch").apply { isDaemon = true }.start()

        Debug.log("stall watch installed (${STALL_MS}ms)")
    }

    /**
     * Capture the main thread's stack.
     *
     * `Thread.getStackTrace()` on ANOTHER thread is safe and does not require the target to
     * cooperate — which is the whole point, since it cannot. The watchdog thread does the
     * writing so the frozen thread is never asked to do anything.
     */
    private fun record(app: Context, main: Looper, postedAt: Long) {
        try {
            val stack = main.thread.stackTrace
                .joinToString("\n") { "    at $it" }
            val text = buildString {
                append("at=").append(System.currentTimeMillis()).append('\n')
                append("heartbeat posted at=").append(postedAt).append('\n')
                append("main thread did not process a message for ").append(STALL_MS).append("ms\n")
                append("thread=").append(main.thread.name).append("\n")
                append(stack)
            }
            File(app.filesDir, FILE).writeText(text)
            // The ring buffer is on the frozen thread's own work queue, but writing here is
            // harmless: it is a synchronized deque, not a UI call.
            Debug.log("STALL main thread frozen ${STALL_MS}ms — stack recorded")
        } catch (t: Throwable) {
            android.util.Log.e("PhoneBridge", "could not record stall: ${t.message}")
        }
    }

    /** The last recorded stall, or null when the main thread has never frozen. */
    fun last(ctx: Context): String? = try {
        File(ctx.applicationContext.filesDir, FILE).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        null
    }

    /** Clear the record, so a later stall is distinguishable from this one. */
    fun clear(ctx: Context) {
        try {
            File(ctx.applicationContext.filesDir, FILE).delete()
        } catch (e: Exception) {
            android.util.Log.w("PhoneBridge", "could not clear stall record: ${e.message}")
        }
    }
}
