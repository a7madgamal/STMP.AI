package com.mido.phonebridge.ui

import com.mido.phonebridge.audit.AuditHeader
import com.mido.phonebridge.core.Updater
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.ui.kit.Led

/** Pure copy and selection for Home. Holds no View, so a test can assert every string. */
object HomeCopy {

    /** Newest-first non-noise headers with a parseable verb, at most [n]. */
    fun recent(headers: List<AuditHeader>, n: Int): List<Pair<AuditHeader, Verb>> =
        headers.asSequence()
            .filter { !it.noise }
            .sortedByDescending { it.ts }
            .mapNotNull { h -> Verb.entries.firstOrNull { it.name == h.verb }?.let { h to it } }
            .take(n)
            .toList()

    enum class UpdateTone { NEUTRAL, OK, CAUTION }

    data class UpdateLine(val text: String, val led: Led.State, val tone: UpdateTone)

    /** The Updates status row for the current checker state (first matching row wins). */
    fun updateLine(checking: Boolean, outcome: Updater.Outcome?, versionName: String): UpdateLine = when {
        checking -> UpdateLine("Checking…", Led.State.PENDING, UpdateTone.NEUTRAL)
        outcome is Updater.Outcome.Available && Updater.installing -> UpdateLine(
            "Installing v${outcome.version}… the app restarts when it finishes",
            Led.State.PENDING, UpdateTone.NEUTRAL,
        )
        outcome is Updater.Outcome.Available -> UpdateLine(
            "v${outcome.version} is ready to install" + (if (outcome.isBeta) " · beta" else ""),
            Led.State.OK, UpdateTone.OK,
        )
        outcome is Updater.Outcome.UpToDate ->
            UpdateLine("Up to date — v$versionName", Led.State.OK, UpdateTone.NEUTRAL)
        outcome is Updater.Outcome.Unavailable ->
            UpdateLine("Can't check: ${outcome.reason}", Led.State.CAUTION, UpdateTone.CAUTION)
        else -> UpdateLine("Not checked yet", Led.State.OFF, UpdateTone.NEUTRAL)
    }
}
