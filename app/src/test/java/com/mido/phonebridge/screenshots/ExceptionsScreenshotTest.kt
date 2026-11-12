package com.mido.phonebridge.screenshots

import com.mido.phonebridge.ExceptionsActivity
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import org.junit.Test
import org.robolectric.shadows.ShadowDialog

/** Exceptions (WP08 section 12). */
class ExceptionsScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun fourExceptions(global: GlobalMode) {
        Fixtures.registerNamedPlugins()
        Fixtures.exceptionsOnly(
            Rule("gmail", Verb.EDIT, Mode.PARANOID),
            Rule("calendar", null, Mode.DONT_DISTURB),
            Rule("tts", Verb.READ, Mode.DONT_DISTURB),
            Rule("secrets", Verb.DELETE, Mode.PARANOID),
        )
        Fixtures.gate(global)
    }

    @Test fun exceptions_empty_whitelist() {
        Fixtures.exceptionsOnly()
        Fixtures.gate(GlobalMode.WHITELIST)
        capture(launch(ExceptionsActivity::class.java), "exceptions", "empty_whitelist")
    }

    @Test fun exceptions_rules_whitelist() {
        fourExceptions(GlobalMode.WHITELIST)
        capture(launch(ExceptionsActivity::class.java), "exceptions", "rules_whitelist")
    }

    @Test fun exceptions_rules_paused_paranoid() {
        fourExceptions(GlobalMode.PARANOID)
        capture(launch(ExceptionsActivity::class.java), "exceptions", "rules_paused_paranoid")
    }

    @Test fun exceptions_rules_paused_dnd() {
        fourExceptions(GlobalMode.DONT_DISTURB)
        capture(launch(ExceptionsActivity::class.java), "exceptions", "rules_paused_dnd")
    }

    @Test fun exceptions_unreadable() {
        Fixtures.gateUnreadable()
        capture(launch(ExceptionsActivity::class.java), "exceptions", "unreadable")
    }

    private fun pick(index: Int) {
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = dialog.listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
    }

    private fun openPluginDialog(): ExceptionsActivity {
        Fixtures.registerNamedPlugins()
        Fixtures.gate(GlobalMode.WHITELIST)
        val a = launch(ExceptionsActivity::class.java)
        findByDescription(a.window.decorView, "Add an exception")!!.performClick()
        return a
    }

    @Test fun exceptions_dialog_plugin() {
        openPluginDialog()
        Shots.screen("exceptions", "dialog_plugin")
    }

    @Test fun exceptions_dialog_verb() {
        openPluginDialog()
        pick(0)
        Shots.screen("exceptions", "dialog_verb")
    }

    @Test fun exceptions_dialog_mode() {
        openPluginDialog()
        pick(0)
        pick(3)
        Shots.screen("exceptions", "dialog_mode")
    }

}
