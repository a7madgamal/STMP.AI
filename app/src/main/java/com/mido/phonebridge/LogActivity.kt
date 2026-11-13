package com.mido.phonebridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.chip.ChipGroup
import com.mido.phonebridge.audit.AuditHeader
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.AuditOutcome
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.ui.AuditCopy
import com.mido.phonebridge.ui.UntrustedText
import com.mido.phonebridge.ui.LogTime
import com.mido.phonebridge.ui.PluginLabel
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.Chips
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.RuleLinearLayout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.Toasts
import com.mido.phonebridge.ui.kit.TopBar
import com.mido.phonebridge.ui.kit.VerbChip
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole
import java.util.Locale

/** The three parts of a sub-screen: the root to show, and the column that holds its content. */
internal class SubScreen(val root: LinearLayout, val scroll: ScrollView, val content: LinearLayout)

/**
 * The frame every audit and policy sub-screen shares (WP08 5.1): the top bar with a back arrow,
 * then a scroll area with 20dp gutters that clears the gesture bar. The top bar pads itself by the
 * status-bar inset, so the content does not (T12).
 */
internal fun AppCompatActivity.subScreen(title: String, action: View? = null): SubScreen {
    val p = Theme.palette(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(p.body)
    }
    root.addView(TopBar.create(this, title, action))
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            Theme.dp(this@subScreen, Dimens.GUTTER), Theme.dp(this@subScreen, Dimens.SPACE_M),
            Theme.dp(this@subScreen, Dimens.GUTTER), Theme.dp(this@subScreen, Dimens.SPACE_XL),
        )
    }
    val scroll = ScrollView(this).apply {
        clipToPadding = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    BottomInset.apply(scroll)
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
    return SubScreen(root, scroll, content)
}

/** The word colour of an outcome tone. */
internal fun AuditCopy.Tone.color(p: Palette): Int = when (this) {
    AuditCopy.Tone.OK -> p.okText
    AuditCopy.Tone.CAUTION -> p.cautionText
    AuditCopy.Tone.NEUTRAL -> p.ink
    AuditCopy.Tone.NOISE -> p.etched
}

/** The LED of an outcome tone: only a good result or a fault lights it. */
internal fun AuditCopy.Tone.led(): Led.State = when (this) {
    AuditCopy.Tone.OK -> Led.State.OK
    AuditCopy.Tone.CAUTION -> Led.State.CAUTION
    else -> Led.State.OFF
}

/**
 * The request log: every request the bridge handled, newest first, grouped by local day.
 *
 * Noise (unrouted paths, rejected-before-the-gate input) is hidden by default and one chip away.
 * Nothing is dropped at write time: the filter is a lens, not a delete (locked decision Q9).
 */
class LogActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var summaryLine: TextView
    private lateinit var chipHost: LinearLayout
    private lateinit var listContainer: LinearLayout

    private lateinit var allChip: Chips.FilterChip
    private lateinit var noiseChip: Chips.FilterChip
    private var outcomeChips: List<Pair<AuditOutcome, Chips.FilterChip>> = emptyList()

    private var hideNoise = true
    private var outcomeFilter: AuditOutcome? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Request log"
        val p = Theme.palette(this)
        content = subScreen("Request log").content

        summaryLine = Type.text(this, "", TypeRole.DETAIL, p.etched).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(summaryLine, wrap())
        chipHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipHost.addView(buildChips(), wrap())
        content.addView(chipHost, wrap().apply { topMargin = Theme.dp(this@LogActivity, Dimens.SPACE_S) })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer, wrap())
        render()
    }

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    /** Built once; [syncChips] only repaints their checked state, so the tapped chip keeps focus. */
    private fun buildChips(): ChipGroup {
        val group = Chips.group(this, "Filter the request log")
        fun add(label: String, onToggle: (Boolean) -> Unit): Chips.FilterChip =
            Chips.filter(this, label, false, onToggle).also { group.addView(it) }
        allChip = add("All") { outcomeFilter = null; render() }
        outcomeChips = listOf(
            "Denied" to AuditOutcome.DENIED,
            "Timed out" to AuditOutcome.TIMEOUT,
            "Failed" to AuditOutcome.HANDLER_FAILED,
        ).map { (label, outcome) ->
            outcome to add(label) { on -> outcomeFilter = if (on) outcome else null; render() }
        }
        noiseChip = add("Show noise") { hideNoise = !hideNoise; render() }
        return group
    }

    private fun syncChips() {
        allChip.checked = outcomeFilter == null
        for ((outcome, chip) in outcomeChips) chip.checked = outcomeFilter == outcome
        noiseChip.checked = !hideNoise
    }

    private fun render() {
        val p = Theme.palette(this)
        val store = AuditLog.from(applicationContext)
        val all = store.headers()
        val hiddenNoise = all.count { it.noise }
        val shown = all
            .filter { !(hideNoise && it.noise) }
            .filter { matchesFilter(it.outcome) }
        val unreadable = store.unreadableCount()

        summaryLine.text = SpannableStringBuilder().apply {
            append("${shown.size} of ${all.size} records")
            if (hideNoise && hiddenNoise > 0) append(" · $hiddenNoise noise hidden")
            if (unreadable > 0) {
                append(" · ")
                val start = length
                append("$unreadable unreadable")
                setSpan(ForegroundColorSpan(p.cautionText), start, length, 0)
            }
        }

        syncChips()

        listContainer.removeAllViews()
        when {
            all.isEmpty() -> listContainer.addView(
                state("No requests yet", "Requests from your agent appear here as the bridge handles them."),
            )
            shown.isEmpty() && outcomeFilter != null -> listContainer.addView(
                state(
                    "Nothing matches this filter", "Choose All to see every record.",
                    KeyButton.Spec("Show all", "Show every record") { outcomeFilter = null; render() },
                ),
            )
            shown.isEmpty() -> listContainer.addView(
                state(
                    "Only noise so far",
                    "$hiddenNoise records are hidden because they were unknown paths or bad requests. " +
                        "Turn on Show noise to see them.",
                ),
            )
            else -> renderList(shown, p)
        }
    }

    private fun state(title: String, body: String, action: KeyButton.Spec? = null): View =
        StateBlock.create(this, StateBlock.Kind.EMPTY, title, body, action).also {
            it.layoutParams = wrap().apply { topMargin = Theme.dp(this@LogActivity, Dimens.SPACE_L) }
        }

    private fun renderList(shown: List<AuditHeader>, p: Palette) {
        val zone = LogTime.zone()
        val locale = Locale.getDefault()
        val is24 = DateFormat.is24HourFormat(this)
        val now = nowMs()
        // Cap the rendered rows: 7 days of history can be thousands of records. Newest first, so the
        // cap only drops the tail the person scrolled past.
        val capped = shown.take(MAX_ROWS)
        var heading: String? = null
        var panel: LinearLayout? = null
        for (h in capped) {
            val day = LogTime.dayHeading(h.ts, now, zone, locale)
            if (day != heading || panel == null) {
                heading = day
                listContainer.addView(SectionHeader.create(this, day), wrap())
                panel = Panel.create(this, padded = false)
                listContainer.addView(panel, wrap())
            }
            panel.addView(row(h, LogTime.clock(h.ts, zone, locale, is24), locale, p))
        }
        if (shown.size > capped.size) {
            listContainer.addView(
                Type.text(this, "${shown.size - capped.size} older records are not shown.", TypeRole.DETAIL, p.etched),
                wrap().apply { topMargin = Theme.dp(this@LogActivity, Dimens.SPACE_M) },
            )
        }
    }

    /** "Denied" covers every refusal (D50), not only the plain DENIED outcome. */
    private fun matchesFilter(o: AuditOutcome): Boolean = when (outcomeFilter) {
        null -> true
        AuditOutcome.DENIED -> AuditCopy.isDenial(o)
        else -> o == outcomeFilter
    }

    private fun row(h: AuditHeader, clock: String, locale: Locale, p: Palette): View {
        val look = AuditCopy.outcome(h.outcome)
        val verb = Verb.values().firstOrNull { it.name.equals(h.verb, true) }
        val chipVerb = verb.takeIf { look.allowsChip }
        val summary = AuditCopy.summary(h.outcome, h.summary)
        val plugin = PluginLabel.of(h.plugin)
        val detail = listOfNotNull(plugin.takeIf { it.isNotBlank() }, AuditCopy.duration(h.durationMs, locale))
            .joinToString(" · ")

        val row = RuleLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = Theme.dp(this@LogActivity, 56)
            setPadding(
                Theme.dp(this@LogActivity, Dimens.MODULE_PAD), Theme.dp(this@LogActivity, 10),
                Theme.dp(this@LogActivity, Dimens.MODULE_PAD), Theme.dp(this@LogActivity, 10),
            )
            isClickable = true
            isFocusable = true
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(p.ripple), null, null,
            )
            setOnClickListener {
                startActivity(Intent(this@LogActivity, LogDetailActivity::class.java).apply {
                    putExtra(LogDetailActivity.EXTRA_ID, h.id)
                })
            }
            setOnLongClickListener { copyRaw(h.id); true }
            contentDescription = listOfNotNull(
                look.word, verb?.let { AuditCopy.verbWords(it) }, UntrustedText.escape(summary).takeIf { it.isNotBlank() },
                plugin.takeIf { it.isNotBlank() }, AuditCopy.duration(h.durationMs, locale), "at $clock",
            ).joinToString(", ")
            ViewCompat.replaceAccessibilityAction(
                this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                "Copy raw record", null,
            )
        }

        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        line1.addView(Led(this).apply {
            state = look.tone.led()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Theme.dp(this, 16), Theme.dp(this, 16)).apply {
            topMargin = Theme.dp(this@LogActivity, 2)
        })
        line1.addView(
            Type.text(this, look.word, TypeRole.BODY_STRONG, look.tone.color(p)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Theme.dp(this@LogActivity, Dimens.SPACE_S)
                marginEnd = Theme.dp(this@LogActivity, Dimens.SPACE_S)
            },
        )
        line1.addView(Type.text(this, clock, TypeRole.READOUT, p.etched), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        row.addView(line1, wrap())

        if (summary.isNotBlank() || chipVerb != null) {
            val line2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            if (chipVerb != null) {
                line2.addView(VerbChip.create(this, chipVerb), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = Theme.dp(this@LogActivity, Dimens.SPACE_S)
                    topMargin = Theme.dp(this@LogActivity, 1)
                })
            }
            if (summary.isNotBlank()) {
                line2.addView(
                    Type.text(this, UntrustedText.mark(summary, p.cautionText), TypeRole.BODY, if (h.noise) p.etched else p.ink).apply {
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            row.addView(line2, wrap().apply { topMargin = Theme.dp(this@LogActivity, 4) })
        }
        if (detail.isNotEmpty()) {
            row.addView(
                Type.text(this, detail, TypeRole.DETAIL, p.etched),
                wrap().apply { topMargin = Theme.dp(this@LogActivity, Dimens.SPACE_2XS) },
            )
        }
        return row
    }

    private fun copyRaw(id: String) {
        val raw = AuditLog.from(applicationContext).line(id)
        if (raw == null) {
            Toasts.show(this, "Record is gone")
            return
        }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("STMP request log", raw))
        // From Android 13 the system shows its own "Copied" chip.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toasts.show(this, "Copied raw record")
    }

    companion object {
        /** Rows rendered at once. The store keeps 2000 headers; this is the view cap. */
        private const val MAX_ROWS = 300

        /** The clock the day headings are measured against; tests pin it. */
        @androidx.annotation.VisibleForTesting
        internal var nowMs: () -> Long = { System.currentTimeMillis() }
    }
}
