package com.mido.phonebridge.plugins.secrets

import android.content.Context
import com.mido.phonebridge.Debug
import com.mido.phonebridge.core.PluginError
import org.json.JSONArray

/**
 * The saved host allowlist (plan D3 / R2).
 *
 * The security boundary is the DOMAIN: a request may only reach a host that has
 * already been approved, and approving a new host is a one-time decision that is
 * remembered. Stored as plain JSON in prefs — a host name is not a secret, and the
 * allowlist has to be readable while the vault is locked so the broker can answer
 * "this host is not allowed" without touching any secret.
 *
 * Matching is deliberately strict: exact host, or a boundary-correct `*.domain`.
 * A naive `endsWith` would let `evil-api.todoist.com` satisfy `api.todoist.com`.
 */
object HostAllowlist {

    private const val PREFS = "phonebridge_secrets"
    private const val F_HOSTS = "allowed_hosts"

    fun all(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(F_HOSTS, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            throw PluginError("allowlist_corrupt", "host allowlist unreadable: ${e.message}")
        }
    }

    fun isAllowed(ctx: Context, host: String): Boolean {
        val h = host.lowercase()
        return all(ctx).any { rule ->
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

    @Synchronized
    fun allow(ctx: Context, host: String) {
        val h = host.lowercase().trim()
        if (h.isEmpty() || h.contains('/') || h.contains(':')) {
            throw PluginError("bad_request", "host must be a bare hostname, got '$host'")
        }
        val next = (all(ctx) + h).distinct()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(F_HOSTS, JSONArray(next).toString())
            .apply()
        Debug.log("allowlist: added host '$h'")
    }

    @Synchronized
    fun revoke(ctx: Context, host: String) {
        val next = all(ctx).filterNot { it.equals(host, ignoreCase = true) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(F_HOSTS, JSONArray(next).toString())
            .apply()
        Debug.log("allowlist: revoked host '$host'")
    }
}
