package com.mido.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the live set of notifications the listener has observed, and exposes
 * the action/dismiss primitives. This is the ONLY place notification actions
 * are executed. It never performs a fallback: if the action can't be fired,
 * it throws / returns an error.
 */
object NotifStore {
    private const val TAG = "NotifStore"

    private val items = LinkedHashMap<String, StatusBarNotification>()

    @Synchronized
    fun put(sbn: StatusBarNotification) {
        items[sbn.key] = sbn
    }

    @Synchronized
    fun remove(sbn: StatusBarNotification) {
        items.remove(sbn.key)
    }

    @Synchronized
    fun seed(service: NotifListenerService) {
        items.clear()
        service.activeNotifications?.forEach { items[it.key] = it }
    }

    @Synchronized
    fun get(key: String): StatusBarNotification? = items[key]

    @Synchronized
    fun list(): List<StatusBarNotification> = items.values.toList()

    /**
     * Effective group key: the OS may override the app's group key when it
     * auto-bundles, so the value the user actually sees is override ?: group.
     * Docs: StatusBarNotification.getOverrideGroupKey()/getGroupKey().
     */
    private fun effectiveGroupKey(sbn: StatusBarNotification): String? =
        sbn.overrideGroupKey?.takeIf { it.isNotBlank() }
            ?: sbn.groupKey?.takeIf { it.isNotBlank() }

    /**
     * The group-summary (child-collapse) entry, if any, for a group key.
     * Docs: FLAG_GROUP_SUMMARY => StatusBarNotification.isGroup().
     */
    private fun isSummary(sbn: StatusBarNotification): Boolean =
        (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    /**
     * Serialize all live notifications to the documented JSON shape.
     *
     * Group summaries (synthetic "N messages" bundles) are de-duplicated:
     * when a real sibling exists in the same effective group key, the summary
     * is omitted; when the summary is all that exists it is emitted with
     * {"bundle":true,"actionable":false} so callers never act on a blob.
     */
    @Synchronized
    fun listJson(ctx: Context): JSONArray {
        // group key -> true when a non-summary sibling lives in that group
        val hasRealSibling = HashMap<String, Boolean>()
        for (sbn in items.values) {
            val gk = effectiveGroupKey(sbn) ?: continue
            if (!isSummary(sbn)) hasRealSibling[gk] = true
        }

        val arr = JSONArray()
        for (sbn in items.values) {
            val gk = effectiveGroupKey(sbn)
            val summary = isSummary(sbn)
            if (summary && gk != null && hasRealSibling[gk] == true) {
                // A real sibling exists: the summary is redundant noise. Drop it.
                continue
            }
            val obj = toJson(ctx, sbn)
            // A summary with no real sibling is a bundle: visible but not actionable.
            obj.put("bundle", summary && (gk == null || hasRealSibling[gk] != true))
            obj.put("actionable", !(summary && (gk == null || hasRealSibling[gk] != true)))
            arr.put(obj)
        }
        return arr
    }

    /**
     * Short, URL/path-safe, stable id derived from the raw StatusBarNotification key.
     * The raw key contains '|' and sometimes newlines, which are hostile in URLs.
     */
    fun publicId(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    /** Reverse lookup: which live notification does this opaque id belong to? */
    @Synchronized
    fun findByPublicId(pid: String): StatusBarNotification? {
        for (sbn in items.values) {
            if (publicId(sbn.key) == pid) return sbn
        }
        return null
    }

    fun toJson(ctx: Context, sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val extras = n.extras

        val appLabel = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        val actions = JSONArray()
        val rawActions = n.actions ?: emptyArray<Notification.Action>()
        rawActions.forEachIndexed { index, action ->
            val title = action.title?.toString() ?: ""
            val hasReply = (action.remoteInputs?.isNotEmpty() == true)
            actions.put(
                JSONObject().apply {
                    put("index", index)
                    put("label", title)
                    put("hasReply", hasReply)
                    put(
                        "remoteInputKeys",
                        JSONArray().apply {
                            action.remoteInputs?.forEach { put(it.resultKey) }
                        }
                    )
                }
            )
        }

        // --- MessagingStyle extraction (Signal/Telegram/WhatsApp group chats) ---
        val messaging = extractMessagingStyle(n)

        // conversationTitle: the chat/group name. Signal & friends expose it via
        // EXTRA_CONVERSATION_TITLE; fall back to subtext, then MessagingStyle.
        val conversationTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: messaging?.conversationTitle?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                ?: ""

        @Suppress("InlinedApi")
        val isGroup = messaging?.isGroup
            ?: extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        return JSONObject().apply {
            put("id", publicId(sbn.key))
            put("package", sbn.packageName)
            put("app", appLabel)
            put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
            put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
            put(
                "bigText",
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            )
            // --- extended fields ---
            put("conversationTitle", conversationTitle)
            put("isGroupConversation", isGroup)
            put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "")
            put("category", n.category ?: "")
            put("channelId", n.channelId ?: "")
            put("groupKey", sbn.groupKey ?: "")
            put("overrideGroupKey", sbn.overrideGroupKey ?: "")
            // isGroup(): "is part of a group" (any member). isAppGroup(): the app
            // itself asked to group. Both come straight from StatusBarNotification.
            put("isGroup", sbn.isGroup)
            put("isAppGroup", sbn.isAppGroup)
            put("isOngoing", (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
            put("isGroupSummary", (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
            @Suppress("DEPRECATION")
            put("priority", n.priority)
            put("visibility", n.visibility)
            put("number", n.number)
            put("occupant", sbn.isOngoing)
            put("messages", messaging?.messages ?: JSONArray())
            put("peopleCount", messaging?.people?.length() ?: 0)
            put("people", messaging?.people ?: JSONArray())
            put("postedAt", sbn.postTime)
            put("isClearable", sbn.isClearable)
            put("actions", actions)
        }
    }

    private class MessagingExtract(
        val conversationTitle: String,
        val isGroup: Boolean,
        val messages: JSONArray,
        val people: JSONArray
    )

    /** Pull Person/MessagingStyle data if present. Returns null when absent. */
    @Suppress("InlinedApi")
    private fun extractMessagingStyle(n: Notification): MessagingExtract? {
        val style = n.extras.getCharSequence(Notification.EXTRA_TEMPLATE)?.toString()
        val title = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""
        val isGroup = n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        val messages = JSONArray()
        val people = JSONArray()

        @Suppress("DEPRECATION")
        val raw = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (raw != null) {
            for (p in raw) {
                val bundle = p as? android.os.Bundle ?: continue
                val sender = bundle.getCharSequence("sender")?.toString() ?: ""
                // senderPerson (API 28+) carries a URI + name
                val person = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    @Suppress("DEPRECATION")
                    bundle.getParcelable<android.app.Person>("sender_person")
                } else null
                @Suppress("NewApi")
                val senderName = person?.name?.toString() ?: sender
                val text = bundle.getCharSequence("text")?.toString() ?: ""
                val time = bundle.getLong("time", 0L)
                messages.put(
                    JSONObject()
                        .put("sender", senderName)
                        .put("text", text)
                        .put("time", time)
                )
                if (senderName.isNotBlank()) {
                    @Suppress("NewApi")
                    val uri = person?.uri ?: ""
                    people.put(JSONObject().put("name", senderName).put("uri", uri))
                }
            }
        }

        // A MessagingStyle notification that carries >1 distinct sender is a group.
        val distinctSenders = HashSet<String>()
        for (i in 0 until messages.length()) {
            val s = messages.optJSONObject(i)?.optString("sender") ?: continue
            if (s.isNotBlank()) distinctSenders.add(s)
        }
        val groupish = isGroup || distinctSenders.size > 1

        val hasData = messages.length() > 0 || people.length() > 0 || title.isNotBlank()
        if (!hasData && style == null) return null

        return MessagingExtract(title, groupish, messages, people)
    }


}
