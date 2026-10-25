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

    /** Delete everything. */
    fun clear()

    /** What is on disk right now. */
    fun storageStats(): AuditStorage
}
