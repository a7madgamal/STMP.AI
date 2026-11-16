package com.mido.phonebridge.core

import com.mido.phonebridge.policy.Mode

/**
 * Raising a resolved mode to a route's floor, and nothing else.
 *
 * **A floor can only make a request LOUDER.** No branch here returns a quieter mode than the one
 * passed in, and that is the property that makes it safe to apply *after* policy has already
 * decided: a rule or a global says how a pair is usually asked about, and a route can insist on
 * being asked properly on top of that. Neither can undercut the other.
 *
 * Pure and total, with no `else` — a third [Mode] becomes a compile error here rather than a
 * silent default inside a security decision.
 */
object GateFloor {

    /** [resolved] raised to [floor] when [floor] is stricter. A null floor changes nothing. */
    fun raise(resolved: Mode, floor: Mode?): Mode {
        if (floor == null) return resolved
        return when (floor) {
            // PARANOID is the strictest mode that exists: every request needs a stamp.
            Mode.PARANOID -> Mode.PARANOID
            // A DONT_DISTURB floor is already satisfied by either mode, because both ask a human.
            // Returning the resolved value rather than the floor is deliberate: it keeps the
            // "only louder" property checkable by reading this function.
            Mode.DONT_DISTURB -> resolved
        }
    }
}
