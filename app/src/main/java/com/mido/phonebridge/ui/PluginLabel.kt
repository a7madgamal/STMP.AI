package com.mido.phonebridge.ui

import com.mido.phonebridge.core.PluginRegistry

/** Plugin id to the name a person reads. Shared by the request log, detail and Exceptions. */
object PluginLabel {
    /**
     * The registered plugin's display name, else the id made readable ("my-plugin" becomes
     * "My plugin"): the registry is filled when the service starts, so a plugin that is gone or
     * not started yet must still read as a name, never as a lowercase id. Blank gives "".
     */
    fun of(id: String): String {
        if (id.isBlank()) return ""
        PluginRegistry.get(id)?.displayName?.let { return it }
        val words = id.trim().replace(Regex("[-_.]"), " ")
        // Brand names keep their own casing ("github" is "GitHub", never "Github").
        BRANDS[words.lowercase()]?.let { return it }
        return words.replaceFirstChar { it.uppercase() }
    }

    private val BRANDS = mapOf("github" to "GitHub", "gitlab" to "GitLab", "openai" to "OpenAI")
}
