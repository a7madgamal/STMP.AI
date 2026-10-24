package com.mido.phonebridge.core

/**
 * The three rules a secret's identifying fields must satisfy: name, docs link, key link.
 *
 * **Extracted from `SecretVault` so they can be TESTED.** They were private methods on the vault,
 * which meant the only way to check a template was valid was to try SAVING it on a device — after
 * pasting a real API key, in a dialog, where the failure is a toast. That is the wrong moment to
 * discover a typo in a built-in list.
 *
 * This is the same move the project has made before (`HostAllowlist`, `ResponseBody`,
 * `VaultState`): a rule that decides what is allowed lives in a pure object, gets its cases
 * named, and is enforced by the build instead of by remembering. `SecretVault` now delegates
 * here, so there is one implementation and no drift between "what the vault accepts" and "what
 * the templates are checked against".
 *
 * No Android, no I/O: `java.net.URL` is JVM-standard, so this runs in the unit suite.
 */
object SecretRules {

    /**
     * Secret names are PLUGIN IDS — they become the route segment and the policy key
     * (`todoist × EXECUTE`), so the shape is constrained by both: lowercase, no slashes, no
     * spaces, 1-64 chars, starting alphanumeric.
     */
    private val NAME = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    /** Throws `bad_request` for a name that could not be a plugin id or a route segment. */
    fun requireName(name: String) {
        if (!name.matches(NAME)) {
            throw PluginError(
                "bad_request",
                "secret name must be lowercase [a-z0-9_-], 1-64 chars, starting alphanumeric"
            )
        }
    }

    /** True when [name] is a legal secret name. For tests and for pre-checking a template. */
    fun isValidName(name: String): Boolean = name.matches(NAME)

    /**
     * A link is either absent or an https URL. https-only because these are opened in a browser
     * from the CRUD screen: allowing http would let a stored link be a cleartext trip, and the
     * two links exist precisely to be trustworthy.
     */
    fun requireUrlOrEmpty(field: String, url: String) {
        if (url.isEmpty()) return
        val parsed = try {
            java.net.URL(url)
        } catch (e: Exception) {
            throw PluginError("bad_request", "$field is not a valid URL: ${e.message}")
        }
        if (parsed.protocol != "https") {
            throw PluginError("bad_request", "$field must be https, got '${parsed.protocol}'")
        }
    }

    /** True when [url] is absent or a valid https URL. */
    fun isValidUrlOrEmpty(url: String): Boolean = try {
        requireUrlOrEmpty("link", url)
        true
    } catch (e: PluginError) {
        false
    }
}
