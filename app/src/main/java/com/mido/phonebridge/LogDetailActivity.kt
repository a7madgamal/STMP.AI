package com.mido.phonebridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.audit.AuditLog
import org.json.JSONObject

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

        val pad = UiKit.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val raw = AuditLog.from(applicationContext).line(id)
        if (raw == null) {
            content.addView(TextView(this).apply {
                text = "This record is no longer stored (aged out, or cleared)."
                textSize = 15f
                setTextColor(UiKit.TEXT_MUTED)
            })
            setContentView(root)
            return
        }

        val o = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            content.addView(TextView(this).apply {
                text = "Stored line is not valid JSON:\n\n$raw"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(0xFFFF7043.toInt())
                setTextIsSelectable(true)
            })
            setContentView(root)
            return
        }

        fun header(t: String): TextView = TextView(this).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xFF78909C.toInt())
            setPadding(0, UiKit.dp(this@LogDetailActivity, 18), 0, UiKit.dp(this@LogDetailActivity, 4))
        }

        fun labelled(label: String, value: String, mono: Boolean = false, color: Int = UiKit.TEXT_ON_DARK) {
            if (value.isBlank()) return
            content.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(0xFFFF7043.toInt())
                setPadding(0, UiKit.dp(this@LogDetailActivity, 8), 0, 0)
            })
            content.addView(TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(color)
                setTextIsSelectable(true)
                if (mono) typeface = Typeface.MONOSPACE
            })
        }

        val outcome = o.optString("outcome")
        content.addView(TextView(this).apply {
            text = "$outcome   ${o.optString("label").ifBlank { "—" }}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiKit.TEXT_ON_DARK)
        })
        content.addView(TextView(this).apply {
            text = "${o.optLong("durationMs")}ms   ·   HTTP ${o.optInt("responseStatus")}   ·   " +
                (if (o.optBoolean("noise")) "noise" else "signal")
            textSize = 13f
            setTextColor(UiKit.TEXT_MUTED)
            setPadding(0, UiKit.dp(this@LogDetailActivity, 4), 0, 0)
        })

        content.addView(header("who"))
        labelled("REQUESTER", o.optString("requester"))
        labelled("REMOTE", o.optString("remote"), mono = true)
        labelled("ORIGIN", o.optString("origin"))
        labelled(
            "UNROUTED" ,
            if (o.optString("plugin").isBlank()) "yes — no route matched, so no plugin or verb" else ""
        )

        content.addView(header("what"))
        labelled("SUMMARY", o.optString("summary"))
        labelled("ROUTE", o.optString("route"), mono = true)
        labelled("LABEL", o.optString("label"), mono = true)
        labelled("PATH", o.optString("path"), mono = true)

        val query = o.optJSONObject("query")
        if (query != null && query.length() > 0) {
            labelled("QUERY", query.toString(), mono = true)
        }

        val err = o.optString("error", "")
        if (err.isNotBlank()) labelled("ERROR", err, mono = true, color = 0xFFFF7043.toInt())

        content.addView(header("request"))
        val headers = o.optJSONObject("requestHeaders")
        if (headers != null && headers.length() > 0) {
            labelled("HEADERS", headers.toString(2), mono = true)
        }
        val reqBody = o.optString("requestBody")
        labelled("BODY", if (reqBody.isBlank()) "(empty)" else reqBody, mono = true)

        content.addView(header("response"))
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
                color = 0xFFFFB74D.toInt()
            )
        }

        val decision = o.optJSONObject("decision")
        if (decision != null) {
            content.addView(header("decision"))
            labelled("MODE", decision.optString("mode"))
            labelled("SOURCE", decision.optString("source"))
            val rule = decision.optString("rule", "")
            if (rule.isNotBlank()) labelled("RULE", rule, mono = true)
            val enq = if (decision.isNull("enqueuedAt")) null else decision.optLong("enqueuedAt")
            val shown = if (decision.isNull("shownAt")) null else decision.optLong("shownAt")
            val decided = if (decision.isNull("decidedAt")) null else decision.optLong("decidedAt")
            if (enq != null) labelled("ENQUEUED", enq.toString(), mono = true)
            if (shown != null) labelled("SHOWN", shown.toString(), mono = true)
            if (decided != null) labelled("DECIDED", decided.toString(), mono = true)
            if (shown != null && decided != null) {
                labelled("HUMAN SAW IT FOR", "${decided - shown}ms")
            }
            labelled("QUEUE DEPTH", decision.optInt("queueDepth").toString())
        }

        content.addView(header("raw"))
        content.addView(TextView(this).apply {
            text = raw
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(UiKit.TEXT_MUTED)
            setTextIsSelectable(true)
            setPadding(0, UiKit.dp(this@LogDetailActivity, 4), 0, 0)
        })

        val copy = Button(this).apply {
            text = "COPY RAW RECORD"
            background = UiKit.pill(UiKit.NEUTRAL_BG)
            setTextColor(UiKit.TEXT_ON_DARK)
            setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("PhoneBridge audit", raw))
                Toast.makeText(this@LogDetailActivity, "copied", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(copy, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(this@LogDetailActivity, 16) })

        setContentView(root)
    }
}
