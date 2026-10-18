package com.mido.phonebridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.audit.AuditHeader
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditOutcome

/**
 * The audit log list: every request the bridge handled, newest first.
 *
 * Noise (unrouted paths, rejected-before-the-gate input) is hidden by default and
 * one tap away. Nothing is ever dropped at write time — the filter is a lens, not
 * a delete (locked decision Q9).
 */
class LogActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var summaryLine: TextView
    private lateinit var noiseChip: Button

    private var hideNoise = true
    private var pluginFilter: String? = null
    private var outcomeFilter: AuditOutcome? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = UiKit.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }

        root.addView(TextView(this).apply {
            text = "Request log"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiKit.TEXT_ON_DARK)
        })

        summaryLine = TextView(this).apply {
            textSize = 13f
            setTextColor(UiKit.TEXT_MUTED)
            setPadding(0, UiKit.dp(this@LogActivity, 4), 0, UiKit.dp(this@LogActivity, 8))
        }
        root.addView(summaryLine)

        // Filter chips scroll sideways so they never wrap into a wall of buttons.
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, UiKit.dp(this@LogActivity, 8))
        }
        // The label names what the tap DOES, so it flips with the state.
        // A fixed label lies half the time: it reads "hide noise" even while
        // noise is already visible.
        noiseChip = chip("") { hideNoise = !hideNoise; render() }
        chips.addView(noiseChip)
        chips.addView(chip("all") { pluginFilter = null; outcomeFilter = null; render() })
        chips.addView(chip("denied") { outcomeFilter = AuditOutcome.DENIED; render() })
        chips.addView(chip("timeout") { outcomeFilter = AuditOutcome.TIMEOUT; render() })
        chips.addView(chip("failed") { outcomeFilter = AuditOutcome.HANDLER_FAILED; render() })
        root.addView(HorizontalScrollView(this).apply { addView(chips) })

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        render()
    }

    private fun chip(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        background = UiKit.pill(UiKit.NEUTRAL_BG)
        setTextColor(UiKit.TEXT_ON_DARK)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = UiKit.dp(this@LogActivity, 6) }
    }

    private fun render() {
        val store = AuditLog.from(applicationContext)
        val all = store.headers()
        val hiddenNoise = all.count { it.noise }
        val shown = all
            .filter { !(hideNoise && it.noise) }
            .filter { pluginFilter == null || it.plugin == pluginFilter }
            .filter { outcomeFilter == null || it.outcome == outcomeFilter }

        noiseChip.text = if (hideNoise) "show noise" else "hide noise"

        val unreadable = store.unreadableCount()
        summaryLine.text = buildString {
            append("${shown.size} of ${all.size} records")
            if (hideNoise && hiddenNoise > 0) append(" · $hiddenNoise noise hidden")
            if (unreadable > 0) append(" · ⚠ $unreadable unreadable")
        }

        listContainer.removeAllViews()
        if (shown.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (all.isEmpty()) "No requests yet."
                else "Nothing matches these filters."
                textSize = 15f
                setTextColor(UiKit.TEXT_MUTED)
                setPadding(0, UiKit.dp(this@LogActivity, 24), 0, 0)
            })
            return
        }

        // Cap the rendered rows: 7 days of history can be thousands of records and
        // the whole list is scanned with `line()` anyway. Newest-first, so the cap
        // only ever drops the tail you scrolled past.
        val capped = shown.take(MAX_ROWS)
        for (h in capped) listContainer.addView(row(h))
        if (shown.size > capped.size) {
            listContainer.addView(TextView(this).apply {
                text = "… ${shown.size - capped.size} older records not shown"
                textSize = 12f
                setTextColor(UiKit.TEXT_MUTED)
                setPadding(0, UiKit.dp(this@LogActivity, 12), 0, 0)
            })
        }
    }

    private fun row(h: AuditHeader): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, UiKit.dp(this@LogActivity, 10), 0, UiKit.dp(this@LogActivity, 10))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@LogActivity, LogDetailActivity::class.java).apply {
                    putExtra(LogDetailActivity.EXTRA_ID, h.id)
                })
            }
            setOnLongClickListener {
                val raw = AuditLog.from(applicationContext).line(h.id)
                if (raw == null) {
                    toast("record is gone")
                } else {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("PhoneBridge audit", raw))
                    toast("copied raw record")
                }
                true
            }
        }
        row.addView(TextView(this).apply {
            text = "${timeOf(h.ts)}   ${glyph(h.outcome)} ${h.outcome.name}   ${h.label.ifBlank { "—" }}"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(colorOf(h.outcome))
        })
        row.addView(TextView(this).apply {
            text = "${h.summary.ifBlank { "(no summary)" }}   ·   ${h.durationMs}ms"
            textSize = 13f
            setTextColor(if (h.noise) UiKit.TEXT_MUTED else UiKit.TEXT_ON_DARK)
            setPadding(0, UiKit.dp(this@LogActivity, 2), 0, 0)
        })
        return row
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun timeOf(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))

    private fun glyph(o: AuditOutcome): String = when (o) {
        AuditOutcome.APPROVED -> "✅"
        AuditOutcome.DENIED -> "⛔"
        AuditOutcome.TIMEOUT -> "⏱"
        AuditOutcome.DENIED_UNATTENDED -> "🌙"
        AuditOutcome.NOTIFY_APPROVED -> "🔔"
        AuditOutcome.NOTIFY_REJECTED -> "🔕"
        AuditOutcome.POLICY_CHANGED -> "⚙️"
        AuditOutcome.REJECTED_INVALID -> "🚫"
        AuditOutcome.UNROUTED -> "❔"
        AuditOutcome.HANDLER_FAILED -> "🔥"
        AuditOutcome.CLIENT_GONE -> "👻"
    }

    private fun colorOf(o: AuditOutcome): Int = when (o) {
        AuditOutcome.APPROVED, AuditOutcome.NOTIFY_APPROVED -> 0xFF81C784.toInt()
        AuditOutcome.DENIED, AuditOutcome.NOTIFY_REJECTED, AuditOutcome.DENIED_UNATTENDED -> 0xFFEF9A9A.toInt()
        AuditOutcome.HANDLER_FAILED -> 0xFFFF7043.toInt()
        // A policy change is not a request and not a failure: it is the reason the
        // records around it look the way they do. Give it its own colour so a scan
        // down the log finds the moment the gate moved.
        AuditOutcome.POLICY_CHANGED -> 0xFF4FC3F7.toInt()
        else -> UiKit.TEXT_MUTED
    }

    private companion object {
        /** Rows rendered at once. The store keeps 2000 headers; this is the view cap. */
        const val MAX_ROWS = 300
    }
}
