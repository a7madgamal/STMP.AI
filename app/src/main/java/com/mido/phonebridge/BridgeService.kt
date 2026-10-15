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
import com.mido.phonebridge.plugins.gmail.GmailPlugin
import com.mido.phonebridge.plugins.notifications.NotificationsPlugin
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
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
         * Desktop-type OAuth client. Loopback redirect flow (the only flow that
         * carries Gmail's restricted scopes). The secret is not treated as a
         * secret for installed/desktop clients (Google's own documentation).
         */
        const val GOOGLE_CLIENT_ID = "503841343554-5ibl0ncgeb2v9b9jarcmg74ko86o1cdl.apps.googleusercontent.com"

        /** Non-secret for Desktop clients per Google's installed-app documentation. */
        const val GOOGLE_CLIENT_SECRET = "GOCSPX-REMOVED-SECRET-A"

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
            applicationContext, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
        )
        registerPlugins()

        if (server == null) {
            val bind = tailnetAddress() ?: "0.0.0.0"
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
            ApprovalGate.await(
                ctx, remote, "$method $path", summary, detail = detailFor(resolved.route.path, path, body)
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

    /** Human-readable one-liner for the approval modal. */
    private fun describe(pluginId: String, routePath: String, body: JSONObject): String = when {
        routePath == "/gmail/messages/send" ->
            "Gmail SEND to ${body.optJSONArray("to")?.toString() ?: "?"} — \"${body.optString("subject")}\""
        pluginId == "gmail" -> "Gmail: ${routePath.removePrefix("/gmail")}"
        pluginId == "notifications" -> "Notifications: ${routePath.removePrefix("/notifications").ifBlank { "list" }}"
        else -> "$pluginId: $routePath"
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
        "bad_request", "unsupported_action", "missing_reply_text", "decode_error" -> Response.Status.BAD_REQUEST
        "notification_gone" -> Response.Status.GONE
        "not_clearable", "google_error" -> Response.Status.CONFLICT
        "listener_unavailable" -> Response.Status.SERVICE_UNAVAILABLE
        "auth_required", "auth_expired" -> Response.Status.UNAUTHORIZED
        "auth_not_configured" -> Response.Status.SERVICE_UNAVAILABLE
        "auth_error", "secure_storage_error" -> Response.Status.INTERNAL_ERROR
        else -> Response.Status.INTERNAL_ERROR
    }
}
