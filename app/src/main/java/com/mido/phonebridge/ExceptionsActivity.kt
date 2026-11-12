package com.mido.phonebridge

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
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
import com.mido.phonebridge.policy.title
import com.mido.phonebridge.ui.AuditCopy
import com.mido.phonebridge.ui.PluginLabel
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.kit.ActionRow
import com.mido.phonebridge.ui.kit.KeyButton
import com.mido.phonebridge.ui.kit.Led
import com.mido.phonebridge.ui.kit.Panel
import com.mido.phonebridge.ui.kit.Readout
import com.mido.phonebridge.ui.kit.SectionHeader
import com.mido.phonebridge.ui.kit.StateBlock
import com.mido.phonebridge.ui.kit.StmpDialog
import com.mido.phonebridge.ui.tokens.Dimens
import com.mido.phonebridge.ui.tokens.Type
import com.mido.phonebridge.ui.tokens.TypeRole

/**
 * Where EXCEPTIONS are authored, the only place. An exception is one `plugin x verb` pair that
 * behaves differently from the default, and the default is "ask for your stamp". Exceptions only
 * count in Whitelist mode; in the other gate modes they are paused, not deleted.
 *
 * Exceptions are exact `plugin x verb` pairs, and a verb may be any action. An exact exception wins
 * over the plugin's any-action exception (see `policy.resolve`). The picker offers
 * `Default | Don't disturb | Paranoid`: Default deletes the exception.
 */
class ExceptionsActivity : AppCompatActivity() {

    private var scroll: ScrollView? = null
    private var content: LinearLayout? = null
    private var addKey: View? = null
    private var currentPolicy: Policy? = null
    private var saveError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The frame (top bar, scroll, insets) is built once; render() only refills `content`.
        // Rebuilding it would attach new views after the window already sent its insets.
        val key = KeyButton.create(
            this, "Add exception", KeyButton.Kind.OUTLINE, KeyButton.Size.SMALL, describe = "Add an exception",
        ) { currentPolicy?.let { pickPlugin(it) } }
        val screen = subScreen("Exceptions", key)
        addKey = key
        scroll = screen.scroll
        content = screen.content
        render()
    }

    override fun onResume() {
        super.onResume()
        // The banner and rows show what is stored now, not what was stored when the screen last drew.
        render()
    }

    private fun dp(v: Int) = Theme.dp(this, v)

    private fun full() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    /** Refills the content column; the top bar's Add key is shown only while the policy reads. */
    private fun render() {
        val content = content ?: return
        val keepY = scroll?.scrollY ?: 0
        content.removeAllViews()
        val policy = try {
            PolicyStore.from(applicationContext).get()
        } catch (e: PolicyException) {
            currentPolicy = null
            addKey?.visibility = View.GONE
            renderUnreadable(content, e)
            return
        }
        currentPolicy = policy
        addKey?.visibility = View.VISIBLE
        val screenScroll = scroll

        content.addView(banner(policy), full().apply { bottomMargin = dp(Dimens.MODULE_GAP) })
        saveError?.let { content.addView(saveFailed(it), full().apply { bottomMargin = dp(Dimens.MODULE_GAP) }) }

        if (policy.rules.isEmpty()) {
            content.addView(
                StateBlock.create(
                    this, StateBlock.Kind.EMPTY, "No exceptions yet",
                    "Every plugin action asks for your stamp first. Add an exception to make one quieter.",
                ),
                full().apply { bottomMargin = dp(Dimens.MODULE_GAP) },
            )
        } else {
            content.addView(SectionHeader.create(this, "Saved exceptions"), full())
            // Padded: ActionRow has no horizontal padding of its own.
            val panel = Panel.create(this)
            for (rule in sorted(policy.rules)) panel.addView(ruleRow(policy, rule), full())
            content.addView(panel, full().apply { bottomMargin = dp(Dimens.MODULE_GAP) })
        }
        explainer(content)
        screenScroll?.post { screenScroll.scrollTo(0, keepY) }
    }

    private fun renderUnreadable(content: LinearLayout, e: PolicyException) {
        content.addView(
            StateBlock.create(
                this, StateBlock.Kind.ERROR, "Exceptions can't be read",
                "The policy file is unreadable, so the server will not start until it is fixed or removed.",
                KeyButton.Spec("Try again", "Read the exceptions again") { render() },
            ),
            full().apply { bottomMargin = dp(Dimens.MODULE_GAP) },
        )
        content.addView(Readout.create(this, e.message ?: "unknown error", Readout.Kind.BODY).root)
    }

    private fun sorted(rules: List<Rule>): List<Rule> =
        rules.sortedWith(compareBy({ PluginLabel.of(it.plugin).lowercase() }, { it.verb?.ordinal ?: -1 }))

    // --- banner and rows -----------------------------------------------------------------------

    private fun banner(policy: Policy): View {
        val p = Theme.palette(this)
        val inForce = policy.global == GlobalMode.WHITELIST
        val title = if (inForce) "Whitelist mode: these exceptions are in force" else "Exceptions are paused"
        val body = if (inForce) {
            "A plugin action with no exception asks for your stamp first."
        } else {
            "The gate mode is ${policy.global.title}, so these exceptions are ignored. " +
                "Switch to Whitelist on Home to use them."
        }
        val panel = Panel.create(this, if (inForce) Panel.Tone.PLAIN else Panel.Tone.CAUTION)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(Led(this).apply {
            state = if (inForce) Led.State.OK else Led.State.CAUTION
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { topMargin = dp(2) })
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(Type.text(this, title, TypeRole.ROW_LABEL, if (inForce) p.ink else p.cautionText), full())
        text.addView(
            Type.text(this, body, TypeRole.DETAIL, if (inForce) p.etched else p.ink),
            full().apply { topMargin = dp(2) },
        )
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        panel.addView(row, full())
        panel.contentDescription = "$title. $body"
        ViewCompat.setScreenReaderFocusable(panel, true)
        return panel
    }

    private fun saveFailed(message: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            StateBlock.create(
                this, StateBlock.Kind.ERROR, "Could not save the exception",
                "The exception was not changed. The list shows what is stored now.",
            ),
            full(),
        )
        box.addView(Readout.create(this, message, Readout.Kind.BODY).root)
        return box
    }

    /** One saved exception, with its mode in words and a way to change or remove it. */
    private fun ruleRow(policy: Policy, rule: Rule): View {
        val title = "${PluginLabel.of(rule.plugin)} · ${AuditCopy.verbWords(rule.verb)}"
        val detail = when (rule.mode) {
            Mode.PARANOID -> "Paranoid · full prompt, asks for your stamp · same as having no exception"
            Mode.DONT_DISTURB -> "Don't disturb · 5 s card, doing nothing approves it"
        }
        val next = toggle(rule.mode)
        val nextName = if (next == Mode.PARANOID) "Paranoid" else "Don't disturb"
        val change = KeyButton.Spec(
            if (next == Mode.PARANOID) "Set Paranoid" else "Set Don't disturb",
            "Change $title to $nextName",
        ) { setRule(Rule(rule.plugin, rule.verb, next), "settings") }
        // Removing an exception can only make the pair ask again, the safe direction: no confirm (D62).
        val remove = KeyButton.Spec("Remove", "Remove the exception for $title") {
            clearRule(rule.plugin, rule.verb, "settings")
        }
        return ActionRow(this, title, detail, listOf(change), remove)
    }

    private fun toggle(mode: Mode): Mode =
        if (mode == Mode.DONT_DISTURB) Mode.PARANOID else Mode.DONT_DISTURB

    private fun explainer(content: LinearLayout) {
        val p = Theme.palette(this)
        content.addView(SectionHeader.create(this, "How an exception asks"), full())
        val panel = Panel.create(this)
        panel.addView(
            Type.text(
                this, "Don't disturb shows a small card for 5 seconds. Doing nothing approves it; tap ✕ to deny.",
                TypeRole.BODY, p.ink,
            ),
            full(),
        )
        panel.addView(
            Type.text(this, "Paranoid opens the full-screen prompt and asks for your stamp.", TypeRole.BODY, p.ink),
            full().apply { topMargin = dp(Dimens.SPACE_S) },
        )
        panel.addView(
            Type.text(
                this,
                "There is no auto-approve option, not here and not in the gate modes. The quietest a request " +
                    "can be is the 5-second card, so every request reaches you first.",
                TypeRole.DETAIL, p.etched,
            ),
            full().apply { topMargin = dp(Dimens.SPACE_S) },
        )
        content.addView(panel, full())
    }

    // --- authoring flow: plugin, then action, then mode ----------------------------------------

    private fun pickPlugin(policy: Policy) {
        // The registry is filled when the service starts. With nothing registered, offer the
        // plugins every install has rather than inventing names that can never match a request.
        val ids = PluginRegistry.all().map { it.id }.ifEmpty { listOf("gmail", "calendar", "notifications") }
        StmpDialog.builder(this)
            .setTitle("Which plugin?")
            .setItems(ids.map { PluginLabel.of(it) }.toTypedArray()) { _, which -> pickVerb(policy, ids[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickVerb(policy: Policy, plugin: String) {
        // Any action first: the broad exception is the one to reach for when you do not want to enumerate.
        val options = listOf<Verb?>(null) + Verb.values().toList()
        StmpDialog.builder(this)
            .setTitle("${PluginLabel.of(plugin)} · which action?")
            .setItems(options.map { verbLabel(it) }.toTypedArray()) { _, which -> pickMode(policy, plugin, options[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verbLabel(v: Verb?): String =
        if (v == null) "Any action" else AuditCopy.verbWords(v).replaceFirstChar { it.uppercase() }

    private fun pickMode(policy: Policy, plugin: String, verb: Verb?) {
        val labels = arrayOf(
            "Default · ask for your stamp (removes any exception)",
            "Don't disturb · 5 s card, silence approves",
            "Paranoid · full prompt, asks for your stamp",
        )
        StmpDialog.builder(this)
            .setTitle("${PluginLabel.of(plugin)} · ${AuditCopy.verbWords(verb)}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> clearRule(plugin, verb, "settings")
                    1 -> setRule(Rule(plugin, verb, Mode.DONT_DISTURB), "settings")
                    else -> setRule(Rule(plugin, verb, Mode.PARANOID), "settings")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Write one exception. The change is audited separately from a gate-mode change: "switch
     * Paranoid to Paranoid" would say nothing happened at the one moment something did.
     */
    private fun setRule(rule: Rule, source: String) {
        val policy = freshPolicy() ?: return
        val before = policy.rules.firstOrNull { it.key == rule.key }
        save(policy.withRule(rule), PolicyAudit.recordRule(before, rule, policy.global, source))
    }

    private fun clearRule(plugin: String, verb: Verb?, source: String) {
        val policy = freshPolicy() ?: return
        val before = policy.rules.firstOrNull { it.plugin == plugin && it.verb == verb }
        save(policy.withoutRule(plugin, verb), PolicyAudit.recordRule(before, null, policy.global, source))
    }

    /**
     * The policy as stored NOW, not the copy this screen last drew: the approval modal and Home
     * write the same file, and applying a change to a stale copy would silently undo theirs
     * (including the gate mode) and audit the wrong global mode. Null after showing the fault.
     */
    private fun freshPolicy(): Policy? = try {
        PolicyStore.from(applicationContext).get()
    } catch (e: PolicyException) {
        saveError = e.message ?: e.javaClass.simpleName
        render()
        null
    }

    private fun save(next: Policy, record: com.mido.phonebridge.audit.AuditRecord) {
        try {
            PolicyStore.from(applicationContext).set(next)
        } catch (e: Exception) {
            saveError = e.message ?: e.javaClass.simpleName
            render()
            return
        }
        saveError = null
        try {
            AuditLog.from(applicationContext).append(record)
        } catch (e: Exception) {
            // An exception that took effect unrecorded is the same class of failure as an unrecorded
            // gate change: surface it rather than pretending it is fine.
            AuditHealth.markDegraded("rule change not recorded: ${e.message}")
        }
        render()
    }
}
