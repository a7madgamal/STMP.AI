package com.mido.phonebridge.plugins.debug

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.BuildConfig
import com.mido.phonebridge.Debug
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.core.*
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Self-diagnosis over HTTP, so a caller can find out why something is failing
 * without a human reading the screen aloud.
 *
 * This exists because of a real dead end: the log recorded `invalid JSON body`
 * with no copy of the body, and nothing exposed the app's own state, so every
 * question about the phone cost a round trip through chat. `GET /debug` answers
 * the mechanical ones in one call.
 *
 * EVERYTHING here is diagnostic, so none of it may leak a credential. It reports
 * SHAPE (lengths, booleans, error strings), never token or secret values — see the
 * redaction rule in [tokenSummary]. Keep it that way when adding fields.
 */
class DebugPlugin(private val ctx: Context) : Plugin {

    override val id = "debug"
    override val displayName = "Debug"

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/debug", "Read the phone's diagnostic report", Verb.READ,
            // The report contains the recent log tail. Storing that response would
            // copy the log into the log on every call, so this route opts out — the
            // record keeps outcome/status/summary, and says the body was not kept.
            auditResponseBody = false
        ) { report(it) }
    )

    override fun status(): PluginStatus = PluginStatus(
        "Debug",
        PluginStatus.State.OK,
        "GET /debug"
    )

    private fun report(req: Req): Resp {
        val o = JSONObject()
        o.put("app", appInfo())
        o.put("server", serverInfo())
        o.put("storage", storageInfo())
        o.put("account", accountInfo())
        o.put("policy", policyInfo())
        o.put("audit", auditInfo())
        o.put("accessLog", accessLog())
        o.put("logTail", logTail())
        o.put("routes", routesInfo())
        return Resp.ok(o)
    }

    private fun appInfo(): JSONObject = JSONObject()
        .put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("packageName", ctx.packageName)
        .put("debugBuild", BuildConfig.DEBUG)
        .put("firstInstallTime", packageInfo().firstInstallTime)
        .put("lastUpdateTime", packageInfo().lastUpdateTime)

    private fun packageInfo() = ctx.packageManager.getPackageInfo(ctx.packageName, 0)

    private fun serverInfo(): JSONObject = JSONObject()
        .put("up", BridgeService.serverUp)
        .put("port", BridgeService.PORT)
        .put("bindAddress", BridgeService.bindAddress)
        .put("lastError", BridgeService.lastError)
        .put("notificationListener", BridgeService.listenerRef != null)
        .put("uptimeMs", System.currentTimeMillis() - processStartMs)

    private fun storageInfo(): JSONObject {
        val o = JSONObject()
        // The real constraint on this app: Q1 says a full disk denies every request.
        // So the free bytes matter more than the total.
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            o.put("dataFreeBytes", stat.availableBytes)
            o.put("dataTotalBytes", stat.totalBytes)
        } catch (e: Exception) {
            o.put("dataError", e.message ?: "stat failed")
        }
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            o.put("memoryFreeBytes", mi.availMem)
            o.put("memoryTotalBytes", mi.totalMem)
            o.put("lowMemory", mi.lowMemory)
        } catch (e: Exception) {
            o.put("memoryError", e.message ?: "stat failed")
        }
        return o
    }

    /**
     * Shape only. A caller needs "is this configured, is it still valid" — never
     * the value. Lengths are enough to tell "empty" from "set" from "truncated".
     */
    private fun accountInfo(): JSONObject = JSONObject()
        .put("loggedIn", AuthManager.isLoggedIn(ctx))
        .put("email", AuthManager.accountEmail ?: JSONObject.NULL)
        .put("clientIdConfigured", AuthManager.clientId.isNotBlank())
        .put("accessTokenCachedMs", AuthManager.tokenRemainingMs())

    /**
     * The switch and its file. An unreadable policy is a real, reportable state —
     * it is the one condition that stops the server, so it must be visible here
     * rather than inferred from a silent absence of traffic.
     */
    private fun policyInfo(): JSONObject = try {
        JSONObject()
            .put("global", PolicyStore.from(ctx).get().global.name)
            .put("readable", true)
    } catch (e: PolicyException) {
        JSONObject()
            .put("readable", false)
            .put("error", e.message ?: "unreadable")
    } catch (e: Exception) {
        JSONObject().put("readable", false).put("error", e.message ?: "unreadable")
    }

    private fun auditInfo(): JSONObject {
        val o = JSONObject()
        o.put("degraded", AuditHealth.reason ?: JSONObject.NULL)
        try {
            val store = AuditLog.from(ctx)
            val stats = store.storageStats()
            o.put("totalBytes", stats.totalBytes)
            o.put("maxBytes", stats.retention.maxTotalBytes)
            o.put("maxAgeDays", stats.retention.maxAgeDays)
            o.put("unreadableLines", store.unreadableCount())
            val days = JSONArray()
            for (d in stats.days) {
                days.put(
                    JSONObject()
                        .put("date", d.date)
                        .put("bytes", d.bytes)
                        .put("lines", d.lines ?: JSONObject.NULL)
                )
            }
            o.put("days", days)
        } catch (e: Exception) {
            o.put("error", e.message ?: "audit store unavailable")
        }
        return o
    }

    /** Recent requests, oldest first, so a caller can read the timeline. */
    private fun accessLog(): JSONArray {
        val out = JSONArray()
        try {
            val headers = AuditLog.from(ctx).headers().take(LOG_LIMIT).reversed()
            for (h in headers) {
                out.put(
                    JSONObject()
                        .put("ts", h.ts)
                        .put("id", h.id)
                        .put("outcome", h.outcome.name)
                        .put("label", h.label)
                        .put("plugin", h.plugin)
                        .put("verb", h.verb)
                        .put("status", h.responseStatus)
                        .put("durationMs", h.durationMs)
                        .put("noise", h.noise)
                        .put("summary", h.summary)
                )
            }
        } catch (e: Exception) {
            // Reported as an empty log with the reason on the caller's next question,
            // not as a crashed report: the rest of the report is still useful.
            out.put(JSONObject().put("error", e.message ?: "audit store unavailable"))
        }
        return out
    }

    /** The in-memory debug ring, already redacted at write time by [Debug.log]. */
    private fun logTail(): String = Debug.dump()

    private fun routesInfo(): JSONArray {
        val out = JSONArray()
        for (p in PluginRegistry.all()) {
            for (r in p.routes()) {
                out.put(
                    JSONObject()
                        .put("method", r.method)
                        .put("path", r.path)
                        .put("verb", r.verb.name)
                        .put("plugin", p.id)
                        // A route that does not store its response has an empty
                        // responseBody in the log; say so here so an empty body is not
                        // read as a broken handler.
                        .put("storesResponseBody", r.auditResponseBody)
                )
            }
        }
        return out
    }

    private companion object {
        const val LOG_LIMIT = 50
        val processStartMs = System.currentTimeMillis()
    }
}
