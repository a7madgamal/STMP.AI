package com.mido.phonebridge

import com.mido.phonebridge.core.AuthManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen that names a problem must offer the way out of it.
 *
 * `GooglePlugin.status()` reports NEEDS_ATTENTION with "not signed in — Gmail, Calendar and Drive
 * cannot serve until you are". On the plugin screen that sentence was a dead end: the only route
 * to sign-in was Home → the Google account row. Gmail's own screen told you what was wrong and
 * gave you nothing to press.
 *
 * The row is decided from the SIGN-IN STATE, not from the status words, because a plugin can be
 * unhealthy for a reason signing in cannot fix — and a button that does nothing is worse than no
 * button. Verified through the rendered view, since the condition lives in the Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SignInRowTest {

    private val savedClientId = AuthManager.clientId

    @After
    fun restore() {
        AuthManager.clientId = savedClientId
        // The registry is a process-wide singleton. A plugin left in it is visible to every later
        // test in the same JVM, which is how this file broke InventoryConstructionTest once.
        com.mido.phonebridge.screenshots.Fixtures.clearPlugins()
    }

    /**
     * A detail screen for [pluginId], with that plugin actually REGISTERED.
     *
     * The registry is populated by the running service, so a bare Robolectric test starts with it
     * empty and the screen correctly renders "That plugin is not registered." — which made the
     * first version of this test pass for the wrong reason on two of three cases.
     */
    private fun detailFor(pluginId: String, classFor: (android.content.Context) -> com.mido.phonebridge.core.Plugin): PluginDetailActivity {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        if (com.mido.phonebridge.core.PluginRegistry.get(pluginId) == null) {
            com.mido.phonebridge.core.PluginRegistry.register(classFor(app))
        }
        return com.mido.phonebridge.screenshots.launch(
            PluginDetailActivity::class.java,
            android.content.Intent().putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, pluginId),
        )
    }

    private fun gmail() = detailFor("gmail") { c -> com.mido.phonebridge.plugins.gmail.GmailPlugin(c) }

    private fun ssh() = detailFor("ssh") { c -> com.mido.phonebridge.plugins.ssh.SshPlugin(c) }

    /** True when the screen offers the sign-in row, found by its label. */
    private fun offersSignIn(a: PluginDetailActivity): Boolean =
        com.mido.phonebridge.screenshots.findByText(
            a.window.decorView, "Google sign-in",
        ) != null

    @Test
    fun `a Google plugin offers sign-in when there is a client and nobody is signed in`() {
        AuthManager.clientId = "test-client-id.apps.googleusercontent.com"
        AuthManager.accountEmail = null
        val a = gmail()
        assertTrue(
            "Gmail cannot serve without an account, so its screen must offer sign-in",
            offersSignIn(a),
        )
    }

    @Test
    fun `a plugin that does not need Google never offers it`() {
        AuthManager.clientId = "test-client-id.apps.googleusercontent.com"
        // SSH does not touch the account; offering sign-in here would send the human somewhere
        // unrelated and imply the machine access depends on Google.
        assertEquals(false, offersSignIn(ssh()))
    }

    @Test
    fun `a build with no OAuth client offers nothing, because there is nothing to open`() {
        AuthManager.clientId = ""
        assertEquals(false, offersSignIn(gmail()))
    }
}
