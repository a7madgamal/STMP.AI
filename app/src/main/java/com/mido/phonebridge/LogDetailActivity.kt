package com.mido.phonebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.mido.phonebridge.audit.AuditBlobs
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.ui.AuditCopy
import com.mido.phonebridge.ui.DecisionModel
import com.mido.phonebridge.ui.JsonCode
import com.mido.phonebridge.ui.LogDetail
import com.mido.phonebridge.ui.LogDetailModel
import com.mido.phonebridge.ui.LogTime
import com.mido.phonebridge.ui.PluginLabel
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.Toasts
import com.mido.phonebridge.ui.kit.VerbChip
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole
import java.time.ZoneId
import java.util.Locale

/**
 * One request log record, read as labelled sections plus the raw line, so the screen can never
 * disagree with the file on disk. Every time is local with the zone named, and an absent fact is
 * omitted or written as a sentence, never as `null` (WP08 4.1).
 */
class LogDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "auditId"
    }

    private lateinit var p: Palette
    private lateinit var zone: ZoneId
    private val locale: Locale = Locale.getDefault()
    private var is24 = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: run {
            finish()
            return
        }
        p = Theme.palette(this)
        zone = LogTime.zone()
        is24 = android.text.format.DateFormat.is24HourFormat(this)
        val content = subScreen("Request").content

        val raw = AuditLog.from(applicationContext).line(id)
        when (val parsed = LogDetailModel.parse(raw)) {
            LogDetail.Missing -> content.addView(state(
                StateBlock.Kind.EMPTY, "This record is gone", "It aged out of the log or the log was cleared.",
            ))
            is LogDetail.Unreadable -> {
                content.addView(state(StateBlock.Kind.ERROR, "This record can't be read", "The stored line is not valid JSON."))
                content.addView(Readout.create(this, parsed.raw, Readout.Kind.BODY).root)
                content.addView(copyKey(parsed.raw), full().apply { topMargin = dp(Dimens.SPACE_L) })
            }
            is LogDetail.Ok -> renderOk(content, parsed.model, checkNotNull(raw))
        }
    }

    private fun dp(v: Int) = Theme.dp(this, v)

    private fun full() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun state(kind: StateBlock.Kind, title: String, body: String): View =
        StateBlock.create(this, kind, title, body).also {
            it.layoutParams = full().apply { bottomMargin = dp(Dimens.MODULE_GAP) }
        }

    private fun clock(ts: Long) = LogTime.clock(ts, zone, locale, is24)
    private fun dateTime(ts: Long) = LogTime.dateTime(ts, zone, locale, is24)

    private fun renderOk(content: LinearLayout, m: LogDetailModel, raw: String) {
        val look = m.outcome?.let { AuditCopy.outcome(it) }
        val word = look?.word ?: AuditCopy.modeWords(m.outcomeRaw)
        val tone = look?.tone ?: AuditCopy.Tone.NEUTRAL

        header(content, m, word, tone, look?.allowsChip ?: false)
        if (!m.isPolicyChange) who(content, m)
        what(content, m)
        if (!m.isPolicyChange) {
            request(content, m)
            response(content, m)
        }
        // A gate change and an unrouted request never went through the gate: no Decision panel.
        if (!m.isPolicyChange && m.plugin != null) m.decision?.let { decision(content, m, it) }

        content.addView(SectionHeader.create(this, "Raw record"), full())
        content.addView(
            Type.text(this, "Formatted for reading. Copy gives the stored line exactly.", TypeRole.CAPTION, p.etched),
            full().apply { bottomMargin = dp(Dimens.SPACE_S) },
        )
        content.addView(Readout.create(this, JsonCode.render(JsonCode.present(raw), JsonCode.readoutInk(p)), Readout.Kind.LOG).root)
        content.addView(copyKey(raw), full().apply { topMargin = dp(Dimens.SPACE_L) })
    }

    // --- sections ------------------------------------------------------------------------------

    private fun section(content: LinearLayout, title: String?): LinearLayout {
        if (title != null) content.addView(SectionHeader.create(this, title), full())
        val panel = Panel.create(this)
        content.addView(panel, full().apply { bottomMargin = dp(Dimens.MODULE_GAP) })
        return panel
    }

    private fun header(content: LinearLayout, m: LogDetailModel, word: String, tone: AuditCopy.Tone, chip: Boolean) {
        val panel = section(content, null)
        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(Led(this).apply {
            state = tone.led()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        line1.addView(
            Type.text(this, word, TypeRole.TITLE, tone.color(p)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(Dimens.SPACE_S)
                marginEnd = dp(Dimens.SPACE_S)
            },
        )
        val verb = m.verb
        if (chip && verb != null) {
            line1.addView(VerbChip.create(this, verb), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(Dimens.SPACE_S) })
        }
        line1.addView(Type.text(this, clock(m.ts), TypeRole.READOUT, p.etched))
        panel.addView(line1, full())

        val headline = when {
            m.plugin != null -> PluginLabel.of(m.plugin)
            m.isPolicyChange -> "Gate change"
            else -> "Unrouted request"
        }
        panel.addView(Type.text(this, headline, TypeRole.ROW_LABEL, p.ink), full().apply { topMargin = dp(Dimens.SPACE_S) })
        if (m.isPolicyChange) {
            panel.addView(Type.text(this, "Changed on the phone", TypeRole.DETAIL, p.etched), full())
        }
        val facts = buildList {
            add(dateTime(m.ts))
            AuditCopy.duration(m.durationMs, locale)?.let { add(it) }
            if (m.responseStatus > 0) add("HTTP ${m.responseStatus}")
            if (m.noise) add("hidden by default as noise")
        }
        panel.addView(Type.text(this, facts.joinToString(" · "), TypeRole.DETAIL, p.etched), full().apply { topMargin = dp(2) })
        panel.addView(
            Type.text(
                this, "Times are shown in your phone's time zone (${LogTime.zoneName(m.ts, zone, locale)}).",
                TypeRole.CAPTION, p.etched,
            ),
            full().apply { topMargin = dp(Dimens.SPACE_XS) },
        )
    }

    private fun who(content: LinearLayout, m: LogDetailModel) {
        val origin = m.origin?.let { AuditCopy.originWords(it) }
        val rows = mutableListOf<View>()
        m.requester?.let { rows += keyValue("Caller", it) }
        m.remote?.let { rows += keyValue("Address", it, mono = true) }
        origin?.let { rows += keyValue("From", it) }
        if (m.plugin == null) {
            rows += Type.text(
                this, "No route matched this path, so there is no plugin or action.", TypeRole.DETAIL, p.etched,
            )
        }
        if (rows.isEmpty()) return
        addSpaced(section(content, "Who"), rows)
    }

    private fun what(content: LinearLayout, m: LogDetailModel) {
        val rows = mutableListOf<View>()
        val summary = m.summary?.let { s -> m.outcome?.let { AuditCopy.summary(it, s) } ?: s }
        summary?.let { rows += keyValue("Summary", it) }
        m.plugin?.let { rows += keyValue("Plugin", PluginLabel.of(it)) }
        if (!m.isPolicyChange) m.verb?.let { rows += keyValue("Action", AuditCopy.verbWords(it).replaceFirstChar { c -> c.uppercase() }) }
        m.route?.let { rows += keyValue("Route", it, mono = true) }
        m.label?.let { rows += keyValue("Id", it, mono = true) }
        m.path?.let { rows += keyValue("Path", it, mono = true) }
        m.query?.let { rows += keyValue("Query", it, mono = true) }
        m.error?.let { rows += keyValue("Error", it, mono = true, color = p.cautionText) }
        if (rows.isEmpty()) return
        addSpaced(section(content, "What"), rows)
    }

    private fun request(content: LinearLayout, m: LogDetailModel) {
        if (m.headers == null && m.requestBody == null && !m.requestAbsent) return
        val panel = section(content, "Request")
        m.headers?.let {
            panel.addView(Etched.label(this, "Headers"), full())
            panel.addView(Readout.create(this, it, Readout.Kind.LOG).root)
        }
        body(panel, m.requestBody, m.requestAbsent, "Not stored. This request's body is deliberately not kept.", "Empty.", m.requestOriginal)
    }

    private fun response(content: LinearLayout, m: LogDetailModel) {
        if (m.responseStatus <= 0 && m.responseBody == null && !m.responseAbsent) return
        val panel = section(content, "Response")
        if (m.responseStatus > 0) panel.addView(keyValue("Status", "HTTP ${m.responseStatus}"), full())
        val blank = if (m.responseStatus > 0) "Empty." else "No response was sent."
        body(panel, m.responseBody, m.responseAbsent, "Not stored. This response is deliberately not kept.", blank, m.responseOriginal)
    }

    private fun body(panel: LinearLayout, text: String?, absent: Boolean, absentSentence: String, blank: String, original: Int?) {
        panel.addView(Etched.label(this, "Body"), full().apply { topMargin = dp(Dimens.SPACE_S) })
        when {
            absent -> panel.addView(Type.text(this, absentSentence, TypeRole.BODY, p.ink), full())
            text == null -> panel.addView(Type.text(this, blank, TypeRole.BODY, p.ink), full())
            else -> {
                val shown: CharSequence =
                    if (JsonCode.looksJson(text)) JsonCode.render(text, JsonCode.readoutInk(p)) else text
                panel.addView(Readout.create(this, shown, Readout.Kind.LOG).root)
            }
        }
        if (original != null && !absent) {
            panel.addView(
                Type.text(
                    this,
                    "Stored body capped at ${AuditBlobs.FIELD_CAP / 1024} KiB. The original was " +
                        "${AuditCopy.bytes(original, locale)}.",
                    TypeRole.DETAIL, p.cautionText,
                ),
                full(),
            )
        }
    }

    private fun decision(content: LinearLayout, m: LogDetailModel, d: DecisionModel) {
        val rows = mutableListOf<View>()
        d.mode?.let { rows += keyValue("Gate mode", AuditCopy.modeWords(it)) }
        d.source?.let { rows += keyValue("Why", AuditCopy.sourceWords(it)) }
        d.globalMode?.let { rows += keyValue("Gate mode before", AuditCopy.modeWords(it)) }
        d.rule?.let { rows += keyValue("Exception", AuditCopy.ruleWords(it)) }
        val decided = d.decidedAt != null
        if (!m.isPolicyChange) {
            if (decided || d.enqueuedAt != null) {
                rows += keyValue("Queued", d.enqueuedAt?.let { dateTime(it) } ?: "Did not wait in the queue.")
            }
            if (decided || d.shownAt != null) {
                rows += keyValue("Shown", d.shownAt?.let { dateTime(it) } ?: "Nothing was shown.")
            }
        }
        rows += keyValue("Decided", d.decidedAt?.let { dateTime(it) } ?: "No decision was made.")
        val shownAt = d.shownAt
        val decidedAt = d.decidedAt
        if (shownAt != null && decidedAt != null) {
            AuditCopy.duration(decidedAt - shownAt, locale)?.let { rows += keyValue("You saw it for", it) }
        }
        if (d.queueDepth > 0) {
            rows += keyValue("Waiting behind it", if (d.queueDepth == 1) "1 request" else "${d.queueDepth} requests")
        }
        addSpaced(section(content, "Decision"), rows)
    }

    // --- pieces --------------------------------------------------------------------------------

    private fun addSpaced(panel: LinearLayout, rows: List<View>) {
        rows.forEachIndexed { i, v ->
            panel.addView(v, full().apply { if (i > 0) topMargin = dp(Dimens.SPACE_M) })
        }
    }

    /** An etched label with its value, one focus item for a screen reader. Screen-private. */
    private fun keyValue(label: String, value: String, mono: Boolean = false, color: Int = p.ink): View {
        val valueView = Type.text(this, value, if (mono) TypeRole.MONO_BODY else TypeRole.BODY, color).apply {
            setTextIsSelectable(true)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Etched.label(this@LogDetailActivity, label), full())
            addView(valueView, full())
            ViewCompat.setScreenReaderFocusable(this, true)
            contentDescription = "$label, $value"
        }
    }

    private fun copyKey(raw: String): View =
        KeyButton.create(
            this, "Copy raw record", KeyButton.Kind.PANEL, KeyButton.Size.REGULAR,
            describe = "Copy the stored record to the clipboard",
        ) {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("STMP request log", raw))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toasts.show(this, "Copied")
        }
}
