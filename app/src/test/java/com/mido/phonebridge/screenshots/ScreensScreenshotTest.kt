package com.mido.phonebridge.screenshots

import android.content.Intent
import com.mido.phonebridge.CallerActivity
import com.mido.phonebridge.ExceptionsActivity
import com.mido.phonebridge.GoogleLoginActivity
import com.mido.phonebridge.LogActivity
import com.mido.phonebridge.LogDetailActivity
import com.mido.phonebridge.OnboardingActivity
import com.mido.phonebridge.PluginDetailActivity
import com.mido.phonebridge.SecretsActivity
import com.mido.phonebridge.SshActivity
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import org.junit.Test

/** Every standalone Activity of WP00 section 5 except the approval gate. */
class ScreensScreenshotTest(variant: Variant) : ShotTest(variant) {

    // caller
    @Test fun caller_default() = caller("default")

    @Test fun caller_name_required() {
        val a = launch(CallerActivity::class.java, Intent().putExtra(CallerActivity.EXTRA_IP, "100.64.0.9"))
        // Confirming with an empty name is what raises the "name required" state.
        findByText(a.window.decorView, "allow")?.performClick()
        capture(a, "caller", "name_required")
    }

    private fun caller(state: String) {
        val a = launch(CallerActivity::class.java, Intent().putExtra(CallerActivity.EXTRA_IP, "100.64.0.9"))
        capture(a, "caller", state)
    }

    // onboarding
    @Test fun onboarding_step1() = onboarding(1)
    @Test fun onboarding_step2() = onboarding(2)
    @Test fun onboarding_step3() = onboarding(3)
    @Test fun onboarding_step4() = onboarding(4)
    @Test fun onboarding_step5() = onboarding(5)

    private fun onboarding(step: Int) {
        val a = launch(OnboardingActivity::class.java)
        repeat(step - 1) { findByText(a.window.decorView, "NEXT")?.performClick() }
        capture(a, "onboarding", "step$step")
    }

    // plugin_detail
    @Test fun plugin_detail_tts() = pluginDetail("tts", "tts")
    @Test fun plugin_detail_contacts_needed() {
        Fixtures.contactsPermission(false); pluginDetail("contacts", "contacts_needed")
    }
    @Test fun plugin_detail_notifications() = pluginDetail("notifications", "notifications")
    @Test fun plugin_detail_unregistered() = pluginDetail("nonexistent", "unregistered", register = false)

    private fun pluginDetail(id: String, state: String, register: Boolean = true) {
        if (register) Fixtures.registerPlugins()
        val a = launch(PluginDetailActivity::class.java, Intent().putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, id))
        capture(a, "plugin_detail", state)
    }

    // secrets, ssh
    @Test fun secrets_locked() = capture(launch(SecretsActivity::class.java), "secrets", "locked")
    @Test fun ssh_locked() = capture(launch(SshActivity::class.java), "ssh", "locked")

    // log
    @Test fun log_empty() = capture(launch(LogActivity::class.java), "log", "empty")

    @Test fun log_records() {
        Fixtures.auditRecords(8)
        capture(launch(LogActivity::class.java), "log", "records")
    }

    @Test fun log_filtered_empty() {
        Fixtures.auditRecords(2)
        val a = launch(LogActivity::class.java)
        findByText(a.window.decorView, "failed")?.performClick()
        capture(a, "log", "filtered_empty")
    }

    @Test fun log_detail_denied_probe() {
        val ids = Fixtures.auditRecords(2)
        val a = launch(LogDetailActivity::class.java, Intent().putExtra(LogDetailActivity.EXTRA_ID, ids[1]))
        capture(a, "log_detail", "denied_probe")
    }

    // exceptions
    @Test fun exceptions_empty() {
        Fixtures.exceptions()
        capture(launch(ExceptionsActivity::class.java), "exceptions", "empty")
    }

    @Test fun exceptions_one_rule() {
        Fixtures.exceptions(Rule("contacts", Verb.READ, Mode.DONT_DISTURB))
        capture(launch(ExceptionsActivity::class.java), "exceptions", "one_rule")
    }

    @Test fun exceptions_rules_ignored() {
        Fixtures.exceptions(Rule("contacts", Verb.READ, Mode.DONT_DISTURB))
        Fixtures.gate(GlobalMode.PARANOID)
        capture(launch(ExceptionsActivity::class.java), "exceptions", "rules_ignored")
    }

    @Test fun exceptions_add_rule_picker() {
        Fixtures.registerPlugins()
        val a = launch(ExceptionsActivity::class.java)
        findByText(a.window.decorView, "+ ADD RULE")?.performClick()
        Shots.screen("exceptions", "add_rule_picker")
    }

    // google login
    @Test fun google_login_no_client_id() = capture(launch(GoogleLoginActivity::class.java), "google_login", "no_client_id")
}
