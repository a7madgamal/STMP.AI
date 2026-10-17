package com.mido.phonebridge.policy

/**
 * The one switch on the home screen. Two values in R2.
 *
 * `NOTIFY` and `AUTOMATIC` are deliberately absent until R3, in the release that
 * makes them behave. A switch position that does something else than it says is
 * worse than a missing one — it teaches the wrong model and hides behind a value
 * nobody tests.
 */
enum class GlobalMode { SECURE, OFF }

/**
 * The mode a request is actually gated with. Distinct from [GlobalMode] because
 * R3's `AUTOMATIC` is a switch value that is not a mode: it resolves to one.
 */
enum class Mode { SECURE, OFF }

/**
 * Total, exhaustive, no default branch.
 *
 * Adding `AUTOMATIC`/`NOTIFY` in R3 becomes a compile error right here, which is
 * the point: the compiler asks where the new value goes instead of an `else`
 * quietly absorbing it and defaulting a security decision.
 */
fun resolveMode(global: GlobalMode): Mode = when (global) {
    GlobalMode.SECURE -> Mode.SECURE
    GlobalMode.OFF -> Mode.OFF
}
