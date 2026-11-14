package com.mido.phonebridge

import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.kit.allViews
import com.mido.phonebridge.screenshots.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The "Sign in to your Google account first" status on Gmail, Calendar and Drive is a way forward. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginDetailSignInTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() {
        Fixtures.dropSingletons()
        AuthManager.clear(app)
        Fixtures.registerPlugins()
    }

    @After fun tearDown() {
        AuthManager.clear(app)
        Fixtures.reset()
    }

    /** Robolectric has no AndroidKeyStore, so the signed-in state is a live in-memory access token. */
    private fun signInWithLiveToken() {
        fun set(name: String, value: Any?) = AuthManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(AuthManager, value)
        set("accessToken", "live-token")
        set("accessTokenExpiry", System.currentTimeMillis() + 3_600_000L)
    }

    private fun open(id: String): PluginDetailActivity =
        Robolectric.buildActivity(
            PluginDetailActivity::class.java,
            Intent().putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, id),
        ).setup().get()

    private fun signInKey(a: PluginDetailActivity): View? =
        a.window.decorView.allViews().firstOrNull { it.contentDescription == "Sign in with Google" }

    @Test fun `gmail not signed in offers a sign in key that opens Google sign-in`() {
        Fixtures.googleClient("client-id")
        val a = open("gmail")
        val key = signInKey(a)
        assertNotNull(key)
        assertTrue(key!!.isClickable)
        key.performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(GoogleLoginActivity::class.java.name, started.component?.className)
    }

    @Test fun `calendar and drive offer it too`() {
        Fixtures.googleClient("client-id")
        for (id in listOf("calendar", "drive")) assertNotNull(id, signInKey(open(id)))
    }

    @Test fun `signed in there is no sign in key`() {
        Fixtures.googleClient("client-id")
        signInWithLiveToken()
        assertNull(signInKey(open("gmail")))
    }

    @Test fun `a build with no Google client has no sign in key`() {
        Fixtures.googleClient("")
        assertNull(signInKey(open("gmail")))
    }

    @Test fun `non Google plugins never get one`() {
        Fixtures.googleClient("client-id")
        assertFalse(signInKey(open("contacts")) != null)
    }
}
