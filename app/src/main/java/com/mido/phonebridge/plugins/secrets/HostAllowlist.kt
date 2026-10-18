package com.mido.phonebridge.plugins.secrets

import com.mido.phonebridge.core.PluginError

/**
 * Host matching for the secret broker (plan D3 / R2).
 *
 * This used to be a *global* allowlist stored in prefs. It is not any more: the allowed
 * hosts belong to the SECRET, not to the app. A global list meant the `todoist` secret
 * could be sent to any host some other secret had approved — the domains were shared, so
 * the blast radius of one careless rule was every secret. Each secret now carries its
 * own list (in the vault's plaintext index), and this object is only the matching rule.
 *
 * What is left here is deliberately pure: no Context, no storage, no I/O. The one piece
 * of logic that decides whether a secret may leave the device is therefore a function
 * you can read, reason about and test in isolation — which is the point.
 */
object HostAllowlist {

    /**
     * Does [host] satisfy any rule in [rules]?
     *
     * Matching is deliberately strict: an exact host, or a boundary-correct `*.domain`.
     * A naive `endsWith` would let `evil-api.todoist.com` satisfy `api.todoist.com` —
     * and, worse, `evil-todoist.com` satisfy `*.todoist.com`.
     */
    fun allows(rules: List<String>, host: String): Boolean {
        val h = host.lowercase()
        return rules.any { rule ->
            val r = rule.lowercase()
            when {
                r.startsWith("*.") -> {
                    val base = r.substring(2)
                    // boundary-correct: "api.todoist.com" matches "*.todoist.com",
                    // but "evil-todoist.com" and "todoist.com" do not.
                    h.endsWith(".$base") && h.length > base.length + 1
                }
                else -> h == r
            }
        }
    }

    /**
     * Validate a rule before it is stored, and return it normalised.
     *
     * Throws rather than storing something that could never match — or that would match
     * far more than intended. Only two shapes are legal: a bare hostname, or `*.` plus a
     * bare hostname. No scheme, no port, no path: those would look like they narrow the
     * rule while the matcher ignores them, which is worse than refusing them.
     */
    fun requireRule(rule: String): String {
        val r = rule.lowercase().trim()
        if (r.isEmpty()) throw PluginError("bad_request", "host must not be empty")
        if (r.contains('/') || r.contains(':') || r.contains(' ')) {
            throw PluginError("bad_request", "host must be a bare hostname, got '$rule'")
        }
        val base = if (r.startsWith("*.")) r.substring(2) else r
        if (base.isEmpty() || base.startsWith('.') || base.endsWith('.')) {
            throw PluginError("bad_request", "host must be a bare hostname, got '$rule'")
        }
        return r
    }
}
