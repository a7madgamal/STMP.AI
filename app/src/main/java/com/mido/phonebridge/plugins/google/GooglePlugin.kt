package com.mido.phonebridge.plugins.google

import android.content.Context
import android.content.Intent
import com.mido.phonebridge.Debug
import com.mido.phonebridge.GoogleLoginActivity
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginSetting
import com.mido.phonebridge.core.PluginStatus

/**
 * The Google account itself: who is signed in, whether the token is alive, and how to change it.
 *
 * **Why this is a plugin and not a section on Home.** Mido, 2026-11-03: *"move the account part to
 * the google plugin"*. The account is a capability the other three depend on, not app furniture —
 * Gmail, Calendar and Drive each refuse to serve without it. Putting it here means the screen that
 * shows "why is Gmail not working" is one tap from the thing that fixes it.
 *
 * **It has no routes, and that is correct.** There is nothing to call on `/google`: the account is
 * a precondition for other plugins, not an endpoint. A plugin with zero routes is a perfectly
 * normal shape — `PluginRegistry` handles it, and `/help` lists it with an empty route list, which
 * is the honest description of what it is.
 *
 * **It cannot be disabled.** Turning off the account would strand Gmail, Calendar and Drive in a
 * state where each reports "not signed in" with no screen that could sign in again. That is the
 * same argument as `help`/`debug`/`logs`, applied to a dependency rather than a diagnostic.
 */
class GooglePlugin(private val ctx: Context) : Plugin {

    override val id = "google"
    override val displayName = "Google account"


    /** No routes: this plugin is a precondition, not an endpoint. */
    override fun routes() = emptyList<com.mido.phonebridge.core.Route>()

    /**
     * The scopes the OTHER Google plugins declare, surfaced here so the account screen can state
     * what signing in actually grants.
     *
     * Not owned: Gmail, Calendar and Drive each declare their own, because each is the authority
     * on what it needs. This reads the registry rather than duplicating a list that would then
     * have to be kept in step by hand.
     */
    override val scopes: List<String>
        get() = com.mido.phonebridge.core.PluginRegistry.all()
            .filter { it.id != id }
            .flatMap { it.scopes }
            .distinct()

    override fun settings(): List<PluginSetting> = listOf(
        PluginSetting.Action(
            id = "signIn",
            label = if (AuthManager.isLoggedIn(ctx)) "Re-sign in" else "Sign in with Google",
            description = "Opens the Google account picker. Re-signing in also refreshes the " +
                "scopes, which is what to do if one plugin says it is missing a permission.",
            run = { c ->
                try {
                    c.startActivity(signInIntent(c))
                } catch (e: Exception) {
                    Debug.log("cannot open Google sign-in: ${e.message}")
                }
            },
        ),
        // **A way out of the account.**
        //
        // Mido, 2026-11-04: *"add button in google plugin to logout"*. The plugin could sign in
        // and re-sign in, but never LEAVE — so changing accounts meant clearing app data or
        // reinstalling. That is not a preference, it is a trapdoor.
        //
        // Only offered when there is something to log out of: a "Log out" button on a signed-out
        // account is a control that cannot do anything, which is the pattern this codebase keeps
        // removing.
        PluginSetting.Action(
            id = "signOut",
            label = if (AuthManager.isLoggedIn(ctx)) "Log out" else "Not signed in",
            description = "Forgets the account and its tokens on this phone. Gmail, Calendar and " +
                "Drive stop serving until you sign in again. Nothing in your Google account is " +
                "changed — stored secrets and SSH keys are untouched.",
            run = { c ->
                try {
                    AuthManager.clear(c)
                    Debug.log("google: signed out")
                } catch (e: Exception) {
                    Debug.log("google: sign-out failed: ${e.message}")
                }
            },
        ).takeIf { AuthManager.isLoggedIn(ctx) }
    ).filterNotNull()

    override fun status(): PluginStatus {
        if (AuthManager.clientId.isBlank()) {
            // OFF here means NOT CONFIGURED, not "you turned it off" — the same distinction the
            // plugins list makes everywhere. There is no OAuth client in this build, so there is
            // nothing to sign into.
            return PluginStatus(
                "Google account",
                PluginStatus.State.OFF,
                "no OAuth client id configured in this build",
            )
        }
        if (!AuthManager.isLoggedIn(ctx)) {
            return PluginStatus(
                "Google account",
                PluginStatus.State.NEEDS_ATTENTION,
                "not signed in — Gmail, Calendar and Drive cannot serve until you are",
            )
        }
        val email = AuthManager.accountEmail ?: "(email unknown)"
        // **Not "token valid for N min".** The access token is deliberately NOT cached across a
        // process start -- it lives in memory, and the refresh token on disk is what survives. So
        // `tokenRemainingMs()` is 0 on every cold read and the row claimed "valid for 0 min" while
        // the account was working perfectly. A number that is wrong on first glance is worse than
        // no number: it invites a re-login that changes nothing.
        //
        // State what is actually true: signed in, with a refresh token that renews on demand.
        // Just the account. Mido, 2026-11-04: *"remove all 'renews automatically' from google
        // plugin"*. It was a reassurance about machinery the human cannot see or act on, printed
        // on every glance at the card.
        return PluginStatus("Google account", PluginStatus.State.OK, email)
    }
}

/**
 * The intent that opens Google sign-in. Shared by the Google account plugin's "Sign in" action and
 * the sign-in row on the Gmail, Calendar and Drive screens, so both start the same screen.
 */
fun signInIntent(c: Context): Intent = Intent(c, GoogleLoginActivity::class.java).apply {
    // The plugin may be given an application context by a caller that is not an Activity, and a
    // background start without this flag throws.
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
