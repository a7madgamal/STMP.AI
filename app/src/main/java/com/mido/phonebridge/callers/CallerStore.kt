package com.mido.phonebridge.callers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * `callers.json` — the addresses a human has decided about.
 *
 * **A separate file from `policy.json`, deliberately.** Policy is the GATE: it decides whether a
 * human is asked, and a broken file refuses to start the server. This is a LABEL REGISTRY: it
 * decides what a caller is CALLED and whether an unnamed one is refused. Mixing them would put a
 * naming typo in the same blast radius as the approval gate.
 *
 * **Read leniently, like the preferences** — a malformed row is dropped and reported, never
 * guessed at. But unlike a preference there is no default entry: an address with no row is
 * UNVERIFIED, which is refused. Absence must never be able to mean "allowed".
 *
 * Writes are atomic (temp + rename), same as `PolicyStore`: a kill mid-write must not leave a
 * half file that the next boot reads as "everyone is verified".
 */
class CallerStore(private val dir: File) {

    private val file: File get() = File(dir, FILE_NAME)

    @Volatile
    private var cached: Map<String, CallerEntry>? = null

    /** Every decided caller. First call reads the file; later calls return the cache. */
    @Synchronized
    fun all(): Map<String, CallerEntry> {
        cached?.let { return it }
        val f = file
        // No file is a real state: a fresh install has named nobody. It is NOT an error, and it
        // is not written back — an empty registry and a missing one behave identically, and
        // writing an empty file would only create something to corrupt.
        if (!f.exists()) {
            cached = emptyMap()
            return emptyMap()
        }
        val entries = try {
            parse(f.readText())
        } catch (e: Exception) {
            throw CallerStoreException("callers.json unreadable: ${e.message}")
        }
        cached = entries
        return entries
    }

    /** The state of one address, or null when nobody has decided about it. */
    @Synchronized
    fun stateOf(remote: String): CallerState? = CallerRegistry.stateOf(all(), remote)

    /** The label for a modal row or an audit record. Never a bare IP pretending to be a name. */
    @Synchronized
    fun labelFor(remote: String): String = CallerRegistry.labelFor(all(), remote)

    /**
     * Record a decision. [state] is `Verified(name)` or `Denied` — never "unverified", because
     * unverified is the absence of a row and cannot be written.
     */
    @Synchronized
    fun set(remote: String, state: CallerState, nowMs: Long = System.currentTimeMillis()) {
        val next = all().toMutableMap()
        val existing = next[remote]
        next[remote] = CallerEntry(
            ip = remote,
            state = state,
            // Keep the ORIGINAL first-seen time. Overwriting it on every rename would erase when
            // the address actually appeared, which is the only forensic fact here.
            firstSeenMs = existing?.firstSeenMs ?: nowMs,
        )
        write(next)
        cached = next
    }

    /** Forget one address entirely, so its next call is unverified again. */
    @Synchronized
    fun forget(remote: String) {
        val next = all().toMutableMap()
        if (next.remove(remote) == null) return
        write(next)
        cached = next
    }

    private fun write(entries: Map<String, CallerEntry>) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw CallerStoreException("cannot create caller dir ${dir.absolutePath}")
        }
        val arr = JSONArray()
        for (e in entries.values.sortedBy { it.ip }) {
            val o = JSONObject().put("ip", e.ip).put("firstSeen", e.firstSeenMs)
            when (val s = e.state) {
                is CallerState.Verified -> o.put("state", "verified").put("name", s.name)
                CallerState.Denied -> o.put("state", "denied")
            }
            arr.put(o)
        }
        val doc = JSONObject().put("version", VERSION).put("callers", arr)

        val tmp = File(dir, "$FILE_NAME.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(doc.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                throw CallerStoreException("cannot replace ${file.absolutePath}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw if (e is CallerStoreException) e else
                CallerStoreException("callers.json write failed: ${e.message}")
        }
    }

    /**
     * Parse the file. A row without a recognisable state is DROPPED, not defaulted.
     *
     * Defaulting an unreadable row to `verified` would let a corrupt byte grant access; defaulting
     * it to `denied` would silently block a caller the human approved. Dropping it makes the
     * address unverified again, which is refused and re-asks — the only outcome that neither
     * grants nor permanently locks anything on bad data.
     */
    private fun parse(raw: String): Map<String, CallerEntry> {
        val doc = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw CallerStoreException("callers.json is not valid JSON: ${e.message}")
        }
        val version = doc.optInt("version", -1)
        if (version != VERSION) {
            throw CallerStoreException("callers.json version $version is not $VERSION")
        }
        val arr = doc.optJSONArray("callers") ?: JSONArray()
        val out = LinkedHashMap<String, CallerEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ip = o.optString("ip").trim()
            if (ip.isEmpty()) continue
            val state: CallerState = when (o.optString("state")) {
                "verified" -> {
                    val name = o.optString("name").trim()
                    // A verified row with no name is not verifiable: the whole point of the
                    // record is the name a human gave it.
                    if (name.isEmpty()) continue
                    CallerState.Verified(name)
                }
                "denied" -> CallerState.Denied
                else -> continue
            }
            out[ip] = CallerEntry(ip = ip, state = state, firstSeenMs = o.optLong("firstSeen", 0L))
        }
        return out
    }

    companion object {
        private const val FILE_NAME = "callers.json"
        private const val VERSION = 1

        @Volatile
        private var singleton: CallerStore? = null

        /** Idempotent, same pattern as `PolicyStore.from` / `AuditLog.from`. */
        @Synchronized
        fun from(ctx: Context): CallerStore {
            singleton?.let { return it }
            val store = CallerStore(File(ctx.filesDir, "callers"))
            singleton = store
            return store
        }
    }
}

/** A registry that cannot be read is not a registry. Never repaired, never guessed. */
class CallerStoreException(message: String) : Exception(message)
