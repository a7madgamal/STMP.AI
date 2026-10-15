package com.mido.phonebridge.plugins.notifications

import android.content.Context
import com.mido.phonebridge.ActionExecutor
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.NotifStore
import com.mido.phonebridge.core.*
import org.json.JSONObject

/**
 * Notifications plugin: the original phone-bridge surface, now expressed through
 * the shared plugin contract. No scopes (local-only).
 */
class NotificationsPlugin(private val ctx: Context) : Plugin {

    override val id = "notifications"
    override val displayName = "Notifications"

    override fun routes(): List<Route> = listOf(
        Route("GET", "/notifications") { list() },
        Route("POST", "/notifications/{id}/action") { action(it) },
        Route("POST", "/notifications/{id}/dismiss") { dismiss(it) }
    )

    override fun status(): PluginStatus {
        val connected = BridgeService.listenerRef != null
        val count = NotifStore.list().size
        return PluginStatus(
            "Notifications",
            if (connected) PluginStatus.State.OK else PluginStatus.State.NEEDS_ATTENTION,
            if (connected) "$count live" else "listener not connected"
        )
    }

    private fun list(): Resp = Resp.ok(JSONObject().put("notifications", NotifStore.listJson(ctx)))

    private fun action(req: Req): Resp {
        val pid = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val sbn = NotifStore.findByPublicId(pid)
            ?: throw PluginError("notification_gone", "no live notification with id=$pid")
        val actionRef: Any = when {
            req.body.has("action") && req.body.get("action") is Number -> req.body.getInt("action")
            req.body.has("action") -> req.body.getString("action")
            else -> throw PluginError("bad_request", "missing 'action'")
        }
        val replyText = if (req.body.has("replyText")) req.body.getString("replyText") else null
        ActionExecutor.performAction(ctx, sbn.key, actionRef, replyText)
        return Resp.ok(JSONObject().put("executed", "action").put("id", pid))
    }

    private fun dismiss(req: Req): Resp {
        val pid = req.pathParams["id"] ?: throw PluginError("bad_request", "missing id")
        val sbn = NotifStore.findByPublicId(pid)
            ?: throw PluginError("notification_gone", "no live notification with id=$pid")
        ActionExecutor.dismiss(sbn.key)
        return Resp.ok(JSONObject().put("executed", "dismiss").put("id", pid))
    }
}
