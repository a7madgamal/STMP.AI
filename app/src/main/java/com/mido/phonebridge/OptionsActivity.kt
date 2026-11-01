package com.mido.phonebridge

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
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
class OptionsActivity : Activity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OptionsActivity, 16), Theme.dp(this@OptionsActivity, 16),
                Theme.dp(this@OptionsActivity, 16), Theme.dp(this@OptionsActivity, 32)
            )
            background = Theme.sheet(Theme.BG)
        }
        scroll.addView(container)
        // Edge-to-edge: without this the first row renders under the status bar. Same call as
        // every other screen — see Theme.inset.
        setContentView(Theme.inset(scroll))

        render()
    }

    private fun render() {
        container.removeAllViews()

        container.addView(Theme.text(this, "OPTIONS", 20f).apply {
            typeface = Theme.handFont(this@OptionsActivity)
        })

        val store = PolicyStore.from(applicationContext)
        val policy = try {
            store.get()
        } catch (e: PolicyException) {
            container.addView(
                Theme.text(
                    this,
                    "⚠ policy unreadable: ${e.message}\n\nThe server will not start until this " +
                        "file is fixed or removed.",
                    12f, Theme.DANGER
                )
            )
            return
        }

        container.addView(Theme.sectionHeader(this, "Startup"))

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

        container.addView(Theme.sectionHeader(this, "Approval"))

        // --- fingerprint for SECURE modals ---
        container.addView(
            toggleRow(
                title = "Fingerprint for approval modals",
                state = if (policy.fingerprintForModals) "ON" else "OFF",
                detail = if (policy.fingerprintForModals) {
                    "A SECURE request needs a fingerprint to approve. A denied or failed prompt " +
                        "leaves the request pending, so you can retry within its window."
                } else {
                    "A SECURE request approves on a plain tap, like NOTIFY already does. " +
                        "The secret vault is NOT affected — it keeps its own fingerprint and is " +
                        "unlocked when the app opens."
                },
                colour = if (policy.fingerprintForModals) Theme.SUCCESS else Theme.ON_SURFACE_VARIANT,
                onToggle = {
                    save(store, policy.withFingerprintForModals(!policy.fingerprintForModals))
                }
            )
        )

        container.addView(Theme.sectionHeader(this, "AI"))

        // --- AI backend: off / local / external ---
        //
        // A three-way choice, not a toggle. "AI on" stopped being one thing when a remote
        // backend became possible: LOCAL loads the on-device model file, EXTERNAL asks a box on
        // the tailnet, and they fail differently. A toggle would have to mean one of them and
        // hide the other behind a second setting that could disagree with it.
        container.addView(
            aiBackendRow(store, policy)
        )

        container.addView(Theme.sectionHeader(this, "Callers"))

        // --- who may reach the bridge ---
        //
        // Not a preference: an unnamed address is REFUSED, so this list is the allow rule. It is
        // visible and editable because a block you cannot undo without hand-editing JSON is a
        // trap, and because a misheard name should be fixable without a rebuild.
        container.addView(callerRows())

        container.addView(Theme.spacer(this, 16))
        container.addView(
            Theme.text(
                this,
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
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val entries = try {
            com.mido.phonebridge.callers.CallerStore.from(applicationContext).all()
        } catch (e: Exception) {
            block.addView(
                Theme.text(
                    this,
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
                    this,
                    "No callers yet. The first request from another machine will ask you to " +
                        "name it.",
                    12f, Theme.ON_SURFACE_VARIANT
                )
            )
            return block
        }

        for (entry in entries.values.sortedBy { it.ip }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    Theme.dp(this@OptionsActivity, 12), Theme.dp(this@OptionsActivity, 10),
                    Theme.dp(this@OptionsActivity, 12), Theme.dp(this@OptionsActivity, 10)
                )
                background = Theme.outlined(
                    this@OptionsActivity, Theme.SURFACE, Theme.RADIUS_CARD
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
                Theme.text(this, "$label\n${entry.ip}", 14f, colour).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            row.addView(
                Theme.button(this, "forget", Theme.ButtonStyle.NEUTRAL, small = true) {
                    try {
                        com.mido.phonebridge.callers.CallerStore
                            .from(applicationContext).forget(entry.ip)
                        com.mido.phonebridge.audit.AuditLog.from(applicationContext).append(
                            com.mido.phonebridge.audit.PolicyAudit.record(
                                policy().global, policy().global, "options"
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(
                            this, "could not forget: ${e.message}", Toast.LENGTH_LONG
                        ).show()
                    }
                    render()
                }
            )
            block.addView(row)
            block.addView(Theme.spacer(this, 8))
        }
        return block
    }

    private fun policy() = com.mido.phonebridge.policy.PolicyStore.from(applicationContext).get()

    /**
     * The AI backend block: current state, what it costs, and one button per alternative.
     *
     * Buttons name the DESTINATION ("use local", "use external") rather than toggling, because
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
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OptionsActivity, 14), Theme.dp(this@OptionsActivity, 12),
                Theme.dp(this@OptionsActivity, 14), Theme.dp(this@OptionsActivity, 12)
            )
            background = Theme.outlined(this@OptionsActivity, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            Theme.text(this, "AI summaries", 15f, Theme.ON_SURFACE).apply {
                typeface = Theme.handFont(this@OptionsActivity)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        header.addView(
            Theme.text(
                this,
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
                this,
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
            ).apply { setPadding(0, Theme.dp(this@OptionsActivity, 6), 0, 0) }
        )

        // One button per state that is not the current one.
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Theme.dp(this@OptionsActivity, 10), 0, 0)
        }
        for (target in com.mido.phonebridge.policy.AiBackend.values()) {
            if (target == current) continue
            val label = when (target) {
                com.mido.phonebridge.policy.AiBackend.OFF -> "turn off"
                com.mido.phonebridge.policy.AiBackend.LOCAL -> "use local"
                com.mido.phonebridge.policy.AiBackend.EXTERNAL -> "use external"
            }
            choices.addView(
                Theme.button(this, label, Theme.ButtonStyle.NEUTRAL, small = true) {
                    save(store, policy.withAiBackend(target))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Theme.dp(this@OptionsActivity, 8) }
            )
        }
        block.addView(choices)
        return block
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
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(this@OptionsActivity, 14), Theme.dp(this@OptionsActivity, 12),
                Theme.dp(this@OptionsActivity, 14), Theme.dp(this@OptionsActivity, 12)
            )
            background = Theme.outlined(this@OptionsActivity, Theme.SURFACE, Theme.RADIUS_CARD)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(
            Theme.text(this, title, 15f, Theme.ON_SURFACE).apply {
                typeface = Theme.handFont(this@OptionsActivity)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        header.addView(Theme.text(this, state, 13f, colour))
        block.addView(header)
        block.addView(
            Theme.text(this, detail, 12f, Theme.ON_SURFACE_VARIANT).apply {
                setPadding(0, Theme.dp(this@OptionsActivity, 6), 0, 0)
            }
        )
        block.addView(
            Theme.button(
                this,
                if (state == "ON") "turn off" else "turn on",
                Theme.ButtonStyle.NEUTRAL,
                small = true,
                onClick = onToggle
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(this@OptionsActivity, 10) }
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
            Toast.makeText(this, "could not save: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        try {
            AuditLog.from(applicationContext).append(
                PolicyAudit.record(next.global, next.global, "options")
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "saved, but the audit log could not record it: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
        // Re-render from the stored value rather than from what we just sent: if the write
        // silently failed, the screen must show the truth, not the intent.
        render()
    }
}
