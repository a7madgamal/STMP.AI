package com.mido.phonebridge.audit

import android.content.Context
import com.mido.phonebridge.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file-backed audit store: one append-only JSONL file per day.
 *
 * **The file name IS the date**, and retention works on whole files. That is what
 * makes pruning safe to reason about: there is no partial trim to get wrong, no
 * rewrite of a line in the middle, and a day either exists or it does not. It also
 * means a corrupt line costs one line, not the day — [load] counts it and moves on.
 *
 * Takes a [File] rather than a [Context] precisely so the whole store can be tested
 * against a temporary directory with no Android runtime in the way.
 *
 * The index is a bounded in-memory ring (newest first) so the list view never reads
 * 20 MB of files to show 50 rows. It is rebuilt from disk on construction, which is
 * what an app restart looks like, and that path is tested.
 */
class AuditLog(
    private val dir: File,
    private val retention: AuditRetention = AuditRetention(),
    private val appVersionName: String,
    private val appVersionCode: Int,
) : AuditStore {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val index = ArrayDeque<AuditHeader>()
    private var unreadable = 0

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cannot create audit dir $dir")
        }
        load()
        prune()
    }

    /**
     * Append one record. Throws on failure — deliberately.
     *
     * Nothing executes unrecorded: a failed write must fail the request, not be
     * swallowed and reported as success. [AuditHealth] is set first so the UI can
     * explain the red banner the human is about to see.
     */
    @Synchronized
    override fun append(record: AuditRecord) {
        val file = dayFile(record.ts)
        try {
            file.appendText(record.toJson().toString() + "\n")
        } catch (e: Exception) {
            AuditHealth.markDegraded(
                "audit append failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            throw e
        }
        AuditHealth.clear()
        index.addFirst(
            AuditHeader(
                id = record.id,
                ts = record.ts,
                outcome = record.outcome,
                label = record.label,
                plugin = record.plugin,
                verb = record.verb,
                summary = record.summary,
                durationMs = record.durationMs,
                responseStatus = record.responseStatus,
                noise = record.noise,
            )
        )
        while (index.size > DEFAULT_MAX) index.removeLast()
    }

    @Synchronized
    override fun headers(): List<AuditHeader> = index.toList()

    @Synchronized
    override fun storageStats(): AuditStorage {
        val days = allDayFiles()
            .sortedByDescending { it.name }
            .map { f ->
                // A day whose lines cannot be counted still reports its size; the null
                // is how the report says "bytes known, lines unknown" instead of lying
                // with a zero.
                val lines = try {
                    f.readLines().count { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                }
                AuditDay(
                    date = f.name.removeSuffix(SUFFIX),
                    bytes = f.length(),
                    lines = lines,
                )
            }
        return AuditStorage(days, days.sumOf { it.bytes }, retention)
    }

    /**
     * The exact stored line for [id].
     *
     * Matches on the JSON `"id":"..."` needle rather than parsing every line: the
     * detail screen wants the verbatim bytes, and re-serialising a parsed record
     * would hand back something that merely looks the same.
     */
    @Synchronized
    override fun line(id: String): String? {
        val needle = "\"id\":\"$id\""
        for (f in dayFilesNewestFirst()) {
            val lines = try {
                f.readLines()
            } catch (e: Exception) {
                unreadable++
                continue
            }
            for (l in lines) {
                if (l.contains(needle)) return l
            }
        }
        return null
    }

    @Synchronized
    override fun unreadableCount(): Int = unreadable

    @Synchronized
    override fun clear() {
        for (f in dayFilesNewestFirst()) f.delete()
        index.clear()
        unreadable = 0
    }

    private fun dayFilesNewestFirst(): List<File> = allDayFiles().sortedByDescending { it.name }

    private fun allDayFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList() ?: emptyList()

    private fun dayFile(ts: Long): File = File(dir, dayFmt.format(Date(ts)) + SUFFIX)

    /**
     * Rebuild the index from disk, newest day first and newest line first.
     *
     * A line that will not parse is COUNTED, never skipped silently — an unreadable
     * count of 0 has to mean "nothing was unreadable", not "nothing was looked at".
     */
    private fun load() {
        for (f in dayFilesNewestFirst()) {
            val lines = try {
                f.readLines()
            } catch (e: Exception) {
                unreadable++
                continue
            }
            for (i in lines.indices.reversed()) {
                val l = lines[i]
                if (l.isBlank()) continue
                val h = AuditHeader.parse(l)
                if (h == null) {
                    unreadable++
                    continue
                }
                index.addLast(h)
                if (index.size >= DEFAULT_MAX) return
            }
        }
    }

    /**
     * Drop what the retention rule says to drop: first by age, then by total size.
     *
     * Age goes first so a very old day cannot survive merely because the log is
     * small. Size then deletes OLDEST FIRST, so the newest history — the part someone
     * debugging actually reads — is what remains.
     */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - retention.maxAgeDays.toLong() * 24L * 60L * 60L * 1000L
        for (f in allDayFiles().sortedBy { it.name }) {
            val ts = dayStart(f.name) ?: continue
            if (ts < cutoff) f.delete()
        }

        var total = allDayFiles().sumOf { it.length() }
        for (f in allDayFiles().sortedBy { it.name }) {
            if (total <= retention.maxTotalBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private fun dayStart(name: String): Long? = try {
        dayFmt.parse(name.removeSuffix(SUFFIX))?.time
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_MAX = 2000
        private const val SUFFIX = ".jsonl"

        @Volatile
        private var singleton: AuditStore? = null

        /**
         * The process-wide store. Idempotent, same pattern as `AuthManager.init`.
         *
         * Every screen calls this rather than constructing its own: two instances
         * over one directory would keep two indexes and disagree about what is in the
         * log until the next restart.
         */
        @Synchronized
        fun from(ctx: Context): AuditStore {
            singleton?.let { return it }
            val store = AuditLog(
                File(ctx.filesDir, "audit"),
                AuditRetention(),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            )
            singleton = store
            return store
        }
    }
}
