package com.mido.phonebridge.audit

/**
 * The audit store: append a record, read the list back, look one up by id.
 *
 * An interface, not a class, so the diagnostics report and the UI depend on the
 * capability rather than on the file-backed implementation. [AuditLog] is the only
 * implementation today.
 */
interface AuditStore {
    /** Append one record. Throws on failure — an unrecorded request must not look recorded. */
    fun append(record: AuditRecord)

    /** Recent records, newest first. */
    fun headers(): List<AuditHeader>

    /** The exact stored JSON line for [id], or null when it is gone. */
    fun line(id: String): String?

    /** How many lines could not be parsed or read. Never silently zero-by-default. */
    fun unreadableCount(): Int

    /**
     * Delete every stored record. Returns how many day files actually left the disk.
     *
     * The count is the evidence: a caller that sees 0 cannot assume the log was empty,
     * because it may also mean nothing could be deleted.
     */
    fun clear(): Int

    /** What is on disk right now. */
    fun storageStats(): AuditStorage
}
