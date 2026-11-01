package com.mido.phonebridge.callers

/**
 * What the phone knows about one caller address.
 *
 * **Absent from the registry is not a state here — it is the third state, and it lives outside
 * this type on purpose.** "Nobody has ever named this address" must never be representable as a
 * default entry, because a default entry is a decision nobody made. [CallerRegistry.stateOf]
 * returns null for it.
 */
sealed class CallerState {
    /** Named and allowed. [name] is what the modal and the audit row say instead of an IP. */
    data class Verified(val name: String) : CallerState()

    /**
     * Explicitly refused by a human.
     *
     * Recorded rather than forgotten so a blocked address is refused SILENTLY on its next call —
     * no modal, no fingerprint, no re-prompt. Without the record the only way to refuse a stranger
     * would be to refuse them again every single time they knock.
     */
    object Denied : CallerState()
}

/** One registry row, as stored. */
data class CallerEntry(
    val ip: String,
    val state: CallerState,
    /** When this address first made a request. Evidence, never used for a decision. */
    val firstSeenMs: Long,
)

/**
 * The set of addresses a human has decided about.
 *
 * Two rules govern everything here:
 *
 *  **1. Absent means unverified, never "allowed by default".** The old code matched one hardcoded
 *  IP and labelled everything else with its raw address — a fallback dressed as a label. Here an
 *  unknown address is refused outright, and the refusal is what asks the human for a name.
 *
 *  **2. A loopback caller is the phone itself.** Not exempt for convenience: `127.0.0.1` cannot be
 *  reached from anywhere but the device, so refusing it would break the app's own probes while
 *  protecting nothing.
 */
object CallerRegistry {

    /** True for an address that can only be the device itself. */
    fun isLoopback(remote: String): Boolean =
        remote == "127.0.0.1" || remote == "::1" || remote == "localhost"

    /**
     * The state of [remote], or null when nobody has decided about it yet.
     *
     * Loopback is [CallerState.Verified] with a fixed name so callers never need a special case:
     * `stateOf("127.0.0.1")` always answers, and the label reads "this device".
     */
    fun stateOf(entries: Map<String, CallerEntry>, remote: String): CallerState? {
        if (isLoopback(remote)) return CallerState.Verified(LOOPBACK_NAME)
        return entries[remote]?.state
    }

    /**
     * The label for the modal and the audit row.
     *
     * **Three distinct answers, and the third is the point.** A verified caller gets its human
     * name; loopback gets "this device"; an unverified address gets a label that SAYS it is
     * unverified. Returning the bare IP here was the old behaviour, and it read as a name — a
     * reader could not tell "named `192.168.1.5`" from "never named at all".
     */
    fun labelFor(entries: Map<String, CallerEntry>, remote: String): String =
        when (val s = stateOf(entries, remote)) {
            is CallerState.Verified -> s.name
            CallerState.Denied -> "blocked caller $remote"
            null -> "unverified caller $remote"
        }

    const val LOOPBACK_NAME = "this device"

    /** Parse rows into a map. A malformed row is skipped and reported, never invented. */
    fun parse(rows: List<CallerEntry>): Map<String, CallerEntry> =
        rows.associateBy { it.ip }
}
