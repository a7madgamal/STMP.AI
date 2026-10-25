package com.mido.phonebridge.audit

/**
 * Why a request got the mode it got.
 *
 * Deliberately its own enum rather than reusing the policy package's `ModeSource`:
 * the audit already depends on `policy` (see [PolicyAudit]), so a shared enum would
 * make that dependency circular. `BridgeService` maps one to the other at the single
 * place that writes a record.
 */
enum class DecisionSource {
    /** No policy applied; the gate ran because it always runs. */
    DEFAULT,

    /** The global switch decided. */
    GLOBAL,

    /** An exact `plugin × verb` rule decided. */
    RULE,
}
