package com.mido.phonebridge.gate

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ApprovalGate.Row
import com.mido.phonebridge.ui.JsonCode
import com.mido.phonebridge.ui.ShellCode
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.UntrustedText
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * The ONE row renderer of the gate (WP03a section 12). It knows rows and nothing else: not Gmail,
 * not Calendar, not notifications. Every value is selectable, and a label is never printed with
 * nothing under it (an empty row is a defect, not a state).
 */
internal object GateRows {

    /** One labelled row, grouped so a screen reader reads label and value as one item. */
    fun row(ctx: Context, r: Row): View {
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(ctx, Dimens.SPACE_M) }
        }
        if (r.label.isNotBlank()) group.addView(Etched.label(ctx, r.label))
        group.addView(value(ctx, r))
        ViewCompat.setScreenReaderFocusable(group, true)
        return group
    }

    /**
     * The value view. **CODE and JSON are their own paths, not mono variants:** a command gets
     * readout colours, token-boundary breaks and the danger underline; a JSON body gets
     * pretty-printed. Any non-WARN row whose value is JSON renders as JSON without the plugin
     * tagging it. **WARN is exempt**: amber means "a consequence you must not miss", and
     * recolouring it because its text happens to be JSON would trade that meaning for tidiness.
     */
    private fun value(ctx: Context, r: Row): View {
        val p = Theme.palette(ctx)
        val asJson = r.style == Row.Style.JSON ||
            (r.style != Row.Style.WARN && r.style != Row.Style.CODE && JsonCode.looksJson(r.value))
        return when {
            r.style == Row.Style.CODE ->
                Readout.create(ctx, ShellCode.renderForReadout(r.value, p), Readout.Kind.BODY, softWrap = true).root
            asJson -> Readout.create(ctx, JsonCode.render(r.value, JsonCode.readoutInk(p)), Readout.Kind.BODY, softWrap = true).root
            else -> {
                val (role, color) = when (r.style) {
                    Row.Style.MONO -> TypeRole.MONO_BODY to p.ink
                    Row.Style.WARN -> TypeRole.MONO_BODY to p.cautionText
                    else -> TypeRole.BODY to p.ink
                }
                Type.text(ctx, UntrustedText.mark(r.value, p.cautionText), role, color).apply {
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
            }
        }
    }

    /** A plain panel titled [title] holding [rows], or null when there is nothing to show. */
    fun panel(ctx: Context, title: String, rows: List<Row>, sections: List<ApprovalGate.Section> = emptyList()): LinearLayout? {
        val visibleSections = sections.map { it.title to it.rows.filter { r -> r.value.isNotBlank() } }
            .filter { it.second.isNotEmpty() }
        val visibleRows = rows.filter { it.value.isNotBlank() }
        if (visibleRows.isEmpty() && visibleSections.isEmpty()) return null
        val panel = Panel.create(ctx)
        panel.addView(SectionHeader.create(ctx, title, inPanel = true))
        for ((sectionTitle, sectionRows) in visibleSections) {
            panel.addView(sectionHeading(ctx, sectionTitle))
            sectionRows.forEach { panel.addView(row(ctx, it)) }
        }
        visibleRows.forEach { panel.addView(row(ctx, it)) }
        return panel
    }

    private fun sectionHeading(ctx: Context, title: String): TextView =
        SectionHeader.create(ctx, title, inPanel = true).apply {
            setPadding(paddingLeft, Theme.dp(ctx, Dimens.SPACE_L), paddingRight, paddingBottom)
        }

    /** The request rows of [view] for the "Request details" panel, without the promoted headline. */
    fun requestRows(view: ApprovalGate.ApprovalView, headlineIndex: Int?): List<Row> = buildList {
        view.destructive.filter { it.value.isNotBlank() }
            .forEachIndexed { i, r -> if (i != headlineIndex) add(r) }
        // A scoped read must show its scope: "count=3, package=com.whatsapp", never a bare verb.
        if (view.filters.isNotEmpty()) {
            add(Row(GateCopy.FILTER, view.filters.entries.joinToString(", ") { "${it.key}=${it.value}" }, Row.Style.MONO))
        }
        val pluginHasReply = view.destructive.any { it.label == GateCopy.REPLY }
        if (!view.replyText.isNullOrBlank() && !pluginHasReply) add(Row(GateCopy.REPLY, view.replyText, Row.Style.LONG))
        // Other buttons the same notification offers: the options are the menu, the action is the pick.
        if (view.options.isNotEmpty()) add(Row(GateCopy.OTHER_ACTIONS, view.options.joinToString(" · ")))
    }

    /** The notification behind the request, as ordinary rows; empty for a route with none. */
    fun notificationRows(view: ApprovalGate.ApprovalView): List<Row> = buildList {
        val head = view.notifTitle.ifBlank { view.appLabel }
        if (head.isNotBlank()) add(Row("TITLE", head))
        if (view.notifSender.isNotBlank()) add(Row("FROM", view.notifSender))
        if (view.notifText.isNotBlank()) add(Row("BODY", view.notifText, Row.Style.LONG))
        if (view.messageCount > 1) add(Row(GateCopy.MESSAGES, GateCopy.messages(view.messageCount), Row.Style.WARN))
    }
}
