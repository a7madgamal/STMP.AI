package com.mido.phonebridge.plugins.ssh

/**
 * A byte buffer that keeps the LAST [cap] bytes written, not the first.
 *
 * **Why the tail and not the head.** A command that overflows the capture budget is almost always
 * a log or a build: the useful part is the end — the error, the summary, the exit line. Keeping
 * the head answered "here are the first 64 KiB of a 400 KiB build" and dropped exactly the bytes
 * the caller asked for. The head was never wrong so much as useless.
 *
 * **Why a ring instead of a growing buffer with a trim.** The old code stopped reading once the
 * cap was hit, which left the command running with nobody draining its pipe — it would block on a
 * full pipe until the deadline and then be killed, turning "too much output" into a timeout. Here
 * the reader always drains, so a chatty command finishes normally and simply reports that its
 * output was cut.
 *
 * Pure and free of Android types, so the arithmetic is covered by unit tests rather than by
 * trusting a loop that only runs on a device.
 */
class SshTail(private val cap: Int) {

    init {
        require(cap > 0) { "cap must be positive, got $cap" }
    }

    private val buf = ByteArray(cap)

    /** Total bytes ever offered, including those already dropped. */
    private var total = 0L

    /** Bytes currently held; never exceeds [cap]. */
    private var held = 0

    /** Next write position in the ring. */
    private var pos = 0

    /** True when bytes were dropped, i.e. [text] is a suffix of what the command wrote. */
    val truncated: Boolean get() = total > held

    @Synchronized
    fun write(source: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        total += len

        // A single write larger than the whole ring: only its final `cap` bytes can survive, so
        // skip the rest rather than copying bytes we are about to overwrite.
        if (len >= cap) {
            System.arraycopy(source, off + len - cap, buf, 0, cap)
            pos = 0
            held = cap
            return
        }

        val first = minOf(len, cap - pos)
        System.arraycopy(source, off, buf, pos, first)
        val rest = len - first
        if (rest > 0) {
            // Wrapped: the tail of the write goes to the front of the ring.
            System.arraycopy(source, off + first, buf, 0, rest)
        }
        pos = (pos + len) % cap
        held = minOf(cap, held + len)
    }

    fun write(source: ByteArray) = write(source, 0, source.size)

    /** The retained bytes, oldest first. A suffix of everything written once [truncated]. */
    @Synchronized
    fun bytes(): ByteArray {
        if (held < cap) {
            // Not yet wrapped: the data is a plain prefix of the ring.
            return buf.copyOfRange(0, held)
        }
        val out = ByteArray(cap)
        System.arraycopy(buf, pos, out, 0, cap - pos)
        System.arraycopy(buf, 0, out, cap - pos, pos)
        return out
    }

    fun text(): String = String(bytes(), Charsets.UTF_8)

    /** Bytes written in total, before any truncation. */
    @Synchronized
    fun totalBytes(): Long = total
}

/**
 * An [java.io.OutputStream] that keeps only the tail, for JSch's stderr sink.
 *
 * JSch writes stderr through this stream as the channel produces it, and the old code handed it a
 * plain `ByteArrayOutputStream` with no cap at all — so stdout was bounded at 64 KiB while stderr
 * was unbounded. A command in a retry loop (`ssh` failing repeatedly, a `curl` that keeps warning)
 * could grow it until the app was killed. The asymmetry was an oversight, not a decision: both
 * streams are attacker-adjacent text and both need a ceiling.
 */
class TailOutputStream(private val cap: Int) : java.io.OutputStream() {

    private val tail = SshTail(cap)

    override fun write(b: Int) = tail.write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) = tail.write(b, off, len)

    fun text(): String = tail.text()

    fun truncated(): Boolean = tail.truncated

    fun totalBytes(): Long = tail.totalBytes()
}
