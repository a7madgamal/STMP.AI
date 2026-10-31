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

    /**
     * Records currently on disk, across every day file.
     *
     * Kept in memory so the count bound costs nothing to enforce on the append path —
     * re-reading every file to count lines would make each write O(history). Set by
     * [prune] after it has scanned once, and incremented by [append] afterwards.
     *
     * Deliberately NOT [index.size]: the index is capped at [DEFAULT_MAX] for the list
     * view, so using it here would silently disable the count bound above that number.
     */
    private var storedLines = 0

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
        storedLines++
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
        pruneIfNeeded()
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
     *
     * **This method is a READ and must not mutate diagnostic state.** It used to
     * increment [unreadable] when a day file would not read, which meant merely
     * browsing the log inflated the corruption count — a diagnostic number that moves
     * when nobody changed the thing it measures is not a diagnostic number. A failed
     * read here returns null for that file and leaves the count alone; [load] is the
     * one place that measures.
     */
    @Synchronized
    override fun line(id: String): String? {
        val needle = "\"id\":\"$id\""
        for (f in dayFilesNewestFirst()) {
            val lines = try {
                f.readLines()
            } catch (e: Exception) {
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

    /**
     * Delete everything. Returns how many day files were removed.
     *
     * A file that will not delete is a real failure — reporting "cleared" over it would
     * make the caller believe the log is gone when it is not. So the removal is checked
     * per file and the count reflects what actually left the filesystem.
     */
    @Synchronized
    override fun clear(): Int {
        var deleted = 0
        for (f in dayFilesNewestFirst()) {
            if (f.delete()) deleted++
        }
        index.clear()
        unreadable = 0
        storedLines = 0
        AuditHealth.clear()
        return deleted
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
     * Drop what the retention rule says to drop: age first, then total size, then
     * record count. All three bounds act on WHOLE DAY FILES, oldest first, so the
     * newest history — the part someone debugging actually reads — is what survives.
     *
     * **This is also the one place that RECONCILES [storedLines] with the disk.** It
     * always measures rather than trusting a running total, because a number that only
     * ever counts up cannot notice a file deleted from outside the app.
     */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - retention.maxAgeDays.toLong() * DAY_MS
        for (f in allDayFiles().sortedBy { it.name }) {
            val ts = dayStart(f.name) ?: continue
            if (ts < cutoff) f.delete()
        }

        var files = allDayFiles().sortedBy { it.name }

        var total = files.sumOf { it.length() }
        if (total > retention.maxTotalBytes) {
            for (f in files) {
                if (total <= retention.maxTotalBytes) break
                val size = f.length()
                if (f.delete()) total -= size
            }
            files = allDayFiles().sortedBy { it.name }
        }

        val perFile = LinkedHashMap<File, Int>()
        var lines = 0
        for (f in files) {
            // A file that will not read contributes 0 to the count rather than
            // aborting the prune: refusing to enforce a bound because one file is
            // damaged is how a log grows forever.
            val n = try {
                f.readLines().count { it.isNotBlank() }
            } catch (e: Exception) {
                0
            }
            perFile[f] = n
            lines += n
        }

        if (lines > retention.maxLines) {
            // Oldest first, and the NEWEST file is never removed: the file name IS the
            // date, so a day cannot be trimmed in half, and deleting today would leave
            // the next write with no history. The cost is that one very busy day can
            // sit above the bound — bounded by that single day, not by growth forever.
            for (f in files.dropLast(1)) {
                if (lines <= retention.maxLines) break
                if (f.delete()) lines -= perFile[f] ?: 0
            }
        }

        storedLines = lines
    }

    /**
     * Prune only when a bound is actually crossed.
     *
     * The append path calls this on every write, so it runs on EVERY request the phone
     * handles — it must stay cheap. Counting bytes and stat-ing the day files is O(days)
     * (single digits); a full prune re-reads every line and is O(history), so it is not
     * something to do per request. That is the difference between enforcement and a
     * tax on the hot path.
     */
    private fun pruneIfNeeded() {
        val cutoff = System.currentTimeMillis() - retention.maxAgeDays.toLong() * DAY_MS
        var total = 0L
        var hasStale = false
        for (f in allDayFiles()) {
            total += f.length()
            val ts = dayStart(f.name)
            if (ts != null && ts < cutoff) hasStale = true
        }
        if (!hasStale && total <= retention.maxTotalBytes && storedLines <= retention.maxLines) return
        prune()
    }

    private fun dayStart(name: String): Long? = try {
        dayFmt.parse(name.removeSuffix(SUFFIX))?.time
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_MAX = 2000
        private const val SUFFIX = ".jsonl"
        private const val DAY_MS = 24L * 60L * 60L * 1000L

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
