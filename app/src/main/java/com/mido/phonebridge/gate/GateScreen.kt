package com.mido.phonebridge.gate

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.ApprovalGate
import com.mido.phonebridge.ApprovalGate.ApproveGesture
import com.mido.phonebridge.core.executesOrDeletes
import com.mido.phonebridge.ui.ShellCode
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.CountdownTicks
import com.mido.phonebridge.ui.kit.Etched
import com.mido.phonebridge.ui.kit.HoldKey
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.MaxHeightScrollView
import com.mido.phonebridge.ui.kit.ObscuredTouchGuard
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.StatusRow
import com.mido.phonebridge.ui.kit.VerbChip
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/** Everything the gate screen shows, read once by ApprovalActivity from the intent and the gate. */
internal class GateInput(
    val id: Long,
    val remote: String,
    val endpoint: String,
    val summary: String,
    val detail: String?,
    val view: ApprovalGate.ApprovalView?,
    val timeoutSeconds: Long,
    /** The exception offer the Activity may render, or null. Never built here. */
    val offer: ApprovalGate.ExceptionOffer?,
    val gesture: ApproveGesture,
    val caller: GateCopy.CallerKind,
    val needsPassword: Boolean,
)

/** What the gate's keys do. The Activity owns every decision; the screen only calls back. */
internal class GateCallbacks(
    val onDeny: () -> Unit,
    val onApprove: (by: String) -> Unit,
    val onExceptionTap: () -> Unit,
    val onPasswordChanged: () -> Unit,
)

/** The built gate and the handles the Activity drives from its tick. */
internal class GateViews(
    val guard: ObscuredTouchGuard,
    val outer: LinearLayout,
    val scroll: NestedScrollView,
    val ticks: CountdownTicks,
    val queueLine: TextView,
    val ai: GateAiPanel,
    val deny: MaterialButton,
    val approveTap: MaterialButton?,
    val holdKey: HoldKey?,
    val exception: GateExceptionOffer?,
    val passwordField: EditText?,
    val bioBanner: LinearLayout,
    val bioTitle: TextView,
    val bioBody: TextView,
    val obscuredBanner: View,
    /** The notices panel (fingerprint + obscured); visible while either of them is. */
    val notices: View,
    /** The key row, dimmed while another app covers the screen. */
    val keyRow: View,
) {
    private var approveArmed: Boolean? = null
    private var approveSeconds = -1

    /** Arm or disarm approve and the exception key on the same signal (WP03a 8.1). */
    fun setApproveArmed(armed: Boolean, secondsUntilArmed: Int) {
        if (approveArmed == armed && (armed || approveSeconds == secondsUntilArmed)) return
        approveArmed = armed
        approveSeconds = secondsUntilArmed
        approveTap?.let { KeyButton.setArmed(it, armed, secondsUntilArmed) }
        holdKey?.let { it.armed = armed; it.secondsUntilArmed = if (armed) 0 else secondsUntilArmed }
        exception?.setArmed(armed, secondsUntilArmed)
    }

    /**
     * While another window fully covers the screen the guard drops every touch (D17), so the keys
     * must LOOK dead: dimmed, not merely ignored. [GateScreen.OBSCURED_KEY_ALPHA] is the dim level.
     */
    fun setObscured(obscured: Boolean) {
        isObscured = obscured
        val alpha = if (obscured) GateScreen.OBSCURED_KEY_ALPHA else 1f
        keyRow.alpha = alpha
        exception?.root?.alpha = alpha
        obscuredBanner.visibility = if (obscured) View.VISIBLE else View.GONE
        refreshNotices()
    }

    /** Show or hide the fingerprint notice; the panel around it follows. */
    fun setBioShown(shown: Boolean) {
        bioBanner.visibility = if (shown) View.VISIBLE else View.GONE
        refreshNotices()
    }

    private var isObscured = false

    private fun refreshNotices() {
        notices.visibility = if (isObscured || bioBanner.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /** After the decision: nothing on the gate takes another tap. */
    fun disableKeys() {
        deny.isEnabled = false
        approveTap?.isEnabled = false
        holdKey?.isEnabled = false
        exception?.disable()
    }
}

/** Builds the Instrument gate (WP03a section 6): ticks on top, a scroll, the keys pinned below. */
internal object GateScreen {

    fun build(ctx: Context, input: GateInput, cb: GateCallbacks): GateViews {
        val p = Theme.palette(ctx)
        val gutter = Theme.dp(ctx, Dimens.GUTTER)
        val view = input.view
        val signal = view?.verb?.executesOrDeletes() == true

        // --- top pinned: the countdown and the queue line
        val ticks = CountdownTicks(ctx, totalSeconds = input.timeoutSeconds.toInt()).apply {
            trailingText = GateCopy.trailing(input.id)
            unarmedText = GateCopy.ARM_SUMMARY_PENDING
            armed = false
        }
        padTrailing(ticks, ctx)
        val queueLine = Type.text(ctx, "", TypeRole.CAPTION, p.etched).apply { visibility = View.GONE }
        val top = column(ctx).apply {
            setPadding(gutter, Theme.dp(ctx, Dimens.SPACE_M), gutter, 0)
            addView(ticks, matchWrap())
            addView(queueLine, matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
        }

        // --- scroll: header, readout, AI, rows, exception, raw request
        val content = column(ctx).apply { setPadding(gutter, Theme.dp(ctx, Dimens.SPACE_M), gutter, Theme.dp(ctx, Dimens.SPACE_M)) }
        content.addView(header(ctx, input, signal))
        val rows = view?.destructive?.filter { it.value.isNotBlank() } ?: emptyList()
        val headline = GateReadout.headline(view?.routeKey ?: input.endpoint, input.endpoint, rows)
        content.addView(Etched.label(ctx, headline.label).apply {
            layoutParams = matchWrap().apply {
                topMargin = Theme.dp(ctx, Dimens.MODULE_GAP)
                bottomMargin = Theme.dp(ctx, Dimens.SPACE_XS)
                marginStart = Theme.dp(ctx, 2); marginEnd = Theme.dp(ctx, 2)
            }
        })
        val headText: CharSequence = if (headline.isCommand) ShellCode.renderForReadout(headline.text, p) else headline.text
        content.addView(Readout.create(ctx, headText, Readout.Kind.COMMAND, prompt = headline.prompt).root)
        val aiSlot = column(ctx)
        content.addView(aiSlot, matchWrap())
        if (view != null) {
            GateRows.panel(ctx, GateCopy.ROWS_TITLE, GateRows.requestRows(view, headline.rowIndex), view.sections)
                ?.let(content::addView)
            GateRows.panel(ctx, GateCopy.NOTIFICATION_TITLE, GateRows.notificationRows(view))?.let(content::addView)
        } else {
            GateRows.panel(ctx, GateCopy.ROWS_TITLE, listOf(
                ApprovalGate.Row(GateCopy.FALLBACK_SOURCE, input.remote, ApprovalGate.Row.Style.MONO),
                ApprovalGate.Row(GateCopy.FALLBACK_ENDPOINT, input.endpoint, ApprovalGate.Row.Style.MONO),
                ApprovalGate.Row(GateCopy.FALLBACK_SUMMARY, input.summary, ApprovalGate.Row.Style.NORMAL),
            ))?.let(content::addView)
        }
        val exception = input.offer?.let { GateExceptionOffer(ctx, it, input.gesture, cb.onExceptionTap) }
        exception?.let { content.addView(it.root) }
        content.addView(rawExpander(ctx, GateCopy.raw(input.endpoint, input.detail)))
        val scroll = NestedScrollView(ctx).apply { isFillViewport = true; addView(content) }
        val scrollArea = FrameLayout(ctx).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scrollFade(ctx, scroll), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, FADE_DP), Gravity.BOTTOM,
            ))
        }

        // --- bottom pinned: the notices, the password, the keys. The notices and the password sit in
        // a scroll of their own with a cap, so a tall stack (fingerprint locked + covered + password)
        // can never squeeze the request itself out of the screen (R1). The keys stay pinned below it.
        val notices = Notices(ctx)
        val password = if (input.needsPassword) passwordField(ctx, cb.onPasswordChanged) else null
        val stack = column(ctx).apply {
            setPadding(gutter, Theme.dp(ctx, Dimens.SPACE_S), gutter, 0)
            addView(notices.panel, matchWrap())
            password?.let { addPassword(ctx, this, it) }
        }
        val stackScroll = MaxHeightScrollView(ctx).apply {
            maxHeightPx = (ctx.resources.displayMetrics.heightPixels * STACK_MAX_FRACTION).toInt()
            fadeColor = p.body
            clipToPadding = false
            addView(stack)
        }
        val keys = keys(ctx, input, signal, cb)
        val bottom = column(ctx).apply {
            addView(hairline(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 1)))
            addView(stackScroll, matchWrap())
            addView(keys.row, matchWrap().apply { marginStart = gutter; marginEnd = gutter })
        }

        val outer = column(ctx).apply {
            setBackgroundColor(p.body)
            addView(top, matchWrap())
            addView(scrollArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, matchWrap())
        }
        // Clear of the system bars as WINDOW INSETS, not a fixed dp: the status bar differs per
        // device, and this modal is drawn over whatever was on screen. The keyboard inset goes to
        // the scrolling content (TOKENS 12), so the field above the keys is never covered.
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        val guard = ObscuredTouchGuard(ctx).apply { addView(outer) }
        return GateViews(
            guard = guard, outer = outer, scroll = scroll, ticks = ticks, queueLine = queueLine,
            ai = GateAiPanel(ctx, aiSlot), deny = keys.deny, approveTap = keys.approveTap, holdKey = keys.holdKey,
            exception = exception, passwordField = password, bioBanner = notices.bio, bioTitle = notices.bioTitle,
            bioBody = notices.bioBody, obscuredBanner = notices.obscured, notices = notices.panel, keyRow = keys.row,
        )
    }

    /** D15: caller name, then address, then caller state, then the verb and action. */
    private fun header(ctx: Context, input: GateInput, signal: Boolean): View {
        val p = Theme.palette(ctx)
        val view = input.view
        val quiet = if (signal) p.ink else p.etched
        val panel = Panel.create(ctx, if (signal) Panel.Tone.SIGNAL else Panel.Tone.PLAIN)
        panel.addView(Type.text(ctx, view?.requester ?: GateCopy.FALLBACK_TITLE, TypeRole.TITLE, p.ink).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, matchWrap())
        panel.addView(
            Type.text(ctx, GateCopy.addressLine(input.remote, view?.account ?: ""), TypeRole.READOUT, quiet),
            matchWrap().apply { topMargin = Theme.dp(ctx, 3) },
        )
        val ok = input.caller == GateCopy.CallerKind.VERIFIED || input.caller == GateCopy.CallerKind.THIS_PHONE
        panel.addView(row(ctx).apply {
            addView(Led(ctx).apply { state = if (ok) Led.State.OK else Led.State.CAUTION }, LinearLayout.LayoutParams(Theme.dp(ctx, 16), Theme.dp(ctx, 16)))
            addView(Type.text(ctx, GateCopy.callerState(input.caller), TypeRole.LABEL, quiet),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Theme.dp(ctx, 6) })
        }, matchWrap().apply { topMargin = Theme.dp(ctx, 6) })
        if (view != null) {
            panel.addView(row(ctx).apply {
                addView(VerbChip.create(ctx, view.verb), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(Type.text(ctx, view.actionLabel, TypeRole.LABEL, p.ink),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Theme.dp(ctx, Dimens.SPACE_S) })
            }, matchWrap().apply { topMargin = Theme.dp(ctx, 10) })
        }
        return panel
    }

    /** The raw request, collapsed: a 56dp row that opens a LOG readout below it. */
    private fun rawExpander(ctx: Context, raw: String): View {
        val panel = Panel.create(ctx).apply { layoutParams = matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_L) } }
        var open = false
        fun render() {
            panel.removeAllViews()
            panel.addView(StatusRow.create(ctx, GateCopy.RAW_REQUEST, if (open) GateCopy.HIDE else GateCopy.SHOW, onClick = {
                open = !open
                render()
            }))
            if (open) panel.addView(Readout.create(ctx, raw, Readout.Kind.LOG, softWrap = true).root)
        }
        render()
        return panel
    }

    /**
     * One caution panel for everything that explains why the keys will not work right now: the
     * fingerprint notice and the "another app is covering this screen" line (D17). One LED, one
     * panel, so the two together cost one card of height instead of two.
     */
    private class Notices(ctx: Context) {
        private val p = Theme.palette(ctx)
        val bioTitle: TextView = Type.text(ctx, "", TypeRole.ROW_LABEL, p.cautionText)
        val bioBody: TextView = Type.text(ctx, "", TypeRole.DETAIL, p.ink)
        val bio: LinearLayout = column(ctx).apply {
            addView(bioTitle, matchWrap())
            addView(bioBody, matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
            visibility = View.GONE
        }
        val obscured: TextView = Type.text(ctx, ObscuredTouchGuard.BANNER_TEXT, TypeRole.BODY_STRONG, p.cautionText).apply {
            visibility = View.GONE
        }
        val panel: LinearLayout = Panel.create(ctx, Panel.Tone.CAUTION).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Led(ctx).apply { state = Led.State.CAUTION },
                LinearLayout.LayoutParams(Theme.dp(ctx, 16), Theme.dp(ctx, 16)).apply { topMargin = Theme.dp(ctx, 2) })
            addView(column(ctx).apply {
                addView(bio, matchWrap())
                addView(obscured, matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Theme.dp(ctx, Dimens.SPACE_S) })
            layoutParams = matchWrap().apply { bottomMargin = Theme.dp(ctx, Dimens.SPACE_S) }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
    }

    /** A 1 dp line above the pinned keys, so scrolled content visibly ends at them. */
    private fun hairline(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Theme.palette(ctx).hairline)
    }

    /** A fade at the foot of the scroll while more lies below, so content never just ends at a key. */
    private fun scrollFade(ctx: Context, scroll: NestedScrollView): View {
        val body = Theme.palette(ctx).body
        val fade = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(body, android.graphics.Color.TRANSPARENT),
            )
            visibility = View.GONE
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val update = {
            val more = scroll.canScrollVertically(1)
            if ((fade.visibility == View.VISIBLE) != more) fade.visibility = if (more) View.VISIBLE else View.GONE
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> update() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        return fade
    }

    /**
     * A gap between the countdown text and the request number: the countdown is the first thing
     * that grows at large text, and the two ran together at 1.35.
     */
    private fun padTrailing(ticks: CountdownTicks, ctx: Context) {
        val trailing = ticks.trailingText ?: return
        fun find(v: View): TextView? {
            if (v is TextView && v.text == trailing) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(ticks)?.setPaddingRelative(Theme.dp(ctx, Dimens.SPACE_M), 0, 0, 0)
    }

    /**
     * The sudo password field. Every security attribute is load-bearing:
     *  - never learnt by the keyboard's personalised dictionary;
     *  - not saved into instance state (a rotation must not preserve it);
     *  - `IMPORTANT_FOR_AUTOFILL_NO`, NOT `NO_EXCLUDE_DESCENDANTS`: that constant PERMITS autofill,
     *    and password managers read it as "go ahead" (Mido, 2026-10-27);
     *  - no copy/cut: a long-press menu that lifts the password to the clipboard is a leak;
     *  - the mask LAST, because `setInputType` re-derives the transformation and silently clears
     *    one set before it. That is why the mask kept disappearing.
     */
    private fun passwordField(ctx: Context, onChanged: () -> Unit): EditText {
        val p = Theme.palette(ctx)
        return EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Type.apply(this, TypeRole.BODY)
            setTextColor(p.ink)
            setHintTextColor(p.etched)
            highlightColor = p.focus
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSingleLine = true
            hint = GateCopy.PASSWORD_HINT
            setSaveEnabled(false)
            isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            val pad = Theme.dp(ctx, 12)
            setPadding(pad, pad, pad, pad)
            minHeight = Theme.dp(ctx, Dimens.MIN_TARGET)
            background = GradientDrawable().apply {
                setColor(p.sunken)
                cornerRadius = Theme.dpF(ctx, Dimens.RADIUS_KEY.toFloat())
                setStroke(Theme.dp(ctx, 1), p.outline)
            }
            customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
                override fun onPrepareActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
                override fun onActionItemClicked(m: android.view.ActionMode?, item: android.view.MenuItem?) = false
                override fun onDestroyActionMode(m: android.view.ActionMode?) {}
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                // Re-evaluate so APPROVE lights up the instant the field is non-empty.
                override fun afterTextChanged(s: android.text.Editable?) = onChanged()
            })
            // THE MASK, last: nothing above can clear it now.
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
    }

    private fun addPassword(ctx: Context, into: LinearLayout, field: EditText) {
        val p = Theme.palette(ctx)
        val label = Etched.label(ctx, GateCopy.PASSWORD_LABEL)
        field.id = View.generateViewId()
        Etched.labelFor(label, field)
        into.addView(label)
        into.addView(field, matchWrap())
        into.addView(Type.text(ctx, GateCopy.PASSWORD_CAPTION, TypeRole.CAPTION, p.etched),
            matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
    }

    private class Keys(val row: LinearLayout, val deny: MaterialButton, val approveTap: MaterialButton?, val holdKey: HoldKey?)

    /** D14: deny is one tap and never smaller than approve. Neither key takes default focus. */
    private fun keys(ctx: Context, input: GateInput, signal: Boolean, cb: GateCallbacks): Keys {
        val p = Theme.palette(ctx)
        val row = row(ctx).apply {
            setPadding(0, Theme.dp(ctx, Dimens.SPACE_M), 0, Theme.dp(ctx, Dimens.GUTTER))
        }
        val deny = KeyButton.create(
            ctx, GateCopy.DENY,
            kind = if (signal) KeyButton.Kind.SIGNAL else KeyButton.Kind.OUTLINE,
            size = KeyButton.Size.LARGE,
        ) { cb.onDeny() }
        row.addView(deny, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = Theme.dp(ctx, Dimens.SPACE_M) })
        return when (input.gesture) {
            ApproveGesture.HOLD -> {
                val hold = HoldKey(ctx).apply { onHeld = { cb.onApprove("hold") } }
                val column = column(ctx).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(hold, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // One line, and the column is as wide as the caption needs (never narrower than the
                    // key): at 1.35 the caption wrapped to two lines under a 72 dp column and sat
                    // off-centre (R1). DENY takes the width that is left.
                    addView(Type.text(ctx, GateCopy.HOLD_CAPTION, TypeRole.LABEL, p.etched).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS); gravity = Gravity.CENTER_HORIZONTAL })
                    minimumWidth = Theme.dp(ctx, Dimens.HOLD_KEY)
                }
                row.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                Keys(row, deny, null, hold)
            }
            ApproveGesture.TAP, ApproveGesture.TAP_THEN_BIOMETRIC -> {
                val bio = input.gesture == ApproveGesture.TAP_THEN_BIOMETRIC
                val approve = KeyButton.create(
                    ctx, GateCopy.APPROVE, KeyButton.Kind.INK, KeyButton.Size.LARGE,
                    describe = if (bio) GateCopy.APPROVE_WITH_FINGERPRINT else null,
                ) { cb.onApprove(if (bio) "biometric" else "tap") }
                row.addView(approve, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                Keys(row, deny, approve, null)
            }
        }
    }

    /** Alpha of the keys while another window covers the screen. */
    const val OBSCURED_KEY_ALPHA = 0.38f

    /** Height of the fade at the foot of the scrolling content. */
    private const val FADE_DP = 16

    /** The notices and the password may take this share of the screen before they scroll. */
    private const val STACK_MAX_FRACTION = 0.38f

    internal fun column(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    internal fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    internal fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** The live AI summary panel (WP03a 8.2). Rebuilt only when the state class or the risk changes. */
internal class GateAiPanel(private val ctx: Context, private val slot: LinearLayout) {
    private var lastAiKey = ""
    private var body: TextView? = null
    private var wordIndex = 0
    private var dots = 0

    fun render(state: ApprovalGate.AiState, reducedMotion: Boolean) {
        val key = when (state) {
            is ApprovalGate.AiState.Loading -> "loading"
            is ApprovalGate.AiState.Ready -> "ready:${state.summary.risk.name}"
            is ApprovalGate.AiState.Failed -> "failed"
            ApprovalGate.AiState.NotNeeded -> "none"
        }
        if (key != lastAiKey) {
            lastAiKey = key
            rebuild(state)
            when (state) {
                is ApprovalGate.AiState.Ready -> slot.announceForAccessibility(GateCopy.aiAnnounceReady(state.summary.description))
                is ApprovalGate.AiState.Failed -> slot.announceForAccessibility(GateCopy.AI_ANNOUNCE_FAILED)
                else -> {}
            }
        }
        if (state is ApprovalGate.AiState.Loading) {
            body?.text = if (reducedMotion) GateCopy.AI_LOADING_REDUCED else GateCopy.loading(wordIndex, dots)
            if (!reducedMotion) {
                dots = (dots + 1) % (GateCopy.DOTS_MAX + 1)
                if (dots == 0) wordIndex++
            }
        }
    }

    private fun rebuild(state: ApprovalGate.AiState) {
        val p = Theme.palette(ctx)
        slot.removeAllViews()
        body = null
        data class Look(
            val tone: Panel.Tone, val led: Led.State, val label: String, val labelColor: Int, val text: String,
            val textColor: Int, val reason: String, val risk: String = "", val riskColor: Int = 0,
        )
        val look = when (state) {
            ApprovalGate.AiState.NotNeeded -> return
            is ApprovalGate.AiState.Loading -> Look(Panel.Tone.PLAIN, Led.State.PENDING, GateCopy.AI_LABEL, p.etched, "", p.etched, "")
            is ApprovalGate.AiState.Ready -> {
                val low = state.summary.risk == com.mido.phonebridge.ai.Risk.LOW
                Look(
                    if (low) Panel.Tone.OK else Panel.Tone.CAUTION, if (low) Led.State.OK else Led.State.CAUTION,
                    GateCopy.AI_LABEL, p.ink, state.summary.description, p.ink, state.summary.reason,
                    risk = GateCopy.aiRiskLine(state.summary.risk), riskColor = if (low) p.okText else p.cautionText,
                )
            }
            is ApprovalGate.AiState.Failed -> Look(
                Panel.Tone.CAUTION, Led.State.CAUTION, GateCopy.AI_UNAVAILABLE, p.ink, GateCopy.aiFailed(state.detail), p.cautionText, "",
            )
        }
        val panel = Panel.create(ctx, look.tone).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE }
        panel.addView(GateScreen.row(ctx).apply {
            addView(Led(ctx).apply { this.state = look.led }, LinearLayout.LayoutParams(Theme.dp(ctx, 16), Theme.dp(ctx, 16)))
            addView(Type.text(ctx, look.label, TypeRole.LABEL, look.labelColor),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Theme.dp(ctx, Dimens.SPACE_S) })
        }, GateScreen.matchWrap())
        // The risk call is its own line, so the label never wraps around it and "HIGH" reads as
        // the model's risk rating, not its confidence.
        if (look.risk.isNotEmpty()) {
            panel.addView(Type.text(ctx, look.risk, TypeRole.BODY_STRONG, look.riskColor),
                GateScreen.matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
        }
        val text = Type.text(ctx, look.text, TypeRole.DETAIL, look.textColor)
        panel.addView(text, GateScreen.matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
        body = text
        if (look.reason.isNotBlank()) {
            panel.addView(Type.text(ctx, look.reason, TypeRole.DETAIL, p.ink),
                GateScreen.matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
        }
        slot.addView(panel)
    }
}

/**
 * The exception key and its caption (WP03a 9.5). On a HOLD gate a tap only arms the exception
 * (D58); the hold then approves and saves it. Elsewhere a tap approves and saves at once.
 */
internal class GateExceptionOffer(
    private val ctx: Context,
    val offer: ApprovalGate.ExceptionOffer,
    private val gesture: ApproveGesture,
    private val onTap: () -> Unit,
) {
    val root: LinearLayout = GateScreen.column(ctx).apply {
        layoutParams = GateScreen.matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_L) }
    }
    var isOn = false
        private set
    private var key: MaterialButton = buildKey()
    private val caption: TextView = Type.text(ctx, "", TypeRole.CAPTION, Theme.palette(ctx).etched)
    private var armed = false
    private var seconds = 1
    private var disabled = false

    init {
        root.addView(key, GateScreen.matchWrap())
        root.addView(caption, GateScreen.matchWrap().apply { topMargin = Theme.dp(ctx, Dimens.SPACE_XS) })
        refreshCaption()
        KeyButton.setArmed(key, false, seconds)
    }

    /** The current key view, for tests and the screen reader. */
    val keyView: MaterialButton get() = key

    private fun buildKey(): MaterialButton = KeyButton.create(
        ctx, GateCopy.exceptionKey(gesture, isOn),
        kind = if (isOn) KeyButton.Kind.INK else KeyButton.Kind.PANEL,
        size = KeyButton.Size.REGULAR,
    ) { if (!disabled) onTap() }

    /** HOLD only: flip whether the hold also saves the exception. */
    fun toggle() {
        isOn = !isOn
        val index = root.indexOfChild(key)
        root.removeView(key)
        key = buildKey()
        root.addView(key, index, GateScreen.matchWrap())
        KeyButton.setArmed(key, armed, seconds)
        if (disabled) key.isEnabled = false
        refreshCaption()
    }

    fun setArmed(armed: Boolean, secondsUntilArmed: Int) {
        this.armed = armed
        this.seconds = secondsUntilArmed
        KeyButton.setArmed(key, armed, secondsUntilArmed)
    }

    fun disable() {
        disabled = true
        key.isEnabled = false
    }

    private fun refreshCaption() {
        caption.text = GateCopy.exceptionCaption(gesture, isOn, offer.rule)
    }
}
