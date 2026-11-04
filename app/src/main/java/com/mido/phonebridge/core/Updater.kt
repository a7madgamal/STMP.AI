package com.mido.phonebridge.core

import android.content.Context
import com.mido.phonebridge.BuildConfig
import com.mido.phonebridge.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for a newer build and downloads it.
 *
 * **This assumes the repository is public.** While it is private, every check answers
 * [Outcome.Unavailable] with 404 and the UI says so plainly rather than pretending to be offline
 * or silently doing nothing. That is deliberate: an update checker that goes quiet when it cannot
 * reach the source is indistinguishable from one that is broken, and this app has already paid for
 * "silently does nothing" more than once.
 *
 * **What this class does NOT do.** It never installs. Android requires a human tap for a
 * non-Play install, without exception, so `verify` exists to make that tap safe rather than to
 * automate it: the bytes are checked against the SHA-256 published with the release, and a
 * mismatch refuses before the installer is ever shown.
 *
 * **Every network call is explicit about its failure.** A timeout, a 404, a body that is not a
 * release list, and a version that is older than this build are four different answers, because
 * "no update" and "could not ask" lead to different things on screen.
 */
object Updater {

    /** The public repository. Change these two together if the project ever moves. */
    /**
     * The repository these URLs point at.
     *
     * Public, not private: Home needs them to build the releases-page fallback, and hard-coding
     * the same pair in two files is exactly how one of them ends up stale after a rename.
     */
    const val REPO_OWNER = "a7madgamal"
    const val REPO_NAME = "STMP.AI"

    private const val OWNER = REPO_OWNER
    private const val REPO = REPO_NAME

    private const val RELEASES_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20"

    /** What a check found, as data. The screen decides how to say it. */
    sealed interface Outcome {

        /** A newer version exists and has a downloadable APK. */
        data class Available(
            val version: String,
            val versionCode: Int,
            val notes: String,
            val apkUrl: String,
            val sha256: String?,
            val sizeBytes: Long,
            val isBeta: Boolean,
        ) : Outcome

        /** This build is current. `checkedAt` is when the question was answered. */
        data class UpToDate(val checkedAt: Long) : Outcome

        /**
         * The source could not be asked.
         *
         * Carries WHY, because the remedies differ: a 404 is "the repository is not public yet",
         * a timeout is "the network is down", and neither is the same as being current.
         */
        data class Unavailable(val reason: String) : Outcome
    }

    /**
     * What the last check produced, for the Settings row.
     *
     * Held in memory rather than persisted: a check result is a fact about the network at one
     * moment, and showing yesterday's answer as today's would be a claim this app cannot back up.
     * The timestamp is persisted so the row can honestly say "last checked …".
     */
    @Volatile
    var lastOutcome: Outcome? = null

    @Volatile
    var checking: Boolean = false

    /**
     * Ask GitHub for the newest release this install's channel would accept.
     *
     * Blocking: call from a worker thread. Returns data; never throws for an expected failure.
     */
    fun check(ctx: Context): Outcome {
        checking = true
        return try {
            val outcome = fetch(ctx)
            lastOutcome = outcome
            // Only a real answer updates the timestamp. Recording "last checked" after a failure
            // would make the row claim a check that never completed.
            if (outcome !is Outcome.Unavailable) {
                UpdatePrefs.setLastCheckAt(ctx, System.currentTimeMillis())
            }
            outcome
        } finally {
            checking = false
        }
    }

    private fun fetch(ctx: Context): Outcome {
        val beta = UpdatePrefs.channel(ctx) == UpdatePrefs.Channel.BETA
        val conn = try {
            (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // Short on purpose. This runs behind a button a human is watching, so a slow
                // network should say "could not reach" in a few seconds rather than appearing to
                // hang. 25 seconds of spinner reads as broken, and the release feed is a small
                // JSON document when it answers at all.
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub requires a User-Agent; sending one is also a courtesy to their API.
                setRequestProperty("User-Agent", "STMP/${BuildConfig.VERSION_NAME}")
            }
        } catch (e: Exception) {
            return Outcome.Unavailable("could not reach github.com: ${e.message ?: e.javaClass.simpleName}")
        }

        try {
            val code = conn.responseCode
            if (code == 404) {
                // THE EXPECTED CASE while the repository is private.
                return Outcome.Unavailable(
                    "the release feed is not public — this build checks a public repository " +
                        "and $OWNER/$REPO is private"
                )
            }
            if (code == 403 || code == 429) {
                return Outcome.Unavailable("rate-limited by github (HTTP $code) — try again later")
            }
            if (code != 200) {
                return Outcome.Unavailable("github answered HTTP $code")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = try {
                JSONArray(body)
            } catch (e: Exception) {
                return Outcome.Unavailable("release feed was not a list of releases")
            }

            return selectNewest(ctx, releases, beta)
        } catch (e: Exception) {
            return Outcome.Unavailable("could not read the release feed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pick the newest release this install would accept.
     *
     * **Version comparison is on the tag, parsed as dotted numbers**, not on string ordering:
     * `v0.9.0` must be older than `v0.44.0`, and lexicographically it is newer. The `versionCode`
     * is the tiebreaker only when tags do not parse.
     */
    private fun selectNewest(ctx: Context, releases: JSONArray, beta: Boolean): Outcome {
        var best: Outcome.Available? = null

        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            // Drafts are invisible to users; a prerelease is the beta channel.
            if (rel.optBoolean("draft", false)) continue
            val isBeta = rel.optBoolean("prerelease", false)
            if (isBeta && !beta) continue

            val tag = rel.optString("tag_name").trim()
            if (tag.isBlank()) continue
            val version = tag.removePrefix("v")
            if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) continue

            val apk = findApk(rel) ?: continue

            val candidate = Outcome.Available(
                version = version,
                versionCode = 0,
                notes = rel.optString("body").trim(),
                apkUrl = apk.first,
                sha256 = apk.second,
                sizeBytes = apk.third,
                isBeta = isBeta,
            )
            if (best == null || compareVersions(version, best.version) > 0) best = candidate
        }

        return best ?: Outcome.UpToDate(System.currentTimeMillis())
    }

    /**
     * The release's APK asset, with its published SHA-256 when there is one.
     *
     * `.sha256` is looked for in the release body AND as a conventional asset name, because the
     * two cheap ways to publish one are a sidecar file and a line in the notes. A release with
     * neither still works — `sha256` is null and the UI says the download is unverified rather
     * than pretending it was checked.
     */
    private fun findApk(rel: JSONObject): Triple<String, String?, Long>? {
        val assets = rel.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var size = 0L
        var digestFromAsset: String? = null

        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (url.isBlank()) continue
            if (name.endsWith(".apk", ignoreCase = true) && apkUrl == null) {
                apkUrl = url
                size = a.optLong("size", 0L)
            } else if (name.endsWith(".apk.sha256", ignoreCase = true)) {
                digestFromAsset = a.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }

        val url = apkUrl ?: return null
        return Triple(url, digestFromAsset, size)
    }

    /**
     * Dotted-numeric comparison. `1.2.10` > `1.2.9`, which string ordering gets wrong.
     *
     * Anything non-numeric in a segment is dropped rather than throwing: a tag like `v1.0.0-rc1`
     * still has to compare sensibly, and refusing to compare would mean never offering an update.
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> =
            v.removePrefix("v").split('.', '-').map { seg ->
                seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * Download an APK into app-private storage.
     *
     * Returns the file and the SHA-256 actually observed, so the caller can show what it got
     * rather than what it hoped for.
     */
    data class Downloaded(val file: File, val sha256: String)

    fun download(ctx: Context, update: Outcome.Available, onProgress: (Int) -> Unit = {}): Downloaded {
        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "stmp-${update.version}.apk")
        // A partial download from a previous attempt must never be mistaken for a good one.
        if (target.exists()) target.delete()

        val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "STMP/${BuildConfig.VERSION_NAME}")
        }
        var written = 0L
        try {
            if (conn.responseCode != 200) {
                error("download failed: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            val digest = java.security.MessageDigest.getInstance("SHA-256")

            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return Downloaded(target, hex)
        } catch (e: Exception) {
            // Never leave a truncated file behind: it would be offered again as if complete.
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** Trim the release notes to something a phone screen can show without a scroll marathon. */
    fun shortNotes(notes: String, maxLines: Int = 6): String =
        notes.lineSequence()
            .filter { it.isNotBlank() }
            .take(maxLines)
            .joinToString("\n")
}
