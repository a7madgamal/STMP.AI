package com.mido.phonebridge.plugins.ssh

import org.json.JSONObject

/**
 * A stored SSH connection: where to connect, as whom, and with which key.
 *
 * **The private key never leaves the phone.** It lives in the same biometric vault as the other
 * secrets, and the only thing that crosses the network is an SSH session the PHONE opens. Mido's
 * framing, which is the reason this design exists at all: *"the key never needs to reach me"*.
 *
 * The whole record is one vault value (a JSON string) under the name `ssh-<name>`, so there is one
 * credential to unlock and one place to delete. A connection is not split across prefs and files.
 */
data class SshConnection(
    /** The address as the user typed it (hostname or IP). Shown on every approval card. */
    val host: String,
    val port: Int = 22,
    val user: String,
    /** PEM private key. In memory only while a call is running; at rest it is vault-encrypted. */
    val privateKey: String,
    /**
     * The server's host key fingerprint from the first successful connection, or null before it.
     *
     * **Trust-on-first-use, stated plainly rather than hidden.** SSH's own default is the same
     * thing: accept the key the first time, refuse if it ever changes. The fingerprint is returned
     * in the first result so it is visible, and every later connection verifies against it — a
     * changed key is exactly the signal a MITM would produce, so it must fail loudly rather than
     * silently reconnect.
     */
    val hostKeyFingerprint: String? = null,
) {
    /** The vault entry name. Names are constrained to `[a-z0-9_-]`, hence the prefix. */
    fun vaultName(): String = "ssh-$user@$host".lowercase().replace(Regex("[^a-z0-9_-]"), "-")

    fun toJson(): JSONObject = JSONObject()
        .put("host", host)
        .put("port", port)
        .put("user", user)
        .put("privateKey", privateKey)
        .put("hostKeyFingerprint", hostKeyFingerprint ?: JSONObject.NULL)

    companion object {
        /** Parse a stored value. Throws rather than defaulting a missing field into a guess. */
        fun fromJson(raw: String): SshConnection {
            val o = JSONObject(raw)
            val host = o.optString("host").trim()
            if (host.isBlank()) throw IllegalArgumentException("stored connection has no host")
            val user = o.optString("user").trim()
            if (user.isBlank()) throw IllegalArgumentException("stored connection has no user")
            val key = o.optString("privateKey")
            if (key.isBlank()) throw IllegalArgumentException("stored connection has no private key")
            val port = o.optInt("port", 22)
            if (port !in 1..65535) throw IllegalArgumentException("stored port $port is out of range")
            val fp = o.optString("hostKeyFingerprint").takeUnless { it.isBlank() }
            return SshConnection(host, port, user, key, fp)
        }
    }
}
