package com.mido.phonebridge.notify

import com.mido.phonebridge.ai.Risk
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.Verb

/**
 * The words on the Don't-disturb card (R1). No Android types, so it is JVM-testable.
 *
 * The card must say WHO is asking and WHICH plugin, and name the risk as a word, because the
 * silence of the card approves after five seconds: whatever is on it is the whole consent.
 */
internal object NotifyCardCopy {

    /** The plugin id behind [label] (`gmail.send` -> `gmail`), preferring the facts when they know it. */
    fun pluginId(label: String, factsPlugin: String?): String =
        factsPlugin?.takeIf { it.isNotBlank() && it != "?" } ?: label.substringBefore('.')

    /** D21: a plugin is named by its display name, never its id. */
    fun pluginName(id: String): String = PluginRegistry.all().firstOrNull { it.id == id }?.displayName ?: id

    /** What the card says when no requester name arrives; never an empty header. */
    const val UNKNOWN_REQUESTER = "unknown requester"

    /** `Work laptop · Contacts`; `unknown requester · Contacts` when no name arrives. */
    fun requesterLine(requester: String, pluginName: String): String =
        "${requester.ifBlank { UNKNOWN_REQUESTER }} · $pluginName"

    /** The AI's call, spelled out so "HIGH" cannot be read as confidence. */
    fun riskWord(risk: Risk): String = "${risk.name} risk"

    fun description(verb: Verb, requesterLine: String, summary: String): String =
        "${verb.name} request from $requesterLine: $summary. Tap to approve now, the ✕ denies, silence approves."
}
