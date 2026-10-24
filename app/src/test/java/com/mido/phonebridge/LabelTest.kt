package com.mido.phonebridge

import com.mido.phonebridge.core.Origin
import com.mido.phonebridge.core.OPENCLAW_IP
import com.mido.phonebridge.core.originOf
import com.mido.phonebridge.core.requesterLabelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * originOf() is a total function over untrusted input (a socket address). It must
 * never throw and never guess UP: a stranger mislabelled OPENCLAW would be treated
 * as the trusted caller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LabelTest {

    @Test
    fun `this build box is OPENCLAW`() {
        assertEquals(Origin.OPENCLAW, originOf(OPENCLAW_IP))
    }

    @Test
    fun `loopback is LOCAL in every spelling`() {
        assertEquals(Origin.LOCAL, originOf("127.0.0.1"))
        assertEquals(Origin.LOCAL, originOf("::1"))
        assertEquals(Origin.LOCAL, originOf("localhost"))
    }

    @Test
    fun `the phone's tailnet address is TAILNET, not OPENCLAW`() {
        assertEquals(Origin.TAILNET, originOf("100.64.0.11"))
        assertEquals(Origin.TAILNET, originOf("100.64.0.1"))
        assertEquals(Origin.TAILNET, originOf("100.127.255.254"))
    }

    @Test
    fun `outside the CGNAT range is UNKNOWN, never TAILNET`() {
        assertEquals(Origin.UNKNOWN, originOf("100.63.0.1"))
        assertEquals(Origin.UNKNOWN, originOf("100.128.0.1"))
    }

    @Test
    fun `garbage is UNKNOWN and never throws`() {
        assertEquals(Origin.UNKNOWN, originOf(""))
        assertEquals(Origin.UNKNOWN, originOf("not-an-ip"))
        assertEquals(Origin.UNKNOWN, originOf("192.168.1.5"))
        assertEquals(Origin.UNKNOWN, originOf("100."))
    }

    @Test
    fun `requester label names the box, otherwise echoes the address`() {
        assertEquals("OpenClaw (this box)", requesterLabelFor(OPENCLAW_IP))
        assertEquals("100.64.0.11", requesterLabelFor("100.64.0.11"))
        assertFalse(requesterLabelFor("100.64.0.11").contains("OpenClaw"))
    }
}
