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
            setBackgroundColor(Theme.BG)
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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

        // --- AI on/off ---
        container.addView(
            toggleRow(
                title = "AI summaries",
                state = if (policy.aiEnabled) "ON" else "OFF",
                detail = if (policy.aiEnabled) {
                    "Each request is described and risk-labelled on the device before you decide. " +
                        "The model stays loaded, so it costs memory while the server runs."
                } else {
                    "No model is loaded, no request is described, and nothing is downloaded. " +
                        "You still see the exact target and payload — the rows are built " +
                        "deterministically. This frees the memory the model holds."
                },
                colour = if (policy.aiEnabled) Theme.SUCCESS else Theme.ON_SURFACE_VARIANT,
                onToggle = { save(store, policy.withAiEnabled(!policy.aiEnabled)) }
            )
        )

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
                typeface = android.graphics.Typeface.DEFAULT_BOLD
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
