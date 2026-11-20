package com.mido.phonebridge.core

/**
 * The one folder STMP writes to, as a fact about the APP rather than about a plugin.
 *
 * **Why this is in core and not on a plugin.** `Downloads/STMP` was a companion constant on
 * `FilesPlugin`, which was fine while it was the only plugin that cared. It is not: the `oauth`
 * plugin reads the packages it uploads from the same folder, and reaching into another plugin's
 * class for it would make that plugin's state part of the other's contract.
 *
 * Mido, 2026-11-19: *"use the file plugin folder if it's hardcoded and safe"* — and it is both.
 * A shared CONSTANT is not shared state: delete `files` from the build entirely and `oauth` still
 * works, because this string lives here. What is deliberately NOT shared is the SAF grant. Each
 * plugin takes its own persistable permission over this folder, in its own prefs, so turning one
 * off cannot break the other.
 *
 * **The security property is unchanged by sharing it.** The name is hardcoded, so no plugin can be
 * pointed at `Downloads` at large, at `DCIM`, or anywhere the human did not choose in the picker.
 */
object StmpFolder {

    /** Where files land, for messages, cards and the audit summary. */
    const val PATH = "Downloads/STMP"

    /**
     * The last path segment of [PATH].
     *
     * A SAF tree's display name is the folder's OWN name — `Downloads`, `STMP` — not the multi-level
     * path the app thinks in. Comparing a grant's name against [PATH] would therefore never match,
     * so a screen checking "is this the folder uploads come from" must compare against this.
     */
    val LAST_SEGMENT: String = PATH.substringAfterLast('/')
}
