package com.mido.phonebridge.core

import android.content.Context

/**
 * One setting a plugin owns, described as DATA rather than as a View.
 *
 * **Why descriptors and not views.** A plugin that builds its own `LinearLayout` puts UI code
 * inside every plugin, and the settings screen becomes a pile of unrelated layouts again — which
 * is exactly what `OptionsActivity` is today. A descriptor can be rendered by ONE screen, tested
 * without an Android runtime, and serialised into the store without the store knowing what it is.
 *
 * The trade is that a plugin cannot draw something arbitrary. That is deliberate: the escapes
 * that genuinely need custom UI (SSH's connection list, TTS's "speak this") are a handful and get
 * their own screen rather than bending this schema.
 *
 * [id] is stable and namespaced per plugin — the store keys on `pluginId.id`, so two plugins may
 * both have an `enabled` or `url` setting without colliding.
 */
sealed interface PluginSetting {

    /** Stable within its plugin. Never renamed: it is a storage key. */
    val id: String

    /** Row label as the human reads it. */
    val label: String

    /** One line under the label explaining the consequence. Empty when the label is enough. */
    val description: String

    /**
     * An on/off switch.
     *
     * [default] is used only when no value has ever been stored — never as a fallback for a
     * corrupt or missing value, which is a distinct case the store refuses.
     */
    data class Toggle(
        override val id: String,
        override val label: String,
        override val description: String = "",
        val default: Boolean,
    ) : PluginSetting

    /**
     * Free text.
     *
     * [numeric] exists so the renderer can pick a keyboard and the store can reject non-digits
     * at the boundary, rather than the plugin re-validating what the field already knew.
     */
    data class Text(
        override val id: String,
        override val label: String,
        override val description: String = "",
        val default: String = "",
        val placeholder: String = "",
        val numeric: Boolean = false,
    ) : PluginSetting

    /**
     * One of a fixed set.
     *
     * [options] is the complete list; a stored value outside it is a defect, not a "custom"
     * choice, and the store reports it rather than quietly substituting the default.
     */
    data class Choice(
        override val id: String,
        override val label: String,
        override val description: String = "",
        val options: List<String>,
        val default: String,
    ) : PluginSetting {
        init {
            require(options.isNotEmpty()) { "choice '$id' has no options" }
            require(default in options) { "choice '$id' default '$default' is not one of $options" }
            require(options.distinct().size == options.size) {
                "choice '$id' has duplicate options: $options"
            }
        }
    }

    /**
     * A button that does something immediately, rather than storing a value.
     *
     * The callback receives a [Context] because the work (connect, generate a key, speak a
     * sample) needs one and the renderer must not be the thing that knows how.
     */
    data class Action(
        override val id: String,
        override val label: String,
        override val description: String = "",
        val run: (Context) -> Unit,
    ) : PluginSetting
}

/**
 * Accesses that are NOT runtime permissions.
 *
 * A runtime permission is granted by a dialog. These are not: each one is a screen in Settings
 * that the user must navigate to themselves, so the request that works for a runtime permission
 * does nothing at all here. Modelling them as permissions would produce a button that silently
 * fails — which is the defect class `PluginPermission` exists to remove, so they get their own
 * representation rather than a flag pretending to be one.
 */
enum class SpecialAccess(val label: String) {
    /** Notification listener: reading the notification shade. Settings > notification access. */
    NOTIFICATION_LISTENER("Notification access"),

    /** Draw over other apps. Needed for the overlay card and the modal. */
    OVERLAY("Draw over apps"),

    /** Exempt from battery optimisation, so the service survives a doze. */
    BATTERY_EXEMPT("Battery optimization"),

    /** Full-screen intent: what wakes a LOCKED screen for an approval. */
    FULL_SCREEN_INTENT("Lock-screen popup"),
}

/**
 * An Android runtime permission a plugin needs, with the reason the human is shown.
 *
 * **Why this lives on the plugin.** Permission handling is currently scattered: `ContactsPlugin`
 * checks inline, `DebugPlugin` checks inline, and two Activities request on their own. Three
 * places that must agree about what a plugin needs is three places to forget. Declaring it here
 * makes the need a property of the plugin, so the request can be raised at the moment a route
 * needs it and the reason comes from the same place as the requirement.
 *
 * [why] is REQUIRED, not defaulted. "Allow contacts?" with no explanation is the prompt users
 * refuse; the whole point of asking at the moment of need is that the reason is concrete.
 */
data class PluginPermission(
    /** The `Manifest.permission.*` constant. */
    val permission: String,
    /** Short human name, e.g. "Contacts". */
    val label: String,
    /** Why this plugin cannot work without it. Shown on the request. */
    val why: String,
    /**
     * Whether the plugin works degraded without it.
     *
     * True means "ask, and carry on if refused". False means the plugin cannot serve at all,
     * which the UI states before asking rather than after.
     */
    val optional: Boolean = false,
)
