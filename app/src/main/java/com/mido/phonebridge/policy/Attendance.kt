package com.mido.phonebridge.policy

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Can a human actually see a prompt right now?
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

/**
 * May an UNATTENDED request proceed anyway?
 *
 * **The rule (Mido, 2026-10-25):** *"for notification level calls it shouldn't matter if the
 * screen was off or the phone locked or not, as long as the app was unlocked once for storage
 * unlock"*. So a `NOTIFY` request may run with nobody at the screen, provided the vault session
 * is open. `SECURE` never may — it exists to require a person.
 *
 * ## What this actually changes, stated plainly
 *
 * `NOTIFY` approves on SILENCE. A dark screen guarantees silence, so before this rule an
 * unattended `NOTIFY` request could never be approved at all — it was denied up front. After it,
 * such a request is approved without ever being readable. That is not "the card was ignored";
 * the card was never legible. The consent model is therefore **"unlocked once, trusted after"**,
 * not "someone saw this".
 *
 * ## Why the vault session is the right bound
 *
 * [com.mido.phonebridge.core.SecretVault.isUnlocked] is `dataKey != null` — a live session, not a
 * stored flag. It dies with the process (restart, reboot, system kill) and on `lock()`. So the
 * trust window is exactly "since you last unlocked this app, while this process is alive". After
 * a reboot you are back to denied, which is the property that keeps this defensible: the phone
 * cannot wake up from a cold boot and start approving things.
 *
 * ## Why it is verb-agnostic
 *
 * Mido's explicit call: *"regardless of verb"*. The alternative — allow it only for `READ` — was
 * rejected because it makes the behaviour depend on a second decision (how a route is classified)
 * that can drift from the first. The blast radius is bounded by the POLICY instead: a route only
 * behaves this way if it is `NOTIFY`, and `NOTIFY` is a deliberate, audited choice per
 * `plugin × verb`. Turning a write into a silent auto-approve requires someone to write that rule.
 *
 * ## What it must never do
 *
 * It cannot rescue `SECURE`. There is no vault state that makes a fingerprint optional — the
 * modal's whole purpose is a present human. Returning true there would silently disable the gate
 * that the rest of this design rests on.
 */
fun unattendedAllowed(mode: Mode, vaultUnlocked: Boolean): Boolean = when (mode) {
    // The whole point: a locked vault means the app has not been unlocked in this process, so
    // there is no session to extend trust from.
    Mode.DONT_DISTURB -> vaultUnlocked
    Mode.PARANOID -> false
}
