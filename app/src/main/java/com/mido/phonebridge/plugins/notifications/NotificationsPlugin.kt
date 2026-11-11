package com.mido.phonebridge.plugins.notifications

import android.content.Context
import com.mido.phonebridge.ActionExecutor
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.NotifStore
import com.mido.phonebridge.core.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notifications plugin: the original phone-bridge surface, now expressed through
 * the shared plugin contract. No scopes (local-only).
 */
class NotificationsPlugin(private val ctx: Context) : Plugin {

    override val id = "notifications"
    override val displayName = "Notifications"

    /**
     * Both notification-related accesses live with the notification plugin, on Mido's
     * instruction (2026-11-03): *"notification related access belong with the notification
     * plugin"*.
     *
     * The split between the two is not cosmetic. `POST_NOTIFICATIONS` is a runtime permission
     * with a system dialog. The listener is a Settings screen the human must navigate to
     * themselves, so a `requestPermissions` call for it does nothing whatsoever — which is why
     * it is declared as [SpecialAccess] rather than pretending to be a permission.
     */
    override fun permissions(): List<PluginPermission> = listOf(
        PluginPermission(
            permission = "android.permission.POST_NOTIFICATIONS",
            label = "Notifications",
            why = "Posting and dismissing notifications on your behalf needs this. Without it " +
                "the routes still answer, but nothing appears in the shade.",
            optional = false,
        )
    )

    override fun specialAccess(): List<SpecialAccess> = listOf(
        SpecialAccess.NOTIFICATION_LISTENER
    )

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/notifications", "List your notifications", Verb.READ,
            // Same parser runs before the gate and inside the handler, so a filter
            // typo answers 400 without ever costing a tap — and the two paths cannot
            // drift apart, because there is only one implementation.
            validate = { requirePackageFilter(it); requireCount(it) }
        ) { list(it) },
        Route("POST", "/notifications/{id}/action", "Fire a notification action", Verb.EXECUTE) { action(it) },
        Route("POST", "/notifications/{id}/dismiss", "Dismiss a notification", Verb.EXECUTE, holdExempt = true) { dismiss(it) }
    )

    /**
     * Rows for the approval modal. Owned here like every other plugin; the gate
     * holds no per-route knowledge. Reads return empty; any mutating route not
     * listed throws (server defect), never a silently empty modal.
     */
    override fun describeRows(req: Req): List<ApprovalRow> = rowsFor(
        req,
        reads = setOf("/notifications"),
        writes = mapOf(
            "POST /notifications/{id}/action" to { r -> actionRows(r) },
            "POST /notifications/{id}/dismiss" to { r -> dismissRows(r) }
        )
    )
    /**
     * The resolved action is looked up on the LIVE notification, so the modal can
     * never understate what will fire. An unknown action fails hard BEFORE the modal.
     */
    private fun actionRows(req: Req): List<ApprovalRow> {
        val pid = req.pathParams["id"]
            ?: throw PluginError("bad_request", "missing notification id")
        val sbn = NotifStore.findByPublicId(pid)
            ?: throw PluginError("notification_gone", "no live notification with id=$pid")
        val actionRef: Any = when {
            req.body.has("action") && req.body.get("action") is Number -> req.body.getInt("action")
            req.body.has("action") -> req.body.getString("action")
            else -> throw PluginError("bad_request", "missing 'action'")
        }
        // Resolve FIRST: unknown action => coded error, no modal.
        val action = try {
            ActionExecutor.resolve(ctx, sbn.key, actionRef)
        } catch (e: Exception) {
            throw PluginError("bad_request", e.message ?: "unknown action")
        }
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val replyText = if (req.body.has("replyText")) req.body.getString("replyText") else null
        return buildList {
            add(ApprovalRow("ACTION", action.title?.toString() ?: "(unnamed action)", ApprovalRow.Style.WARN))
            if (title.isNotBlank()) add(ApprovalRow("TITLE", title))
            if (text.isNotBlank()) add(ApprovalRow("BODY", text, ApprovalRow.Style.LONG))
            if (!replyText.isNullOrBlank()) add(ApprovalRow("REPLY", replyText, ApprovalRow.Style.LONG))
        }
    }

    private fun dismissRows(req: Req): List<ApprovalRow> {
        val pid = req.pathParams["id"]
            ?: throw PluginError("bad_request", "missing notification id")
        val sbn = NotifStore.findByPublicId(pid)
            ?: throw PluginError("notification_gone", "no live notification with id=$pid")
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val appLabel = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            throw PluginError("notification_gone", "app for '${sbn.packageName}' is gone: ${e.message}")
        }
        return buildList {
            add(ApprovalRow("DISMISS", "notification from $appLabel"))
            if (title.isNotBlank()) add(ApprovalRow("TITLE", title))
            if (text.isNotBlank()) add(ApprovalRow("BODY", text, ApprovalRow.Style.LONG))
        }
    }

    override fun status(): PluginStatus {
        val connected = BridgeService.listenerRef != null
        val count = NotifStore.list().size
        return PluginStatus(
            "Notifications",
            if (connected) PluginStatus.State.OK else PluginStatus.State.NEEDS_ATTENTION,
            if (connected) "$count live" else "listener not connected"
        )
    }

    /** `package` filter: absent is fine, blank is a hard 400. */
    private fun requirePackageFilter(req: Req): String? = req.query["package"]?.let { raw ->
        val v = raw.trim()
        if (v.isEmpty()) throw PluginError("bad_package", "package must not be empty")
        v
    }

    /** `count` filter: absent is fine, anything not a non-negative integer is a hard 400. */
    private fun requireCount(req: Req): Int? = req.query["count"]?.let { raw ->
        val v = raw.trim().toIntOrNull()
            ?: throw PluginError("bad_count", "count must be an integer, got '$raw'")
        if (v < 0) throw PluginError("bad_count", "count must be >= 0, got $v")
        v
    }

    private fun list(req: Req): Resp {
        // Optional filters. Absent => current behavior (everything).
        // Malformed => hard 400, never a silent fallback to "all".
        val packageFilter: String? = requirePackageFilter(req)
        val count: Int? = requireCount(req)

        var all = NotifStore.listJson(ctx)

        // Filter by package (exact, case-sensitive) before anything else.
        if (packageFilter != null) {
            val kept = JSONArray()
            for (i in 0 until all.length()) {
                val o = all.optJSONObject(i) ?: continue
                if (o.optString("package") == packageFilter) kept.put(o)
            }
            all = kept
        }

        // "Most recent N" needs an order; only sort when count is requested so
        // callers that pass no count keep the store's natural order.
        if (count != null) {
            val sorted = (0 until all.length())
                .mapNotNull { all.optJSONObject(it) }
                .sortedByDescending { it.optLong("postedAt", 0L) }
            val out = JSONArray()
            sorted.take(count).forEach { out.put(it) }
            all = out
        }

        return Resp.ok(JSONObject().put("notifications", all))
    }

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
