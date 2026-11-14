package com.mido.phonebridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.audit.AuditField
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.ui.JsonCode
import com.mido.phonebridge.ui.LogTime
import com.mido.phonebridge.ui.OutcomeCopy
import com.mido.phonebridge.ui.Theme
import org.json.JSONObject
import java.util.Locale

/**
 * One audit record, verbatim.
 *
 * Reads the stored JSON line and renders it as labelled sections — and renders the
 * raw line too, so the screen can never disagree with the file on disk. The
 * truncation notice is explicit when a body was capped: never a silent cut.
 */
class LogDetailActivity : Activity() {

    companion object {
        const val EXTRA_ID = "auditId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID) ?: run {
            finish()
            return
        }

        val pad = Theme.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Theme.sheet(Theme.BG)
        }
        val scroll = com.mido.phonebridge.ui.OverflowScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.setContent(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val zone = LogTime.zone()
        val locale = Locale.getDefault()
        val is24 = LogTime.is24Hour(this)

        val raw = AuditLog.from(applicationContext).line(id)
        if (raw == null) {
            content.addView(Theme.text(
                this, "This record is no longer stored (aged out, or cleared).", 15f, Theme.ON_SURFACE_VARIANT
            ))
            setContentView(Theme.inset(root))
            return
        }

        val o = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            content.addView(Theme.text(
                this, "Stored line is not valid JSON:\n\n$raw", 12f, Theme.DANGER, mono = true
            ).apply { setTextIsSelectable(true) })
            setContentView(Theme.inset(root))
            return
        }

        // Absence has to be read as absence: `optString` answers the string "null" for a JSON
        // null on Android, which printed `ERROR null` on every clean record. See `AuditField`.
        fun text(o: JSONObject, key: String): String? = AuditField.text(o, key)

        fun labelled(label: String, value: String?, mono: Boolean = false, color: Int = Theme.ON_SURFACE) {
            if (value.isNullOrBlank()) return
            content.addView(Theme.text(this, label, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT).apply {
                setPadding(0, Theme.dp(this@LogDetailActivity, 8), 0, 0)
            })
            // **JSON renders rich wherever it appears**, without every call site having to
            // know it is JSON. A body does not arrive with a type tag — `requestBody` is just
            // a string — so the detector decides. Rendering only touches the copy on screen;
            // the stored audit line is untouched.
            val view = if (mono && JsonCode.looksJson(value)) {
                Theme.text(this, "", Theme.TEXT_CAPTION_SP, color).apply {
                    text = JsonCode.render(value)
                    typeface = Theme.monoFont(this@LogDetailActivity)
                    setTextIsSelectable(true)
                }
            } else {
                Theme.text(this, value, Theme.TEXT_BODY_SP, color, mono = mono).apply {
                    setTextIsSelectable(true)
                }
            }
            content.addView(view)
        }

        // The stored value is the enum's identifier; the screen shows a sentence. A record whose
        // outcome is absent or unknown keeps its raw text rather than being called "Unknown".
        val outcomeRaw = text(o, "outcome")
        val outcome = outcomeRaw?.let { raw ->
            AuditOutcome.values().firstOrNull { it.name == raw }
        }
        val headline = outcome?.let(OutcomeCopy::words) ?: outcomeRaw ?: "Record"
        content.addView(Theme.text(this, "$headline   ${text(o, "label") ?: "—"}", 20f).apply {
            typeface = Theme.bodyFont(this@LogDetailActivity)
        })
        content.addView(Theme.text(
            this,
            "${o.optLong("durationMs")}ms   ·   HTTP ${o.optInt("responseStatus")}   ·   " +
                (if (o.optBoolean("noise")) "noise" else "signal"),
            13f, Theme.ON_SURFACE_VARIANT
        ).apply { setPadding(0, Theme.dp(this@LogDetailActivity, 4), 0, 0) })
        // When it happened, in the phone's own format and zone. A record is the one place a
        // bare millisecond count is unreadable: the log list groups by day and this screen has
        // to agree with it.
        content.addView(Theme.text(
            this, LogTime.dateTime(o.optLong("ts"), LogTime.zone(), locale, LogTime.is24Hour(this)),
            13f, Theme.ON_SURFACE_VARIANT,
        ).apply { setPadding(0, Theme.dp(this@LogDetailActivity, 2), 0, 0) })

        content.addView(Theme.sectionHeader(this, "who"))
        labelled("REQUESTER", text(o, "requester"))
        labelled("REMOTE", text(o, "remote"), mono = true)
        labelled("ORIGIN", text(o, "origin"))
        labelled(
            "UNROUTED",
            if (text(o, "plugin") == null) "yes — no route matched, so no plugin or verb" else ""
        )

        content.addView(Theme.sectionHeader(this, "what"))
        labelled("SUMMARY", text(o, "summary"))
        labelled("ROUTE", text(o, "route"), mono = true)
        labelled("LABEL", text(o, "label"), mono = true)
        labelled("PATH", text(o, "path"), mono = true)

        val query = o.optJSONObject("query")
        if (query != null && query.length() > 0) {
            labelled("QUERY", query.toString(), mono = true)
        }

        val err = text(o, "error")
        if (err != null) labelled("ERROR", err, mono = true, color = Theme.DANGER)

        content.addView(Theme.sectionHeader(this, "request"))
        val headers = o.optJSONObject("requestHeaders")
        if (headers != null && headers.length() > 0) {
            labelled("HEADERS", headers.toString(2), mono = true)
        }
        val reqBody = o.optString("requestBody")
        labelled("BODY", if (reqBody.isBlank()) "(empty)" else reqBody, mono = true)

        content.addView(Theme.sectionHeader(this, "response"))
        val respBody = o.optString("responseBody")
        labelled("BODY", if (respBody.isBlank()) "(none — no handler ran)" else respBody, mono = true)

        // Truncation is stated, never implied: a capped body shows its real size.
        val trunc = o.optJSONObject("truncated")
        if (trunc != null && trunc.length() > 0) {
            val parts = mutableListOf<String>()
            if (trunc.has("requestBody")) parts += "request ${trunc.optInt("requestBody")} bytes original"
            if (trunc.has("responseBody")) parts += "response ${trunc.optInt("responseBody")} bytes original"
            labelled(
                "TRUNCATED", "stored body capped at 32 KiB — " + parts.joinToString(", "),
                color = Theme.WARNING
            )
        }

        val decision = o.optJSONObject("decision")
        if (decision != null) {
            content.addView(Theme.sectionHeader(this, "decision"))
            labelled("MODE", text(decision, "mode"))
            labelled("SOURCE", text(decision, "source"))
            val rule = text(decision, "rule")
            if (rule != null) labelled("RULE", rule, mono = true)
            val enq = AuditField.millis(decision, "enqueuedAt")
            val shown = AuditField.millis(decision, "shownAt")
            val decided = AuditField.millis(decision, "decidedAt")
            // Times, not millisecond counts: these three are moments on a clock, and the whole
            // screen is supposed to say WHEN something happened.
            fun clockAt(ts: Long) = LogTime.clock(ts, zone, locale, is24)
            if (enq != null) labelled("ENQUEUED", clockAt(enq), mono = true)
            if (shown != null) labelled("SHOWN", clockAt(shown), mono = true)
            if (decided != null) labelled("DECIDED", clockAt(decided), mono = true)
            if (shown != null && decided != null) {
                labelled("HUMAN SAW IT FOR", "${decided - shown}ms")
            }
            labelled("QUEUE DEPTH", decision.optInt("queueDepth").toString())
        }

        content.addView(Theme.sectionHeader(this, "raw"))
        content.addView(Theme.text(this, raw, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT, mono = true).apply {
            setTextIsSelectable(true)
            setPadding(0, Theme.dp(this@LogDetailActivity, 4), 0, 0)
        })

        content.addView(
            Theme.button(this, "COPY RAW RECORD", Theme.ButtonStyle.NEUTRAL) {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("PhoneBridge audit", raw))
                Toast.makeText(this@LogDetailActivity, "copied", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(this@LogDetailActivity, 16) }
        )

        setContentView(Theme.inset(root))
    }
}
