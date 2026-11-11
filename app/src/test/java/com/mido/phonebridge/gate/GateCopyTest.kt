package com.mido.phonebridge.gate

import com.mido.phonebridge.ApprovalGate.ApproveGesture
import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.plugins.gmail.GmailPlugin
import com.mido.phonebridge.policy.Mode
import com.mido.phonebridge.policy.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WP03a section 7: exception captions name the plugin and mode in words (D21, D64). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GateCopyTest {

    @Before
    fun registerGmail() {
        PluginRegistry.register(GmailPlugin(RuntimeEnvironment.getApplication()))
    }

    private val gmailDelete = Rule("gmail", Verb.DELETE, Mode.DONT_DISTURB)

    @Test
    fun `the exception caption uses the display name and the mode name`() {
        val tap = GateCopy.exceptionCaption(ApproveGesture.TAP, on = false, rule = gmailDelete)
        assertEquals("Approves this request and sets Gmail · delete to Don't disturb from now on.", tap)
        assertFalse(tap.contains("gmail"))
        assertEquals(tap, GateCopy.exceptionCaption(ApproveGesture.TAP_THEN_BIOMETRIC, on = false, rule = gmailDelete))
        assertEquals(
            "Tap to also save this exception, then hold the key: Gmail · delete → Don't disturb.",
            GateCopy.exceptionCaption(ApproveGesture.HOLD, on = false, rule = gmailDelete),
        )
        assertEquals(
            "The exception is saved when you hold the key: Gmail · delete → Don't disturb.",
            GateCopy.exceptionCaption(ApproveGesture.HOLD, on = true, rule = gmailDelete),
        )
    }

    @Test
    fun `any verb and paranoid`() {
        val caption = GateCopy.exceptionCaption(ApproveGesture.TAP, false, Rule("gmail", null, Mode.PARANOID))
        assertTrue(caption, caption.contains("Gmail · any verb to Paranoid"))
    }

    @Test
    fun `an unregistered plugin falls back to its id`() {
        assertEquals("nope", GateCopy.pluginName("nope"))
    }

    @Test
    fun `small strings`() {
        assertEquals("1 waiting behind this one", GateCopy.queue(1))
        assertEquals("REQ 1043", GateCopy.trailing(1043))
        assertEquals("ALWAYS NOTIFY · ON", GateCopy.exceptionKey("ALWAYS NOTIFY", on = true))
        assertEquals("AI summary · may be wrong · HIGH", GateCopy.aiReadyLabel(Risk.HIGH))
        assertEquals("100.64.0.7 · as me@example.com", GateCopy.addressLine("100.64.0.7", "me@example.com"))
        assertEquals("100.64.0.7", GateCopy.addressLine("100.64.0.7", "—"))
        assertEquals("thinking...", GateCopy.loading(0, 3))
    }

    @Test
    fun `no string uses a banned glossary word`() {
        val banned = listOf("PhoneBridge", "AUTOMATIC", "REJECTED", "Options")
        val strings = GateCopy::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.isAccessible = true; it.get(null) as String } +
            GateCopy.LOADING_WORDS +
            GateCopy.CallerKind.values().map(GateCopy::callerState) +
            listOf(
                GateCopy.exceptionCaption(ApproveGesture.HOLD, true, gmailDelete),
                GateCopy.exceptionCaption(ApproveGesture.HOLD, false, gmailDelete),
                GateCopy.exceptionCaption(ApproveGesture.TAP, false, gmailDelete),
                GateCopy.aiFailed("x"), GateCopy.windowTitle("x"), GateCopy.armGuard(1), GateCopy.armArmed(47),
            )
        assertTrue("found ${strings.size} strings", strings.size > 30)
        for (s in strings) for (b in banned) assertFalse("'$s' contains $b", s.contains(b))
    }
}
