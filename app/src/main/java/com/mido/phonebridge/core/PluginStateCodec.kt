package com.mido.phonebridge.core

import org.json.JSONObject

/**
 * The stored form of plugin enable/disable state, as a pure function pair.
 *
 * **Why this is separate from [PluginSettingsStore].** The store needs a `Context` for
 * SharedPreferences, which makes the whole class untouchable from a bare JVM test. The part that
 * can actually be WRONG — interpreting bytes that may be corrupt — is exactly the part with no
 * Android in it, so it lives here and gets tested directly.
 *
 * The rule this exists to enforce: a stored value that is not a boolean is a DEFECT and must be
 * refused. `optBoolean` would answer `false`, which silently switches a plugin off — and for a
 * capability the human relies on, that is worse than refusing to start.
 */
object PluginStateCodec {

    /**
     * Decode the enabled map.
     *
     * @throws PluginSettingsException when the JSON is unparseable, or any value is not a
     *   boolean. Never returns a partial map: half-read state is how a plugin ends up disabled
     *   with no record of why.
     */
    fun decodeEnabled(raw: String): Map<String, Boolean> {
        val obj = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw PluginSettingsException("plugin state is unreadable: ${e.message}")
        }
        val out = LinkedHashMap<String, Boolean>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            if (v !is Boolean) {
                throw PluginSettingsException(
                    "plugin state for '$k' is ${v?.javaClass?.simpleName ?: "null"}, not a boolean"
                )
            }
            out[k] = v
        }
        return out
    }

    /** Encode the enabled map. Ordered as given, so a diff of the file stays readable. */
    fun encodeEnabled(map: Map<String, Boolean>): String {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        return obj.toString()
    }

    /**
     * Decode a stored boolean setting.
     *
     * Same refusal rule as above, and for the same reason: `"yes"` is not `false`, it is a
     * value this app never wrote and cannot interpret.
     */
    fun decodeBoolean(settingKey: String, raw: String): Boolean = when (raw) {
        "true" -> true
        "false" -> false
        else -> throw PluginSettingsException(
            "setting '$settingKey' holds '$raw', which is not a boolean"
        )
    }
}
