package com.mido.phonebridge

import com.mido.phonebridge.core.BindAddress
import com.mido.phonebridge.core.NicAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which IPv4 the server binds to.
 *
 * **Why this is worth a file of tests.** The old rule lived inside `BridgeService` as
 * `tailnetAddress()`, which returned an address only in Tailscale's `100.64.0.0/10` range and
 * `null` for everything else — and `null` meant "refuse to start". That made Tailscale a hard
 * requirement enforced by a helper whose name did not say so: a phone on ordinary Wi-Fi could not
 * serve at all, and the only evidence was a status notification about Tailscale.
 *
 * A bind decision is the difference between "the bridge works" and "the bridge silently does not
 * exist", so every branch here is asserted rather than reasoned about.
 */
class BindAddressTest {

    private fun nic(name: String, ip: String) = NicAddress(name, ip)

    @Test
    fun `the tailnet wins when several addresses are usable`() {
        // Tailscale's address is the only one that survives leaving the network, so it is
        // preferred — but it is a preference now, not a requirement.
        val chosen = BindAddress.choose(
            listOf(
                nic("wlan0", "192.168.1.5"),
                nic("tailscale0", "100.64.0.11"),
                nic("eth0", "10.0.0.9"),
            )
        )
        assertEquals("100.64.0.11", chosen)
    }

    @Test
    fun `wifi is used when there is no tailnet address`() {
        // THE regression this whole change exists for. Before, this returned null and the
        // service refused to start — on a perfectly good network.
        val chosen = BindAddress.choose(listOf(nic("wlan0", "192.168.1.5")))
        assertEquals("192.168.1.5", chosen)
    }

    @Test
    fun `wifi beats an unnamed interface`() {
        val chosen = BindAddress.choose(
            listOf(
                nic("dummy0", "10.0.0.2"),
                nic("wlan0", "192.168.1.5"),
            )
        )
        assertEquals("192.168.1.5", chosen)
    }

    @Test
    fun `a cellular address is never chosen`() {
        // `rmnet` is a valid IPv4 that no inbound connection can reach, because the carrier
        // NATs it. Binding one reports success and every caller times out — the worst kind of
        // failure, because nothing looks wrong.
        for (name in listOf("rmnet_data0", "rmnet0", "ccmni0", "pdp0", "seth0")) {
            assertFalse("$name must not be usable", BindAddress.isUsable(nic(name, "10.64.1.7")))
        }
        assertNull(BindAddress.choose(listOf(nic("rmnet_data0", "10.64.1.7"))))
    }

    @Test
    fun `a cellular address loses to wifi even though both are present`() {
        val chosen = BindAddress.choose(
            listOf(
                nic("rmnet_data0", "10.64.1.7"),
                nic("wlan0", "192.168.1.5"),
            )
        )
        assertEquals("192.168.1.5", chosen)
    }

    @Test
    fun `loopback is never chosen`() {
        // Reachable only from the phone itself, so binding it would make the bridge appear to
        // work while being unreachable from the agent.
        assertFalse(BindAddress.isUsable(nic("lo", "127.0.0.1")))
        assertNull(BindAddress.choose(listOf(nic("lo", "127.0.0.1"))))
    }

    @Test
    fun `link-local means DHCP failed, not that we have an address`() {
        assertFalse(BindAddress.isUsable(nic("wlan0", "169.254.13.7")))
    }

    @Test
    fun `ipv6 is rejected because the server binds ipv4`() {
        assertFalse(BindAddress.isUsable(nic("wlan0", "fe80::1")))
        assertFalse(BindAddress.isUsable(nic("wlan0", "2001:db8::1")))
    }

    @Test
    fun `nothing usable means null, never a wildcard bind`() {
        // Null is a real state (airplane mode). `0.0.0.0` is a decision nobody made: it would
        // expose the bridge on every interface at once.
        assertNull(BindAddress.choose(emptyList()))
        assertNull(BindAddress.choose(listOf(nic("lo", "127.0.0.1"))))
    }

    @Test
    fun `the tailnet range boundaries are exact`() {
        // 100.64.0.0/10 covers second octet 64..127. Off-by-one here would either steal a
        // public address from a carrier or refuse a real tailnet one.
        assertFalse("100.63 is outside the range", BindAddress.isTailnet("100.63.255.1"))
        assertTrue("100.64 is the first", BindAddress.isTailnet("100.64.0.1"))
        assertTrue("100.127 is the last", BindAddress.isTailnet("100.127.255.254"))
        assertFalse("100.128 is outside", BindAddress.isTailnet("100.128.0.1"))
        assertFalse("a different first octet", BindAddress.isTailnet("101.64.0.1"))
        assertFalse("not an address", BindAddress.isTailnet("100.64"))
    }
}
