package com.mido.phonebridge.plugins.google

import android.content.Context
import android.content.Intent
import com.mido.phonebridge.Debug
import com.mido.phonebridge.GoogleLoginActivity
import com.mido.phonebridge.core.AuthManager
import com.mido.phonebridge.core.GoogleAccounts
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginRegistry
import com.mido.phonebridge.core.PluginSetting
import com.mido.phonebridge.core.PluginStatus

/**
 * The Google accounts themselves: who is connected, what each may do, and how to change it.
 *
 * **Why this is a plugin and not a section on Home.** Mido, 2026-11-03: *"move the account part to
 * the google plugin"*. The account is a capability the other three depend on, not app furniture —
 * Gmail, Calendar and Drive each refuse to serve without one. Putting it here means the screen
 * that shows "why is Gmail not working" is one tap from the thing that fixes it.
 *
 * **It has no routes, and that is correct.** There is nothing to call on `/google`: accounts are
 * chosen per request with `?account=`, and the list is published by `/help`. A plugin with zero
 * routes is a perfectly normal shape — `PluginRegistry` handles it.
 *
 * **It cannot be disabled.** Turning off the accounts would strand Gmail, Calendar and Drive in a
 * state where each reports "no account" with no screen that could add one. Same argument as
 * `help`/`debug`/`logs`, applied to a dependency rather than a diagnostic.
 *
 * **There is no "current account" and therefore no switch.** Every request names its own account,
 * so the only thing this screen can usefully do is manage the set: add, re-authorise, remove.
 */
class GooglePlugin(private val ctx: Context) : Plugin {

    override val id = "google"
    override val displayName = "Google accounts"

    /** No routes: this plugin is a precondition, not an endpoint. */
    override fun routes() = emptyList<com.mido.phonebridge.core.Route>()

    /**
     * The scopes the OTHER Google plugins declare, surfaced here so the add flow can state what
     * connecting an account actually grants.
     *
     * Not owned: Gmail, Calendar and Drive each declare their own, because each is the authority
     * on what it needs. This reads the registry rather than duplicating a list that would then
     * have to be kept in step by hand.
     */
    override val scopes: List<String>
        get() = PluginRegistry.all()
            .filter { it.id != id }
            .flatMap { it.scopes }
            .distinct()

    /**
     * One row per account, rebuilt on every render.
     *
     * Dynamic because the list is data: a fixed set of settings could not describe N accounts,
     * and a separate screen for two of them is ceremony. `settings()` is already re-read on each
     * paint, so this is the shape the screen wanted anyway.
     *
     * Each account offers RE-AUTHORISE and REMOVE. There is deliberately no per-account "make
     * default": a default is precisely the implicit binding this design deleted.
     */
    override fun settings(): List<PluginSetting> {
        val accounts = try {
            GoogleAccounts.all(ctx)
        } catch (e: PluginError) {
            // A storage defect must be visible on this screen — it is the one place that can
            // explain it — rather than thrown into a blank page.
            return listOf(
                PluginSetting.Action(
                    id = "storageError",
                    label = "Account storage is unreadable",
                    description = e.message ?: e.code,
                    run = { },
                ),
            )
        }
        val out = ArrayList<PluginSetting>(accounts.size * 2 + 2)
        for (account in accounts) {
            val scopes = account.grantedScopes.size.let {
                if (it == 0) "granted scopes unknown" else "$it granted"
            }
            out.add(
                PluginSetting.Action(
                    id = "reauth:${account.sub.ifBlank { account.email }}",
                    label = account.display,
                    description = "Re-authorise this account · $scopes",
                    run = { c ->
                        try {
                            c.startActivity(
                                Intent(c, GoogleLoginActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    putExtra(GoogleLoginActivity.EXTRA_EMAIL, account.email)
                                    putExtra(GoogleLoginActivity.EXTRA_ADD, false)
                                },
                            )
                        } catch (e: Exception) {
                            Debug.log("cannot open Google re-authorisation: ${e.message}")
                        }
                    },
                ),
            )
            out.add(
                PluginSetting.Action(
                    id = "remove:${account.sub.ifBlank { account.email }}",
                    label = "Remove ${account.email}",
                    // The blast radius, stated where the choice is made: this is the row that
                    // ends access, and it revokes at Google rather than only forgetting locally.
                    description = "Revokes this account's token at Google and forgets it here. " +
                        "Gmail, Calendar and Drive stop acting as it. Nothing in the Google " +
                        "account itself is changed.",
                    run = { c ->
                        try {
                            AuthManager.removeAccount(c, account)
                            Debug.log("google: removed ${account.email}")
                        } catch (e: PluginError) {
                            Debug.log("google: remove ${account.email}: ${e.code}: ${e.message}")
                        }
                    },
                ),
            )
        }
        out.add(
            PluginSetting.Action(
                id = "add",
                label = if (accounts.isEmpty()) "Connect a Google account" else "Add another account",
                description = "Opens the Google account picker. Connecting grants " +
                    "${scopes.size} scopes so Gmail, Calendar and Drive can act as it.",
                run = { c ->
                    try {
                        c.startActivity(
                            Intent(c, GoogleLoginActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(GoogleLoginActivity.EXTRA_ADD, true)
                            },
                        )
                    } catch (e: Exception) {
                        Debug.log("cannot open Google sign-in: ${e.message}")
                    }
                },
            ),
        )
        return out
    }

    override fun status(): PluginStatus {
        if (AuthManager.clientId.isBlank()) {
            // OFF here means NOT CONFIGURED, not "you turned it off". There is no OAuth client
            // in this build, so there is nothing to connect.
            return PluginStatus(
                "Google accounts",
                PluginStatus.State.OFF,
                "no OAuth client id configured in this build",
            )
        }
        val accounts = try {
            GoogleAccounts.all(ctx)
        } catch (e: PluginError) {
            return PluginStatus("Google accounts", PluginStatus.State.ERROR, e.message ?: e.code)
        }
        return when (accounts.size) {
            0 -> PluginStatus(
                "Google accounts",
                PluginStatus.State.NEEDS_ATTENTION,
                "no account connected — Gmail, Calendar and Drive cannot serve until one is",
            )
            1 -> PluginStatus(
                "Google accounts",
                PluginStatus.State.OK,
                // One account: name it. That is the single-account interface, unchanged.
                accounts[0].email,
            )
            else -> PluginStatus(
                "Google accounts",
                PluginStatus.State.OK,
                // **No address here, on purpose.** With several accounts there is no single
                // answer to "who is signed in", and picking one would be a lie of exactly the
                // kind this change removed. The count is the true statement; the list is one tap
                // away, and each request names its own account in the modal.
                "${accounts.size} accounts — every request names one",
            )
        }
    }
}
