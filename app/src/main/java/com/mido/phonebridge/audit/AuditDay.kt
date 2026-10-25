package com.mido.phonebridge.audit

/** One day-file's size, and how many usable lines it holds (null when unreadable). */
data class AuditDay(
    val date: String,
    val bytes: Long,
    val lines: Int?,
)
