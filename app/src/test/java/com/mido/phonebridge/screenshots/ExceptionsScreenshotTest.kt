package com.mido.phonebridge.screenshots

import com.mido.phonebridge.ExceptionsActivity
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import org.junit.Test

/** Exceptions (rules) screen. */
class ExceptionsScreenshotTest(variant: Variant) : ShotTest(variant) {

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
}
