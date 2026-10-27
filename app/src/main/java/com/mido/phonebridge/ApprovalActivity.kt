package com.mido.phonebridge

import androidx.fragment.app.FragmentActivity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.StampOverlay
import com.mido.phonebridge.alert.SoundAlerts

/**
 * Full-screen approval UI. Shows every detail of the pending request and
 * blocks until the human chooses approve/deny.
 */
class ApprovalActivity : FragmentActivity() {

    /**
     * The password field, when this request needs one; null otherwise.
     *
     * A FIELD rather than a local because [approveNow] is a method and has to read it —
     * the locals in [onCreate] are not in scope there. Cleared in [onDestroy] so a
     * finishing Activity does not keep a reference to a view holding a secret.
     */
    private var passwordField: EditText? = null

    /** Whether this request needs a sudo password. Read once, from the gate's view. */
    private var needsPassword = false

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_TIMEOUT_SECONDS = "timeoutSeconds"
        const val EXTRA_CREATED_AT = "createdAt"
        const val EXTRA_WAITING_BEHIND = "waitingBehind"

        /**
         * Words cycled while the model works, so the wait reads as thinking rather than
         * hanging (Mido, 2026-10-19: *"add fake loading feeling, claude style some word like
         * thinking... wondering..."*).
         *
         * Deliberately honest about being indeterminate: there is no token stream to measure,
         * so a percentage would be invented. A rotating word plus a dot cycle shows liveness
         * and nothing more.
         */
        private val LOADING_WORDS = listOf(
            "thinking", "wondering", "considering", "reading", "summarising", "weighing it up"
        )

        /** Dots cycle 0→3 then the word advances, so the line visibly moves. */
        private const val DOTS_MAX = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw over other apps / show over the lockscreen, turn the screen on,
        // and keep it on while the human decides. This is what makes the modal
        // appear *immediately* regardless of what is in the foreground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // FLAG_SECURE whenever this modal can hold a password: without it the typed
        // password appears in the recents thumbnail and is capturable by a screen
        // recorder. Set unconditionally for a password request — the flag cannot be
        // added later without re-creating the window.
        val wantsPassword = ApprovalGate.currentView()?.needsPassword == true
        if (wantsPassword) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val remote = intent.getStringExtra(EXTRA_REMOTE) ?: "?"
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: "?"
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "?"
        val timeoutSeconds = intent.getLongExtra(EXTRA_TIMEOUT_SECONDS, ApprovalGate.TIMEOUT_SECONDS)
        val createdAtMs = intent.getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis())

        // Two sources of truth:
        //  - view:  the RESOLVED, human-readable WHAT/ON (built by the caller
        //           from the live notification + resolved action)
        //  - detail: the raw JSON, shown collapsed under "details"
        val view = ApprovalGate.currentView()
        val detail = ApprovalGate.currentDetailJson()
        // The gate decides whether this request needs a biometric; the UI only renders
        // that decision (see ApprovalView.requiresBiometric). Read once, here.
        val requiresBiometric = view?.requiresBiometric == true
        // The exception offer, or null. Read once, like requiresBiometric: the gate
        // decided whether the modal may offer it (only under AUTOMATIC).
        val offer = view?.exceptionOffer
        // The gate decided whether this request cannot proceed without a password.
        // Read once, like the other two: the UI renders decisions, it does not make them.
        needsPassword = view?.needsPassword == true

        val pad = Theme.dp(this, 24)

        // Scrollable modal body. Long notifications (a big Arabic message, a
        // multi-line task) previously pushed the buttons off the bottom edge with
        // no way to reach them — a timed-out approval through no fault of the
        // user. The whole content scrolls, so every control is always reachable.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Theme.sheet(Theme.BG)
        }

        fun label(t: String, size: Float, color: Int = Theme.ON_SURFACE): TextView =
            Theme.text(this, t, size, color).apply {
                setPadding(0, 0, 0, Theme.dp(this@ApprovalActivity, 12))
            }

        content.addView(label("🔐 awaiting your stamp", 20f))

        // --- AI: LIVE, because the summary arrives AFTER this view is built. ---
        // The modal now appears immediately and the model answers in the background (Mido,
        // 2026-10-19), so these views cannot be filled from the intent — they are created
        // empty and driven by the tick below, from ApprovalGate.currentAiState().
        val aiHeader = Theme.sectionHeader(this, "SUMMARY")
        // The summary is the ONE thing the human actually reads, so it is the largest text
        // on the screen (Mido, 2026-10-19: *"make the ui summary more concise and larger"*).
        // "Concise" is enforced by BOUNDING it (3 lines, ellipsized) rather than by cutting
        // the model's sentence — the shape is already one short line, and silently dropping
        // its tail would hide exactly the detail a summary exists to surface.
        val aiLine = Theme.text(this, "", 26f, Theme.ON_SURFACE).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, Theme.dp(this@ApprovalActivity, 10))
        }
        val aiRiskLine = Theme.text(this, "", 13f, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, 0, 0, Theme.dp(this@ApprovalActivity, 12))
            visibility = ViewGroup.GONE
        }
        content.addView(aiHeader)
        content.addView(aiLine)
        content.addView(aiRiskLine)

        if (view != null) {
            // --- WHO ---
            content.addView(Theme.sectionHeader(this, "WHO"))
            content.addView(label(view.requester, 15f, Theme.WARNING))
            if (view.account.isNotBlank() && view.account != "—") {
                content.addView(label("as ${view.account}", 13f, Theme.SUCCESS))
            }

            // --- WHAT (the resolved action, in words) ---
            content.addView(Theme.sectionHeader(this, "WHAT"))
            content.addView(label("\"${view.actionLabel}\"", 16f))

            // Render a labelled row: label on its own quiet line, value below in the
            // style the row asked for. This is the ONE renderer; it knows nothing
            // about Gmail, Calendar, or notifications — only rows. Never print a
            // label with nothing under it (an empty row is a defect, not a state).
            fun row(r: ApprovalGate.Row) {
                content.addView(Theme.text(this, r.label, 11f, Theme.ON_SURFACE_VARIANT).apply {
                    setPadding(0, Theme.dp(this@ApprovalActivity, 8), 0, 0)
                })
                content.addView(Theme.text(
                    this,
                    r.value,
                    13f,
                    if (r.style == ApprovalGate.Row.Style.WARN) Theme.WARNING else Theme.ON_SURFACE,
                    mono = r.style == ApprovalGate.Row.Style.MONO ||
                        r.style == ApprovalGate.Row.Style.WARN
                ).apply {
                    setTextIsSelectable(true)
                    setPadding(0, 0, 0, Theme.dp(this@ApprovalActivity, 6))
                })
            }

            // Plugin-provided sections (Calendar, Gmail, …). Each section renders
            // only when it has rows — the component never invents a header.
            for (sec in view.sections) {
                val rows = sec.rows.filter { it.value.isNotBlank() }
                if (rows.isEmpty()) continue
                content.addView(Theme.sectionHeader(this, sec.title))
                rows.forEach { row(it) }
            }

            // Legacy target block (mutating routes) — same renderer, one section.
            if (view.destructive.isNotEmpty()) {
                view.destructive
                    .filter { it.second.isNotBlank() }
                    .forEach { row(ApprovalGate.Row(it.first, it.second, ApprovalGate.Row.Style.WARN)) }
            }
            // Scope the read carried (filters). A scoped read must show its scope:
            // "count=3, package=com.whatsapp", never a bare verb.
            if (view.filters.isNotEmpty()) {
                val scope = view.filters.entries.joinToString(", ") { "${it.key}=${it.value}" }
                content.addView(label("filter: $scope", 13f, Theme.WARNING))
            }
            if (!view.replyText.isNullOrBlank()) {
                content.addView(label("reply: ${view.replyText}", 13f, Theme.SUCCESS))
            }
            // Other buttons the same notification offers (options are a menu, the
            // resolved WHAT is the pick). Show them so the human sees the space.
            if (view.options.isNotEmpty()) {
                content.addView(label("other actions: " + view.options.joinToString(" · "), 12f, Theme.ON_SURFACE_VARIANT))
            }

            // Notification content as ordinary rows — the same renderer as every
            // plugin. Only rendered when there is a notification behind the request;
            // a read route has none and gets no section at all.
            val notifRows = buildList {
                val head = if (view.notifTitle.isNotBlank()) view.notifTitle
                else view.appLabel
                if (head.isNotBlank()) add(ApprovalGate.Row("TITLE", head))
                if (view.notifSender.isNotBlank()) add(ApprovalGate.Row("FROM", view.notifSender))
                if (view.notifText.isNotBlank()) add(ApprovalGate.Row("BODY", view.notifText, ApprovalGate.Row.Style.LONG))
                if (view.messageCount > 1) add(ApprovalGate.Row("", "💬 ${view.messageCount} messages", ApprovalGate.Row.Style.WARN))
            }
            if (notifRows.isNotEmpty()) {
                content.addView(Theme.sectionHeader(this, "ON"))
                notifRows.forEach { row(it) }
            }
        } else {
            // No structured view (e.g. non-notification routes): keep the old fields.
            content.addView(label("Source: $remote", 12f, Theme.WARNING))
            content.addView(label("Endpoint: $endpoint", 12f, Theme.WARNING))
            content.addView(label("Summary: $summary", 13f))
        }

        // --- Details: raw endpoint + full JSON, collapsed by default. ---
        val detailBody = Theme.text(
            this, "endpoint: $endpoint\n\n" + (detail ?: "(no detail)"),
            12f, Theme.ON_SURFACE_VARIANT, mono = true
        ).apply { setTextIsSelectable(true) }
        val detailScroll = ScrollView(this).apply {
            addView(detailBody)
            visibility = ViewGroup.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this@ApprovalActivity, 220)
            )
        }
        val detailToggle = Theme.text(this, "▸ details", 13f, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, Theme.dp(this@ApprovalActivity, 14), 0, Theme.dp(this@ApprovalActivity, 8))
            isClickable = true
            setOnClickListener {
                val show = detailScroll.visibility != ViewGroup.VISIBLE
                detailScroll.visibility = if (show) ViewGroup.VISIBLE else ViewGroup.GONE
                text = if (show) "▾ details" else "▸ details"
            }
        }

        // --- Countdown to auto-deny, from the moment the buttons go live. ---
        // NOT from createdAtMs: the clock starts when the human can actually decide, so a
        // slow model never spends their decision time before they can use any of it (Mido,
        // 2026-10-19: "60 at enable"). While the AI is still working there is no countdown
        // to show, because it has not started.
        val countdown = Theme.text(this, "", 13f, Theme.WARNING).apply {
            setPadding(0, 0, 0, Theme.dp(this@ApprovalActivity, 6))
        }

        // --- Queue position: only when others are actually waiting. The queue is
        // FIFO, so this request is the one on screen and the rest wait their turn.
        // Never render a "0 waiting" line — an empty statement is noise.
        val queueLine = Theme.text(this, "", 13f, Theme.ON_SURFACE_VARIANT).apply {
            setPadding(0, 0, 0, Theme.dp(this@ApprovalActivity, 6))
            visibility = ViewGroup.GONE
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // Loading-word + dot animation state. Advanced only while the fetch is running.
        var wordIdx = 0
        var dotCount = 0
        // Assigned once the buttons and the bar exist (below). A `var` rather than a local
        // `fun` so the tick above can call it without a forward reference, and so there is
        // exactly ONE place that decides whether the controls are live.
        var applyArmed: (Boolean) -> Unit = {}
        // The password field is a field on the Activity (see its declaration), not a
        // local: approveNow() runs from a button callback and reads it from there.
        /** Whether APPROVE may be tapped. True unless a password is required and empty. */
        var passwordReady: () -> Boolean = { true }
        /** The last arm state, so a keystroke can re-evaluate it without guessing. */
        var lastArmed = false

        /**
         * Paint APPROVE with a risk colour — assigned once the button exists.
         *
         * A holder rather than touching `allow` directly because the tick that DOES the
         * painting is created ~150 lines before the button is; `applyArmed` solved the same
         * ordering problem the same way. Null risk = "no assessment", which restores the
         * resting colour instead of leaving a stale one behind.
         */
        var applyRisk: (com.mido.phonebridge.ai.Risk?) -> Unit = {}
        val tick = object : Runnable {
            override fun run() {
                // The AI block is LIVE: read the gate's current state every tick rather
                // than trusting a value captured at build time.
                when (val ai = ApprovalGate.currentAiState()) {
                    is ApprovalGate.AiState.Loading -> {
                        aiHeader.text = "SUMMARY"
                        // A thinking word + a dot cycle (Mido, 2026-10-19). The words are
                        // honest about being indeterminate: there is no token stream to
                        // measure, so a percentage would be invented. The dots loop
                        // 0,1,2,3 to show the app is alive rather than hung.
                        val word = LOADING_WORDS[wordIdx % LOADING_WORDS.size]
                        aiLine.text = word + ".".repeat(dotCount)
                        aiLine.setTextColor(Theme.ON_SURFACE_VARIANT)
                        aiRiskLine.visibility = ViewGroup.GONE
                        // Back to the resting colour while we do not yet know the risk —
                        // otherwise a previous assessment's colour would linger on the button.
                        applyRisk(null)
                        dotCount = (dotCount + 1) % (DOTS_MAX + 1)
                        if (dotCount == 0) wordIdx++
                    }
                    is ApprovalGate.AiState.Ready -> {
                        aiHeader.text = "SUMMARY"
                        aiLine.text = ai.summary.description
                        aiLine.setTextColor(Theme.ON_SURFACE)
                        val reason = ai.summary.reason
                        aiRiskLine.text = "risk: ${ai.summary.risk.name.lowercase()}" +
                            (if (reason.isNotBlank()) " — $reason" else "")
                        // One mapping, shared with the NOTIFY card's strip (Theme.riskColor).
                        aiRiskLine.setTextColor(Theme.riskColor(ai.summary.risk))
                        aiRiskLine.visibility = ViewGroup.VISIBLE

                        // APPROVE carries the risk colour too (Mido, 2026-10-19: *"make the
                        // approve button color match the risk label color"*). The risk is the
                        // one thing the human needs before tapping, and putting it on the thing
                        // they tap is the strongest place it can sit.
                        //
                        // DENY stays NEUTRAL, deliberately: two coloured decision buttons would
                        // make them look like a pair of equal choices, which is exactly the
                        // confusion the 0.23.0 pass fixed by separating and restyling them. Red
                        // APPROVE next to grey DENY still reads as "approve, carefully".
                        applyRisk(ai.summary.risk)
                    }
                    is ApprovalGate.AiState.Failed -> {
                        // Fail LOUDLY but unlock (Mido, 2026-10-19). The human sees the
                        // error and can still decide — a modal with dead buttons because
                        // one transient call failed is a dead end, not a safety property.
                        aiHeader.text = "SUMMARY"
                        aiLine.text = "⚠ could not summarise — ${ai.detail}"
                        aiLine.setTextColor(Theme.DANGER)
                        aiRiskLine.visibility = ViewGroup.GONE
                        // No risk was assessed, so the button must not wear a risk colour —
                        // an absent assessment is not "safe", and painting it green would say
                        // it was. Resting colour, with the error stated above it.
                        applyRisk(null)
                    }
                    ApprovalGate.AiState.NotNeeded -> {
                        // The route declares `aiSummary = false`: no summary is expected,
                        // so the block is removed rather than left saying "loading".
                        aiHeader.visibility = ViewGroup.GONE
                        aiLine.visibility = ViewGroup.GONE
                        aiRiskLine.visibility = ViewGroup.GONE
                    }
                }

                // THREE phases, not two. `enabledAtMs` is now an ARM TIME that can sit in the
                // future (the misclick guard), so "is it stamped" is no longer the same question
                // as "may the human tap". Conflating them is what would make the guard useless:
                // a future timestamp still reads as `> 0` and the buttons would go live instantly.
                val now = System.currentTimeMillis()
                val enabledAt = ApprovalGate.currentEnabledAtMs()
                val armed = enabledAt > 0L && ArmGuard.isArmed(enabledAt, now)

                when {
                    enabledAt == 0L -> {
                        // No arm time yet: the summary is still being fetched.
                        countdown.text = "⏳ waiting for the summary…"
                        countdown.setTextColor(Theme.ON_SURFACE_VARIANT)
                    }
                    !armed -> {
                        // THE GUARD WINDOW (Mido, 2026-10-21: *"when AI is disabled, we need the
                        // 3 seconds protection again to avoid misclicking the dialog"*). Say what
                        // it is — a silent dead modal looks broken, and a human who does not know
                        // why the button is inert will tap harder, which is the opposite of safe.
                        //
                        // Deliberately NOT the auto-deny clock: the 60s window has not started,
                        // so showing it here would claim time the human has not been given.
                        val left = ArmGuard.remainingSeconds(enabledAt, now)
                        countdown.text = "⏳ ready in ${left}s"
                        countdown.setTextColor(Theme.ON_SURFACE_VARIANT)
                        countdown.contentDescription = "arming in ${left}s"
                    }
                    else -> {
                        // Armed: now the real 60s window, measured from the arm time — so the
                        // human gets their FULL decision time once the buttons work.
                        val elapsedSec = (now - enabledAt) / 1000L
                        val left = (timeoutSeconds - elapsedSec).coerceAtLeast(0L)
                        val pct = if (timeoutSeconds > 0) (left * 100 / timeoutSeconds) else 0
                        countdown.text = "⏳ auto-deny in ${left}s"
                        countdown.setTextColor(if (left <= 10) Theme.DANGER else Theme.WARNING)
                        countdown.contentDescription = "timeout progress ${pct}%"
                    }
                }

                // The gate's live state is the ONLY enable trigger — no timer — and the guard is
                // part of that state, not a second condition bolted on here.
                applyArmed(armed)

                // Refresh the queue label from live state every tick, so it appears
                // the moment a follower queues (never a value sampled once at
                // enqueue time, which was always 0 for the request that became
                // head while the follower was still arriving).
                val behind = ApprovalGate.waitingBehindCurrent()
                queueLine.visibility = if (behind > 0) ViewGroup.VISIBLE else ViewGroup.GONE
                if (behind > 0) {
                    queueLine.text = "≡ $behind waiting behind this one"
                }

                // Keep ticking while the modal is live. A loading modal has no countdown to
                // expire on, so the tick — not a timer — is what ends it.
                if (!isFinishing) {
                    if (ApprovalGate.isCurrent(id)) {
                        handler.postDelayed(this, 120L)
                    } else if (!stamping) {
                        // The gate gave up (window expired) or the request was replaced.
                        // Close the modal rather than leaving a dead prompt on screen: the
                        // server has already answered, so a modal still asking is a lie.
                        //
                        // NOT while a stamp is playing: a resolved request is exactly what
                        // starts the stamp, so finishing here would kill the animation on its
                        // first frame. The overlay finishes the Activity itself via onFinished.
                        finish()
                    }
                }
            }
        }
        handler.post(tick)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // The arming indicator. INDETERMINATE on purpose: inference is a blocking call with
        // no token stream, so any determinate fill would be a number we made up. It is shown
        // only while the summary is being fetched and hidden the instant the buttons go live
        // (Mido, 2026-10-19 — the buttons enable on the fetch, not on a fixed timer).
        val armBar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            trackThickness = Theme.dp(this@ApprovalActivity, 3)
            trackCornerRadius = Theme.dp(this@ApprovalActivity, 2)
            setIndicatorColor(Theme.ACCENT)
            setTrackColor(Theme.SURFACE_VARIANT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Theme.dp(this@ApprovalActivity, 10) }
        }

        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad / 2)
        }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(
                Theme.dp(this@ApprovalActivity, 56),
                Theme.dp(this@ApprovalActivity, 56)
            )
        }
        iconRow.addView(icon)

        val deny = Theme.button(this, "DENY", Theme.ButtonStyle.NEUTRAL) {
            finishWith(id, approved = false)
        }.apply {
            isEnabled = false
            alpha = 0.4f
        }
        val allow = Theme.button(this, "APPROVE", Theme.ButtonStyle.SUCCESS) {
            // Mutating routes bind APPROVE to a live biometric: the tap opens the
            // system prompt and the request fires only on success. A failed or
            // dismissed prompt leaves the request PENDING with APPROVE still live,
            // so tapping again gives another attempt — the countdown keeps running
            // underneath and is the only limit. Reads approve on a plain tap.
            //
            // A password request does BOTH, in this order: the fingerprint proves the
            // human is present, then the chars are read from the field. Order matters —
            // collecting the password first would leave it held in memory across an
            // asynchronous prompt that may never complete.
            if (requiresBiometric) {
                promptBiometric { approveNow(id) }
            } else {
                approveNow(id)
            }
        }.apply {
            isEnabled = false
            alpha = 0.4f
        }

        // The exception button (Mido, 2026-10-19): approve this request AND make its pair
        // behave this way from now on. Its own full-width row, NOT a third column in the
        // DENY/APPROVE pair — that pair carries 12dp specifically so the two decisions are
        // never one fat-finger apart, and wedging a third button between them would undo
        // that. Secondary style, so a reflexive tap meant for APPROVE cannot land here.
        //
        // The offer is null unless the switch is AUTOMATIC (the only mode that reads the
        // rule table), so the row simply does not exist otherwise — no disabled button
        // implying a feature that is off.
        var exceptionButton: MaterialButton? = null
        val exceptionRow: View? = offer?.let { off ->
            val btn = Theme.button(this, off.label, Theme.ButtonStyle.NEUTRAL) {
                // Same biometric gate as APPROVE — and the same ORDERING, which is not
                // cosmetic. `promptBiometric` is ASYNCHRONOUS: it shows the system prompt
                // and returns immediately, and the resolve happens later in its callback.
                //
                // The first version called `finish()` unconditionally right after starting
                // the prompt, which destroyed this Activity before the fingerprint could
                // run. The callback never fired, the request was never resolved, and the
                // gate re-posted the modal — reported on device as *"I clicked always
                // notify like 5 times, modal kept coming back, only when I clicked approve
                // I got a fingerprint"*. The missing prompt was the tell: a prompt that
                // never appears means the Activity died first.
                //
                // APPROVE never had this bug because its resolve+finish lives INSIDE the
                // callback. This now mirrors it exactly: nothing finishes until the
                // decision is actually resolved. On a dismissed or failed prompt we do
                // nothing at all, so the request stays pending and the human can retry —
                // same behaviour as APPROVE.
                if (requiresBiometric) {
                    promptBiometric {
                        ApprovalGate.resolveWithException(id, off.rule)
                        finish()
                    }
                } else {
                    ApprovalGate.resolveWithException(id, off.rule)
                    finish()
                }
            }.apply {
                isEnabled = false
                alpha = 0.4f
            }
            exceptionButton = btn
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(btn)
                addView(
                    label(
                        "approves this request, and sets ${off.rule.plugin} · " +
                            "${off.rule.verb?.name?.lowercase() ?: "any verb"} to " +
                            "${off.rule.mode.name.lowercase()} from now on",
                        11f, Theme.ON_SURFACE_VARIANT
                    )
                )
            }
        }
        // Fails closed: if a mutating route needs a biometric that this device cannot
        // provide (none enrolled, none present), APPROVE must never be tappable — only
        // DENY works, and the reason is stated.
        val biometricUsable = requiresBiometric && canUseBiometric()
        val biometricBlocked = requiresBiometric && !biometricUsable

        // The ONE enable path. No timer any more (Mido, 2026-10-19): the tick above calls
        // this with the gate's live `enabledAtMs > 0`, so the controls go live exactly when
        // the summary lands — or when a route needs no summary at all.
        //
        // DENY is enabled FIRST and independently: denying is fail-closed, and making someone
        // wait for a summary they do not need in order to REJECT a request is backwards.
        // APPROVE waits for the fetch and, when a biometric is required but unusable, never
        // enables at all (fails closed, stated below).
        applyArmed = { armed ->
            // Guarded rather than early-returned: a lambda assigned to a `var` has no label
            // to `return@` from, and the guard reads the same anyway.
            if (!isFinishing) {
                armBar.visibility = if (armed) ViewGroup.GONE else ViewGroup.VISIBLE
                lastArmed = armed
                deny.isEnabled = true
                deny.alpha = 1f
                if (armed && !biometricBlocked) {
                    // A password request is not approvable on an empty field: tapping
                    // APPROVE would then resolve, and sudo would reject seconds later
                    // with a message about a terminal rather than about the typo.
                    val ready = passwordReady()
                    allow.isEnabled = ready
                    allow.alpha = if (ready) 1f else 0.4f
                    // The exception button approves a request, so it arms on the SAME
                    // signal APPROVE does — never earlier, or a tap could land before the
                    // summary the human is meant to read.
                    exceptionButton?.let { it.isEnabled = true; it.alpha = 1f }
                } else if (!armed) {
                    allow.isEnabled = false
                    allow.alpha = 0.4f
                    exceptionButton?.let { it.isEnabled = false; it.alpha = 0.4f }
                }
            }
        }
        // APPROVE wears the risk colour (Mido, 2026-10-19: *"make the approve button color
        // match the risk label color"*). The risk is the one fact the human needs before
        // tapping, and the button is the strongest place it can sit.
        //
        // DENY stays NEUTRAL on purpose: two coloured decision buttons would read as a pair of
        // equal choices, which is what the 0.23.0 pass separated them to avoid. Red APPROVE
        // beside grey DENY still says "approve, carefully".
        //
        // null means no assessment yet (still loading, or the fetch failed). The button then
        // keeps its resting green rather than implying a safety that was never determined —
        // an absent assessment is not "safe".
        applyRisk = { risk ->
            if (!isFinishing) {
                val colour = if (risk == null) Theme.SUCCESS else Theme.riskColor(risk)
                allow.backgroundTintList = android.content.res.ColorStateList.valueOf(colour)
            }
        }

        // Start in the loading state: buttons disabled, indicator visible. The tick flips
        // this the moment the gate reports the request is enabled.
        applyArmed(false)

        // --- SUDO PASSWORD ---
        // Only when the gate says this request cannot proceed without one. The field is
        // REQUIRED to approve, so it lives in the pinned block directly above the buttons
        // and can never scroll out of reach.
        val passwordSection: View? = if (needsPassword) {
            val field = EditText(this).apply {
                // **Masking is applied at the END of this block, on purpose.**
                // `setInputType` internally re-derives the transformation from the type, so an
                // explicit `transformationMethod` set BEFORE it is silently cleared. That is why
                // the mask kept disappearing.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                // Never learn it: the keyboard's personalised dictionary is not a place
                // a root password may live.
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                isSingleLine = true
                hint = "sudo password"
                // Not saved into instance state: a rotation must not preserve it.
                setSaveEnabled(false)
                isSaveFromParentEnabled = false
                // **NO, not NO_EXCLUDE_DESCENDANTS.** The old value was inverted: that constant
                // means "this view is unimportant, but do not exclude its descendants", i.e. it
                // PERMITS autofill. Password managers read it as "go ahead" and offered to save
                // the root password (Mido, 2026-10-27: *"stop password managers from suggesting to
                // save it"*). `IMPORTANT_FOR_AUTOFILL_NO` is the one that opts this view out.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                // `autofillHints` is a read-only property on TextView, so it cannot be cleared
                // here; the flag above is the one the AutofillManager actually consults.
                setPadding(
                    Theme.dp(this@ApprovalActivity, 12), Theme.dp(this@ApprovalActivity, 12),
                    Theme.dp(this@ApprovalActivity, 12), Theme.dp(this@ApprovalActivity, 12)
                )
                background = Theme.sheetDp(this@ApprovalActivity, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL)
                setTextColor(Theme.ON_SURFACE)
                // No copy/cut. A long-press menu that can lift the password into the
                // clipboard is a leak with a friendly face.
                customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
                    override fun onPrepareActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
                    override fun onActionItemClicked(m: android.view.ActionMode?, item: android.view.MenuItem?) = false
                    override fun onDestroyActionMode(m: android.view.ActionMode?) {}
                }
                // THE MASK, last: nothing above can clear it now.
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            }
            passwordField = field
            passwordReady = { field.text.isNotEmpty() }
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // Re-evaluate so APPROVE lights up the instant the field is non-empty,
                    // without needing a second tap or a tick.
                    applyArmed(lastArmed)
                }
            })
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, Theme.dp(this@ApprovalActivity, 10), 0, Theme.dp(this@ApprovalActivity, 10))
                addView(Theme.sectionHeader(this@ApprovalActivity, "SUDO PASSWORD"))
                addView(field)
                addView(
                    label(
                        "runs as root — the password goes to sudo's stdin on this device, " +
                            "not over the network",
                        11f, Theme.ON_SURFACE_VARIANT
                    )
                )
            }
        } else null
        // A gap between the two decisions, so APPROVE and DENY are never one fat-finger
        // apart (Mido, 2026-10-19: *"add margins between the buttons"*).
        btnRow.addView(deny, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Theme.dp(this@ApprovalActivity, 12)
        })
        btnRow.addView(allow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Scroll the DESCRIPTIVE content only; pin the decision controls (icon,
        // countdown, details toggle, buttons) to the bottom of the screen so they
        // are always reachable no matter how long the notification is. Buttons
        // that scroll away are buttons the user misses -> a timed-out request.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

        val pinned = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            background = Theme.sheet(Theme.BG)
        }
        pinned.addView(detailToggle)
        pinned.addView(detailScroll)
        pinned.addView(queueLine)
        pinned.addView(countdown)
        if (biometricBlocked) {
            pinned.addView(
                label("⚠ APPROVE unavailable — set up a fingerprint/PIN (strong) to approve", 14f, Theme.DANGER)
            )
        }
        pinned.addView(iconRow)
        pinned.addView(armBar)
        if (exceptionRow != null) {
            pinned.addView(
                exceptionRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Theme.dp(this@ApprovalActivity, 10) }
            )
        }
        if (passwordSection != null) {
            pinned.addView(
                passwordSection,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        pinned.addView(btnRow)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.sheet(Theme.BG)
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(pinned)
        }
        // Keep the modal clear of the system bars (Mido, 2026-10-19: *"add top padding so
        // that when I get a notification with the modal it doesn't overlap"*). Applied as
        // WINDOW INSETS, not a fixed dp: the status bar is a different height per device and
        // orientation, and this modal is drawn over whatever was on screen — so a hardcoded
        // number would be wrong exactly where it matters. Bottom is included too, so the
        // buttons never sit under the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(outer)
    }

    /**
     * True from the moment a decision is taken until the stamp has finished.
     *
     * The 120ms tick loop calls `finish()` as soon as `ApprovalGate.isCurrent(id)` is false —
     * which is the INSTANT `finishWith` resolves the request, i.e. before the stamp has drawn a
     * single frame. So the overlay was added to a window that was already being torn down, and
     * the animation died after a frame or two (Mido, 2026-10-27: *"it dismisses too fast to see
     * the animation"*).
     *
     * The tick must not decide the modal is dead while a stamp is playing: the stamp IS the exit.
     */
    private var stamping = false

    private fun finishWith(id: Long, approved: Boolean) {
        val resolved = ApprovalGate.resolve(id, approved)
        if (!resolved) Log.w("ApprovalActivity", "request $id already resolved/expired")
        // The stamp fires on the human's decision — both approve AND deny — not on
        // arrival. One firm rubber-stamp thud + the red ink mark, then the request
        // resolves and the modal leaves. See StampOverlay.
        showStamp(approved)
    }

    /**
     * The full-screen rubber-stamp moment, on every decision.
     *
     * [StampOverlay] draws the red ink mark over a dimmed scrim, plays the physical
     * thud + haptic (via [SoundAlerts.stamp]), and only then finishes the Activity on
     * its [StampOverlay.onFinished]. The gate is already resolved above, so the only
     * thing this delays is the modal's disappearance — never the decision itself.
     */
    private fun showStamp(approved: Boolean) {
        // Set BEFORE the overlay is added, so the tick loop cannot finish the Activity between
        // these two statements.
        stamping = true
        val root = (findViewById<android.view.ViewGroup>(android.R.id.content))
        val overlay = StampOverlay(this).apply {
            val verdict = if (approved) StampOverlay.Verdict.APPROVED else StampOverlay.Verdict.DENIED
            onFinished = { finish() }
        }
        // Full-screen, on top of everything in this window (the modal).
        root.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        SoundAlerts.stamp(this)
        overlay.stamp(if (approved) StampOverlay.Verdict.APPROVED else StampOverlay.Verdict.DENIED)
    }

    /**
     * Approve, taking the password path when this request needs one.
     *
     * Reads the chars straight out of the `Editable` rather than through
     * `text.toString()`: a String cannot be wiped, and going through one would leave an
     * unremovable copy of the password in the heap for no benefit.
     */
    private fun approveNow(id: Long) {
        val field = passwordField
        if (!needsPassword || field == null) {
            finishWith(id, approved = true)
            return
        }
        val chars = CharArray(field.length())
        field.text.getChars(0, field.length(), chars, 0)
        finishWithPassword(id, chars)
    }

    /**
     * Approve a request that needed a password, handing the chars to the gate.
     *
     * **The caller's array is wiped immediately after the hand-off.** The gate keeps its
     * own copy, so this one has no reason to survive — and an Activity that is finishing
     * is exactly the kind of object whose memory sits around afterwards.
     *
     * The chars are read straight out of the `Editable` rather than through
     * `text.toString()`: a String cannot be wiped, and going through one would leave an
     * unremovable copy of the password in the heap for no benefit.
     */
    private fun finishWithPassword(id: Long, password: CharArray) {
        val resolved = try {
            ApprovalGate.resolveWithPassword(id, password)
        } finally {
            // Wipe first, before anything can throw or delay.
            password.fill('\u0000')
        }
        if (!resolved) {
            Log.w("ApprovalActivity", "request $id does not need a password, or expired")
            // Nothing was approved, so there is no decision to stamp. Leave the modal up so the
            // human can retry rather than dismissing on a silent failure.
            return
        }
        // **The stamp fires here too.** This path used to call `finish()` directly, which is why
        // the sudo modal dismissed instantly with no stamp and no sound — the password approve
        // was the ONLY decision that skipped the animation entirely (Mido, 2026-10-27: *"no
        // fucking stamp sound and immediately dismissed"*). Every other approve/deny goes through
        // `finishWith`, which stamps; this one has to match.
        showStamp(approved = true)
    }

    /**
     * Bind a mutating APPROVE to a live biometric.
     *
     * When a vault exists the prompt carries the vault's `CryptoObject` (decision A:
     * every scan is crypto-bound). Two things fall out of that: the approval is
     * unforgeable — no plugin, bug or stray callback can approve without a live finger on
     * a hardware key — and if the vault happens to be locked, the SAME authorised cipher
     * unwraps it, so one modal both unlocks and approves. Fingerprint-only, by platform
     * rule: a crypto-bound prompt cannot accept `DEVICE_CREDENTIAL`.
     *
     * Before the vault exists there is nothing to bind to, so the prompt falls back to
     * the plain form (STRONG or device credential). That is not a weakening: there is no
     * secret on the device yet, and keeping the PIN escape hatch until setup is done is
     * strictly more usable.
     *
     * On success [onApproved] fires and the request resolves. On failure/dismiss we do
     * NOTHING to the request: it stays pending on its own countdown, APPROVE stays
     * live, and tapping it again re-prompts — the window is the only limit. Reads never
     * reach here (see [ApprovalView.requiresBiometric]).
     */
    private fun promptBiometric(onApproved: () -> Unit) {
        // Prepare the vault cipher FIRST: when a vault exists the prompt must carry it
        // (decision A — every scan is crypto-bound), and if the key was invalidated there
        // is no prompt worth showing. Failing closed here is the whole point.
        val cipher = try {
            if (SecretVault.exists(this)) SecretVault.prepareUnlockCipher(this) else null
        } catch (e: Exception) {
            Log.w("ApprovalActivity", "vault cipher unavailable (${e.message}); not approving")
            return
        }
        val authenticators = if (cipher != null) {
            // Crypto-bound: the platform forbids DEVICE_CREDENTIAL alongside a
            // CryptoObject, so a bound prompt is fingerprint-only by construction.
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        val manager = BiometricManager.from(this)
        val can = manager.canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Fails closed: no prompt, no approval. The caller disables APPROVE and
            // states the reason; this guard exists so a stray tap can never approve.
            Log.w("ApprovalActivity", "biometric unavailable (code=$can); not approving")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cipher != null) {
                        val authorised = result.cryptoObject?.cipher
                        if (authorised == null) {
                            // Impossible in principle: we passed a CryptoObject. Refuse
                            // rather than treat "authenticated" as sufficient.
                            Log.w("ApprovalActivity", "no authorised cipher returned; not approving")
                            return
                        }
                        if (!SecretVault.isUnlocked()) {
                            try {
                                // One modal does both (decision A): the finger that
                                // approves also opens the vault, so a SECURE call on a
                                // locked vault is not a dead end.
                                SecretVault.unlock(this@ApprovalActivity, authorised)
                                Log.i("ApprovalActivity", "vault unlocked by the approval prompt")
                            } catch (e: Exception) {
                                Log.w("ApprovalActivity", "vault unlock failed: ${e.message}")
                                return
                            }
                        }
                    }
                    // Success wins the race: the request is approved even if the
                    // countdown was about to expire. resolve() is idempotent — if the
                    // window DID close first, it is a no-op and logged, never a wrong
                    // approval (resolve only applies while the request is still pending).
                    onApproved()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Dismissed / locked out: leave the request pending. APPROVE stays
                    // live so the human can try again within the window (point 7).
                    Log.i("ApprovalActivity", "biometric error $errorCode: $errString")
                }
                override fun onAuthenticationFailed() {
                    // A single bad read: the prompt stays up for a retry; nothing to do.
                    Log.i("ApprovalActivity", "biometric failed (retryable)")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (cipher != null) "Unlock and approve" else "Approve request")
            .setSubtitle(endpointLabel())
            .setAllowedAuthenticators(authenticators)
        // A STRONG-only prompt REQUIRES a negative button (the platform throws without
        // one). With device credential allowed the system supplies its own, so adding
        // one there would be an error — hence the branch.
        if (cipher != null) info.setNegativeButtonText("Cancel")
        if (cipher != null) {
            prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(cipher))
        } else {
            prompt.authenticate(info.build())
        }
    }

    private fun endpointLabel(): String =
        intent.getStringExtra(EXTRA_ENDPOINT) ?: "PhoneBridge"

    /**
     * Can this device satisfy the approval requirement right now? Uses the SAME
     * authenticator set as [promptBiometric], so the button state and the prompt can
     * never disagree: when a vault exists that is STRONG biometric alone (crypto-bound,
     * decision A), otherwise STRONG or device credential.
     *
     * Also fails closed when the vault cipher cannot be prepared — an invalidated key
     * means the prompt could never succeed, so APPROVE must not enable.
     */
    private fun canUseBiometric(): Boolean {
        val bound = if (SecretVault.exists(this)) {
            try {
                // Prepare it for its side effect: this throws when the key is
                // invalidated, which is exactly the case where APPROVE must not enable.
                SecretVault.prepareUnlockCipher(this)
                true
            } catch (e: Exception) {
                Log.w("ApprovalActivity", "vault cipher unavailable; APPROVE stays disabled: ${e.message}")
                return false
            }
        } else {
            false
        }
        val authenticators = if (bound) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        return BiometricManager.from(this).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    @Deprecated("Deprecated in the platform; kept for back-compat behavior on older devices.")
    override fun onBackPressed() {
        // Back == deny. Never leave a request dangling open by accident.
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        finishWith(id, approved = false)
    }

    /**
     * Drop the reference to the password field, and wipe whatever it still holds.
     *
     * A finishing Activity is exactly the kind of object whose memory sits around
     * afterwards, and this one may be holding a root password. Clearing the `Editable`
     * first means the chars are gone even if a view reference survives somewhere; dropping
     * the field then removes the last easy path to them.
     */
    override fun onDestroy() {
        passwordField?.text?.clear()
        passwordField = null
        super.onDestroy()
    }
}
