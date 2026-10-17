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
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.plugins.calendar.CalendarPlugin
import com.mido.phonebridge.plugins.gmail.GmailPlugin
import com.mido.phonebridge.plugins.notifications.NotificationsPlugin
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
    }

    private var server: HttpServer? = null
    private var wakeLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Debug.log("service onCreate")
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

    override fun onDestroy() {
        Debug.log("service onDestroy")
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
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

    override fun serve(session: IHTTPSession): Response {
        val rawUri = session.uri
        val path = rawUri.substringBefore('?').trimEnd('/')
        val method = session.method.name
        val remote = session.remoteIpAddress ?: "unknown"

        return try {
            val resolved = PluginRegistry.resolve(method, path)
                ?: return json(Response.Status.NOT_FOUND, err("not_found", "unknown route $method $path"))

            val body = if (method == "POST") readBody(session) else JSONObject()
            val query = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

            // Central gate: describe the concrete call, then wait for the human.
            val summary = describe(resolved.plugin.id, resolved.route.path, body)
            val am = com.mido.phonebridge.core.AuthManager
            val account = am.accountEmail
                ?: if (am.isLoggedIn(ctx)) "(signed in)" else "—"
            ApprovalGate.await(
                ctx, remote, "$method $path", summary,
                detail = detailFor(resolved.route.path, path, body),
                account = account,
                view = approvalView(remote, account, resolved.route, path, body, query)
            )

            val req = Req(
                method = method,
                path = path,
                query = query,
                pathParams = resolved.pathParams,
                body = body,
                remote = remote
            )
            val resp = runBlocking { resolved.route.handler(req) }
            json(statusOf(resp.status), resp.body)
        } catch (e: PluginError) {
            json(httpStatusFor(e.code), err(e.code, e.message ?: e.code))
        } catch (e: ApprovalGate.DeniedException) {
            json(Response.Status.FORBIDDEN, err("denied", "request denied by user"))
        } catch (e: ApprovalGate.TimeoutException) {
            json(Response.Status.REQUEST_TIMEOUT, err("approval_timeout", "approval timed out"))
        } catch (e: Exception) {
            Log.e("HttpServer", "unhandled", e)
            json(Response.Status.INTERNAL_ERROR, err("internal_error", e.message ?: "error"))
        }
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
        query: Map<String, String>
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
                destructive = pluginRows(route, actualPath, body, query, remote),
                requiresBiometric = true
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
                destructive = pluginRows(route, actualPath, body, query, remote),
                requiresBiometric = true
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
            destructive = pluginRows(route, actualPath, body, query, remote),
            // Biometric gates APPROVE on EVERY request, no exception — the modal always
            // asks for a fingerprint. The gate states it; the Activity renders it and
            // never recomputes the rule itself.
            requiresBiometric = true
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
        else -> if (pluginId.isBlank()) routePath else "$pluginId: $routePath"
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

    private fun readBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val raw = map["postData"] ?: "{}"
        return try {
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            throw PluginError("bad_request", "invalid JSON body: ${e.message}")
        }
    }

    private fun statusOf(code: Int): Response.Status =
        Response.Status.values().firstOrNull { it.requestStatus == code } ?: Response.Status.OK

    private fun err(code: String, msg: String): JSONObject =
        JSONObject().put("ok", false).put("error", code).put("detail", msg)

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())

    private fun httpStatusFor(code: String): Response.Status = when (code) {
        "bad_request", "bad_count", "bad_package", "bad_time_min", "bad_time_max", "bad_max", "unsupported_action", "missing_reply_text", "decode_error" -> Response.Status.BAD_REQUEST
        "notification_gone" -> Response.Status.GONE
        "not_clearable", "google_error" -> Response.Status.CONFLICT
        "listener_unavailable" -> Response.Status.SERVICE_UNAVAILABLE
        "auth_required", "auth_expired" -> Response.Status.UNAUTHORIZED
        "auth_not_configured" -> Response.Status.SERVICE_UNAVAILABLE
        "auth_error", "secure_storage_error" -> Response.Status.INTERNAL_ERROR
        else -> Response.Status.INTERNAL_ERROR
    }
}
