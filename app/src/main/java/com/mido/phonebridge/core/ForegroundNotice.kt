package com.mido.phonebridge.core

import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.title

/**
 * What the ongoing (foreground) notification says (D23, D51).
 *
 * Pure and separate for the same reason as [BridgeStatusNotice]: copy a human reads belongs where
 * a test can fail on it. D23's single line is split over the two lines a collapsed notification
 * has, so neither ellipsizes early.
 */
object ForegroundNotice {

    /** Title line: the address the bridge answers on, or "starting" until it is bound. */
    fun title(address: String?, port: Int): String =
        if (address.isNullOrBlank()) "STMP · starting" else "STMP · open on $address:$port"

    /** Text line: the gate mode, or why there is no address yet. */
    fun text(address: String?, mode: GlobalMode?): String = when {
        address.isNullOrBlank() -> "Looking for a network address"
        mode == null -> "Gate mode unknown"
        else -> "${mode.title} mode"
    }

    /** The lock-screen version: no address, no mode, no actions. */
    const val PUBLIC_TITLE = "STMP is running"
}
