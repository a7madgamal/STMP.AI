package com.mido.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mido.phonebridge.audit.AuditClassify
import com.mido.phonebridge.audit.AuditDraft
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditStore
import com.mido.phonebridge.audit.Decision
import com.mido.phonebridge.audit.DecisionSource
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.audit.recordResponse
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.core.labelOf
import com.mido.phonebridge.notify.NotifyGate
import com.mido.phonebridge.plugins.calendar.CalendarPlugin
import com.mido.phonebridge.plugins.gmail.GmailPlugin
import com.mido.phonebridge.plugins.notifications.NotificationsPlugin
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.ModeResolution
import com.mido.phonebridge.policy.ModeSource
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.policy.exceptionModeFor
import com.mido.phonebridge.policy.isAttended
import com.mido.phonebridge.policy.resolve
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Foreground service that owns the HTTP server. Every request is gated through
 * ApprovalGate before any plugin handler runs — a plugin cannot opt out.
 */
class BridgeService : Service() {

    companion object {
        const val PORT = 8787
        const val CHANNEL_ID = "bridge_service"
        const val NOTIF_ID = 4242

        /**
         * Android-type OAuth client. Authenticates by package name + signing
         * SHA-1: no embedded secret, no redirect URI, no loopback listener.
         * Login uses Credential Manager + PKCE authorization-code flow.
         */
        const val GOOGLE_CLIENT_ID = "503841343554-opgr4ibts6scrl0hauaruhmf1r8g1j3o.apps.googleusercontent.com"

        /**
         * Web-type client id, passed as `serverClientId` so Play Services
         * returns a one-time server auth code we exchange for a refresh token.
         * The Web client's SECRET is never embedded.
         */
        const val GOOGLE_SERVER_CLIENT_ID = "503841343554-hpcv5g12s7lk3mbgbqprdhokrj15fd0h.apps.googleusercontent.com"

        /**
         * Web-client secret. Required to redeem the server auth code against the
         * Web client (Web clients must authenticate the exchange). Google
         * documents installed-app secrets as non-secret; this app is personal,
         * sideloaded, and never distributed.
         */
        const val GOOGLE_CLIENT_SECRET = "GOCSPX-REMOVED-SECRET-B"

        @Volatile
        var listenerRef: NotifListenerService? = null

        @Volatile
        var serverUp = false

        @Volatile
        var lastError = ""

        @Volatile
        var bindAddress: String = ""

        fun markRunning(v: Boolean) { serverUp = v }

        /**
         * Register one secret plugin at runtime, so a secret added in the CRUD screen
         * becomes routable without restarting the service. Idempotent.
         *
         * Static and deliberately narrow: the CRUD screen owns the secret VALUE, but the
         * routing table is the service's, so this is the one seam between them.
         */
        fun registerSecretPlugin(ctx: Context, name: String) {
            if (PluginRegistry.get(name) == null) {
                PluginRegistry.register(
                    com.mido.phonebridge.plugins.secrets.SecretPlugin(ctx.applicationContext, name)
                )
                Debug.log("secret plugin registered at runtime: '$name'")
            }
        }
    }

    private var server: HttpServer? = null
    private var wakeLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Debug.log("service onCreate")
        // Warm the AI model as soon as the service exists (Mido, 2026-10-19: "warmed up in
        // memory on app load"), so the first request does not pay the load. Off the main
        // thread, and a no-op when the model is absent — readiness, not warm-up, is what
        // decides whether the bridge may serve.
        Thread({
            try {
                com.mido.phonebridge.ai.ModelRegistry.warm(applicationContext)
                Debug.log("model warm: ${com.mido.phonebridge.ai.ModelRegistry.status(applicationContext)}")
            } catch (e: Exception) {
                Debug.log("model warm-up failed: ${e.message}")
            }
        }, "model-warm").apply { isDaemon = true }.start()
    }

    /**
     * Give the warm model back when the OS is short on memory.
     *
     * The release valve for a ~700 MiB resident engine (measured 2026-10-19) on a 24/7
     * service. Surrendering it voluntarily is strictly better than being killed: a killed
     * foreground service denies every request, while a released engine merely reloads on
     * the next one. The model FILE is never touched — only the loaded engine.
     *
     * **Why there is no level comparison.** The fine-grained levels
     * (`TRIM_MEMORY_RUNNING_LOW` and friends) are DEPRECATED as of API 34/35 precisely
     * because apps are no longer notified of them, so testing `level >= RUNNING_LOW`
     * would be dead logic behind a build warning. Any trim callback is the OS saying
     * memory is tight, and a luxury is the first thing to surrender — so this releases
     * unconditionally. That is also free in this app: a request arriving with the screen
     * off is DENIED as unattended (v0.12.0), so the engine only ever matters while the
     * screen is on, and the reload cost is bounded by the 0.4 s load measured on the host.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.mido.phonebridge.ai.ModelRegistry.release()
        Debug.log("onTrimMemory($level): model engine released")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Debug.log("service onStartCommand startId=$startId")
        try {
            startForegroundCompat()
            Debug.log("startForeground() ok")
        } catch (e: Exception) {
            lastError = "startForeground: ${e.javaClass.simpleName}: ${e.message}"
            Debug.log("FOREGROUND FAILED: $lastError")
            stopSelf()
            return START_NOT_STICKY
        }

        com.mido.phonebridge.core.AuthManager.init(
            applicationContext, GOOGLE_CLIENT_ID, GOOGLE_SERVER_CLIENT_ID, GOOGLE_CLIENT_SECRET
        )
        // Offline access: ask Google for a one-time server auth code against the
        // Web client, then exchange it for a refresh token. The Web client's
        // SECRET is never embedded.
        PlayAuth.configure(serverClientId = GOOGLE_SERVER_CLIENT_ID, offline = true)
        registerPlugins()

        // POLICY, before any listener exists. An unreadable policy means the server
        // will not come up at all — no default, no fallback to SECURE. A gate running
        // on a switch nobody could read is exactly the failure R1's fail-hard rule
        // exists to prevent, and "assume secure" is still an assumption.
        try {
            val p = PolicyStore.from(applicationContext).get()
            Debug.log("policy loaded: ${p.global}")
        } catch (e: Exception) {
            lastError = "policy unreadable: ${e.message}"
            Debug.log("REFUSING TO START: $lastError")
            stopSelf()
            return START_NOT_STICKY
        }

        // Wire the one alarm that must never be silent: an error code nobody classified.
        // It answers 500 (the safe direction) but says so in the ring, so "we crashed"
        // and "nobody taught me this code" stay distinguishable.
        com.mido.phonebridge.core.ErrorCodes.unclassified = { code ->
            Debug.log("UNCLASSIFIED error code '$code' -> 500 (defect)")
        }

        if (server == null) {
            val bind = tailnetAddress()
            if (bind == null) {
                // S8: never silently bind all interfaces.
                lastError = "no tailnet address found; refusing to bind 0.0.0.0"
                Debug.log("REFUSING TO START: $lastError")
                stopSelf()
                return START_NOT_STICKY
            }
            bindAddress = bind
            Debug.log("binding to $bind:$PORT")
            val s = HttpServer(this, bind, PORT)
            try {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                lastError = ""
                Debug.log("HTTP server bound on $bind:$PORT")
            } catch (e: Exception) {
                lastError = "bind: ${e.javaClass.simpleName}: ${e.message}"
                Debug.log("BIND FAILED: $lastError")
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            Debug.log("server already running")
        }
        markRunning(true)
        return START_STICKY
    }

    /**
     * Android 15+ only. Called when the system has decided this foreground service must
     * stop (e.g. a time-limited type hit its cap). The service is no longer foreground at
     * this point and has a few seconds to call [stopSelf]; if it does not, the system
     * throws ForegroundServiceDidNotStopInTimeException and kills the process — which is
     * exactly the crash this override exists to prevent. We stop cleanly and record why.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Debug.log("onTimeout: system requires stop (startId=$startId fgsType=$fgsType) — stopping")
        stopSelf()
    }

    override fun onDestroy() {
        Debug.log("service onDestroy")
        // Kill any overlay still on screen. These are windows owned by the process:
        // if the service dies without removing them they are exactly the kind of
        // orphaned view that survives a stop and looks like a ghost prompt nobody can
        // dismiss. Their requests resolve through the gate's own shutdown path, so
        // clearing the views here costs no audit records.
        com.mido.phonebridge.notify.NotifyOverlay.dismissAll()
        server?.stop()
        server = null
        markRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerPlugins() {
        if (PluginRegistry.get("notifications") == null) {
            PluginRegistry.register(NotificationsPlugin(applicationContext))
        }
        if (PluginRegistry.get("gmail") == null) {
            PluginRegistry.register(GmailPlugin(applicationContext))
        }
        if (PluginRegistry.get("calendar") == null) {
            PluginRegistry.register(CalendarPlugin(applicationContext))
        }
        if (PluginRegistry.get("debug") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.debug.DebugPlugin(applicationContext))
        }
        if (PluginRegistry.get("logs") == null) {
            PluginRegistry.register(com.mido.phonebridge.plugins.logs.LogcatPlugin(applicationContext))
        }
        // The metadata half of the secrets feature: a route that answers "what is this
        // secret, where are its docs, what may it reach" WITHOUT the value. Registered
        // once (not per secret) because it serves the whole index.
        if (PluginRegistry.get("secrets") == null) {
            PluginRegistry.register(
                com.mido.phonebridge.plugins.secrets.SecretIndexPlugin(applicationContext)
            )
        }
        registerSecretPlugins()
    }

    /**
     * Register one plugin per stored secret (plan D7/D8).
     *
     * Dynamic, because the secret list is data, not code: adding a service is adding a
     * secret in the CRUD screen, not a release. The vault's name index is plaintext
     * precisely so this works while the vault is locked — the rules screen must be able
     * to show `todoist × EXECUTE` without a fingerprint.
     *
     * Idempotent (PluginRegistry.register ignores a duplicate id), so a service restart
     * re-registering is a no-op.
     */
    private fun registerSecretPlugins() {
        val names = try {
            com.mido.phonebridge.core.SecretVault.names(applicationContext)
        } catch (e: Exception) {
            // A corrupt index must not silently hide secrets: refuse to start, same as
            // an unreadable policy. Better a bridge that will not serve than one that
            // quietly forgets a credential exists.
            lastError = "secret index unreadable: ${e.message}"
            Debug.log("REFUSING TO START: $lastError")
            stopSelf()
            return
        }
        for (name in names) {
            if (PluginRegistry.get(name) == null) {
                PluginRegistry.register(
                    com.mido.phonebridge.plugins.secrets.SecretPlugin(applicationContext, name)
                )
            }
        }
        if (names.isNotEmpty()) Debug.log("registered ${names.size} secret plugin(s): ${names.joinToString()}")
    }

    /** First IPv4 in the Tailscale CGNAT range 100.64.0.0/10, else null. */
    private fun tailnetAddress(): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("100.")) {
                        val parts = ip.split(".")
                        val second = parts.getOrNull(1)?.toIntOrNull() ?: continue
                        // 100.64.0.0/10  => second octet 64..127
                        if (second in 64..127) return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BridgeService", "tailnetAddress failed: ${e.message}")
        }
        return null
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneBridge running")
            .setContentText("HTTP server on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // specialUse (API 34+), not dataSync: dataSync is capped at 6h/24h on
            // Android 15+ and the system kills the process when the cap is hit.
            // Below 34 the type constant does not exist; the overload without a
            // type is the only option and carries no such cap.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        Debug.log("foreground notification posted")
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "Bridge service", NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }
}

/**
 * NanoHTTPD server. Routes come from PluginRegistry; gating is central.
 *
 * Every resolved request passes through ApprovalGate.await() before its handler
 * runs, so no plugin can execute anything without a human approval.
 */
class HttpServer(
    private val ctx: Context,
    bindHost: String,
    port: Int
) : NanoHTTPD(bindHost, port) {

    private val auditStore: AuditStore get() = AuditLog.from(ctx)

    /**
     * The switch, read once per request.
     *
     * Deliberately NOT cached in a field here: `PolicyStore` caches the parsed file,
     * so this is a map lookup, and going through the store every request means a
     * change made on the home screen takes effect on the very next request with no
     * cache-invalidation path to get wrong.
     */
    private val policy: Policy get() = PolicyStore.from(ctx).get()

    override fun serve(session: IHTTPSession): Response {
        val startedAt = System.currentTimeMillis()
        val rawUri = session.uri
        val path = rawUri.substringBefore('?').trimEnd('/')
        val method = session.method.name
        val remote = session.remoteIpAddress ?: "unknown"
        val query = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

        val draft = AuditDraft(
            startedAtMs = startedAt,
            remote = remote,
            method = method,
            path = path,
            query = query,
            requestHeaders = session.headers.filterKeys { it !in HOP_BY_HOP },
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE
        )

        // The whole request runs here and NEVER throws: every failure path sets a
        // terminal outcome on the draft, so the epilogue below always has one fact
        // to record and there is no path into the handler without a decision.
        val result = dispatch(session, draft, method, path, remote, query)

        // Audit epilogue, inside the request: the client gets its answer only after
        // the record is on disk. A failed append is a request failure (locked Q1) —
        // the side effect may already have happened, so say exactly that rather than
        // reporting a success we cannot prove.
        return try {
            if (draft.written) throw IllegalStateException("audit record already written for this request")
            draft.markWritten()
            auditStore.append(draft.toRecord(System.currentTimeMillis()))
            json(result.status, result.body)
        } catch (e: Exception) {
            Log.e(TAG, "audit append failed", e)
            json(
                Response.Status.INTERNAL_ERROR,
                err("audit_unavailable", "action may have executed, no record: ${e.message}")
            )
        }
    }

    /** One HTTP response plus the status the audit should record for it. */
    private class Dispatched(val status: Response.Status, val body: JSONObject)

    /**
     * Resolve, describe, gate, execute — setting exactly one terminal [AuditOutcome]
     * on [draft] no matter how it ends.
     */
    private fun dispatch(
        session: IHTTPSession,
        draft: AuditDraft,
        method: String,
        path: String,
        remote: String,
        query: Map<String, String>
    ): Dispatched {
        return try {
            val resolved = PluginRegistry.resolve(method, path)
            if (resolved == null) {
                draft.outcome(AuditOutcome.UNROUTED)
                return Dispatched(
                    Response.Status.NOT_FOUND,
                    err("not_found", "unknown route $method $path")
                )
            }
            // Fill the label BEFORE anything can fail: a rejected request still has
            // a plugin and a verb, and the log is far more useful with them.
            draft.bindRoute(resolved.plugin.id, resolved.route.verb, resolved.route.path)

            // MODEL READINESS. The AI is core functionality (Mido, 2026-10-19), so the
            // bridge does not serve without it: a missing model is a NOT-READY state,
            // not a degraded one. Every route answers 503 until the model is
            // provisioned, which is what turns "the app downloads its own model" into
            // part of setup rather than a convenience. Checked here, before the body
            // is read and before validation, so the caller learns the bridge is not up
            // without first being asked to fix their input.
            //
            // `/debug` and `/logs` declare `aiSummary = false`, which is what exempts them:
            // a route that needs no AI needs no model. They are how the not-ready state is
            // DIAGNOSED from outside, and blocking them would make a not-ready bridge
            // undiagnosable — the dead end `/debug` exists to prevent. Read-only, and
            // neither opens a decision.
            val modelState = com.mido.phonebridge.ai.ModelRegistry.readiness(ctx)
            if (!com.mido.phonebridge.ai.ModelReadiness.serves(modelState.ready, resolved.route.aiSummary)) {
                // HANDLER_FAILED, not REJECTED_INVALID: a 503 is OUR side, and the audit
                // rule is that 5xx is never noise. Recording it as the caller's mistake
                // would hide a not-ready bridge among typos — the exact misclassification
                // `AuditClassify` exists to prevent.
                draft.outcome(AuditOutcome.HANDLER_FAILED)
                draft.error("model_not_ready")
                val obj = err("model_not_ready", modelState.detail)
                draft.response(503, obj.toString())
                return Dispatched(Response.Status.SERVICE_UNAVAILABLE, obj)
            }

            val rawBody = if (method == "POST") readRawBody(session) else ""
            // Verbatim, and BEFORE the parse that can throw. A malformed body still
            // has to leave a record: without this the log says "invalid JSON" and
            // keeps not one byte of what was actually sent.
            draft.requestBody(rawBody)
            val body = if (method == "POST") parseBody(rawBody) else JSONObject()

            val req = Req(
                method = method,
                path = path,
                query = query,
                pathParams = resolved.pathParams,
                body = body,
                remote = remote
            )

            // PRE-GATE. Input that can never succeed must not cost a tap: the modal
            // is a decision, and there is no decision to make about `count=-1`.
            // Runs before the description, so a bad request never even builds a
            // modal. A throw here is REJECTED_INVALID (4xx, noise) by construction —
            // see the catch below, where `gated()` is still false.
            resolved.route.validate?.invoke(req)

            val summary = describe(resolved.plugin.id, resolved.route.path, body)
            draft.summary(summary)

            val am = com.mido.phonebridge.core.AuthManager
            val account = am.accountEmail
                ?: if (am.isLoggedIn(ctx)) "(signed in)" else "—"

            // POLICY. Resolved after validation (bad input still costs nothing) and
            // before the gate. Guarded rather than assumed: startup already refuses on
            // an unreadable policy, so this is the belt to that braces, and a request
            // must never run on a mode nobody could read.
            val resolvedPolicy = try {
                val p = policy
                resolve(p.global, p.rules, resolved.plugin.id, resolved.route.verb)
            } catch (e: Exception) {
                draft.outcome(AuditOutcome.HANDLER_FAILED)
                draft.error("policy_unavailable")
                val obj = err("policy_unavailable", e.message ?: "policy unreadable")
                draft.response(503, obj.toString())
                return Dispatched(Response.Status.SERVICE_UNAVAILABLE, obj)
            }
            val mode = resolvedPolicy.mode
            val globalMode = policy.global

            // ATTENDANCE. A request that cannot be shown to an attending human is
            // DENIED. There is no exempt mode any more: 0.12.0 removed the `OFF` switch,
            // so no request can run without a human present, in any mode. Checked here,
            // after validation, so a malformed request still gets its honest 400 and
            // never a misleading `denied`. NOTIFY re-checks at approve time (NotifyGate).
            if (!isAttended(ctx)) {
                draft.decision(
                    Decision(
                        mode.name, decisionSource(resolvedPolicy.source),
                        globalMode.name, resolvedPolicy.ruleKey,
                        null, null, null, 0
                    )
                )
                draft.outcome(AuditOutcome.DENIED_UNATTENDED)
                draft.error("denied")
                val obj = err("denied", "gate unavailable: screen off or device locked")
                draft.response(403, obj.toString())
                return Dispatched(Response.Status.FORBIDDEN, obj)
            }

            when (mode) {
                Mode.NOTIFY -> {
                    // A small overlay card, not a notification: the draw permission
                    // this app already holds makes the prompt ours, so it appears
                    // above whatever the human is looking at instead of fighting the
                    // shade. The card fetches the AI summary FIRST, then starts its 5s
                    // clock; silence approves, a tap approves-and-skips, the ✕ rejects.
                    val decision = NotifyGate.await(
                        ctx,
                        summary,
                        labelOf(resolved.plugin.id, resolved.route.verb),
                        resolved.route.verb,
                        // Null facts for a route that declares `aiSummary = false`: the card
                        // then shows its deterministic content and its clock starts at once.
                        // Never built for an opted-out route, so the rows are not fetched
                        // either — an opted-out route pays nothing for the AI.
                        if (com.mido.phonebridge.ai.ModelReadiness.requiresAi(resolved.route.aiSummary))
                            aiFactsFor(resolved.route, summary, path, body, query, remote)
                        else null,
                        needsAi = com.mido.phonebridge.ai.ModelReadiness.requiresAi(resolved.route.aiSummary)
                    )
                    when (decision) {
                        is NotifyGate.Result.Approved -> {
                            draft.decision(
                                Decision(
                                    mode.name, decisionSource(resolvedPolicy.source),
                                    globalMode.name, resolvedPolicy.ruleKey,
                                    null, decision.shownAt, decision.decidedAt, 0
                                )
                            )
                            draft.outcome(AuditOutcome.NOTIFY_APPROVED)
                        }
                        is NotifyGate.Result.Tapped -> {
                            // A deliberate tap-approve: recorded as NOTIFY_APPROVED
                            // (the outcome set is closed) but the decision block's
                            // decidedAt is the tap instant, and the detail carries the
                            // distinction so the log can tell a tap from silence.
                            draft.decision(
                                Decision(
                                    mode.name, decisionSource(resolvedPolicy.source),
                                    globalMode.name, resolvedPolicy.ruleKey,
                                    null, decision.shownAt, decision.decidedAt, 0
                                )
                            )
                            draft.outcome(AuditOutcome.NOTIFY_APPROVED)
                            draft.error("tapped")
                        }
                        is NotifyGate.Result.Rejected -> {
                            draft.decision(
                                Decision(
                                    mode.name, decisionSource(resolvedPolicy.source),
                                    globalMode.name, resolvedPolicy.ruleKey,
                                    null, decision.shownAt, decision.decidedAt, 0
                                )
                            )
                            draft.outcome(AuditOutcome.NOTIFY_REJECTED)
                            draft.error("denied")
                            draft.response(403, null)
                            return Dispatched(
                                Response.Status.FORBIDDEN,
                                err("denied", "request denied by user")
                            )
                        }
                        is NotifyGate.Result.AiFailed -> {
                            // The card could not be described, so it was withdrawn without a
                            // decision. The AI is core (Mido, 2026-10-19): this is a fault on
                            // our side, answered as a coded error — NEVER as an approval, and
                            // never as a deny either, because no human said no.
                            draft.outcome(AuditOutcome.HANDLER_FAILED)
                            draft.error("ai_unavailable")
                            val obj = err("ai_unavailable", decision.detail)
                            draft.response(500, obj.toString())
                            return Dispatched(Response.Status.INTERNAL_ERROR, obj)
                        }
                        is NotifyGate.Result.Unavailable -> {
                            // Could not be shown: no permission, evicted, or the screen
                            // went away mid-window. Deny, and say which. Approving
                            // because we failed to draw something would be the one
                            // failure mode this whole mode must never have.
                            draft.decision(
                                Decision(
                                    mode.name, decisionSource(resolvedPolicy.source),
                                    globalMode.name, resolvedPolicy.ruleKey,
                                    null, decision.shownAt, decision.decidedAt, 0
                                )
                            )
                            draft.outcome(AuditOutcome.DENIED_UNATTENDED)
                            draft.error("denied")
                            val obj = err("denied", "gate unavailable: ${decision.detail}")
                            draft.response(403, obj.toString())
                            return Dispatched(Response.Status.FORBIDDEN, obj)
                        }
                    }
                }

                Mode.SECURE -> {
                    // Central gate: describe the concrete call, then wait for the human.
                    //
                    // The AI summary is NO LONGER computed here (Mido, 2026-10-19). It used
                    // to block the modal for up to 10s and then leave the buttons dead for
                    // another second — two static waits stacked. The modal now appears
                    // immediately and the gate fetches in the background: the buttons enable
                    // when the summary lands, and the 60s clock starts from THAT instant, so
                    // the model's latency is never charged to the human's decision time.
                    //
                    // Rows are built ONCE, before the view, because a bad target must still
                    // throw pre-gate (coded 4xx, no modal) and the AI needs the same rows.
                    val rows = pluginRows(resolved.route, path, body, query, remote)
                    val gate = ApprovalGate.await(
                        ctx, remote, "$method $path", summary,
                        detail = detailFor(resolved.route.path, path, body),
                        account = account,
                        view = approvalView(
                            remote, account, resolved.route, path, body, query,
                            summary = summary,
                            requiresBiometric = mode == Mode.SECURE,
                            rows = rows,
                            exceptionOffer = exceptionOfferFor(
                                globalMode, resolved.plugin.id, resolved.route.verb, mode
                            )
                        ),
                        // Null for a route that declares `aiSummary = false`: there is nothing
                        // to wait for, so its buttons go live at once.
                        aiFacts = if (com.mido.phonebridge.ai.ModelReadiness
                                .requiresAi(resolved.route.aiSummary))
                            factsFor(resolved.route, summary, rows) else null
                    )
                    draft.decision(gateDecision(gate, globalMode, resolvedPolicy))

                    when (gate) {
                        is ApprovalGate.GateResult.Denied -> {
                            draft.outcome(AuditOutcome.DENIED)
                            draft.error("denied")
                            draft.response(403, null)
                            return Dispatched(
                                Response.Status.FORBIDDEN,
                                err("denied", "request denied by user")
                            )
                        }
                        is ApprovalGate.GateResult.TimedOut -> {
                            draft.outcome(AuditOutcome.TIMEOUT)
                            draft.error("approval_timeout")
                            draft.response(408, null)
                            return Dispatched(
                                Response.Status.REQUEST_TIMEOUT,
                                err("approval_timeout", "approval timed out")
                            )
                        }
                        is ApprovalGate.GateResult.ApprovedWithException -> {
                            // The human approved AND asked for this pair to behave
                            // differently next time. Both halves are real, so both are
                            // recorded: the approval here, the policy move in the same
                            // commit below. A write that fails does NOT deny the request —
                            // the human did approve — but it is surfaced, never swallowed.
                            draft.outcome(AuditOutcome.APPROVED)
                            writeException(gate.rule)
                        }
                        is ApprovalGate.GateResult.Approved ->
                            draft.outcome(AuditOutcome.APPROVED)
                    }
                }
            }

            // ONE handler call and ONE response-recording site, for every mode. They used
            // to be two — an ungated branch (the `OFF` switch, removed in 0.12.0) returned
            // EARLY, so `Route.auditResponseBody` was honoured on one path and ignored on
            // the other, and /debug stored its entire body whenever the gate was off
            // (measured on device: ~13.7 KB per record, against ~1 KB under SECURE).
            // The handler call cannot be duplicated again without deleting this comment.
            val resp = runBlocking { resolved.route.handler(req) }
            recordResponse(draft, resolved.route.auditResponseBody, resp.status, resp.body)
            Dispatched(statusOf(resp.status), resp.body)
        } catch (e: PluginError) {
            // Two very different failures share this type. BEFORE the gate it is the
            // caller's fault (bad input, unknown id) → 4xx, noise. AFTER the gate the
            // human already approved and the server still failed → a real fault.
            val st = httpStatusFor(e.code)
            draft.outcome(AuditClassify.forError(st.requestStatus, draft.gated()))
            draft.error(e.code)
            val bodyObj = err(e.code, e.message ?: e.code)
            draft.response(st.requestStatus, bodyObj.toString())
            Dispatched(st, bodyObj)
        } catch (e: Exception) {
            Log.e(TAG, "unhandled", e)
            draft.outcome(AuditClassify.forCrash())
            draft.error("internal_error")
            val bodyObj = err("internal_error", e.message ?: "error")
            draft.response(500, bodyObj.toString())
            Dispatched(Response.Status.INTERNAL_ERROR, bodyObj)
        }
    }

    /** Map a gate outcome onto the audit's decision block. */
    private fun gateDecision(
        g: ApprovalGate.GateResult,
        globalMode: GlobalMode,
        resolved: ModeResolution
    ): Decision {
        val mode = Mode.SECURE.name
        val source = decisionSource(resolved.source)
        val ruleKey = resolved.ruleKey
        return when (g) {
            is ApprovalGate.GateResult.Approved -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.decidedAt, g.queueDepth
            )
            // The exception tap is an approval first: the decision block reads like an
            // approval (a human did approve this request), and the policy move it also
            // caused is its own POLICY_CHANGED record. Two facts, two lines.
            is ApprovalGate.GateResult.ApprovedWithException -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.decidedAt, g.queueDepth
            )
            is ApprovalGate.GateResult.Denied -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.decidedAt, g.queueDepth
            )
            is ApprovalGate.GateResult.TimedOut -> Decision(
                mode, source, globalMode.name, ruleKey,
                g.enqueuedAt, g.shownAt, g.expiredAt, g.queueDepth
            )
        }
    }

    /**
     * The exception the modal may offer for this request, or null when it must not.
     *
     * **Only under `AUTOMATIC`.** `resolve()` reads the rule table in that mode alone, so
     * under a forced `SECURE`/`NOTIFY` a written rule is inert — offering the button there
     * would be offering a tap that changes nothing. Rather than flip the switch behind the
     * human's back (Mido's call, 2026-10-19: *"it should work with automatic and that's
     * it"*), the offer is simply absent and the button does not render.
     *
     * The mode is the OTHER one — see [exceptionModeFor] — so the label always names the
     * behaviour the tap will produce.
     */
    private fun exceptionOfferFor(
        globalMode: GlobalMode,
        plugin: String,
        verb: Verb,
        resolvedMode: Mode
    ): ApprovalGate.ExceptionOffer? {
        if (globalMode != GlobalMode.AUTOMATIC) return null
        val exceptionMode = exceptionModeFor(resolvedMode)
        return ApprovalGate.ExceptionOffer(
            rule = Rule(plugin, verb, exceptionMode),
            label = if (exceptionMode == Mode.NOTIFY) "ALWAYS NOTIFY" else "ALWAYS ASK"
        )
    }

    /**
     * Persist a rule the human authored from the modal, and record it.
     *
     * Read-modify-write, never a fresh [Policy]: constructing a new one would reset every
     * sibling rule, which is a bug this project already paid for once in `applyGate`.
     * Recorded with `source = "modal"` so the log says WHERE a policy decision came from,
     * not merely that one happened.
     *
     * A failure here does not fail the request — the human already approved — but it is
     * surfaced through the audit health banner rather than swallowed, because a rule that
     * took effect unrecorded is the same class of failure as an unrecorded switch flip.
     */
    private fun writeException(rule: Rule) {
        try {
            val store = PolicyStore.from(ctx)
            val current = store.get()
            val before = current.rules.firstOrNull { it.key == rule.key }
            store.set(current.withRule(rule))
            auditStore.append(PolicyAudit.recordRule(before, rule, current.global, "modal"))
        } catch (e: Exception) {
            AuditHealth.markDegraded("modal exception not recorded: ${e.message}")
            Log.w("BridgeService", "exception write failed: ${e.message}")
        }
    }

    /**
     * Map the policy's own source enum onto the audit's. Two enums rather than one
     * because `PolicyAudit` (in the audit package) already imports `GlobalMode` from
     * the policy package, so sharing an enum would make the dependency circular.
     * This function is the single seam, so the mapping exists once.
     */
    private fun decisionSource(source: ModeSource): DecisionSource = when (source) {
        ModeSource.GLOBAL -> DecisionSource.GLOBAL
        ModeSource.RULE -> DecisionSource.RULE
        ModeSource.DEFAULT -> DecisionSource.DEFAULT
    }

    private companion object {
        const val TAG = "HttpServer"

        /** Headers that say nothing about the caller and everything about TCP. */
        val HOP_BY_HOP = setOf("host", "content-length", "connection", "accept-encoding")
    }

    /**
     * Build the structured WHO / WHAT / ON view for the modal.
     *
     * For notification actions the WHAT is the RESOLVED action label (looked up
     * on the live notification) and the ON block is the real notification — so
     * the modal can never understate what will happen. For every other route we
     * fall back to the route-level description.
     */
    private fun approvalView(
        remote: String,
        account: String,
        route: Route,
        actualPath: String,
        body: JSONObject,
        query: Map<String, String>,
        summary: String,
        requiresBiometric: Boolean,
        /**
         * Target rows, built ONCE by the caller and shared with the AI facts.
         *
         * Passed in rather than gathered here because the summary now needs the SAME rows
         * (see [factsFor]) and building them twice is a second live Google round-trip per
         * approval on a route that pre-fetches its target — gmail trash, calendar delete.
         */
        rows: List<Pair<String, String>>,
        /** The exception the modal may offer, or null. See [exceptionOfferFor]. */
        exceptionOffer: ApprovalGate.ExceptionOffer? = null
    ): ApprovalGate.ApprovalView {
        val routePath = route.path

        // /notifications/{id}/action -> resolve the action against live data.
        if (routePath == "/notifications/{id}/action") {
            val pid = actualPath.trim('/').split('/').getOrNull(1) ?: ""
            val sbn = NotifStore.findByPublicId(pid)
            val actionRef: Any = when {
                body.has("action") && body.get("action") is Number -> body.getInt("action")
                body.has("action") -> body.getString("action")
                else -> throw PluginError("bad_request", "missing 'action'")
            }
            // Resolve FIRST: unknown action => coded error, no modal, no 500.
            val action = try {
                ActionExecutor.resolve(ctx, sbn?.key ?: "", actionRef)
            } catch (e: BridgeError) {
                throw PluginError(e.code, e.message ?: e.code)
            }
            val extras = sbn?.notification?.extras
            val appLabel = try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(sbn!!.packageName, 0)).toString()
            } catch (e: Exception) {
                sbn?.packageName ?: "?"
            }
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val sender = (sbn?.notification?.extras
                ?.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString())
                ?.takeIf { it.isNotBlank() } ?: ""
            // Other buttons the same notification offers, excluding the one being
            // fired. Options are the menu; the resolved action is the pick.
            val fired = action.title?.toString() ?: ""
            val otherActions = (sbn?.notification?.actions ?: emptyArray<android.app.Notification.Action>())
                .mapNotNull { it.title?.toString()?.takeIf { t -> t.isNotBlank() && t != fired } }
            // Target rows come from the plugin (NotificationsPlugin.describeRows),
            // same as every other plugin — one source of truth, not a second copy here.
            return ApprovalGate.ApprovalView(
                requester = requesterLabel(remote),
                account = account,
                actionLabel = action.title?.toString() ?: "(unnamed action)",
                appLabel = appLabel,
                notifTitle = title,
                notifSender = sender,
                notifText = text,
                messageCount = sbn?.notification?.number ?: 0,
                replyText = if (body.has("replyText")) body.getString("replyText") else null,
                options = otherActions,
                destructive = rows,
                requiresBiometric = requiresBiometric,
                exceptionOffer = exceptionOffer
            )
        }

        // /notifications/{id}/dismiss -> resolve the notification ONCE, so the modal
        // can show what is being dismissed exactly like an action does. Same rows,
        // same guarantee: the human sees the real title/body, not a bare verb.
        if (routePath == "/notifications/{id}/dismiss") {
            val pid = actualPath.trim('/').split('/').getOrNull(1) ?: ""
            val sbn = NotifStore.findByPublicId(pid)
                ?: throw PluginError("notification_gone", "no live notification for id '$pid'")
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            return ApprovalGate.ApprovalView(
                requester = requesterLabel(remote),
                account = account,
                actionLabel = "Dismiss a notification",
                appLabel = "",
                notifTitle = title,
                notifSender = "",
                notifText = text,
                messageCount = sbn.notification.number,
                replyText = null,
                destructive = rows,
                requiresBiometric = requiresBiometric,
                exceptionOffer = exceptionOffer
            )
        }

        // Every other route is describable by construction: the label lives on the
        // Route itself (see `Route.describe`), so a route the gate cannot describe
        // cannot be registered. No parallel table to drift out of sync — that is
        // exactly how /calendar/events shipped and 500'd with `undescribed_route`.
        val actionLabel = route.describe
        return ApprovalGate.ApprovalView(
            requester = requesterLabel(remote),
            account = account,
            actionLabel = actionLabel,
            appLabel = "",
            notifTitle = "",
            notifSender = "",
            notifText = "",
            messageCount = 0,
            replyText = null,
            // State the scope being approved, never a bare verb. A filtered read
            // must show its filters: "count=3, package=com.whatsapp".
            filters = query.filterKeys { it == "count" || it == "package" }
                .filterValues { it.isNotBlank() },
            // Target rows come from the PLUGIN, never a table here: only the plugin
            // knows its request shapes and can fetch live data to name the target.
            // Rows are keyed on method+path inside the plugin, so two routes sharing
            // a path template (GET vs POST, POST vs DELETE) can never be confused.
            destructive = rows,
            // Biometric is a SECURE-only rule, computed by the policy and passed in.
            // The gate states it; the Activity renders it and never recomputes it.
            // In R3 this always evaluates true, because a view only exists on the
            // SECURE path — it is correctness plumbing so a future mode reusing the
            // modal cannot silently inherit a fingerprint.
            requiresBiometric = requiresBiometric,
            // The exception offer is built ONCE by the caller and passed in, like the rows.
            // Omitting it here was the bug this line exists to prevent: every branch of
            // this function returns an ApprovalView, and a branch that forgets the offer
            // silently renders a modal with no third button — no error, no log, just a
            // feature that is absent on exactly the routes that use this path (calendar,
            // gmail). Every `return ApprovalView(...)` in this function must pass it.
            exceptionOffer = exceptionOffer
        )
    }

    /**
     * Build the facts a summariser sees, from rows the caller ALREADY built.
     *
     * Facts are the deterministic projection the modal renders: the route's own label, the
     * audit summary, and the labelled rows. Raw JSON is deliberately NOT handed over — it is
     * unbounded and caller-authored, and the rows are the human-readable truth of it. A
     * summariser that saw different facts than the human could describe something other than
     * what is being approved.
     *
     * **This no longer RUNS the model.** It used to be `aiSummary`, which called the model
     * inline and threw `ai_unavailable` on failure. That moved into [ApprovalGate], because
     * the modal now appears BEFORE the summary and only the gate knows when the request
     * actually becomes the one on screen (Mido, 2026-10-19). Keeping the call here would
     * have meant fetching for a queued request nobody can see yet. This is now a pure
     * projection — cheap, no I/O, no latency.
     *
     * Rows are passed in rather than gathered here so they are built ONCE: the modal needs
     * them and so does the AI, and a second gather is a second live Google round-trip per
     * approval on a route that pre-fetches its target (gmail trash, calendar delete).
     */
    private fun factsFor(
        route: Route,
        summary: String,
        rows: List<Pair<String, String>>
    ): com.mido.phonebridge.ai.RequestFacts =
        com.mido.phonebridge.ai.RequestFacts(
            plugin = PluginRegistry.resolve(route.method, route.path)?.plugin?.id ?: "?",
            verb = route.verb.name,
            routeLabel = route.describe,
            summary = summary,
            rows = rows.filter { it.second.isNotBlank() }
        )

    /**
     * The facts a summariser sees, built for any mode. Row gathering is GUARDED: a
     * route whose rows cannot be built (a pre-fetch that fails) still gets a summary
     * from its label and summary rather than turning a display concern into a request
     * failure. That guard matters most on the NOTIFY path, where the rows were never
     * previously built at all.
     */
    private fun aiFactsFor(
        route: Route,
        summary: String,
        actualPath: String,
        body: JSONObject,
        query: Map<String, String>,
        remote: String
    ): com.mido.phonebridge.ai.RequestFacts {
        val rows = try {
            pluginRows(route, actualPath, body, query, remote)
        } catch (e: Exception) {
            emptyList()
        }
        return com.mido.phonebridge.ai.RequestFacts(
            plugin = PluginRegistry.resolve(route.method, route.path)?.plugin?.id ?: "?",
            verb = route.verb.name,
            routeLabel = route.describe,
            summary = summary,
            rows = rows.filter { it.second.isNotBlank() }
        )
    }

    /**
     * Ask the resolved plugin for its modal target rows. Wraps the call so a plugin
     * failing to describe (bad required input, missing id, unreachable live data)
     * surfaces as a coded [PluginError] → status BEFORE any modal, never a partial
     * or placeholder view.
     */
    private fun pluginRows(
        route: Route,
        actualPath: String,
        body: JSONObject,
        query: Map<String, String>,
        remote: String
    ): List<Pair<String, String>> {
        val plugin = PluginRegistry.resolve(route.method, route.path)?.plugin
            ?: throw PluginError("internal_error", "no plugin for ${route.method} ${route.path}")
        val pathParams = pathParamsFor(route, actualPath)
        // `path` is the ROUTE TEMPLATE (`/calendar/events/{id}`), because plugins
        // key their `when (method to path)` on the template — that is the route's
        // identity. The concrete path is only needed to extract path params, which
        // is done above. Passing the concrete path here made every plugin's `when`
        // miss and fall through to `emptyList()`, so mutating modals rendered with
        // no target rows at all ("no details").
        val req = Req(
            method = route.method,
            path = route.path,
            query = query,
            pathParams = pathParams,
            body = body,
            remote = remote
        )
        return plugin.describeRows(req).map { it.label to it.value }
    }

    /**
     * Extract the {x} segments a concrete path filled, using the route template as
     * the key. Same matching rule as the registry router: equal segment counts,
     * `{name}` captures, everything else must be equal.
     */
    private fun pathParamsFor(route: Route, actualPath: String): Map<String, String> {
        val tplSegs = route.path.trim('/').split('/')
        val actSegs = actualPath.trim('/').split('/')
        if (tplSegs.size != actSegs.size) {
            throw PluginError("internal_error", "path '${route.path}' does not match '${actualPath}'")
        }
        val out = HashMap<String, String>()
        for (i in tplSegs.indices) {
            val t = tplSegs[i]
            if (t.startsWith("{") && t.endsWith("}")) {
                out[t.substring(1, t.length - 1)] = actSegs[i]
            }
        }
        return out
    }

    /** Turn a source IP into something a human recognises. */
    private fun requesterLabel(remote: String): String =
        if (remote == "100.64.0.10") "OpenClaw (this box)" else remote

    // Target-row building lives in each Plugin (see Plugin.describeRows): only the
    // plugin knows its request shapes and can fetch live data to name the target.
    // The former hard-coded table here keyed on path (then method+path) and was the
    // exact place a GET/DELETE got validated as a POST. It is gone by design.

    /** Human-readable one-liner for the approval modal. */
    private fun describe(pluginId: String, routePath: String, body: JSONObject): String = when {
        // S4: destructive verbs get verbatim target fields, never reformatted.
        routePath == "/gmail/messages/send" -> {
            val to = jsonArrayToStrings(body.optJSONArray("to")).joinToString(", ").ifBlank { "?" }
            val subj = body.optString("subject").ifBlank { "(no subject)" }
            val bodyPreview = body.optString("body").take(280)
            "SEND EMAIL  to=[$to]  subject=[$subj]  body=[$bodyPreview]"
        }
        routePath == "/gmail/messages/{id}/trash" ->
            "TRASH EMAIL  id=[${body.optString("_target").ifBlank { "?" }}]"
        routePath == "/gmail/messages/{id}/modify" ->
            "MODIFY EMAIL  add=[${jsonArrayToStrings(body.optJSONArray("addLabels"))}] remove=[${jsonArrayToStrings(body.optJSONArray("removeLabels"))}]"
        pluginId == "gmail" -> "Gmail: ${routePath.removePrefix("/gmail")}"
        pluginId == "notifications" ->
            "Notifications: ${routePath.removePrefix("/notifications").ifBlank { "list" }}"
        // A brokered third-party call. The default branch would produce
        // `todoist: /secrets/todoist/call` — a path, which tells the summariser
        // NOTHING about what is about to happen. That was a real complaint (Mido,
        // 2026-10-19: *"I expected the todolist adding one to say Adding 8 todos to
        // PhoneBridge"*): the payload is the whole reason the AI exists, so the
        // deterministic summary has to describe the payload, not the route.
        routePath.startsWith("/secrets/") -> describeSecretCall(pluginId, body)
        else -> if (pluginId.isBlank()) routePath else "$pluginId: $routePath"
    }

    /**
     * A brokered call, described by what it does rather than by its URL.
     *
     * `POST https://api.todoist.com/api/v1/sync` with a body is, to a reader, "some sync call".
     * This turns it into `todoist · POST /api/v1/sync`, and the payload's SHAPE is contributed
     * separately as its own fact row by the secrets plugin — deliberately ADDITIVE (Mido,
     * 2026-10-19: *"the itemadd x 8 thing should be an addition to the context not a
     * replacement"*). Squeezing it into this string instead displaced what the summary was for.
     *
     * Deterministic, so it is true even when the AI is unavailable and the audit record is
     * readable on its own.
     */
    private fun describeSecretCall(pluginId: String, body: JSONObject): String {
        val method = body.optString("method", "GET").uppercase()
        val url = body.optString("url", "")
        val path = runCatching { java.net.URL(url).path }.getOrNull().orEmpty().ifBlank { url.ifBlank { "?" } }
        return "$pluginId · $method $path"
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun detailFor(routePath: String, actualPath: String, body: JSONObject): JSONObject =
        JSONObject()
            .put("plugin_route", routePath)
            .put("actual_path", actualPath)
            .put("body", body)

    /**
     * The body as the caller sent it. Never throws on bad content — only on a socket
     * failure, which the caller records as a transport fault rather than a client one.
     */
    private fun readRawBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    /** Parse, failing hard and coded. The raw text is already on the draft by now. */
    private fun parseBody(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        throw PluginError("bad_request", "invalid JSON body: ${e.message}")
    }

    /**
     * The one place an integer status becomes NanoHTTPD's enum.
     *
     * The previous form was `values().firstOrNull { ... } ?: Response.Status.OK` — an
     * unmapped status silently became **200 OK**. That is the worst possible direction
     * for this fallback: a failure reported as success. Unknown now answers 500, loudly,
     * because "we could not represent this status" must never read as "it worked".
     */
    private fun statusOf(code: Int): Response.Status =
        Response.Status.values().firstOrNull { it.requestStatus == code } ?: run {
            Log.e(TAG, "no NanoHTTPD status for $code -> 500 (defect)")
            Response.Status.INTERNAL_ERROR
        }

    private fun err(code: String, msg: String): JSONObject =
        JSONObject().put("ok", false).put("error", code).put("detail", msg)

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())

    private fun httpStatusFor(code: String): Response.Status =
        statusOf(com.mido.phonebridge.core.ErrorCodes.statusFor(code))
}
