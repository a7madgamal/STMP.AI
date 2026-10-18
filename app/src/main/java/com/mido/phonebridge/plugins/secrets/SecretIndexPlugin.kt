package com.mido.phonebridge.plugins.secrets

import android.content.Context
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.core.Verb
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read a secret's NON-secret data over HTTP.
 *
 * Everything about a secret except the value: its name, where its API is documented,
 * where the key is issued, and which hosts it may reach. This exists because the caller
 * (a host, an agent, a human at a terminal) needs those facts to *use* a secret
 * correctly — and today the only way to learn them was to ask the person holding the
 * phone to read the screen aloud. That is the same dead end `GET /debug` was built to
 * close, one level down.
 *
 * It deliberately does NOT require an unlocked vault, because the data it returns is not
 * encrypted: names, links and hosts live in the plaintext index (a name is a policy
 * unit, a link is a pointer, a host is a rule — none is a credential). So this route
 * answers while the vault is shut, which is exactly when you need it: a call just failed
 * and you want the docs URL without a fingerprint.
 *
 * The VALUE is never returned here, under any circumstances, and there is no parameter
 * that could ask for it. Reading a secret value is the broker's job and it is gated;
 * this route is the boring half, kept boring on purpose.
 */
class SecretIndexPlugin(private val ctx: Context) : Plugin {

    override val id = "secrets"
    override val displayName = "Secrets (metadata)"

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/secrets",
            "List every secret's non-secret metadata (no values)",
            Verb.READ
        ) { list() },
        Route(
            "GET", "/secrets/{name}",
            "Read one secret's non-secret metadata (no value)",
            Verb.READ
        ) { one(it) }
    )

    override fun status(): PluginStatus {
        val n = try {
            SecretVault.names(ctx).size
        } catch (e: Exception) {
            return PluginStatus("Secrets", PluginStatus.State.ERROR, "index unreadable: ${e.message}")
        }
        return PluginStatus(
            "Secrets",
            if (n == 0) PluginStatus.State.NEEDS_ATTENTION else PluginStatus.State.OK,
            "$n secret(s) · GET /secrets"
        )
    }

    private fun list(): Resp {
        val arr = JSONArray()
        for (name in SecretVault.names(ctx)) arr.put(describe(name))
        return Resp(200, JSONObject().put("ok", true).put("secrets", arr))
    }

    private fun one(req: Req): Resp {
        val name = req.pathParams["name"]
            ?: throw PluginError("bad_request", "missing secret name")
        if (!SecretVault.has(ctx, name)) {
            throw PluginError("unknown_secret", "no secret named '$name'")
        }
        return Resp(200, JSONObject().put("ok", true).put("secret", describe(name)))
    }

    /**
     * The metadata object. Note what is NOT here: the value. Also note that `hosts` is
     * reported as-is (possibly empty) rather than omitted — "this secret can reach
     * nothing" is a fact the caller needs, and an absent field would read as "unknown".
     */
    private fun describe(name: String): JSONObject {
        val meta = SecretVault.meta(ctx, name)
        return JSONObject().apply {
            put("name", name)
            put("docs", meta.docs)
            put("key", meta.key)
            put("hosts", JSONArray(meta.hosts))
            put("callRoute", "POST /secrets/$name/call")
        }
    }
}
