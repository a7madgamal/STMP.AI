package com.mido.phonebridge.policy

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Can a human actually see a prompt right now?
 *
 * The rule this serves (locked): a request that cannot be shown to an attending human
 * is DENIED. Only `OFF` approves unattended, because `OFF` is the one mode where no
 * human was ever expected.
 *
 * Screen off or locked both count as unattended. The distinction between them does
 * not matter here: in both cases nobody is looking.
 */
fun isAttended(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    return isAttended(pm.isInteractive, km.isKeyguardLocked)
}

/**
 * The decision itself, as a pure function of the two facts.
 *
 * Split out so it can be tested on the JVM: `PowerManager` and `KeyguardManager` are
 * Android services that cannot exist in a unit test, and this is the check that
 * decides whether a request runs unattended. It must not be the untested part.
 */
fun isAttended(screenInteractive: Boolean, keyguardLocked: Boolean): Boolean =
    screenInteractive && !keyguardLocked
