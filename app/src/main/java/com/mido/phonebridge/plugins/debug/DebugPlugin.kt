package com.mido.phonebridge.plugins.debug

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.mido.phonebridge.ApprovalGate
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
import com.mido.phonebridge.policy.Rule
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
            auditResponseBody = false,
            // No AI: this route EXISTS to explain a broken bridge, so it must not depend
            // on the model — the thing most likely to be broken. It also needs no
            // decision, so there is nothing for a summary to clarify. Declaring it here
            // is what exempts it from the model-readiness gate (Mido, 2026-10-19:
            // "we need to be able to disable AI summary for some endpoints").
            aiSummary = false
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
        o.put("memory", memoryInfo())
        o.put("account", accountInfo())
        o.put("policy", policyInfo())
        o.put("permissions", permissionsInfo())
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

    /**
     * What THIS PROCESS is holding, in bytes.
     *
     * **`storage.memoryFreeBytes` was never the answer to "how much is the app using".** It
     * reports the device's free RAM, which only tells you the phone has less left — never what
     * took it. So every question about this app's footprint needed a calculation from the model
     * constants instead of a reading, and the calculation was wrong for weeks: the KV cache sat
     * at the 8192 window through a model revert because nothing on the device ever contradicted
     * it. Mido, 2026-10-20: *"check memory usage"* — unanswerable, twice.
     *
     * Now it is a reading. `totalPss` is the honest single number (all shared pages counted
     * once); Java and native are split out because a ~896 MiB KV cache is NATIVE and would be
     * invisible in a heap-only view — precisely the mistake that hid this bug.
     */
    private fun memoryInfo(): JSONObject {
        val o = JSONObject()
        // The service's own process. `myPid` is the bridge process because /debug is served
        // from it — there is no second process to confuse it with.
        val pid = android.os.Process.myPid()
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = am.getProcessMemoryInfo(intArrayOf(pid))
            val mi = infos.firstOrNull()
            if (mi == null) {
                // Fail loudly rather than reporting a zero that reads as "no memory used".
                o.put("error", "getProcessMemoryInfo returned no entry")
            } else {
                o.put("totalPssBytes", mi.totalPss * 1024L)
                o.put("javaHeapBytes", mi.dalvikPss * 1024L)
                o.put("nativeHeapBytes", mi.nativePss * 1024L)
                o.put("otherPssBytes", mi.otherPss * 1024L)
            }
        } catch (e: Exception) {
            o.put("error", e.message ?: e.javaClass.simpleName)
        }
        // Runtime heap: cheap, and answers "is the collector thrashing" separately from PSS.
        val rt = Runtime.getRuntime()
        o.put("javaUsedBytes", rt.totalMemory() - rt.freeMemory())
        o.put("javaCommittedBytes", rt.totalMemory())
        o.put("javaMaxBytes", rt.maxMemory())
        // The configured context window, so the size of the KV cache can be checked against the
        // build actually running rather than against what the source says it should be.
        o.put("aiContextTokens", com.mido.phonebridge.ai.ModelRegistry.contextTokens())
        o.put("aiModelResident", com.mido.phonebridge.ai.ModelRegistry.isEngineLoaded())
        return o
    }

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

    private fun permissionsInfo(): JSONObject = JSONObject()
        // `USE_FULL_SCREEN_INTENT` is a SPECIAL APP ACCESS on Android 14+, not a runtime
        // permission: its default is re-derived whenever the package is replaced, so an
        // update can silently revoke a manual grant. Reported here because the gate has a
        // SECOND delivery path (`ctx.startActivity`, legal via draw-over-apps), so whether
        // this is granted should never be guessed — read it.
        .put("fullScreenIntent", canUseFullScreenIntentSafe())
        .put("drawOverApps", android.provider.Settings.canDrawOverlays(ctx))
        .put("notifications", hasNotifPermission())

    /**
     * The FSI app-op, without the API-34 guard leaking into here.
     *
     * Below 34 there is no such permission and the answer is "not applicable" — reported as
     * JSON null rather than `false`, because "not required" and "revoked" are different
     * facts and a reader must not confuse them.
     */
    private fun canUseFullScreenIntentSafe(): Any {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return JSONObject.NULL
        }
        return try {
            ApprovalGate.canUseFullScreenIntent(ctx)
        } catch (e: Exception) {
            JSONObject.NULL
        }
    }

    private fun hasNotifPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * The switch, its file, and the RULES — read-only.
     *
     * Rules are reported because without them the gate cannot be inspected from outside: a
     * caller could see `global: AUTOMATIC` and still have no idea whether a given
     * `plugin × verb` will open a modal or a 5-second card. That made every rule question a
     * round trip through the human reading the phone screen, which is exactly what this
     * endpoint exists to avoid.
     *
     * **Read-only by construction: there is no write route, and no parameter here can change
     * anything.** Policy is written in two places only — the EXCEPTIONS screen and the
     * modal's exception button — both of which audit the change. A diagnostic surface that
     * could mutate the gate would be a security hole, not a feature (Mido, 2026-10-19: *"add
     * policy rules to logs but read only"*).
     *
     * The `verb` field carries the literal `"ANY"` for an any-verb rule, matching the on-disk
     * format and `Rule.key` — so a reader never has to interpret a null.
     */
    private fun policyInfo(): JSONObject = try {
        val p = PolicyStore.from(ctx).get()
        JSONObject()
            .put("global", p.global.name)
            .put("readable", true)
            .put("ruleCount", p.rules.size)
            .put(
                "rules",
                JSONArray().apply {
                    for (r in p.rules) {
                        put(
                            JSONObject()
                                .put("plugin", r.plugin)
                                .put("verb", r.verb?.name ?: Rule.ANY)
                                .put("mode", r.mode.name)
                                // `plugin/VERB` — the exact key a record's `rule` field
                                // carries, so a log line and this report can be compared
                                // without reconstructing the string by hand.
                                .put("key", r.key)
                        )
                    }
                }
            )
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
