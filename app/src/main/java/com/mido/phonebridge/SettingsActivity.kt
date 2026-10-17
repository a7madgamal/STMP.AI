package com.mido.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

/**
 * Where per-pair rules are authored. The only place, deliberately.
 *
 * `AUTOMATIC` is a switch position that does nothing until a rule exists, so this
 * screen and `AUTOMATIC` ship together — a switch value whose whole point is the
 * rule table, with no way to fill that table, is the exact defect that got `NOTIFY`
 * cut from R2 in the first place.
 *
 * Rules are exact `plugin × verb` pairs. No wildcards, no cascade, nothing to rank.
 * The picker offers `default | notify | secure`: `default` deletes the rule.
 */
class SettingsActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@SettingsActivity, 16), UiKit.dp(this@SettingsActivity, 16),
                UiKit.dp(this@SettingsActivity, 16), UiKit.dp(this@SettingsActivity, 32))
        }
        scroll.addView(container)
        setContentView(scroll)

        render()
    }

    private fun render() {
        container.removeAllViews()

        header = TextView(this).apply {
            text = "PER-PAIR RULES"
            textSize = 18f
            setTextColor(UiKit.TEXT_ON_LIGHT)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        container.addView(header)

        val policy = try {
            PolicyStore.from(applicationContext).get()
        } catch (e: PolicyException) {
            container.addView(
                text("⚠ policy unreadable: ${e.message}\n\nThe server will not start until " +
                    "this file is fixed or removed.", UiKit.RED)
            )
            return
        }

        container.addView(
            text(
                when (policy.global) {
                    GlobalMode.AUTOMATIC ->
                        "The switch is AUTOMATIC, so these rules are in force. " +
                            "A pair with no rule asks you first."
                    else ->
                        "Rules are ignored while the switch is ${policy.global.name}. " +
                            "Set the switch to AUTOMATIC to use them."
                },
                UiKit.TEXT_MUTED
            )
        )
        container.addView(spacer(8))

        // --- existing rules ---
        if (policy.rules.isEmpty()) {
            container.addView(text("No rules yet. Every pair asks you first.", UiKit.TEXT_MUTED))
        } else {
            for (rule in policy.rules) container.addView(ruleRow(policy, rule))
        }

        container.addView(spacer(12))
        container.addView(
            colored("+ ADD RULE", UiKit.GREEN_BG) { pickPlugin(policy) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(spacer(20))
        container.addView(
            text(
                "NOTIFY shows a small overlay for 5 seconds. Doing nothing approves it; " +
                    "tap ✕ to reject. SECURE opens the full-screen prompt with a fingerprint.\n\n" +
                    "There is no auto-approve option — not here and not on the switch. The " +
                    "quietest a request can be is the 5s overlay, so every request reaches " +
                    "a human first.",
                UiKit.TEXT_MUTED
            )
        )
    }

    /** One configured pair, with its current mode and a way to change it. */
    private fun ruleRow(policy: Policy, rule: Rule): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UiKit.dp(this@SettingsActivity, 8), 0, UiKit.dp(this@SettingsActivity, 8))
        }
        val label = TextView(this).apply {
            // `secure (default)` because a stored SECURE and no rule behave
            // identically. Saying so stops anyone hunting for a difference
            // that does not exist.
            text = "${rule.plugin} · ${rule.verb.name.lowercase()} — " +
                rule.mode.name.lowercase() +
                if (rule.mode == Mode.SECURE) " (default)" else ""
            textSize = 13f
            setTextColor(UiKit.TEXT_ON_LIGHT)
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(
            smallButton(if (rule.mode == Mode.NOTIFY) "→ secure" else "→ notify") {
                setRule(policy, Rule(rule.plugin, rule.verb, toggle(rule.mode)), "settings")
            }
        )
        row.addView(
            smallButton("remove") {
                clearRule(policy, rule.plugin, rule.verb, "settings")
            }
        )
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
        val verbs = Verb.values().map { it.name }
        AlertDialog.Builder(this)
            .setTitle("$plugin — which verb?")
            .setItems(verbs.toTypedArray()) { _, which ->
                pickMode(policy, plugin, Verb.values()[which])
            }
            .show()
    }

    private fun pickMode(policy: Policy, plugin: String, verb: Verb) {
        val existing = policy.rules.firstOrNull { it.plugin == plugin && it.verb == verb }
        val labels = arrayOf(
            "default — ask me (removes any rule)",
            "notify — 5s overlay, silence approves",
            "secure — full-screen + fingerprint"
        )
        AlertDialog.Builder(this)
            .setTitle("$plugin · ${verb.name.lowercase()}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> clearRule(policy, plugin, verb, "settings")
                    1 -> setRule(policy, Rule(plugin, verb, Mode.NOTIFY), "settings")
                    else -> setRule(
                        policy,
                        Rule(plugin, verb, Mode.SECURE),
                        "settings"
                    )
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

    private fun clearRule(policy: Policy, plugin: String, verb: Verb, source: String) {
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

    // --- view helpers ---

    private fun text(s: String, color: Int): TextView = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(color)
        setPadding(0, UiKit.dp(this@SettingsActivity, 4), 0, UiKit.dp(this@SettingsActivity, 4))
    }

    private fun spacer(dp: Int): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, UiKit.dp(this@SettingsActivity, dp))
    }

    private fun colored(label: String, bg: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        background = UiKit.pill(bg)
        setTextColor(UiKit.TEXT_ON_DARK)
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 10f
        background = UiKit.pill(UiKit.NEUTRAL_BG)
        setTextColor(UiKit.TEXT_ON_DARK)
        setOnClickListener { onClick() }
    }
}
