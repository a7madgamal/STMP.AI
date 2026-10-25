package com.mido.phonebridge.audit

import kotlin.text.Charsets

/**
 * Byte-caps a string that is about to be written to the log.
 *
 * The cap is on BYTES, not characters, because the file is the thing that has to
 * stay bounded. Cutting at a byte offset can land inside a multi-byte UTF-8
 * sequence, which produces a replacement character on read and — worse — makes the
 * line look corrupted. So the cut walks back to the start of the sequence.
 *
 * [originalBytes] is reported so the record can say "this was truncated" instead of
 * silently presenting a short body as the whole thing. A body that looks complete
 * but is not is the failure mode this exists to prevent.
 */
object AuditBlobs {

    const val FIELD_CAP: Int = 32 * 1024

    fun cap(s: String): Capped {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= FIELD_CAP) return Capped(s, 0)

        // Walk back to a leading byte: 0b10xxxxxx is a continuation byte, so a cut
        // just before one is a valid boundary.
        var end = FIELD_CAP
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return Capped(String(bytes, 0, end, Charsets.UTF_8), bytes.size)
    }
}
