package com.mido.phonebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.mido.phonebridge.audit.AuditHeader
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.notify.NotifyOverlay
import com.mido.phonebridge.ui.OutcomeCopy
import com.mido.phonebridge.ui.Theme

/** Waiting requests and recorded decisions. Deliberately offers NO resolver or policy action. */
class ActivityFragment : BridgeFragment() {
    override val tab = Theme.Tab.ACTIVITY
    override val ownsScrolling: Boolean get() = false

    private lateinit var pendingHost: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var summaryLine: TextView
    private lateinit var noiseChip: com.google.android.material.button.MaterialButton
    private lateinit var pluginChip: com.google.android.material.button.MaterialButton
    private var hideNoise = true
    private var pluginFilter: String? = null
    private var outcomeFilter: AuditOutcome? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!isAdded || view == null) return
            renderPending()
            ticker.postDelayed(this, 1_000L)
        }
    }

    override fun buildContent(container: LinearLayout) {
        container.addView(Theme.text(ctx, "Activity", Theme.TEXT_TITLE_SP).apply {
            typeface = Theme.handFont(ctx)
        })
        container.addView(Theme.text(
            ctx, "What is waiting, and what happened. Decisions still happen only on the request prompt.",
            Theme.TEXT_DETAIL_SP, Theme.ON_SURFACE_VARIANT,
        ))
        pendingHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(pendingHost)
        summaryLine = Theme.text(ctx, "", Theme.TEXT_DETAIL_SP, Theme.ON_SURFACE_VARIANT)
        container.addView(summaryLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Theme.dp(ctx, Theme.ROW_GAP_DP) })
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // Every chip carries its own gap. They used to be added bare, so the whole strip rendered
        // as one long touching bar (Mido, 2026-11-11: "the filter buttons in the middle screen
        // are touching everything"). A trailing margin on the last chip is harmless inside the
        // scroll strip — it becomes end padding.
        fun addChip(view: android.view.View) {
            chips.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = Theme.dp(ctx, Theme.ROW_GAP_DP) })
        }
        noiseChip = chip("") { hideNoise = !hideNoise; renderHistory() }
        addChip(noiseChip)
        addChip(chip("all") { pluginFilter = null; outcomeFilter = null; renderHistory() })
        pluginChip = chip("plugin ▾") { showPluginFilter() }
        addChip(pluginChip)
        addChip(chip("denied") { outcomeFilter = AuditOutcome.DENIED; renderHistory() })
        addChip(chip("timeout") { outcomeFilter = AuditOutcome.TIMEOUT; renderHistory() })
        addChip(chip("failed") { outcomeFilter = AuditOutcome.HANDLER_FAILED; renderHistory() })
        container.addView(HorizontalScrollView(ctx).apply { addView(chips) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = Theme.dp(ctx, 4)
            bottomMargin = Theme.dp(ctx, Theme.ROW_GAP_DP)
        })
        listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listContainer)
        render()
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
        render()
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroyView() {
        ticker.removeCallbacks(tick)
        super.onDestroyView()
    }

    override fun repaint() = render()

    private fun render() {
        if (!::listContainer.isInitialized) return
        renderPending()
        renderHistory()
    }

    private fun renderPending() {
        renderPending(ApprovalGate.pendingSnapshots() + NotifyOverlay.pendingSnapshots())
    }

    internal fun renderPending(pending: List<PendingDecisionSnapshot>) {
        if (!::pendingHost.isInitialized) return
        (activity as? MainActivity)?.setPendingCount(pending.size)
        pendingHost.removeAllViews()
        pendingHost.addView(Theme.sectionHeader(ctx, "Awaiting decision"))
        if (pending.isEmpty()) {
            pendingHost.addView(Theme.text(ctx, "Nothing is waiting.", Theme.TEXT_BODY_SP, Theme.ON_SURFACE_VARIANT))
            return
        }
        for (item in pending) {
            pendingHost.addView(Theme.statusRow(
                ctx, item.action, "${item.caller} · ${item.verb} · ${item.state}\n" +
                    "${item.endpoint} · waiting ${item.waitingMs / 1_000}s · read-only",
                Theme.WARNING,
            ))
        }
    }

    private fun chip(label: String, onClick: () -> Unit) = Theme.chip(ctx, label, onClick = onClick)

    private fun showPluginFilter() {
        val plugins = AuditLog.from(ctx).headers().map { it.plugin }
            .filter { it.isNotBlank() }.distinct().sorted()
        val popup = PopupMenu(ctx, pluginChip)
        popup.menu.add("All plugins")
        plugins.forEach(popup.menu::add)
        popup.setOnMenuItemClickListener { item ->
            pluginFilter = if (item.title == "All plugins") null else item.title.toString()
            renderHistory()
            true
        }
        popup.show()
    }

    private fun renderHistory() {
        val store = AuditLog.from(ctx)
        val all = store.headers()
        val hiddenNoise = all.count { it.noise }
        val shown = all.filter { !(hideNoise && it.noise) }
            .filter { pluginFilter == null || it.plugin == pluginFilter }
            .filter { outcomeFilter == null || it.outcome == outcomeFilter }
        noiseChip.text = if (hideNoise) "show noise" else "hide noise"
        pluginChip.text = pluginFilter?.let { "$it ▾" } ?: "plugin ▾"
        val unreadable = store.unreadableCount()
        summaryLine.text = buildString {
            append("${shown.size} of ${all.size} records")
            if (hideNoise && hiddenNoise > 0) append(" · $hiddenNoise noise hidden")
            if (unreadable > 0) append(" · ⚠ $unreadable unreadable")
        }
        listContainer.removeAllViews()
        if (shown.isEmpty()) {
            listContainer.addView(Theme.text(
                ctx, if (all.isEmpty()) "No requests yet." else "Nothing matches these filters.",
                Theme.TEXT_BODY_SP, Theme.ON_SURFACE_VARIANT,
            ))
            return
        }
        val capped = shown.take(MAX_ROWS)
        capped.forEach { listContainer.addView(historyRow(it)) }
        if (shown.size > capped.size) {
            listContainer.addView(Theme.text(
                ctx, "${shown.size - capped.size} older records not shown", Theme.TEXT_CAPTION_SP,
                Theme.ON_SURFACE_VARIANT,
            ))
        }
    }

    private fun historyRow(h: AuditHeader): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 12), Theme.dp(ctx, 14), Theme.dp(ctx, 12))
            background = Theme.rippled(ctx, Theme.SURFACE, Theme.RADIUS_CONTROL)
            isClickable = true
            isFocusable = true
            contentDescription = "Request details: ${h.label}, ${OutcomeCopy.words(h.outcome)}"
            setOnClickListener {
                go(Intent(ctx, LogDetailActivity::class.java).apply {
                    putExtra(LogDetailActivity.EXTRA_ID, h.id)
                })
            }
            setOnLongClickListener {
                val raw = AuditLog.from(ctx).line(h.id)
                if (raw == null) toast("record is gone") else {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("STMP audit", raw))
                    toast("copied raw record")
                }
                true
            }
        }
        row.addView(Theme.text(ctx, "${glyph(h.outcome)} ${OutcomeCopy.words(h.outcome)} · ${h.label.ifBlank { "—" }}",
            Theme.TEXT_LABEL_SP, colorOf(h.outcome)))
        row.addView(Theme.text(ctx, h.summary.ifBlank { "(no summary)" }, Theme.TEXT_BODY_SP))
        row.addView(Theme.text(ctx, "${timeOf(h.ts)} · ${h.durationMs}ms · Request details ›",
            Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT))
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Theme.dp(ctx, Theme.ROW_GAP_DP) }
        return row
    }

    private fun timeOf(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))

    private fun glyph(o: AuditOutcome): String = when (o) {
        AuditOutcome.APPROVED -> "✓"
        AuditOutcome.DENIED -> "✕"
        AuditOutcome.TIMEOUT -> "◷"
        AuditOutcome.DENIED_UNATTENDED -> "☾"
        AuditOutcome.NOTIFY_APPROVED -> "✓"
        AuditOutcome.NOTIFY_REJECTED -> "✕"
        AuditOutcome.POLICY_CHANGED -> "⚙"
        AuditOutcome.REJECTED_INVALID -> "✕"
        AuditOutcome.UNROUTED -> "?"
        AuditOutcome.HANDLER_FAILED -> "!"
        AuditOutcome.CLIENT_GONE -> "↩"
    }

    private fun colorOf(o: AuditOutcome): Int = when (o) {
        AuditOutcome.APPROVED, AuditOutcome.NOTIFY_APPROVED -> Theme.SUCCESS
        AuditOutcome.DENIED, AuditOutcome.NOTIFY_REJECTED, AuditOutcome.DENIED_UNATTENDED -> Theme.DANGER
        AuditOutcome.HANDLER_FAILED -> Theme.WARNING
        AuditOutcome.POLICY_CHANGED -> Theme.ACCENT
        else -> Theme.ON_SURFACE_VARIANT
    }

    private companion object { const val MAX_ROWS = 300 }
}
