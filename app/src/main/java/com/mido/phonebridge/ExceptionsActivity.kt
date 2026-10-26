package com.mido.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.audit.AuditLog
import com.mido.phonebridge.audit.PolicyAudit
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Policy
import com.mido.phonebridge.policy.PolicyException
import com.mido.phonebridge.policy.PolicyStore
import com.mido.phonebridge.policy.Rule
import com.mido.phonebridge.ui.Theme

/**
 * Where EXCEPTIONS are authored. The only place, deliberately.
 *
 * An exception is one `plugin × verb` pair that behaves differently from the default,
 * and the default is "ask": `AUTOMATIC` with no rule resolves to `SECURE`. So the list
 * is every rule — each one an exception to the ask-everything default. (The screen was
 * called "PER-PAIR RULES" until 2026-10-19; the name described the implementation
 * instead of the intent, and Mido asked for it changed.)
 *
 * `AUTOMATIC` is a switch position that does nothing until a rule exists, so this
 * screen and `AUTOMATIC` ship together — a switch value whose whole point is the
 * rule table, with no way to fill that table, is the exact defect that got `NOTIFY`
 * cut from R2 in the first place.
 *
 * Rules are exact `plugin × verb` pairs, and a verb may be ANY. The picker offers
 * `default | notify | secure`: `default` deletes the rule. ANY is one axis, two
 * levels — an exact rule wins over the plugin's ANY rule (see `policy.resolve`).
 */
class ExceptionsActivity : Activity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(this@ExceptionsActivity, 16), Theme.dp(this@ExceptionsActivity, 16),
                Theme.dp(this@ExceptionsActivity, 16), Theme.dp(this@ExceptionsActivity, 32))
            setBackgroundColor(Theme.BG)
        }
        scroll.addView(container)
        setContentView(Theme.inset(scroll))

        render()
    }

    private fun render() {
        container.removeAllViews()

        container.addView(Theme.text(this, "EXCEPTIONS", 20f).apply {
            typeface = Theme.handFont(this@ExceptionsActivity)
        })

        val policy = try {
            PolicyStore.from(applicationContext).get()
        } catch (e: PolicyException) {
            container.addView(
                Theme.text(
                    this,
                    "⚠ policy unreadable: ${e.message}\n\nThe server will not start until " +
                        "this file is fixed or removed.",
                    12f, Theme.DANGER
                )
            )
            return
        }

        container.addView(
            Theme.text(
                this,
                when (policy.global) {
                    GlobalMode.AUTOMATIC ->
                        "The switch is AUTOMATIC, so these rules are in force. " +
                            "A pair with no rule asks you first."
                    else ->
                        "Rules are ignored while the switch is ${policy.global.name}. " +
                            "Set the switch to AUTOMATIC to use them."
                },
                12f, Theme.ON_SURFACE_VARIANT
            )
        )
        container.addView(Theme.spacer(this, 8))

        // --- existing rules ---
        if (policy.rules.isEmpty()) {
            container.addView(Theme.text(this, "No rules yet. Every pair asks you first.", 12f, Theme.ON_SURFACE_VARIANT))
        } else {
            for (rule in policy.rules) container.addView(ruleRow(policy, rule))
        }

        container.addView(Theme.spacer(this, 12))
        container.addView(
            Theme.button(this, "+ ADD RULE", Theme.ButtonStyle.SUCCESS) { pickPlugin(policy) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(Theme.spacer(this, 20))
        container.addView(
            Theme.text(
                this,
                "NOTIFY shows a small overlay for 5 seconds. Doing nothing approves it; " +
                    "tap ✕ to reject. SECURE opens the full-screen prompt with a fingerprint.\n\n" +
                    "There is no auto-approve option — not here and not on the switch. The " +
                    "quietest a request can be is the 5s overlay, so every request reaches " +
                    "a human first.",
                12f, Theme.ON_SURFACE_VARIANT
            )
        )
    }

    /**
     * One configured pair, with its current mode and a way to change it.
     *
     * [rule.verb] null is an ANY rule and is rendered as "any" — the label has to say
     * what the rule actually covers, or a broad rule reads as a narrow one.
     */
    private fun ruleRow(policy: Policy, rule: Rule): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Theme.dp(this@ExceptionsActivity, 12), Theme.dp(this@ExceptionsActivity, 10),
                Theme.dp(this@ExceptionsActivity, 12), Theme.dp(this@ExceptionsActivity, 10))
            background = Theme.outlined(this@ExceptionsActivity, Theme.SURFACE, Theme.RADIUS_CONTROL)
        }
        val label = Theme.text(
            this,
            // `secure (default)` because a stored SECURE and no rule behave
            // identically. Saying so stops anyone hunting for a difference
            // that does not exist.
            "${rule.plugin} · ${rule.verb?.name?.lowercase() ?: "any"} — " +
                rule.mode.name.lowercase() +
                if (rule.mode == Mode.SECURE) " (default)" else "",
            13f
        )
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            Theme.button(this, if (rule.mode == Mode.NOTIFY) "→ secure" else "→ notify",
                Theme.ButtonStyle.NEUTRAL, small = true) {
                setRule(policy, Rule(rule.plugin, rule.verb, toggle(rule.mode)), "settings")
            }
        )
        row.addView(
            Theme.button(this, "remove", Theme.ButtonStyle.NEUTRAL, small = true) {
                clearRule(policy, rule.plugin, rule.verb, "settings")
            }
        )
        // Same gap as every other list (Theme.ROW_GAP_DP). Set here because these rows are
        // built by this screen rather than by Theme.statusRow.
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Theme.dp(this@ExceptionsActivity, Theme.ROW_GAP_DP) }
        return row
    }

    private fun toggle(mode: Mode): Mode =
        if (mode == Mode.NOTIFY) Mode.SECURE else Mode.NOTIFY

    // --- authoring flow: plugin, then verb, then mode ---

    private fun pickPlugin(policy: Policy) {
        val plugins = PluginRegistry.all().map { it.id }.ifEmpty {
            // The registry is populated when the service starts. If nothing is
            // registered there is nothing to author a rule for, and inventing names
            // here would create rules that can never match a real request.
            listOf("gmail", "calendar", "notifications")
        }
        AlertDialog.Builder(this)
            .setTitle("Which plugin?")
            .setItems(plugins.toTypedArray()) { _, which -> pickVerb(policy, plugins[which]) }
            .show()
    }

    private fun pickVerb(policy: Policy, plugin: String) {
        // ANY first, then the real verbs. Order matters for a picker: the broad rule is
        // the one you reach for when you do not want to enumerate, so it leads.
        val options = listOf(Rule.ANY) + Verb.values().map { it.name }
        AlertDialog.Builder(this)
            .setTitle("$plugin — which verb?")
            .setItems(options.toTypedArray()) { _, which ->
                val verb = if (which == 0) null else Verb.values()[which - 1]
                pickMode(policy, plugin, verb)
            }
            .show()
    }

    private fun pickMode(policy: Policy, plugin: String, verb: Verb?) {
        val labels = arrayOf(
            "default — ask me (removes any rule)",
            "notify — 5s overlay, silence approves",
            "secure — full-screen + fingerprint"
        )
        AlertDialog.Builder(this)
            .setTitle("$plugin · ${verb?.name?.lowercase() ?: "any verb"}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> clearRule(policy, plugin, verb, "settings")
                    1 -> setRule(policy, Rule(plugin, verb, Mode.NOTIFY), "settings")
                    else -> setRule(policy, Rule(plugin, verb, Mode.SECURE), "settings")
                }
            }
            .show()
    }

    /**
     * Write one rule. The rule change is audited separately from a switch change:
     * "switch SECURE → SECURE" would say nothing happened at the one moment
     * something did.
     */
    private fun setRule(policy: Policy, rule: Rule, source: String) {
        val before = policy.rules.firstOrNull { it.key == rule.key }
        save(policy.withRule(rule), PolicyAudit.recordRule(before, rule, policy.global, source))
    }

    private fun clearRule(policy: Policy, plugin: String, verb: Verb?, source: String) {
        val before = policy.rules.firstOrNull { it.plugin == plugin && it.verb == verb }
        save(
            policy.withoutRule(plugin, verb),
            PolicyAudit.recordRule(before, null, policy.global, source)
        )
    }

    private fun save(next: Policy, record: com.mido.phonebridge.audit.AuditRecord) {
        try {
            PolicyStore.from(applicationContext).set(next)
        } catch (e: Exception) {
            Toast.makeText(this, "could not save: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        try {
            AuditLog.from(applicationContext).append(record)
        } catch (e: Exception) {
            // A rule that took effect unrecorded is the same class of failure as an
            // unrecorded switch flip: surface it rather than pretending it is fine.
            AuditHealth.markDegraded("rule change not recorded: ${e.message}")
        }
        render()
    }
}
