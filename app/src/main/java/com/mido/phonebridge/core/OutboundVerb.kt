package com.mido.phonebridge.core

/**
 * The verb an OUTBOUND HTTP method carries, for brokered third-party calls.
 *
 * **Why this exists.** The broker is one route — `POST /secrets/{name}/call` — carrying every
 * third-party call. A static `EXECUTE` verb meant policy, the audit label, the exception button
 * and the AI facts all keyed on `todoist/EXECUTE`, so "reads only" was **inexpressible**: an
 * exception tapped while reading a task list also silenced task deletion, because the pair had
 * no read in it. Mido, 2026-10-19: *"I clicked meaning it as a read only operation."*
 *
 * **The mapping is by EFFECT, and errs loud.** Only methods that provably change nothing are
 * READ. Anything unrecognised is a WRITE, because the one direction that can hurt is guessing
 * quiet: a mis-classified destructive call would then be approvable by silence on a NOTIFY
 * card. A POST-based search API (GraphQL, some vendors) therefore reads as WRITE — visible and
 * interruptive, which is the safe way to be wrong.
 *
 * Pure and Context-free so it is unit-testable — the same split as `isAttended`, `PayloadShape`
 * and `PromptBudget`. This decides a policy key, so it must not be the untested part.
 */
object OutboundVerb {

    /**
     * `GET`/`HEAD`/`OPTIONS` are reads; `DELETE` is a delete; everything else is a write.
     *
     * OPTIONS is included because it is a capability probe — no body, no state change. TRACE
     * deliberately is NOT: it is rarely used, and treating an unknown-to-us method as quiet is
     * the mistake this object exists to avoid.
     */
    fun of(rawMethod: String): Verb =
        when (rawMethod.trim().uppercase()) {
            "GET", "HEAD", "OPTIONS" -> Verb.READ
            "DELETE" -> Verb.DELETE
            else -> Verb.WRITE
        }
}
