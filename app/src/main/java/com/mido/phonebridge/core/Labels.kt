package com.mido.phonebridge.core

/**
 * What kind of act a route performs.
 *
 * REQUIRED on every [Route], not defaulted: a route nobody classified must not
 * exist, so making it a construction-time requirement turns "I forgot to label
 * it" into a compile error. Same trick that made [Route.describe] mandatory.
 *
 * The five values are exhaustive on purpose. A route that does not fit one of
 * them is a design problem to fix, not a sixth bucket to add: rules key on
 * `(plugin, verb)` and a new value silently changes what every stored rule means
 * for that plugin.
 */
enum class Verb { READ, WRITE, EDIT, DELETE, EXECUTE }

/**
 * Where a request came from. Recorded on every audit record and NEVER used to
 * gate anything (R1/R2/R3): it is an IP-derived label, not an authenticated
 * principal, so a rule keyed on it would encode a guess.
 */
enum class Origin { OPENCLAW, LOCAL, TAILNET, UNKNOWN }

/** This build box's tailnet address. */
const val OPENCLAW_IP = "100.64.0.10"

/**
 * Pure, total mapping from a remote address to an origin label.
 *
 * Never throws and never guesses: anything unrecognised is [Origin.UNKNOWN].
 * Deliberately blunt — a false UNKNOWN costs a filter, a false OPENCLAW would
 * mislabel a stranger as the trusted caller.
 */
fun originOf(remote: String): Origin = when {
    remote == OPENCLAW_IP -> Origin.OPENCLAW
    remote == "127.0.0.1" || remote == "::1" || remote == "localhost" -> Origin.LOCAL
    remote.startsWith("100.") -> {
        // Tailscale CGNAT range is 100.64.0.0/10 => second octet 64..127.
        val second = remote.split(".").getOrNull(1)?.toIntOrNull()
        if (second != null && second in 64..127) Origin.TAILNET else Origin.UNKNOWN
    }
    else -> Origin.UNKNOWN
}

/** Human-recognisable name for the caller, used by the modal and the log. */
fun requesterLabelFor(remote: String): String =
    if (remote == OPENCLAW_IP) "OpenClaw (this box)" else remote

/** The audit label for a request: `plugin.verb`, e.g. `gmail.write`. */
fun labelOf(pluginId: String, verb: Verb): String = "$pluginId.${verb.name.lowercase()}"
