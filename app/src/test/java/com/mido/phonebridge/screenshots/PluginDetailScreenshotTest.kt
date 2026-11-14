package com.mido.phonebridge.screenshots

import android.content.Intent
import android.view.View
import android.widget.EditText
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.PluginDetailActivity
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginPermission
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSetting
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.SpecialAccess
import com.mido.phonebridge.core.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSettings

/** A plugin with whatever the state under test needs; the production plugins cover the rest. */
class DetailFakePlugin(
    override val id: String,
    override val displayName: String,
    private val perms: List<PluginPermission> = emptyList(),
    private val access: List<SpecialAccess> = emptyList(),
    private val declared: List<PluginSetting> = emptyList(),
    private val routeCount: Int = 2,
    private val throwing: Boolean = false,
) : Plugin {
    override fun permissions() = perms
    override fun specialAccess() = access
    override fun settings() = declared
    override fun status(): PluginStatus {
        if (throwing) throw IllegalStateException("fixture status failure")
        return PluginStatus(displayName, PluginStatus.State.OK, "ready")
    }
    override fun routes(): List<Route> = (1..routeCount).map {
        Route(
            method = "GET", path = "/$id/item$it", describe = "Read item number $it of the $displayName list",
            verb = Verb.READ, handler = { Resp.ok() },
        )
    }
}

/** Plugin detail screen (WP06 section 11.2). */
class PluginDetailScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun register(plugin: Plugin) {
        Fixtures.clearPlugins()
        PluginRegistry.register(plugin)
    }

    private fun open(id: String, state: String, secret: String? = null): PluginDetailActivity {
        val intent = Intent().putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, id)
        if (secret != null) intent.putExtra(PluginDetailActivity.EXTRA_SECRET_NAME, secret)
        val a = launch(PluginDetailActivity::class.java, intent)
        capture(a, "plugin_detail", state)
        return a
    }

    private fun production(id: String, state: String) {
        Fixtures.registerPlugins()
        open(id, state)
    }

    private val contacts = PluginPermission("android.permission.READ_CONTACTS", "Contacts", "It searches your contacts.", false)
    private val textSetting = PluginSetting.Text("limit", "Character cap", "Longest text it will speak, 1 to 5000.", default = "", numeric = true, placeholder = "500")
    private fun choice(n: Int) = PluginSetting.Choice(
        "mode", "Voice style", "How it sounds.", options = (1..n).map { "Option $it" }, default = "Option 1",
    )

    @Test fun plugin_detail_tts() = production("tts", "tts")
    @Test fun plugin_detail_tts_off() {
        Fixtures.registerPlugins(); Fixtures.pluginsSwitchedOff("tts"); open("tts", "tts_off")
    }
    @Test fun plugin_detail_contacts_needed() {
        Fixtures.contactsPermission(false); production("contacts", "contacts_needed")
    }
    @Test fun plugin_detail_contacts_granted() {
        Fixtures.contactsPermission(true); production("contacts", "contacts_granted")
    }
    @Test fun plugin_detail_permission_optional() {
        register(DetailFakePlugin("camera", "Camera", perms = listOf(PluginPermission("android.permission.CAMERA", "Camera", "Lets it take a photo when asked.", true))))
        open("camera", "permission_optional")
    }
    @Test fun plugin_detail_notifications() = production("notifications", "notifications")
    @Test fun plugin_detail_notifications_access_granted() {
        BridgeService.listenerRef = org.robolectric.Robolectric.buildService(com.mido.phonebridge.NotifListenerService::class.java).get()
        try { production("notifications", "notifications_access_granted") } finally { BridgeService.listenerRef = null }
    }
    @Test fun plugin_detail_access_all_four() {
        ShadowSettings.setCanDrawOverlays(true)
        register(DetailFakePlugin("everything", "Everything", access = SpecialAccess.entries))
        open("everything", "access_all_four")
    }
    @Test fun plugin_detail_google_not_set_up() {
        Fixtures.registerPlugins(); Fixtures.googleClient(""); open("google", "google_not_set_up")
    }
    @Test fun plugin_detail_error_status() {
        register(DetailFakePlugin("broken", "Broken", throwing = true)); open("broken", "error_status")
    }
    @Test fun plugin_detail_ssh_connection() {
        Fixtures.registerPlugins(); open("ssh", "ssh_connection", secret = "kvm-box")
    }
    @Test fun plugin_detail_secret() {
        Fixtures.clearPlugins()
        PluginRegistry.register(com.mido.phonebridge.plugins.secrets.SecretPlugin(Fixtures.app, "deploy-token"))
        open("deploy-token", "secret")
    }
    @Test fun plugin_detail_choice_knob() { register(DetailFakePlugin("styled", "Styled", declared = listOf(choice(3)))); open("styled", "choice_knob") }
    @Test fun plugin_detail_choice_chips() { register(DetailFakePlugin("styled", "Styled", declared = listOf(choice(6)))); open("styled", "choice_chips") }
    @Test fun plugin_detail_text_unset() { register(DetailFakePlugin("texty", "Texty", declared = listOf(textSetting))); open("texty", "text_unset") }
    @Test fun plugin_detail_text_set() {
        register(DetailFakePlugin("texty", "Texty", declared = listOf(textSetting)))
        com.mido.phonebridge.core.PluginSettingsStore.from(Fixtures.app).put("texty", textSetting, "500")
        open("texty", "text_set")
    }
    @Test fun plugin_detail_routes_long() { register(DetailFakePlugin("long", "Long", routeCount = 14)); open("long", "routes_long") }
    @Test fun plugin_detail_unregistered() {
        Fixtures.clearPlugins()
        val a = open("nonexistent", "unregistered")
        assertFalse("the not-registered state must stay on screen", a.isFinishing)
    }

    private fun editDialog(): PluginDetailActivity {
        register(DetailFakePlugin("texty", "Texty", declared = listOf(textSetting)))
        val a = launch(PluginDetailActivity::class.java, Intent().putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, "texty"))
        (findByDescription(a.window.decorView, "Edit Character cap") as View).performClick()
        return a
    }

    @Test fun plugin_detail_text_prompt() { editDialog(); Shots.screen("plugin_detail", "text_prompt") }

    @Test fun plugin_detail_text_prompt_error() {
        editDialog()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = firstEditText(dialog.window!!.decorView)!!
        input.setText("lots")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue("a refusal keeps the dialog open", dialog.isShowing)
        val error = firstTextStartingWith(dialog.window!!.decorView, "Couldn't save:")
        assertNotNull("a refusal shows \"Couldn't save: ...\"", error)
        assertEquals("the refusal line is visible", View.VISIBLE, error!!.visibility)
        Shots.screen("plugin_detail", "text_prompt_error")
    }

    @Test fun plugin_detail_text_prompt_accepts_a_valid_value() {
        val activity = editDialog()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        firstEditText(dialog.window!!.decorView)!!.setText("500")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse("an accepted value closes the dialog", dialog.isShowing)
        assertNotNull("the readout shows 500", findByText(activity.window.decorView, "500"))
    }

    private fun firstTextStartingWith(v: View, prefix: String): android.widget.TextView? {
        if (v is android.widget.TextView && v.text.startsWith(prefix)) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstTextStartingWith(v.getChildAt(i), prefix)?.let { return it }
        return null
    }

    private fun firstEditText(v: View): EditText? {
        if (v is EditText) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstEditText(v.getChildAt(i))?.let { return it }
        return null
    }
}
