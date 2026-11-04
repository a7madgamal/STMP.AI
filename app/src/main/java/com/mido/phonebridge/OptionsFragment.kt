package com.mido.phonebridge

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.core.SecretVault
import com.mido.phonebridge.ui.Theme

/**
 * Device-level PREFERENCES — not security decisions.
 *
 * **Deliberately separate from [ExceptionsActivity].** That screen authors the gate: which
 * `plugin × verb` pairs behave differently from "ask". Every write there decides whether a human
 * is asked, so it is strict, audited as `POLICY_CHANGED`, and a broken file refuses to start the
 * server. These toggles are the opposite: a preference, cheap to flip, and a broken value must
 * never be able to take the bridge down.
 *
 * The two live in the same `policy.json` for now (Mido, 2026-10-20: *"single file for now"*), but
 * they are parsed on different terms — see `Policy.lenientFlag`. Location is a file-layout
 * decision; strictness is the property that actually matters, and that one is preserved.
 *
 * The screen exists so the NEXT setting has a home instead of becoming another row on the home
 * checklist, which is already long.
 */
class OptionsFragment : BridgeFragment() {

    private lateinit var logView: TextView

    private lateinit var container: LinearLayout

    // This screen IS the Settings tab. It inherits the bar and the insets from BridgeScreen,
    // which also carries the `ApprovalGate.relaunchCurrent` call that MainActivity used to own —
    // sitting here while a request was pending used to strand the modal off-screen.
    override val tab = Theme.Tab.SETTINGS

    override fun buildContent(container: LinearLayout) {
        this.container = container
        render()
    }

    override fun repaint() = render()

    private fun render() {
        container.removeAllViews()

        container.addView(Theme.text(ctx, "OPTIONS", 20f).apply {
            typeface = Theme.handFont(ctx)
        })

        val store = PolicyStore.from(requireContext())
        val policy = try {
            store.get()
        } catch (e: PolicyException) {
            container.addView(
                Theme.text(
                    ctx,
                    "⚠ policy unreadable: ${e.message}\n\nThe server will not start until this " +
                        "file is fixed or removed.",
                    12f, Theme.DANGER
                )
            )
            return
        }

        // --- BRIDGE: how the server behaves ---
        container.addView(Theme.sectionHeader(ctx, "Bridge"))

        // --- auto-start ---
        container.addView(
            toggleRow(
                title = "Auto-start on boot",
                state = if (policy.autoStartOnBoot) "ON" else "OFF",
                detail = if (policy.autoStartOnBoot) {
                    "The bridge starts itself after a reboot, after an app update, and when the " +
                        "app opens. Tailscale can take a few seconds to come up, so it retries " +
                        "for about 3 minutes and tells you if it gives up."
                } else {
                    "Nothing starts on its own. Open the app and tap START SERVER. After a " +
                        "reboot the bridge stays down until you do."
                },
                colour = if (policy.autoStartOnBoot) Theme.SUCCESS else Theme.ON_SURFACE_VARIANT,
                onToggle = { save(store, policy.withAutoStartOnBoot(!policy.autoStartOnBoot)) }
            )
        )

        // The fingerprint toggle moved to Home, into the gate card (Mido, 2026-11-03: *"gate on
        // home"*). The mode picker and the fingerprint requirement are one subject — when you are
        // asked, and how you prove it — so splitting them across two screens meant neither could
        // be reasoned about in full.

        container.addView(Theme.sectionHeader(ctx, "AI"))

        // --- AI backend: off / local / external ---
        //
        // A three-way choice, not a toggle. "AI on" stopped being one thing when a remote
        // backend became possible: LOCAL loads the on-device model file, EXTERNAL asks a box on
        // the tailnet, and they fail differently. A toggle would have to mean one of them and
        // hide the other behind a second setting that could disagree with it.
        container.addView(
            aiBackendRow(store, policy)
        )
        // The model row sits with the backend that uses it. It was under Permissions, which put
        // "which model is loaded" a screen-section away from "which backend runs it".
        container.addView(aiModelRow(store, policy))

        // Speak used to be configured here. It now lives on the plugin that owns it — Plugins →
        // Speak — so the settings sit with the routes they govern instead of in a second list
        // that had to be kept in step by hand.

        container.addView(Theme.sectionHeader(ctx, "Permissions"))

        // These five rows came OFF Home (Mido, 2026-11-03: *"rethink each button screen"*).
        // They are device and service state, not live answers about whether the bridge is
        // running — which is the one question Home now answers. Kept in the same order the
        // right-hand bracket in Mido's drawing ran down the screen.
        container.addView(Theme.statusRow(ctx, "Lock-screen popup",
            if (ApprovalGate.canUseFullScreenIntent(ctx)) "granted (wakes a locked screen)"
            else "optional — tap if approvals miss on a locked screen",
            if (ApprovalGate.canUseFullScreenIntent(ctx)) Theme.SUCCESS
            else Theme.ON_SURFACE_VARIANT,
            onClick = { com.mido.phonebridge.ui.SystemAccess.openFullScreenIntentSettings(ctx) }
        ))
        container.addView(Theme.statusRow(ctx, "Draw over apps",
            if (com.mido.phonebridge.ui.SystemAccess.overlayGranted(ctx)) "granted" else "tap to grant",
            if (com.mido.phonebridge.ui.SystemAccess.overlayGranted(ctx)) Theme.SUCCESS
            else Theme.WARNING,
            onClick = { com.mido.phonebridge.ui.SystemAccess.openOverlaySettings(ctx) }
        ))
        container.addView(Theme.statusRow(ctx, "Battery optimization",
            if (com.mido.phonebridge.ui.SystemAccess.batteryExempt(ctx)) "exempt"
            else "tap to allow background",
            if (com.mido.phonebridge.ui.SystemAccess.batteryExempt(ctx)) Theme.SUCCESS
            else Theme.WARNING,
            onClick = { com.mido.phonebridge.ui.SystemAccess.requestBatteryExemption(ctx) }
        ))
        // The vault: locked on every new process, one scan opens it for the session. Shown here
        // because a locked vault is why a secret call answers `secret_locked`.
        container.addView(Theme.statusRow(ctx, "Secret vault",
            if (SecretVault.isUnlocked()) "unlocked (this session)" else "locked — tap to unlock",
            if (SecretVault.isUnlocked()) Theme.SUCCESS else Theme.WARNING,
            onClick = { VaultUnlock.ensureUnlocked(hostActivity, force = true) { render() } }
        ))
        // --- TOOLS: the destinations that are not tabs ---
        container.addView(Theme.sectionHeader(ctx, "Tools"))
        container.addView(toolRow("Get started") {
            go(Intent(ctx, OnboardingActivity::class.java))
        })
        container.addView(toolRow("Request log") {
            go(Intent(ctx, LogActivity::class.java))
        })
        container.addView(toolRow("Exceptions") {
            go(Intent(ctx, ExceptionsActivity::class.java))
        })
        container.addView(toolRow("SSH") {
            go(Intent(ctx, SshActivity::class.java))
        })

        // --- DEBUG ---
        container.addView(Theme.sectionHeader(ctx, "Debug"))
        container.addView(Theme.button(ctx, "SHOW LOG", Theme.ButtonStyle.GHOST) {
            DebugLogOpen.open = !DebugLogOpen.open
            logView.visibility = if (DebugLogOpen.open) TextView.VISIBLE else TextView.GONE
        })
        logView = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Theme.ON_SURFACE_VARIANT)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            visibility = if (DebugLogOpen.open) TextView.VISIBLE else TextView.GONE
            text = Debug.dump()
        }
        container.addView(logView)

        // --- CALLERS: who may reach the bridge at all ---
        container.addView(Theme.sectionHeader(ctx, "Callers"))

        // --- who may reach the bridge ---
        //
        // Not a preference: an unnamed address is REFUSED, so this list is the allow rule. It is
        // visible and editable because a block you cannot undo without hand-editing JSON is a
        // trap, and because a misheard name should be fixable without a rebuild.
        container.addView(callerRows())

        container.addView(Theme.spacer(ctx, 16))
        container.addView(
            Theme.text(
                ctx,
                "These are preferences, not gate rules. To change WHEN you are asked, use " +
                    "EXCEPTIONS.",
                12f, Theme.ON_SURFACE_VARIANT
            )
        )
    }

    /**
     * The caller registry, as a list.
     *
     * Each row can be renamed or forgotten. Forget is the ONLY way back from a block or a
     * misheard name, and it deliberately means "unverified again" rather than "allowed" — the
     * address has to re-earn a name through the prompt, which is the same door everyone else
     * came through.
     */
    private fun callerRows(): LinearLayout {
        val block = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val entries = try {
            com.mido.phonebridge.callers.CallerStore.from(requireContext()).all()
        } catch (e: Exception) {
            block.addView(
                Theme.text(
                    ctx,
                    "\u26a0 caller registry unreadable: ${e.message}\n\nEvery request is refused " +
                        "until this file is fixed or removed.",
                    12f, Theme.DANGER
                )
            )
            return block
        }

        if (entries.isEmpty()) {
            block.addView(
                Theme.text(
                    ctx,
                    "No callers yet. The first request from another machine will ask you to " +
                        "name it.",
                    12f, Theme.ON_SURFACE_VARIANT
                )
            )
            return block
        }

        for (entry in entries.values.sortedBy { it.ip }) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    Theme.dp(ctx, 12), Theme.dp(ctx, 10),
                    Theme.dp(ctx, 12), Theme.dp(ctx, 10)
                )
                background = Theme.outlined(
                    ctx, Theme.SURFACE, Theme.RADIUS_CARD
                )
            }
            val label = when (val st = entry.state) {
                is com.mido.phonebridge.callers.CallerState.Verified -> st.name
                com.mido.phonebridge.callers.CallerState.Denied -> "blocked"
            }
            val colour = when (entry.state) {
                is com.mido.phonebridge.callers.CallerState.Verified -> Theme.SUCCESS
                com.mido.phonebridge.callers.CallerState.Denied -> Theme.DANGER
            }
            row.addView(
                Theme.text(ctx, "$label · ${entry.ip}", 14f, colour).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            row.addView(
                Theme.button(ctx, "forget", Theme.ButtonStyle.NEUTRAL, small = true) {
                    try {
                        com.mido.phonebridge.callers.CallerStore
                            .from(requireContext()).forget(entry.ip)
                        com.mido.phonebridge.audit.AuditLog.from(requireContext()).append(
                            com.mido.phonebridge.audit.PolicyAudit.record(
                                policy().global, policy().global, "options"
                            )
                        )
                    } catch (e: Exception) {
                        toast("could not forget: ${e.message}")
                    }
                    render()
                }
            )
            block.addView(row)
            block.addView(Theme.spacer(ctx, 8))
        }
        return block
    }

    /**
     * How the phone speaks.
     *
     * **No on/off toggle, deliberately.** Mido, 2026-11-02: *"a silent phone is enough"* — the
     * utterance rides the notification stream, so silent/vibrate mode already silences it. A
     * toggle would be a second brake that can disagree with the first, and the one thing a
     * setting must never do is make "off" ambiguous.
     *
     * Every value is saved through [TtsSettings.clamped], so the field cannot store something the
     * engine would refuse — a rate of 0 is accepted by the UI, clamped on save, and read back as
     * the value actually in force.
     */
    private fun numberRow(
        label: String,
        value: String,
        hint: String,
        detail: String,
        integer: Boolean,
        commit: (String) -> String?,
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Theme.dp(ctx, 12), 0, 0)
        }
        row.addView(
            Theme.text(ctx, label, 13f, Theme.ON_SURFACE)
        )
        val field = EditText(ctx).apply {
            setText(value)
            this.hint = hint
            inputType = if (integer) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            setSaveEnabled(false)
            setTextColor(Theme.ON_SURFACE)
            setHintTextColor(Theme.ON_SURFACE_VARIANT)
            highlightColor = Theme.ACCENT
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            background = Theme.outlined(ctx, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL)
            setPadding(
                Theme.dp(ctx, 12), Theme.dp(ctx, 10),
                Theme.dp(ctx, 12), Theme.dp(ctx, 10)
            )
        }
        row.addView(field)
        row.addView(
            Theme.text(ctx, detail, 12f, Theme.ON_SURFACE_VARIANT).apply {
                setPadding(0, Theme.dp(ctx, 6), 0, 0)
            }
        )
        val error = Theme.text(ctx, "", 12f, Theme.DANGER).apply {
            setPadding(0, Theme.dp(ctx, 6), 0, 0)
            visibility = android.view.View.GONE
        }
        row.addView(error)
        row.addView(
            Theme.button(ctx, "save", Theme.ButtonStyle.NEUTRAL, small = true) {
                val problem = commit(field.text.toString().trim())
                if (problem != null) {
                    error.text = problem
                    error.visibility = android.view.View.VISIBLE
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(ctx, 8) }
        )
        return row
    }

    private fun policy() = com.mido.phonebridge.policy.PolicyStore.from(requireContext()).get()

    /**
     * The AI backend block: current state, what it costs, and one button per alternative.
     *
     * Buttons name the DESTINATION ("on-device", "use external") rather than toggling, because
     * three states cannot be expressed as on/off without hiding which one is active.
     *
     * The external row states the failure mode plainly: an unreachable box refuses requests
     * rather than approving them without a description. That is a decision (Mido, 2026-10-31:
     * *"fail"*) and a human choosing EXTERNAL should read it here, not discover it later when the
     * LAN box is asleep.
     */
    private fun aiBackendRow(
        store: com.mido.phonebridge.policy.PolicyStore,
        policy: com.mido.phonebridge.policy.Policy,
    ): LinearLayout {
        val current = policy.aiBackend
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(ctx, 14), Theme.dp(ctx, 12),
                Theme.dp(ctx, 14), Theme.dp(ctx, 12)
            )
            background = Theme.outlined(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            Theme.text(ctx, "AI summaries", 15f, Theme.ON_SURFACE).apply {
                typeface = Theme.handFont(ctx)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        header.addView(
            Theme.text(
                ctx,
                when (current) {
                    com.mido.phonebridge.policy.AiBackend.OFF -> "OFF"
                    com.mido.phonebridge.policy.AiBackend.LOCAL -> "LOCAL"
                    com.mido.phonebridge.policy.AiBackend.EXTERNAL -> "EXTERNAL"
                },
                13f,
                if (current == com.mido.phonebridge.policy.AiBackend.OFF) {
                    Theme.ON_SURFACE_VARIANT
                } else {
                    Theme.SUCCESS
                }
            )
        )
        block.addView(header)
        block.addView(
            Theme.text(
                ctx,
                when (current) {
                    com.mido.phonebridge.policy.AiBackend.OFF ->
                        "No model is loaded, no request is described, and nothing is downloaded. " +
                            "You still see the exact target and payload — the rows are built " +
                            "deterministically. This frees the memory the model holds."
                    com.mido.phonebridge.policy.AiBackend.LOCAL ->
                        "Each request is described and risk-labelled on the device before you " +
                            "decide. The model stays loaded, so it costs memory while the server runs."
                    com.mido.phonebridge.policy.AiBackend.EXTERNAL ->
                        "Requests are described by a model server on your tailnet. If that box is " +
                            "unreachable, requests are REFUSED with an error naming it — never " +
                            "approved without a description. Nothing is loaded on the phone."
                },
                12f, Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(ctx, 6), 0, 0) }
        )

        // One button per state that is not the current one.
        val choices = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Theme.dp(ctx, 10), 0, 0)
        }
        for (target in com.mido.phonebridge.policy.AiBackend.values()) {
            if (target == current) continue
            val label = when (target) {
                com.mido.phonebridge.policy.AiBackend.OFF -> "turn off"
                com.mido.phonebridge.policy.AiBackend.LOCAL -> "on-device"
                com.mido.phonebridge.policy.AiBackend.EXTERNAL -> "use external"
            }
            choices.addView(
                Theme.button(ctx, label, Theme.ButtonStyle.NEUTRAL, small = true) {
                    save(store, policy.withAiBackend(target))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Theme.dp(ctx, 8) }
            )
        }
        block.addView(choices)

        // --- the external endpoint ---
        //
        // **Only shown when EXTERNAL is selected**, because a URL field under a backend that
        // ignores it is a control that does nothing — the same lie the single-boolean gate told
        // before `BioStatus`. It is editable rather than a build constant because choosing
        // EXTERNAL and finding no way to say WHERE is a dead end: the setting promises a remote
        // server and then refuses for want of an address nobody could enter.
        if (current == com.mido.phonebridge.policy.AiBackend.EXTERNAL) {
            block.addView(
                Theme.text(
                    ctx,
                    "Server URL",
                    13f, Theme.ON_SURFACE
                ).apply { setPadding(0, Theme.dp(ctx, 14), 0, Theme.dp(ctx, 6)) }
            )

            val urlField = EditText(ctx).apply {
                setText(policy.aiExternalUrl)
                hint = "http://host:port  (no trailing slash)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                isSingleLine = true
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                setSaveEnabled(false)
                setTextColor(Theme.ON_SURFACE)
                setHintTextColor(Theme.ON_SURFACE_VARIANT)
                highlightColor = Theme.ACCENT
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                background = Theme.outlined(ctx, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL)
                setPadding(
                    Theme.dp(ctx, 12), Theme.dp(ctx, 10),
                    Theme.dp(ctx, 12), Theme.dp(ctx, 10)
                )
            }

            // Fail hard, before saving: a URL the app cannot parse makes every AI-describable
            // route fail, and saving it would hide the mistake behind a "not reachable" that
            // looks like the server's fault rather than the address's.
            val errorLine = Theme.text(ctx, "", 12f, Theme.DANGER).apply {
                setPadding(0, Theme.dp(ctx, 6), 0, 0)
                visibility = android.view.View.GONE
            }

            block.addView(urlField)

            // --- optional token ---
            //
            // **Masked, because it is a credential.** Unsloth treats a private host as trusted and
            // answers 401 with `www-authenticate: Bearer` the moment the same server is reached
            // over a tailnet address, which is not private. Sending a token is the honest fix;
            // routing around auth with a Host-rewriting proxy is not.
            //
            // Optional on purpose: a loopback or LAN server needs none, and sending an empty
            // Authorization header makes some servers reject the request outright.
            block.addView(
                Theme.text(
                    ctx,
                    "Token (optional)",
                    13f, Theme.ON_SURFACE
                ).apply { setPadding(0, Theme.dp(ctx, 12), 0, Theme.dp(ctx, 6)) }
            )

            val tokenField = EditText(ctx).apply {
                setText(policy.aiExternalToken)
                hint = "leave empty if the server trusts this device"
                // Masking applied AFTER inputType, for the reason recorded in ApprovalActivity:
                // setInputType re-derives the transformation and would clear an earlier one.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setSaveEnabled(false)
                isSaveFromParentEnabled = false
                setTextColor(Theme.ON_SURFACE)
                setHintTextColor(Theme.ON_SURFACE_VARIANT)
                highlightColor = Theme.ACCENT
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                background = Theme.outlined(ctx, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL)
                setPadding(
                    Theme.dp(ctx, 12), Theme.dp(ctx, 10),
                    Theme.dp(ctx, 12), Theme.dp(ctx, 10)
                )
                // Set LAST, after inputType: `setInputType` re-derives the transformation from
                // the type and silently clears anything assigned before it — the bug recorded in
                // ApprovalActivity. Declared explicitly so the mask is not left to the theme.
                transformationMethod =
                    android.text.method.PasswordTransformationMethod.getInstance()
            }
            block.addView(tokenField)

            // --- the model, chosen from what the SERVER says it has ---
            //
            // **A picker, not a text field, and the list comes from the server.** A model id is
            // an exact string a server decides; letting it be typed is how "qwen3:8b" ended up
            // in a constant while the server served something else entirely. Unsloth answers an
            // unknown tag with its loaded default instead of failing, so a wrong name is not an
            // error a human sees — it is a silent substitution.
            block.addView(
                Theme.text(ctx, "Model", 13f, Theme.ON_SURFACE)
                    .apply {
                        setPadding(
                            0, Theme.dp(ctx, 14), 0,
                            Theme.dp(ctx, 6)
                        )
                    }
            )

            // The last successful listing, held here because the picker is populated
            // asynchronously and `save` must store the dialect that produced the choice — the two
            // travel together or the id goes down a route the server does not implement.
            var listed: com.mido.phonebridge.ai.ModelCatalogue? = null

            val modelStatus = Theme.text(
                ctx,
                if (policy.aiExternalModel.isBlank()) {
                    "No model selected. Press refresh to ask the server — EXTERNAL refuses " +
                        "until one is chosen."
                } else {
                    "Using ${policy.aiExternalModel}" +
                        (policy.aiExternalDialect?.let { " (${it.label})" } ?: "")
                },
                12f,
                if (policy.aiExternalModel.isBlank()) Theme.WARNING else Theme.ON_SURFACE_VARIANT
            ).apply { setPadding(0, Theme.dp(ctx, 8), 0, 0) }

            val modelSpinner = android.widget.Spinner(ctx).apply {
                visibility = android.view.View.GONE
                background = Theme.outlined(
                    ctx, Theme.SURFACE_VARIANT, Theme.RADIUS_CONTROL
                )
            }

            val refresh = Theme.button(ctx, "refresh models", Theme.ButtonStyle.NEUTRAL, small = true) {
                val url = urlField.text.toString().trim()
                val problem = validateEndpoint(url)
                if (problem != null) {
                    errorLine.text = problem
                    errorLine.visibility = android.view.View.VISIBLE
                    return@button
                }
                if (url.isBlank()) {
                    errorLine.text = "Enter the server URL first, then refresh."
                    errorLine.visibility = android.view.View.VISIBLE
                    return@button
                }
                errorLine.visibility = android.view.View.GONE
                modelStatus.text = "asking $url …"

                // Off the main thread: this is a network call and the UI thread is not allowed
                // to block on one. The result comes back through ui { } so every view
                // touch happens where Android requires it.
                Thread {
                    // An explicit outcome, NOT a Pair that has to be re-cast.
                    //
                    // The first version built `null to message` on failure and returned the
                    // catalogue directly on success, then tried `outcome as? Pair<*, *>` to pull
                    // them apart — which is null for BOTH, so a working server reported
                    // "could not list models — no answer" every single time. A success value and
                    // a failure value that need a cast to tell apart is a type doing two jobs.
                    val outcome: Result<com.mido.phonebridge.ai.ModelCatalogue> = try {
                        Result.success(
                            com.mido.phonebridge.ai.ExternalModels.fetch(
                                url, tokenField.text.toString().trim()
                            )
                        )
                    } catch (e: Exception) {
                        Result.failure(
                            IllegalStateException(e.message ?: e.javaClass.simpleName, e)
                        )
                    }
                    ui {
                        val catalogue = outcome.getOrNull()
                        if (catalogue == null) {
                            val why = outcome.exceptionOrNull()?.message ?: "no answer"
                            modelStatus.text = "could not list models — $why"
                            modelStatus.setTextColor(Theme.DANGER)
                            modelSpinner.visibility = android.view.View.GONE
                            return@ui
                        }
                        listed = catalogue
                        modelSpinner.adapter = android.widget.ArrayAdapter(
                            ctx,
                            android.R.layout.simple_spinner_dropdown_item,
                            catalogue.models
                        )
                        // Preselect the stored model when the server still offers it, so a
                        // refresh does not silently move a working install to a different model.
                        val keep = catalogue.models.indexOf(policy.aiExternalModel)
                        if (keep >= 0) modelSpinner.setSelection(keep)
                        modelSpinner.visibility = android.view.View.VISIBLE
                        modelStatus.text = "${catalogue.models.size} model(s) — " +
                            "picked from a ${catalogue.dialect.label} server. Press save."
                        modelStatus.setTextColor(Theme.ON_SURFACE_VARIANT)
                    }
                }.start()
            }

            block.addView(refresh, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(ctx, 8) })
            block.addView(modelSpinner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(ctx, 8) })
            block.addView(modelStatus)

            block.addView(errorLine)

            block.addView(
                Theme.button(ctx, "save", Theme.ButtonStyle.PRIMARY, small = true) {
                    val raw = urlField.text.toString().trim()
                    val problem = validateEndpoint(raw)
                    if (problem != null) {
                        errorLine.text = problem
                        errorLine.visibility = android.view.View.VISIBLE
                        return@button
                    }
                    // **A model is REQUIRED**, not defaulted. Without one the app would have to
                    // invent a name, and an invented name is exactly the fallback that made this
                    // backend look broken while it was really asking for a model that did not
                    // exist. Refusing here is the honest failure.
                    val chosen = (modelSpinner.visibility == android.view.View.VISIBLE)
                        .let { visible ->
                            if (visible) modelSpinner.selectedItem?.toString() else null
                        } ?: listed?.takeIf { policy.aiExternalModel.isNotBlank() }
                            ?.let { policy.aiExternalModel }
                    if (chosen.isNullOrBlank()) {
                        errorLine.text = "Press refresh and pick a model — EXTERNAL cannot " +
                            "describe requests without one."
                        errorLine.visibility = android.view.View.VISIBLE
                        return@button
                    }
                    val dialect = listed?.dialect ?: policy.aiExternalDialect
                    if (dialect == null) {
                        errorLine.text = "Press refresh first: the model was never listed, so " +
                            "the server protocol is unknown."
                        errorLine.visibility = android.view.View.VISIBLE
                        return@button
                    }
                    errorLine.visibility = android.view.View.GONE
                    // ONE write for URL, token, model and dialect. Four saves would emit four
                    // policy-change records and leave windows where the stored set is internally
                    // inconsistent — an address without its credential, or a model without the
                    // route that can serve it.
                    save(
                        store,
                        policy.withAiExternalUrl(raw)
                            .withAiExternalToken(tokenField.text.toString().trim())
                            .withAiExternalModel(chosen, dialect)
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Theme.dp(ctx, 10) }
            )

            block.addView(
                Theme.text(
                    ctx,
                    if (policy.aiExternalUrl.isBlank()) {
                        "No server set, so EXTERNAL refuses every request.\n\n" +
                            "Format: http://host:port — no trailing slash, no path.\n" +
                            "e.g. http://192.168.1.9:8888  ·  http://100.101.102.103:11434\n\n" +
                            "The port is the part people get wrong: Unsloth Studio serves 8888, " +
                            "Ollama serves 11434. The address must be one THIS PHONE can reach — " +
                            "a LAN address works only on the same network, so a tailnet address " +
                            "is usually what you want."
                    } else {
                        "Requests are described by ${policy.aiExternalUrl}. If it is unreachable " +
                            "they are refused — never approved without a description."
                    },
                    12f,
                    if (policy.aiExternalUrl.isBlank()) Theme.WARNING else Theme.ON_SURFACE_VARIANT
                ).apply { setPadding(0, Theme.dp(ctx, 8), 0, 0) }
            )
        }

        return block
    }

    /**
     * Why [raw] is not a usable endpoint, or null when it is.
     *
     * Empty is invalid HERE but valid as a stored value: clearing the field is how a human
     * removes a server, and that has to be expressible. So the caller distinguishes "blank means
     * remove" from "malformed means refuse" — this only rejects what cannot work.
     */
    private fun validateEndpoint(raw: String): String? {
        if (raw.isBlank()) return null
        val parsed = try {
            java.net.URI(raw)
        } catch (e: Exception) {
            return "not a valid URL: ${e.message}"
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "must start with http:// or https://"
        }
        if (parsed.host.isNullOrBlank()) return "no host in that URL"
        // Trailing slashes would double up when the chat path is appended.
        if (raw.endsWith("/")) return "drop the trailing slash"
        return null
    }

    /**
     * One toggle rendered as a titled block.
     *
     * The button label states the ACTION (`turn on` / `turn off`), and the state line says what
     * is true NOW. A single button labelled with the current state is the classic ambiguity: tap
     * it and you cannot tell whether you are switching to that state or away from it.
     */
    private fun toggleRow(
        title: String,
        state: String,
        detail: String,
        colour: Int,
        onToggle: () -> Unit,
    ): LinearLayout {
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(ctx, 14), Theme.dp(ctx, 12),
                Theme.dp(ctx, 14), Theme.dp(ctx, 12)
            )
            background = Theme.outlined(ctx, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(
            Theme.text(ctx, title, 15f, Theme.ON_SURFACE).apply {
                typeface = Theme.handFont(ctx)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        // The switch, in the header rather than a "turn on" button underneath.
        //
        // `state == "ON"` was the only signal the row had, and it duplicated what the knob now
        // shows. `colour` is kept as a parameter so an unreadable store can still say so.
        header.addView(Theme.toggle(ctx, state == "ON") { onToggle() })
        block.addView(header)
        block.addView(
            Theme.text(ctx, detail, 12f, Theme.ON_SURFACE_VARIANT).apply {
                setPadding(0, Theme.dp(ctx, 6), 0, 0)
            }
        )
        return block
    }

    /**
     * Persist a preference.
     *
     * Audited as `POLICY_CHANGED` because it changes what the gate DOES — a fingerprint
     * requirement disappearing is exactly the kind of change a later reader will want dated. The
     * record carries the global mode, which is unchanged by a toggle, so these entries are
     * distinguishable from a switch flip by their source string.
     */
    private fun save(store: PolicyStore, next: com.mido.phonebridge.policy.Policy) {
        try {
            store.set(next)
        } catch (e: Exception) {
            toast("could not save: ${e.message}")
            return
        }
        try {
            AuditLog.from(requireContext()).append(
                PolicyAudit.record(next.global, next.global, "options")
            )
        } catch (e: Exception) {
            toast("saved, but the audit log could not record it: ${e.message}")
        }
        // Re-render from the stored value rather than from what we just sent: if the write
        // silently failed, the screen must show the truth, not the intent.
        render()
    }

    /**
     * The AI model row, moved here from Home.
     *
     * Rendered as a BUTTON while it is the thing to press, and as a plain row once ready: the
     * action and its feedback are the same control, which is why the label carries the progress.
     */
    private fun aiModelRow(
        store: PolicyStore,
        policy: com.mido.phonebridge.policy.Policy,
    ): android.view.View {
        val modelState = com.mido.phonebridge.ai.ModelRegistry.readiness(ctx)
        val aiOn = com.mido.phonebridge.ai.AiGateway.enabled(ctx)
        val downloading = aiOn && com.mido.phonebridge.ai.ModelProvisioner.running
        val progress = com.mido.phonebridge.ai.ModelProvisioner.progress

        // With the AI off there is nothing to fetch, so offering a download would advertise the
        // exact behaviour that was turned off above on this same screen.
        if (!aiOn) {
            return Theme.statusRow(ctx, "AI model",
                "off — no model loaded",
                Theme.ON_SURFACE_VARIANT
            )
        }
        if (downloading) {
            val pct = progress?.percent ?: 0
            return Theme.progressButton(ctx, "AI MODEL — DOWNLOADING $pct%", pct)
        }
        return Theme.statusRow(ctx, "AI model",
            if (modelState.ready) "${modelState.detail} — tap to re-download"
            else "tap to download — ${modelState.detail}",
            if (modelState.ready) Theme.SUCCESS else Theme.WARNING,
            onClick = {
                // A ready model CONFIRMS before re-fetching ~474 MB. Tapping a green row used to
                // silently restart the download (Mido, 2026-10-19).
                if (modelState.ready) confirmRedownload() else startModelDownload()
            }
        )
    }

    private fun confirmRedownload() {
        confirm(
            title = "Re-download the AI model?",
            message = "The model is already downloaded and verified. Re-downloading fetches " +
                "~474 MB again and replaces the working copy.",
            positive = "Re-download",
            negative = "Keep it",
            onNegative = { Debug.log("re-download declined") },
        ) { startModelDownload(force = true) }
    }

    private fun startModelDownload(force: Boolean = false) {
        Debug.log("model download requested (force=$force)")
        render()
        Thread({
            val result = com.mido.phonebridge.ai.ModelProvisioner.provision(
                requireContext(),
                force = force,
                onProgress = { ui { render() } },
            )
            ui {
                Debug.log("model provision: ${result.detail}")
                toast(result.detail)
                render()
            }
        }, "model-download").apply { isDaemon = true }.start()
    }

    /** One full-width destination row. Vertical, because five across truncated their labels. */
    private fun toolRow(label: String, onClick: () -> Unit): android.view.View =
        Theme.button(ctx, label.uppercase(), Theme.ButtonStyle.NEUTRAL, onClick = onClick).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(ctx, 6) }
        }
}
