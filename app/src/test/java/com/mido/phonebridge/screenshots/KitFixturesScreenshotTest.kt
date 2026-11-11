package com.mido.phonebridge.screenshots

import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.debug.KitHostActivity
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.ActionRow
import com.mido.phonebridge.ui.kit.Chips
import com.mido.phonebridge.ui.kit.CountdownTicks
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.Field
import com.mido.phonebridge.ui.kit.HoldKey
import com.mido.phonebridge.ui.kit.InfoKey
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.KnobSelector
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.PunchView
import com.mido.phonebridge.ui.kit.RadioMark
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.Receipt
import com.mido.phonebridge.ui.kit.RockerSwitch
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.kit.TabBar
import com.mido.phonebridge.ui.kit.Tape
import com.mido.phonebridge.ui.kit.TopBar
import com.mido.phonebridge.ui.kit.VerbChip
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * One entry per component state. The screenshot tests capture every fixture in all four theme x
 * font variants and `TouchTargetTest` walks the very same views, so a state cannot be pictured
 * without also being checked for touch targets.
 *
 * [drive] runs after the view is placed and laid out (touch events, clock advances).
 */
class KitFixture(
    val screen: String,
    val state: String,
    val reduced: Boolean = false,
    val drive: ((View, (Long) -> Unit) -> Unit)? = null,
    val build: (KitHostActivity) -> View,
)

object KitFixtures {

    private const val SAMPLE_COMMAND = "ssh mido@kvm-box 'sudo rm -rf /var/cache/stmp | sh -c \"echo done\"'"

    private fun text(a: KitHostActivity, s: String) =
        Type.text(a, s, TypeRole.BODY, Theme.palette(a).ink)

    private fun panelOf(a: KitHostActivity, vararg views: View): LinearLayout =
        Panel.create(a).also { p -> views.forEach { p.addView(it) } }

    private fun rowOf(a: KitHostActivity, vararg views: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12 }) }
    }

    private fun touch(v: View, action: Int, t: Long) {
        val ev = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, 5f, 5f, 0)
        v.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun led(a: KitHostActivity, s: Led.State) = rowOf(
        a, Led(a).apply { state = s }, text(a, s.name.lowercase()),
    )

    private fun key(
        a: KitHostActivity, label: String, kind: KeyButton.Kind, size: KeyButton.Size = KeyButton.Size.REGULAR,
    ) = KeyButton.create(a, label, kind, size) {}

    private fun keyRow(a: KitHostActivity, v: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        if (v.layoutParams != null) addView(v)
        else addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun spec(label: String) = KeyButton.Spec(label, "$label laptop") {}

    private fun gate(a: KitHostActivity, selected: Int) =
        KnobSelector(a, "Gate mode", listOf("Whitelist", "Paranoid", "Don't disturb")).also { it.select(selected, false) }

    private fun readout(a: KitHostActivity, content: CharSequence, kind: Readout.Kind, prompt: String? = null) =
        Readout.create(a, content, kind, prompt).root

    private fun fieldRoot(f: Field.Handle) = f.root

    val all: List<KitFixture> by lazy {
        buildList {
            // 1 Panel
            for (tone in Panel.Tone.entries) {
                add(KitFixture("kit_panel", tone.name.lowercase()) { a ->
                    Panel.create(a, tone).also { it.addView(text(a, "Bridge · live")) }
                })
            }
            add(KitFixture("kit_panel", "clickable") { a ->
                Panel.create(a, onClick = {}).also { it.addView(text(a, "Tap the whole panel")) }
            })
            // 2 Etched, 3 SectionHeader
            add(KitFixture("kit_etched", "label_with_value") { a ->
                LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Etched.label(a, "Will run, verbatim"))
                    addView(text(a, "ssh mido@kvm-box"))
                }
            })
            add(KitFixture("kit_section_header", "plain") { a -> SectionHeader.create(a, "Developer") })
            add(KitFixture("kit_section_header", "in_panel") { a ->
                panelOf(a, SectionHeader.create(a, "Bridge · live", inPanel = true), text(a, "Listening on 192.168.1.100:8787"))
            })
            // 4 Led
            for (s in Led.State.entries) add(KitFixture("kit_led", s.name.lowercase()) { a -> led(a, s) })
            add(KitFixture("kit_led", "pending_reduced", reduced = true) { a -> led(a, Led.State.PENDING) })
            // 5 VerbChip
            for (v in Verb.entries) add(KitFixture("kit_verb_chip", v.name.lowercase()) { a -> keyRow(a, VerbChip.create(a, v)) })
            // 6 KeyButton
            add(KitFixture("kit_key", "ink_regular") { a -> keyRow(a, key(a, "Approve", KeyButton.Kind.INK)) })
            add(KitFixture("kit_key", "panel_regular") { a -> keyRow(a, key(a, "Cancel", KeyButton.Kind.PANEL)) })
            add(KitFixture("kit_key", "signal_regular") { a -> keyRow(a, key(a, "Run it", KeyButton.Kind.SIGNAL)) })
            add(KitFixture("kit_key", "outline_regular") { a -> keyRow(a, key(a, "Deny", KeyButton.Kind.OUTLINE)) })
            add(KitFixture("kit_key", "ink_large") { a -> keyRow(a, key(a, "Approve", KeyButton.Kind.INK, KeyButton.Size.LARGE)) })
            add(KitFixture("kit_key", "outline_large") { a -> keyRow(a, key(a, "Deny", KeyButton.Kind.OUTLINE, KeyButton.Size.LARGE)) })
            add(KitFixture("kit_key", "panel_small") { a -> keyRow(a, key(a, "Copy", KeyButton.Kind.PANEL, KeyButton.Size.SMALL)) })
            add(KitFixture("kit_key", "outline_small") { a -> keyRow(a, key(a, "Forget", KeyButton.Kind.OUTLINE, KeyButton.Size.SMALL)) })
            add(KitFixture("kit_key", "ink_pressed") { a -> keyRow(a, key(a, "Approve", KeyButton.Kind.INK).also { it.isPressed = true }) })
            add(KitFixture("kit_key", "panel_focused") { a ->
                keyRow(a, key(a, "Cancel", KeyButton.Kind.PANEL).also { it.isFocusableInTouchMode = true; it.requestFocus() })
            })
            add(KitFixture("kit_key", "ink_disabled") { a -> keyRow(a, key(a, "Approve", KeyButton.Kind.INK).also { it.isEnabled = false }) })
            add(KitFixture("kit_key", "outline_unarmed") { a ->
                keyRow(a, key(a, "Deny", KeyButton.Kind.OUTLINE, KeyButton.Size.LARGE).also { KeyButton.setArmed(it, false, 1) })
            })
            add(KitFixture("kit_key", "progress_40") { a -> keyRow(a, KeyButton.progress(a, "Downloading", 40)) })
            add(KitFixture("kit_key", "long_label_wraps") { a ->
                keyRow(a, key(a, "Replace the stored value everywhere", KeyButton.Kind.PANEL))
            })
            // 7 RockerSwitch
            add(KitFixture("kit_rocker", "off") { a -> keyRow(a, Theme.rocker(a, false, "Auto-start") {}) })
            add(KitFixture("kit_rocker", "on") { a -> keyRow(a, Theme.rocker(a, true, "Auto-start") {}) })
            add(KitFixture("kit_rocker", "pending") { a -> keyRow(a, Theme.rocker(a, false, "Auto-start", pending = true) {}) })
            add(KitFixture("kit_rocker", "pending_reduced", reduced = true) { a -> keyRow(a, Theme.rocker(a, false, "Auto-start", pending = true) {}) })
            add(KitFixture("kit_rocker", "on_disabled") { a -> keyRow(a, Theme.rocker(a, true, "Auto-start") {}.also { it.isEnabled = false }) })
            add(KitFixture("kit_rocker", "on_pressed") { a -> keyRow(a, Theme.rocker(a, true, "Auto-start") {}.also { it.isPressed = true }) })
            add(KitFixture("kit_rocker", "off_focused") { a ->
                keyRow(a, Theme.rocker(a, false, "Auto-start") {}.also { it.isFocusableInTouchMode = true; it.requestFocus() })
            })
            // 8 KnobSelector
            for (i in 0..2) add(KitFixture("kit_knob", "gate_${listOf("whitelist", "paranoid", "dont_disturb")[i]}") { a -> gate(a, i) })
            add(KitFixture("kit_knob", "ai_on_this_phone") { a ->
                KnobSelector(a, "AI", listOf("Off", "On this phone", "Remote server")).also { it.select(1, false) }
            })
            add(KitFixture("kit_knob", "gate_disabled") { a -> gate(a, 0).also { it.isEnabled = false } })
            // 9 Chips
            add(KitFixture("kit_chips", "group_mixed") { a ->
                Chips.group(a, "Filter by outcome").also { g ->
                    g.addView(Chips.filter(a, "All", true) {})
                    g.addView(Chips.filter(a, "Approved", false) {})
                    g.addView(Chips.filter(a, "Denied", false) {})
                }
            })
            add(KitFixture("kit_chips", "group_wraps") { a ->
                Chips.group(a, "Filter by outcome").also { g ->
                    listOf("All", "Approved", "Denied", "Timed out", "Blocked", "Policy").forEachIndexed { i, s ->
                        g.addView(Chips.filter(a, s, i == 1) {})
                    }
                }
            })
            // 10 RadioMark, 11 InfoKey
            add(KitFixture("kit_radio_mark", "unselected_selected") { a ->
                rowOf(a, RadioMark.create(a, false), RadioMark.create(a, true))
            })
            add(KitFixture("kit_info_key", "default") { a -> keyRow(a, InfoKey.create(a, "About Speak") {}) })
            add(KitFixture("kit_info_key", "pressed") { a -> keyRow(a, InfoKey.create(a, "About Speak") {}.also { it.isPressed = true }) })
            // 12 Field
            add(KitFixture("kit_field", "empty_hint") { a -> fieldRoot(Field.create(a, "Name", "", "e.g. home laptop")) })
            add(KitFixture("kit_field", "filled") { a -> fieldRoot(Field.create(a, "Name", "home laptop", "e.g. home laptop")) })
            add(KitFixture("kit_field", "focused") { a ->
                Field.create(a, "Name", "home laptop", "").also { it.edit.requestFocus() }.root
            })
            add(KitFixture("kit_field", "error") { a ->
                Field.create(a, "Name", "home laptop", "").also { it.setError("That name is already used") }.root
            })
            add(KitFixture("kit_field", "url") { a -> fieldRoot(Field.create(a, "Server", "https://example.com/v1", "https://", Field.Kind.URL)) })
            add(KitFixture("kit_field", "number") { a -> fieldRoot(Field.create(a, "Port", "8787", "", Field.Kind.NUMBER)) })
            add(KitFixture("kit_field", "secret_masked") { a -> fieldRoot(Field.create(a, "Token", "hunter2hunter2", "", Field.Kind.SECRET)) })
            add(KitFixture("kit_field", "disabled") { a ->
                Field.create(a, "Name", "home laptop", "").also { it.setEnabled(false) }.root
            })
            // 13 Readout
            add(KitFixture("kit_readout", "command_danger") { a ->
                val ranges = listOf(
                    SAMPLE_COMMAND.indexOf("sudo").let { it until it + 4 },
                    SAMPLE_COMMAND.indexOf("rm -rf").let { it until it + 6 },
                    SAMPLE_COMMAND.indexOf("| sh").let { it until it + 4 },
                )
                readout(a, Readout.withDanger(a, SAMPLE_COMMAND, ranges), Readout.Kind.COMMAND, "mido@kvm-box $")
            })
            add(KitFixture("kit_readout", "command_long_scrolls") { a ->
                readout(a, (1..60).joinToString("\n") { "echo line $it && ls -la /var/log" }, Readout.Kind.COMMAND)
            })
            add(KitFixture("kit_readout", "body") { a -> readout(a, "{\n  \"to\": \"+49 151 0000000\",\n  \"text\": \"On my way\"\n}", Readout.Kind.BODY) })
            add(KitFixture("kit_readout", "display") { a -> readout(a, "100.64.0.2", Readout.Kind.DISPLAY) })
            add(KitFixture("kit_readout", "log") { a -> readout(a, "09:40:00 server up\n09:40:03 probe ok", Readout.Kind.LOG) })
            // 14 StatusRow
            add(KitFixture("kit_status_row", "plain_and_values") { a ->
                panelOf(
                    a,
                    StatusRow.create(a, "Bridge", "Running", StatusRow.Tone.OK, led = Led.State.OK),
                    StatusRow.create(a, "Notifications", "Needs access", StatusRow.Tone.CAUTION, detail = "Open Settings to allow them", led = Led.State.CAUTION),
                    StatusRow.create(a, "Address", "", detail = "192.168.1.100:8787"),
                )
            })
            add(KitFixture("kit_status_row", "clickable") { a ->
                panelOf(a, StatusRow.create(a, "Exceptions", "2", onClick = {}), StatusRow.create(a, "Request log", "", detail = "Latest first", onClick = {}))
            })
            add(KitFixture("kit_status_row", "trailing_rocker_on") { a ->
                panelOf(a, StatusRow.create(a, "Start on boot", "", detail = "Starts the bridge after a restart", trailing = Theme.rocker(a, true, "Start on boot") {}))
            })
            add(KitFixture("kit_status_row", "trailing_rocker_off") { a ->
                panelOf(a, StatusRow.create(a, "Start on boot", "", trailing = Theme.rocker(a, false, "Start on boot") {}))
            })
            add(KitFixture("kit_status_row", "action_under_value") { a ->
                panelOf(a, StatusRow.create(a, "Notification access", "Off", StatusRow.Tone.CAUTION, led = Led.State.CAUTION, action = spec("Open settings")))
            })
            add(KitFixture("kit_status_row", "action_long_detail") { a ->
                panelOf(
                    a,
                    StatusRow.create(
                        a, "Contacts permission", "Needs attention", StatusRow.Tone.CAUTION,
                        detail = "Android will not ask again. Enable Contacts in Settings, then approve the request again.",
                        led = Led.State.CAUTION, action = spec("Open settings"),
                    ),
                )
            })
            // 15 ActionRow
            add(KitFixture("kit_action_row", "short_actions") { a ->
                panelOf(a, ActionRow(a, "home laptop", "100.64.0.2", listOf(spec("Edit")), spec("Forget")))
            })
            add(KitFixture("kit_action_row", "wide_actions") { a ->
                panelOf(a, ActionRow(a, "API token", "stored", listOf(spec("Replace value"), spec("Copy name")), spec("Delete secret")))
            })
            // 16 StateBlock
            add(KitFixture("kit_state_block", "empty") { a -> StateBlock.create(a, StateBlock.Kind.EMPTY, "No requests yet", "Requests appear here as callers use the bridge.") })
            add(KitFixture("kit_state_block", "error") { a -> StateBlock.create(a, StateBlock.Kind.ERROR, "Could not read the log", "Try again in a moment.") })
            add(KitFixture("kit_state_block", "error_with_action") { a ->
                StateBlock.create(a, StateBlock.Kind.ERROR, "Could not read the log", "Try again in a moment.", spec("Retry"))
            })
            add(KitFixture("kit_state_block", "loading") { a -> StateBlock.create(a, StateBlock.Kind.LOADING, "Loading", "One moment.") })
            add(KitFixture("kit_state_block", "loading_reduced", reduced = true) { a -> StateBlock.create(a, StateBlock.Kind.LOADING, "Loading", "One moment.") })
            // 19 Tape
            add(KitFixture("kit_tape", "rows") { a ->
                panelOf(
                    a,
                    Tape.row(a, Verb.READ, "contacts search", Fixtures.NOW_MS),
                    Tape.row(a, Verb.EXECUTE, "ssh run on kvm-box", Fixtures.NOW_MS + 60_000),
                    Tape.row(a, Verb.DELETE, "files delete report.pdf", Fixtures.NOW_MS + 120_000),
                )
            })
            add(KitFixture("kit_tape", "clickable") { a ->
                panelOf(a, Tape.row(a, Verb.WRITE, "notes add", Fixtures.NOW_MS, onClick = {}), Tape.row(a, Verb.EDIT, "notes edit", Fixtures.NOW_MS + 1, onClick = {}))
            })
            // 10/11 TopBar, TabBar
            add(KitFixture("kit_top_bar", "title_only") { a -> TopBar.create(a, "Exceptions") })
            add(KitFixture("kit_top_bar", "with_action") { a ->
                TopBar.create(a, "Secrets", KeyButton.create(a, "Add", KeyButton.Kind.OUTLINE, KeyButton.Size.SMALL) {})
            })
            add(KitFixture("kit_top_bar", "long_title") { a -> TopBar.create(a, "A rather long screen title that has to end somewhere") })
            for (t in Theme.Tab.entries) {
                add(KitFixture("kit_tab_bar", "${t.name.lowercase()}_selected") { a -> TabBar(a, t) {} })
            }
            // 20 CountdownTicks
            add(KitFixture("kit_countdown", "armed_47") { a ->
                CountdownTicks(a, 60).also { it.deadlineUptimeMs = SystemClock.uptimeMillis() + 47_000; it.trailingText = "REQ 413" }
            })
            add(KitFixture("kit_countdown", "last_ten") { a ->
                CountdownTicks(a, 60).also { it.deadlineUptimeMs = SystemClock.uptimeMillis() + 8_000 }
            })
            add(KitFixture("kit_countdown", "unarmed") { a ->
                CountdownTicks(a, 60).also { it.deadlineUptimeMs = SystemClock.uptimeMillis() + 60_000; it.armed = false }
            })
            add(KitFixture("kit_countdown", "unarmed_waiting_for_ai") { a ->
                CountdownTicks(a, 60).also {
                    it.deadlineUptimeMs = SystemClock.uptimeMillis() + 60_000
                    it.armed = false
                    it.unarmedText = "waiting for the AI summary"
                }
            })
            // 9 HoldKey
            add(KitFixture("kit_hold_key", "idle") { a -> keyRow(a, HoldKey(a)) })
            add(KitFixture("kit_hold_key", "holding_half", drive = { v, advance ->
                val t = SystemClock.uptimeMillis(); touch(v, MotionEvent.ACTION_DOWN, t); advance(600)
            }) { a -> keyRow(a, HoldKey(a)) })
            add(KitFixture("kit_hold_key", "holding_half_reduced", reduced = true, drive = { v, advance ->
                val t = SystemClock.uptimeMillis(); touch(v, MotionEvent.ACTION_DOWN, t); advance(600)
            }) { a -> keyRow(a, HoldKey(a)) })
            add(KitFixture("kit_hold_key", "complete", drive = { v, advance ->
                val t = SystemClock.uptimeMillis(); touch(v, MotionEvent.ACTION_DOWN, t); advance(1300)
            }) { a -> keyRow(a, HoldKey(a)) })
            add(KitFixture("kit_hold_key", "unarmed") { a -> keyRow(a, HoldKey(a).also { it.secondsUntilArmed = 1; it.armed = false }) })
            // 19.2 Receipt
            val receiptLines = listOf(
                "STMP RECEIPT ········ 413", "laptop  EXECUTE  /ssh/run", "stamped 09:40:00.201",
                "status waiting", "by this phone · hold",
            )
            add(KitFixture("kit_receipt", "fed", drive = { v, advance -> (v as Receipt).feed(); advance(400) }) { a ->
                Receipt(a, receiptLines, "413", "waiting")
            })
            add(KitFixture("kit_receipt", "mid_feed", drive = { v, advance -> (v as Receipt).feed(); advance(130) }) { a ->
                Receipt(a, receiptLines, "413", "waiting")
            })
            add(KitFixture("kit_receipt", "fed_reduced", reduced = true, drive = { v, _ -> (v as Receipt).feed() }) { a ->
                Receipt(a, receiptLines, "413", "waiting")
            })
            // 21 Punch
            fun punch(v: PunchView.Verdict, held: Boolean, meta: String = "") = { a: KitHostActivity ->
                PunchView(a).also {
                    it.soundHook = {}
                    it.holdAfterSettle = held
                    it.meta = meta
                    it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 400))
                    it.tag = v
                }
            }
            for ((name, v, t) in listOf(
                Triple("approved_impact", PunchView.Verdict.APPROVED, 200L),
                Triple("approved_settled", PunchView.Verdict.APPROVED, 720L),
                Triple("denied_settled", PunchView.Verdict.DENIED, 720L),
            )) {
                add(KitFixture("kit_punch", name, drive = { view, advance ->
                    (view as PunchView).stamp(v); advance(t)
                }, build = punch(v, true)))
            }
            add(KitFixture("kit_punch", "approved_with_meta", drive = { view, advance ->
                (view as PunchView).stamp(PunchView.Verdict.APPROVED); advance(720)
            }, build = punch(PunchView.Verdict.APPROVED, true, "No. 413 · 09:40:00")))
            add(KitFixture("kit_punch", "denied_reduced", reduced = true, drive = { view, _ ->
                (view as PunchView).stamp(PunchView.Verdict.DENIED)
            }, build = punch(PunchView.Verdict.DENIED, true)))
            // 24 ObscuredTouchGuard banner
            add(KitFixture("kit_obscured_guard", "banner") { a -> ObscuredTouchGuard.banner(a).also { it.visibility = View.VISIBLE } })
        }
    }

    fun of(screen: String): List<KitFixture> = all.filter { it.screen == screen }
}
