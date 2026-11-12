package com.mido.phonebridge.ui

import com.mido.phonebridge.core.SpecialAccess
import com.mido.phonebridge.ui.kit.Led

/**
 * The words on the plugin screen, kept pure so a test can read every one.
 *
 * Nothing here names a route, an error code or a plugin id (D21): the screen says what a person
 * can do about the state, not how the server reached it.
 */
object PluginDetailCopy {

    /** A permission or access row: the word, its tone and its light. The word always shows. */
    data class RowState(val word: String, val tone: PluginRowModel.Tone, val led: Led.State)

    private val GRANTED = RowState("GRANTED", PluginRowModel.Tone.OK, Led.State.OK)
    private val OPTIONAL = RowState("OPTIONAL", PluginRowModel.Tone.NEUTRAL, Led.State.OFF)
    private val NEEDED = RowState("NEEDED", PluginRowModel.Tone.CAUTION, Led.State.CAUTION)

    fun permissionState(granted: Boolean, optional: Boolean): RowState = when {
        granted -> GRANTED
        optional -> OPTIONAL
        else -> NEEDED
    }

    fun accessState(granted: Boolean): RowState = if (granted) GRANTED else NEEDED

    fun accessReason(access: SpecialAccess): String = when (access) {
        SpecialAccess.NOTIFICATION_LISTENER -> "Lets the Notifications plugin read your phone's notifications."
        SpecialAccess.OVERLAY -> "Lets STMP show the approval prompt over other apps."
        SpecialAccess.BATTERY_EXEMPT -> "Keeps the bridge running when the screen has been off for a while."
        SpecialAccess.FULL_SCREEN_INTENT -> "Lets an approval wake the screen while the phone is locked."
    }

    /** [on] is the stored switch; [showsOn] is what the rocker shows (on and able to serve). */
    fun switchSentence(on: Boolean, showsOn: Boolean): String = when {
        showsOn -> "On. Its requests are served and gated like any other."
        on -> "Switched on, but it can't serve until the problem below is fixed. " +
            "Its requests are refused with an explanation."
        else -> "Off. Agents that call it are told it is off."
    }

    /** The cannot-serve sentence is the only one drawn as a caution. */
    fun isCaution(on: Boolean, showsOn: Boolean): Boolean = on && !showsOn

    /** A text setting's current value as the readout shows it; never a bare dash. */
    fun textValue(value: String): String = value.ifEmpty { "Not set" }

    fun signedIn(email: String?): String = if (email.isNullOrEmpty()) "Signed in" else "Signed in as $email"

    fun refusal(reason: String?): String = "Couldn't save: ${reason ?: "the value was refused"}"
}
