package com.mido.phonebridge.screenshots

import android.content.Intent
import com.mido.phonebridge.PluginDetailActivity
import org.junit.Test

/** Plugin detail screen. */
class PluginDetailScreenshotTest(variant: Variant) : ShotTest(variant) {

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
}
