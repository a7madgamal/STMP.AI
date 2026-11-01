package com.mido.phonebridge.core

/**
 * One candidate address found on a network interface.
 *
 * Interface name matters because the address alone cannot say whether it is reachable: a
 * `rmnet` address is a perfectly valid IPv4 that no inbound connection can ever reach, because
 * the carrier NATs it. So the name is a fact the choice needs, not decoration.
 */
data class NicAddress(val name: String, val ip: String)

/**
 * Which local IPv4 the HTTP server should bind to.
 *
 * **Why this is its own pure object.** The choice used to live inside `BridgeService` as
 * `tailnetAddress()`, which returned an address only in Tailscale's `100.64.0.0/10` range and
 * `null` for everything else. The service then treated `null` as "refuse to start", so a phone
 * on a perfectly good Wi-Fi network could not serve at all — Tailscale was a hard requirement
 * enforced by a helper whose name did not say so.
 *
 * Tailscale is now a recommendation, not a precondition: prefer its address when one exists
 * (it is the only one that survives leaving the network), otherwise use whatever is reachable.
 *
 * Extracted so the rule can be asserted without an Android runtime — the same treatment
 * `HostAllowlist`, `ResponseBody`, `StartupRetry` and `VaultState` already get. A bind decision
 * buried in an interface loop is a decision nobody can test.
 */
object BindAddress {

    /**
     * Interface-name prefixes that are cellular.
     *
     * A carrier address is inbound-unreachable by construction, so binding one is a silent
     * failure dressed as success: the server reports bound, and every caller times out.
     * Android uses `rmnet*` on most devices, `ccmni*` on MediaTek, `pdp*`/`seth*` on older
     * Qualcomm stacks.
     */
    private val CELLULAR_PREFIXES = listOf("rmnet", "ccmni", "pdp", "seth")

    /** Interfaces preferred after the tailnet when several are usable. */
    private val PREFERRED_PREFIXES = listOf("wlan", "eth")

    /** True when [ip] is in Tailscale's CGNAT range, `100.64.0.0/10`. */
    fun isTailnet(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        if (parts[0] != "100") return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 64..127
    }

    /**
     * Could a caller actually reach [a]?
     *
     * Rejects anything that is not a routable IPv4: IPv6 (the server binds IPv4), loopback
     * (reachable only from the phone itself), link-local (`169.254/16`, which means DHCP failed),
     * the `0.0.0.0/8` range, and cellular as described above.
     */
    fun isUsable(a: NicAddress): Boolean {
        if (a.ip.isEmpty() || ':' in a.ip) return false
        val parts = a.ip.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        if (octets[0] == 0) return false
        if (octets[0] == 127) return false
        if (octets[0] == 169 && octets[1] == 254) return false
        val name = a.name.lowercase()
        if (CELLULAR_PREFIXES.any { name.startsWith(it) }) return false
        return true
    }

    /**
     * The address to bind, or null when the phone has nothing reachable.
     *
     * Null is a real state (airplane mode, no DHCP lease) and the caller treats it as "wait",
     * never as a reason to bind `0.0.0.0` — binding every interface is a decision nobody made.
     */
    fun choose(candidates: List<NicAddress>): String? {
        val usable = candidates.filter { isUsable(it) }
        usable.firstOrNull { isTailnet(it.ip) }?.let { return it.ip }
        usable.firstOrNull { c -> PREFERRED_PREFIXES.any { c.name.lowercase().startsWith(it) } }
            ?.let { return it.ip }
        return usable.firstOrNull()?.ip
    }
}
