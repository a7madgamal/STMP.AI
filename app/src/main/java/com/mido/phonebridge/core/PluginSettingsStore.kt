package com.mido.phonebridge.core

import android.content.Context
import org.json.JSONObject

/**
 * Which plugins are ON, and what each plugin's settings are set to.
 *
 * **Why this is not in `PolicyStore`.** Policy answers "may this request happen"; this answers
 * "does this capability exist". They change on different schedules and for different reasons,
 * and a corrupt plugin file must not be able to take the approval gate down with it.
 *
 * **Storage lifetime rules, and they are the whole design:**
 *
 *  - A plugin that has never been chosen uses [Plugin.enabledByDefault]. That is a FIRST-RUN
 *    default, never a fallback: once a value exists, its absence is not possible again.
 *  - A plugin DELETED from the build leaves its entry behind. Harmless, and deliberate — an
 *    uninstalled-then-reinstalled plugin gets its old choice back rather than silently
 *    re-enabling itself under a human who had turned it off.
 *  - A stored boolean that is not a boolean, or a plugin name that is not a string, is a
 *    CORRUPT file. That refuses rather than defaulting, for the same reason the policy file
 *    does: silently re-enabling a capability a human disabled is a security regression dressed
 *    as resilience.
 *
 * Writes are atomic (temp + rename), same as `PolicyStore`, so a kill mid-write cannot leave a
 * half file that the next boot reads as "everything default".
 */
class PluginSettingsStore internal constructor(ctx: Context) {

    /**
     * Only the SharedPreferences handle is held, never the Context.
     *
     * The store is a process-wide singleton, so keeping `ctx` in a field would pin whatever
     * Context was passed first — an Activity, in a future caller — for the life of the
     * process. Lint flags that as `StaticFieldLeak` and it is right. `SharedPreferences` is
     * already an application-scoped handle, so it is the only thing worth keeping.
     */
    private val prefs = ctx.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether [plugin] is on, honouring a stored choice over its default.
     *
     * Every plugin honours a stored choice. There is no un-disableable plugin any more, so
     * there is no second case to answer for.
     */
    fun isEnabled(plugin: Plugin): Boolean {
        val stored = readEnabled()
        return stored[plugin.id] ?: plugin.enabledByDefault
    }

    /** Convenience for a plugin id, with the plugin's own default supplied by the caller. */
    fun isEnabled(pluginId: String, default: Boolean): Boolean =
        readEnabled()[pluginId] ?: default

    /**
     * Turn [plugin] on or off.
     *
     * **No exceptions.** Mido, 2026-11-04: *"all plugins should be toggleable no exceptions"*.
     * This used to refuse the three diagnostic plugins, which meant the store and the screen
     * disagreed with the human about which switches were real.
     *
     * The safety that carve-out was protecting is carried by the DISPATCH path instead: a
     * disabled plugin still answers, refusing with `plugin_disabled`. So turning off `help`
     * makes routes explain that they are off rather than 404 — recoverable from the same screen
     * by the same switch, which is the property that actually matters.
     */
    fun setEnabled(plugin: Plugin, enabled: Boolean) {
        val next = readEnabled().toMutableMap()
        next[plugin.id] = enabled
        writeEnabled(next)
    }

    /** Every stored choice. Absent means "never chosen"; callers fall back to the default. */
    fun enabledSnapshot(): Map<String, Boolean> = readEnabled()

    /**
     * A stored setting value, or [fallback] when nothing has been chosen.
     *
     * [fallback] is the descriptor's `default` field, passed in by the caller — the store does
     * not import [PluginSetting] and does not need to know what a toggle is.
     */
    fun value(pluginId: String, settingId: String, fallback: String): String {
        val raw = prefs.getString(scalarKey(pluginId, settingId), null) ?: return fallback
        return raw
    }

    fun booleanValue(pluginId: String, settingId: String, fallback: Boolean): Boolean {
        val raw = prefs.getString(scalarKey(pluginId, settingId), null) ?: return fallback
        // Strict: a stored value that is neither "true" nor "false" is corrupt, not a false.
        return PluginStateCodec.decodeBoolean("$pluginId.$settingId", raw)
    }

    /**
     * Write a value, validating it against the descriptor that declared it where possible.
     *
     * Validation happens HERE rather than in the renderer because a settings screen is not the
     * only way in — a migration or a future import also writes — and a value outside a choice's
     * options is a defect that must be caught at the boundary rather than read back later as a
     * mysterious behaviour.
     */
    fun put(pluginId: String, setting: PluginSetting, value: String) {
        when (setting) {
            is PluginSetting.Choice -> if (value !in setting.options) {
                throw PluginSettingsException(
                    "setting '$pluginId.${setting.id}' got '$value', not one of ${setting.options}"
                )
            }
            is PluginSetting.Text -> if (setting.numeric && value.isNotEmpty() &&
                value.toIntOrNull() == null
            ) {
                throw PluginSettingsException(
                    "setting '$pluginId.${setting.id}' is numeric but got '$value'"
                )
            }
            is PluginSetting.Toggle, is PluginSetting.Action -> Unit
        }
        prefs.edit().putString(scalarKey(pluginId, setting.id), value).apply()
    }

    fun putBoolean(pluginId: String, settingId: String, value: Boolean) {
        prefs.edit().putString(scalarKey(pluginId, settingId), value.toString()).apply()
    }

    /**
     * Wipe every stored choice. For tests and for a "reset to defaults" action.
     *
     * Deliberately NOT offered on uninstall: Android already removes the prefs, and a reset
     * inside the app would be a way to silently re-enable a capability a human turned off
     * without them noticing which ones changed.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // --- storage ---

    private fun scalarKey(pluginId: String, settingId: String) = "s:$pluginId:$settingId"

    /**
     * Read the enabled map.
     *
     * Stored as one JSON object rather than N boolean keys: a single read, a single atomic
     * write, and no way for a half-updated set to exist. `ENABLED_JSON` absent means "nobody
     * has chosen yet", which is distinct from "{}" — an empty object means every plugin is
     * following its default, and both are legitimate.
     */
    private fun readEnabled(): Map<String, Boolean> {
        val raw = prefs.getString(ENABLED_JSON, null) ?: return emptyMap()
        // Parsing lives in PluginStateCodec so the refusal rule is testable without a Context;
        // this method only supplies the bytes.
        return PluginStateCodec.decodeEnabled(raw)
    }

    private fun writeEnabled(map: Map<String, Boolean>) {
        if (!prefs.edit().putString(ENABLED_JSON, PluginStateCodec.encodeEnabled(map)).commit()) {
            throw PluginSettingsException("could not persist plugin state")
        }
    }

        companion object {
        private const val PREFS = "plugin_settings"
        private const val ENABLED_JSON = "enabled"

        @Volatile
        private var singleton: PluginSettingsStore? = null

        /** Idempotent, same pattern as `PolicyStore.from` / `AuditLog.from`. */
        @Synchronized
        fun from(ctx: Context): PluginSettingsStore {
            singleton?.let { return it }
            val store = PluginSettingsStore(ctx.applicationContext)
            singleton = store
            return store
        }

        /** Tests only: drop the singleton so a fresh store is built. */
        @Synchronized
        internal fun resetForTest() {
            singleton = null
        }
    }
}

/** A stored plugin setting was unreadable or refused. Never swallowed. */
class PluginSettingsException(message: String) : Exception(message)
