package com.mido.phonebridge

/**
 * Whether the debug log is expanded on the Settings screen.
 *
 * **Why this is not a field on the fragment.** Tab switching uses `replace`, so the Settings
 * fragment is destroyed and rebuilt on every visit. A `private var logExpanded` therefore reset
 * to false the moment you looked at another tab and came back — the log you just opened closed
 * itself, which reads as the button not working.
 *
 * This is genuinely app-scoped state rather than screen-scoped: "I opened the log" is a statement
 * about the session, not about that one instance of the view. Nothing observes it and it holds no
 * context, so there is nothing to leak.
 *
 * Deliberately a plain mutable boolean rather than saved instance state: saved state would also
 * need the fragment to restore it before the first render, which is more machinery than a
 * developer-only debug toggle justifies.
 */
object DebugLogOpen {
    @Volatile
    var open: Boolean = false
}
