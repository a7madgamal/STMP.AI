package com.mido.phonebridge.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Provisions the AI model: fetches it once, verifies it, and puts it where
 * [ModelRegistry] expects it.
 *
 * **Why the app downloads it instead of shipping it.** A ~474 MB file in `assets/`
 * would be compressed into the APK, could not be memory-mapped by the runtime, and
 * would make the APK unshippable. It is also not a build artifact: the model is a
 * *provisioned resource*, like the vault — created on first use rather than embedded.
 *
 * **Why this is not optional.** Mido, 2026-10-19: the AI is core functionality, so a
 * missing model is a NOT-READY state, not a degraded one. Every AI route answers
 * `503 model_not_ready` until this succeeds, which is what makes the download part of
 * setup rather than a convenience.
 *
 * **Verified, not hoped for.** The bytes go to `<name>.part` and are only renamed onto
 * the real path once size and SHA-256 match the pinned values in [ModelRegistry]. A
 * truncated download therefore cannot be mistaken for a model — it stays a `.part` and
 * the bridge stays not-ready. Resume (HTTP `Range`) is supported because 474 MB over a
 * phone connection will be interrupted at least once.
 */
object ModelProvisioner {

    private const val TAG = "ModelProvisioner"

    /** One progress report. [total] is the expected size; it is never 0. */
    class Progress(val bytes: Long, val total: Long) {
        val percent: Int get() = if (total <= 0L) 0 else ((bytes * 100L) / total).toInt()
    }

    class Result(val ok: Boolean, val detail: String)

    /**
     * Live progress, for a UI that re-attaches mid-download. Null when nothing is
     * running. Kept here rather than in the Activity because the download outlives
     * any one screen — a rotation or a trip to another app must not lose it.
     */
    @Volatile
    var progress: Progress? = null
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** Why the last attempt failed, or "" — surfaced on the home screen. */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * Download the model unless it is already present and verified. Blocking; call off
     * the main thread. Returns when the file is in place and verified, or when it failed.
     *
     * **Idempotent unless [force].** A verified model is not re-fetched, because the home
     * screen's row is tappable and a tap on an already-ready row must not silently start a
     * 474 MB download (reported on device 2026-10-19: *"the model downloaded, I touched it,
     * it started downloading again"*). Re-downloading is still possible, but it has to be
     * asked for — the UI confirms first and passes `force = true`.
     */
    fun provision(
        ctx: Context,
        force: Boolean = false,
        onProgress: ((Progress) -> Unit)? = null
    ): Result {
        // Checked BEFORE the `running` flag, so a tap on a ready row while another
        // download is somehow in flight still answers "already present" rather than
        // "a download is already running" — the first is the true state of the world.
        if (!force) {
            val state = ModelRegistry.readiness(ctx)
            if (state.ready) {
                Log.i(TAG, "model already present; not re-fetching")
                return Result(true, "already present (${state.detail})")
            }
        }
        synchronized(this) {
            if (running) return Result(false, "a download is already running")
            running = true
        }
        lastError = ""
        return try {
            fetch(ctx, onProgress)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "provision failed: $lastError")
            Result(false, lastError)
        } finally {
            running = false
            progress = null
        }
    }

    private fun fetch(ctx: Context, onProgress: ((Progress) -> Unit)?): Result {
        val target = ModelRegistry.modelFile(ctx)
        val dir = target.parentFile ?: return Result(false, "model path has no parent directory")
        if (!dir.exists() && !dir.mkdirs()) {
            return Result(false, "cannot create ${dir.absolutePath}")
        }
        val part = File(dir, target.name + ".part")

        val already = if (part.exists()) part.length() else 0L
        val conn = (URL(ModelRegistry.MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            // Ask to resume only when there is something to resume from. A server that
            // ignores the header answers 200 with the whole file, which is handled below.
            if (already > 0L) setRequestProperty("Range", "bytes=$already-")
        }

        val code: Int
        val bodyLen: Long
        try {
            conn.connect()
            code = conn.responseCode
            val partial = code == HttpURLConnection.HTTP_PARTIAL && already > 0L
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                return Result(false, "server answered HTTP $code")
            }
            // A 200 to a ranged request means the server sent the WHOLE file: the partial
            // bytes are now a prefix we must not append to, so start clean.
            if (!partial && part.exists()) part.delete()
            bodyLen = conn.contentLengthLong
            val startAt = if (partial) already else 0L
            val total = if (bodyLen > 0L) startAt + bodyLen else ModelRegistry.EXPECTED_SIZE

            var written = startAt
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, partial).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250L) {
                            lastReport = now
                            val p = Progress(written, total)
                            progress = p
                            onProgress?.invoke(p)
                        }
                    }
                    out.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        val reason = verify(part, ModelRegistry.EXPECTED_SIZE, ModelRegistry.EXPECTED_SHA256)
        if (reason != null) {
            // Never leave a bad file where a good one belongs: the whole point of the
            // .part staging is that a failure cannot masquerade as a provisioned model.
            part.delete()
            lastError = reason
            Log.w(TAG, "verification failed: $reason")
            return Result(false, reason)
        }
        if (!part.renameTo(target)) {
            // A stale target blocks the rename on some filesystems. Drop it and retry
            // once, then fail loudly rather than leaving a half-state.
            target.delete()
            if (!part.renameTo(target)) {
                lastError = "cannot move ${part.name} into place"
                return Result(false, lastError)
            }
        }
        val mb = target.length() / 1024 / 1024
        Log.i(TAG, "model provisioned ($mb MB)")
        return Result(true, "model ready ($mb MB)")
    }

    /**
     * Verify a downloaded model. Returns null when it is exactly right, or a short
     * reason when it is not.
     *
     * Pure apart from reading [file] — no Android, no network — so a JVM test can prove
     * that a truncated or altered file is rejected. That matters more here than anywhere
     * else in the app: a model that passes on size alone would load and then fail
     * opaquely at inference time.
     */
    fun verify(file: File, expectedSize: Long, expectedSha256: String): String? {
        if (!file.exists()) return "file missing"
        val len = file.length()
        if (len != expectedSize) return "size $len != expected $expectedSize"
        val actual = sha256(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) return "sha256 mismatch"
        return null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder(64)
        for (b in md.digest()) sb.append("%02x".format(b))
        return sb.toString()
    }
}
