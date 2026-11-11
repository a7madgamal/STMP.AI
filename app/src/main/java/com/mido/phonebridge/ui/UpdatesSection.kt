package com.mido.phonebridge.ui

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import com.mido.phonebridge.BuildConfig
import com.mido.phonebridge.Debug
import com.mido.phonebridge.ScreenHost

/**
 * The existing check/download/verify/install flow, hosted by Preferences rather than Home.
 * Updater and ApkInstaller remain the only owners of update state and installation behavior.
 */
class UpdatesSection(
    context: Context,
    private val host: ScreenHost,
) : LinearLayout(context) {
    private var pendingAfterResume: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        addView(updateBlock())
    }

    /** Re-read update state and continue a permission-gated install after returning. */
    fun resume() {
        refresh()
        val pending = pendingAfterResume ?: return
        pendingAfterResume = null
        post(pending)
    }

    private fun onUi(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (isAttachedToWindow) block()
        }
    }

    private fun updateBlock(): android.view.View {
        // **ONE card, not five stacked pieces.** Mido, 2026-11-04: *"make updates a single
        // group"*. It used to be a status row, a bordered channel row, a button and two loose
        // captions — five surfaces that happened to sit near each other. One torn card matches
        // the rest of Home, where a subject is a card and a card is one subject.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Theme.dp(context, 14), Theme.dp(context, 12),
                Theme.dp(context, 14), Theme.dp(context, 12),
            )
            background = Theme.tornCard(context, Theme.SURFACE, Theme.RADIUS_CARD)
        }

        val outcome = com.mido.phonebridge.core.Updater.lastOutcome
        val checking = com.mido.phonebridge.core.Updater.checking
        val channel = com.mido.phonebridge.core.UpdatePrefs.channel(context)

        // **GREEN when an update exists.** This row used `Theme.ACCENT`, which is `0xFFB23A2F`
        // — byte-identical to `Theme.DANGER`. The one piece of good news this screen can deliver
        // was painted in the colour used for errors. Mido, 2026-11-04: *"a red dot next to an
        // exciting update is the last thing a user needs to see"*. (This fix was lost once when
        // the section moved screens; it is stated here so the next move does not lose it again.)
        val (line, colour) = when {
            checking -> "checking…" to Theme.ON_SURFACE_VARIANT
            outcome is com.mido.phonebridge.core.Updater.Outcome.Available ->
                "v${outcome.version} is ready to install" +
                    (if (outcome.isBeta) " · beta" else "") to Theme.SUCCESS
            outcome is com.mido.phonebridge.core.Updater.Outcome.UpToDate ->
                "up to date — v${BuildConfig.VERSION_NAME}" to Theme.ON_SURFACE_VARIANT
            outcome is com.mido.phonebridge.core.Updater.Outcome.Unavailable ->
                "cannot check: ${outcome.reason}" to Theme.WARNING
            else -> "not checked yet" to Theme.ON_SURFACE_VARIANT
        }

        // Status line: what is true right now.
        //
        // **One line, not two.** "checking…" and "asking GitHub…" were saying the same thing on
        // consecutive rows — Mido, 2026-11-04: *"asking github and checking... are redundant"*.
        // The spinner rides beside the status word instead: it adds the one thing the word cannot
        // say (that something is actively happening) without repeating it.
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        if (checking) {
            statusRow.addView(android.widget.ProgressBar(context).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(
                    Theme.dp(context, 14), Theme.dp(context, 14)
                ).apply { marginEnd = Theme.dp(context, 8) }
            })
        }
        statusRow.addView(Theme.text(context, line, Theme.TEXT_BODY_SP, colour))
        card.addView(statusRow)

        // **What you are running, always.** The version previously appeared only in the
        // "up to date" line — so the moment an update existed, or the check had not run, the
        // number vanished. That is the one fact a person needs to answer "am I on the build I
        // just installed?", and it should not depend on the outcome of a network call.
        card.addView(
            Theme.text(
                context,
                "installed: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                13f,
                Theme.ON_SURFACE_VARIANT,
            ).apply { setPadding(0, Theme.dp(context, 4), 0, 0) }
        )

        // The channel, inside the same card. A switch and its label, no nested border.
        val channelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dp(context, 10), 0, 0)
        }
        channelRow.addView(
            Theme.text(
                context,
                if (channel == com.mido.phonebridge.core.UpdatePrefs.Channel.BETA) {
                    "Beta channel — every push"
                } else {
                    "Stable channel — promoted only"
                },
                13f,
                Theme.ON_SURFACE_VARIANT,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        channelRow.addView(
            Theme.toggle(context, channel == com.mido.phonebridge.core.UpdatePrefs.Channel.BETA) { want ->
                com.mido.phonebridge.core.UpdatePrefs.setChannel(
                    context,
                    if (want) com.mido.phonebridge.core.UpdatePrefs.Channel.BETA
                    else com.mido.phonebridge.core.UpdatePrefs.Channel.STABLE,
                )
                // The channel changes what counts as an update, so the old answer is void.
                com.mido.phonebridge.core.Updater.lastOutcome = null
                // NOT `refresh()`: it would tear down this switch while its click is still being
                // dispatched, which is exactly why the knob never moved.
                checkForUpdates()
            }
        )
        card.addView(channelRow)

        // The action. One button, never two: an available update replaces "check" with "update",
        // because re-checking is not what you want when you already know.
        if (outcome is com.mido.phonebridge.core.Updater.Outcome.Available) {
            val notes = com.mido.phonebridge.core.Updater.shortNotes(outcome.notes)
            if (notes.isNotEmpty()) {
                card.addView(
                    Theme.text(context, notes, Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT).apply {
                        setPadding(0, Theme.dp(context, 8), 0, 0)
                    }
                )
            }
            // **A loader from the tap until the app restarts, or until CANCEL.**
            //
            // Mido, 2026-11-04: *"clicking update should immediately show a loader until the app is
            // actually restarted or user canceling"*. Before this, the whole download-verify-install
            // ran behind a toast that vanished after two seconds, so for most of a minute the
            // screen looked like the tap had been ignored.
            //
            // The state lives on `Updater`, not on this fragment: the section is rebuilt on every
            // repaint, so a local flag would be wiped the first time anything else refreshed.
            if (com.mido.phonebridge.core.Updater.installing) {
                val busy = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, Theme.dp(context, 10), 0, 0)
                }
                busy.addView(android.widget.ProgressBar(context).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(
                        Theme.dp(context, 18), Theme.dp(context, 18)
                    ).apply { marginEnd = Theme.dp(context, 10) }
                })
                busy.addView(
                    Theme.text(
                        context,
                        "Installing v${outcome.version}… the app restarts when it finishes",
                        12f, Theme.ON_SURFACE_VARIANT,
                    ),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                card.addView(busy)

                // CANCEL, offered while the download is still running. Once Android's own
                // confirmation dialog is up, the system owns the decision and this button
                // disappears from the next repaint.
                card.addView(
                    Theme.button(context, "CANCEL", Theme.ButtonStyle.NEUTRAL, small = true) {
                        com.mido.phonebridge.core.Updater.installCancelled = true
                        com.mido.phonebridge.core.Updater.installing = false
                        com.mido.phonebridge.core.Updater.installingVersion = null
                        Debug.log("update: cancelled by the human")
                        refresh()
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(context, 8) },
                )
            } else {
                card.addView(
                    Theme.button(
                        context, "UPDATE TO v${outcome.version}", Theme.ButtonStyle.SUCCESS,
                    ) { installUpdate(outcome) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = Theme.dp(context, 10) },
                )
            }
        } else {
            val check = Theme.button(
                context, if (checking) "CHECKING…" else "CHECK NOW", Theme.ButtonStyle.NEUTRAL,
            ) { checkForUpdates() }
            // Genuinely disabled: a second tap while a check runs would race the first result.
            check.isEnabled = !checking
            card.addView(check, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(context, 10) })
        }

        // When we last actually asked. From the persisted timestamp, so it is honest across a
        // restart; coarse on purpose, because a tick for "3 minutes" is not worth an alarm.
        val lastCheck = com.mido.phonebridge.core.UpdatePrefs.lastCheckAt(context)
        if (lastCheck > 0L) {
            card.addView(
                Theme.text(context, "last checked ${agoText(lastCheck)}", Theme.TEXT_CAPTION_SP, Theme.ON_SURFACE_VARIANT)
                    .apply { setPadding(0, Theme.dp(context, 8), 0, 0) }
            )
        }

        // The unreachable case says what will actually make it work.
        //
        // **It used to blame a private repository, and that stopped being true the day the
        // project was made public.** A note that names the wrong cause is worse than no note: it
        // sends someone to check a setting that is already correct. What a human can act on is
        // "your network", "wait", or "get it from the releases page" — so that is what it says.
        if (outcome is com.mido.phonebridge.core.Updater.Outcome.Unavailable) {
            card.addView(
                Theme.text(
                    context,
                    "This is a network or GitHub-side problem, not a setting. The button below " +
                        "opens the releases page in your browser, which is the same APK.",
                    12f, Theme.ON_SURFACE_VARIANT,
                ).apply { setPadding(0, Theme.dp(context, 8), 0, 0) }
            )
        }

        // --- the way out when this mechanism fails ---
        //
        // Mido, 2026-11-04: *"in case updates mechanism go wrong, add a small button for github
        // releases page directly"*.
        //
        // Deliberately ALWAYS shown, not only when a check failed. The cases it rescues include
        // the ones the app cannot detect: a release published without an APK, a version tag this
        // build's comparison misjudges, an install the system refuses, a download that verifies
        // here and will not install there. Those all report something other than "unavailable",
        // so a fallback that only appeared on failure would be missing exactly when it is needed.
        //
        // Small and GHOST so it never competes with CHECK NOW: it is a door, not an action.
        // A GHOST door when things work, a real action when they do not.
        //
        // The button exists in both states (the failures it rescues include ones this app cannot
        // detect), but when the check just FAILED it is the only thing on the card that can
        // actually move — so it stops being a quiet link and becomes the primary control.
        val checkFailed = outcome is com.mido.phonebridge.core.Updater.Outcome.Unavailable
        card.addView(
            Theme.button(
                context,
                if (checkFailed) "GET IT FROM GITHUB" else "OPEN RELEASES PAGE",
                if (checkFailed) Theme.ButtonStyle.SUCCESS else Theme.ButtonStyle.GHOST,
                small = !checkFailed,
            ) {
                // Handed to the browser rather than fetched: this is the escape hatch, so it must
                // not depend on any code in this app that could be the thing that is broken.
                val url = "https://github.com/${com.mido.phonebridge.core.Updater.REPO_OWNER}" +
                    "/${com.mido.phonebridge.core.Updater.REPO_NAME}/releases"
                try {
                    host.go(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Exception) {
                    // No browser installed. Rare, but the one case where a dead button would look
                    // like the update system failing again.
                    Debug.log("update: cannot open releases page: ${e.message}")
                    host.toast("no browser to open $url")
                }
            },
            LinearLayout.LayoutParams(
                if (checkFailed) LinearLayout.LayoutParams.MATCH_PARENT
                else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Theme.dp(context, 10) },
        )
        return card
    }


    /** Check on a worker, then repaint. The main thread is never blocked on this. */
    /**
     * Rebuild ONLY the update section, posted off the current call stack.
     *
     * **Why posted, and why not `refresh()`.** The update controls live INSIDE `updatesHost`, so
     * rebuilding it from their own click handler removes the view whose click is still being
     * dispatched — `removeAllViews()` mid-dispatch. That is why the channel switch did not even
     * flip (reported 2026-11-04) and why CHECK NOW appeared to do nothing: the tap tore down its
     * own target before the handler finished.
     *
     * Posting defers the rebuild to the next loop, after touch dispatch has completed and the
     * pressed state has been released. Rebuilding only this section also means the rest of Home
     * is not disturbed — the hero, the gate and the audit banner keep their identity, so a press
     * anywhere else cannot be eaten by somebody toggling a channel.
     */
    private fun refresh() {
        post {
            if (!isAttachedToWindow) return@post
            removeAllViews()
            addView(updateBlock())
        }
    }

    private fun checkForUpdates() {
        com.mido.phonebridge.core.Updater.lastOutcome = null
        com.mido.phonebridge.core.Updater.checking = true
        refresh()

        // **The context is taken HERE, on the main thread, before the worker starts.**
        //
        // `context` inside the thread throws `IllegalStateException` when the fragment
        // is detached — which is exactly what happens around an update: the install completes,
        // the activity is torn down, and a check started in that window dies before `check()` is
        // ever entered. `check()` clears `checking` in its own `finally`, so a throw BEFORE it
        // leaves the flag stuck at `true` forever and the card reads "checking…" until the app is
        // killed. Mido, 2026-11-10: *"the checking status right after updating is stuck forever"*.
        //
        // The application context does not belong to the fragment, so capturing it early removes
        // the whole failure rather than catching it.
        val appContext = context.applicationContext

        Thread({
            try {
                val outcome = com.mido.phonebridge.core.Updater.check(appContext)
                Debug.log("update check: ${outcome.javaClass.simpleName}")
                onUi { refresh() }
            } finally {
                // **Belt and braces.** If anything above throws before `check()` sets its own
                // `finally`, the flag must still come down: a spinner that never stops is worse
                // than a check that failed, because it hides the CHECK NOW button too.
                com.mido.phonebridge.core.Updater.checking = false
                onUi { refresh() }
            }
        }, "update-check").apply { isDaemon = true }.start()
    }


    /**
     * Download, verify, then hand to the installer.
     *
     * **Verification is not optional.** A digest mismatch deletes the file and refuses; an
     * update that cannot be checked against what was published is exactly the thing that should
     * not be installed.
     */
    private fun installUpdate(update: com.mido.phonebridge.core.Updater.Outcome.Available) {
        // **Answered BEFORE the 42 MB download, not after it.**
        //
        // Android requires the human to allow installs for this app once. Discovering that after
        // fetching and verifying a whole APK means the only thing left to show is an error about
        // a download that was never going to be usable — and that is exactly what happened on
        // 2026-11-04, as an unreadable toast.
        if (!com.mido.phonebridge.core.ApkInstaller.canInstall(context)) {
            showInstallPermissionDialog { installUpdate(update) }
            return
        }

        // **Set BEFORE the worker starts**, so the loader is on screen at the next frame rather
        // than after the first network call returns.
        com.mido.phonebridge.core.Updater.installCancelled = false
        com.mido.phonebridge.core.Updater.installingVersion = update.version
        com.mido.phonebridge.core.Updater.installing = true
        refresh()

        Thread({
            try {
                val got = com.mido.phonebridge.core.Updater.download(context.applicationContext, update) { _ -> }
                // A cancel during the download means the bytes are not wanted. Delete them and
                // stop: installing a version the human withdrew while it downloaded is the one
                // thing a cancel button must actually prevent.
                if (com.mido.phonebridge.core.Updater.installCancelled) {
                    got.file.delete()
                    Debug.log("update: cancelled mid-download; file removed")
                    return@Thread
                }
                val expected = update.sha256
                if (expected != null) {
                    val normalized = expected.trim().lowercase().substringAfterLast(" ")
                    if (!got.sha256.equals(normalized, ignoreCase = true)) {
                        got.file.delete()
                        Debug.log("update: digest mismatch, refusing")
                        onUi {
                            com.mido.phonebridge.core.Updater.installing = false
                            com.mido.phonebridge.core.Updater.installingVersion = null
                            host.toast("download was corrupted — refusing to install")
                            refresh()
                        }
                        return@Thread
                    }
                    Debug.log("update: digest verified")
                } else {
                    Debug.log("update: no published digest; installing unverified")
                }
                onUi {
                    com.mido.phonebridge.core.ApkInstaller.install(context, got.file) { result ->
                        onUi {
                            when (result) {
                                is com.mido.phonebridge.core.ApkInstaller.Result.ConfirmationShown ->
                                    host.toast("confirm the install in the dialog")
                                // The permission was revoked between the pre-check and the
                                // commit, or a managed device forbids it. Same dialog either way:
                                // the fix is a settings toggle, and it has a button.
                                is com.mido.phonebridge.core.ApkInstaller.Result.NeedsPermission ->
                                    showInstallPermissionDialog { installUpdate(update) }
                                is com.mido.phonebridge.core.ApkInstaller.Result.Refused -> {
                                    // No install is happening, so the loader must stop: a
                                    // spinner that outlives its work is the exact failure this
                                    // change exists to remove.
                                    com.mido.phonebridge.core.Updater.installing = false
                                    com.mido.phonebridge.core.Updater.installingVersion = null
                                    showProblemDialog("Update could not be installed", result.reason)
                                }
                            }
                            refresh()
                        }
                    }
                }
            } catch (e: Exception) {
                Debug.log("update: download failed: ${e.message}")
                onUi {
                    com.mido.phonebridge.core.Updater.installing = false
                    com.mido.phonebridge.core.Updater.installingVersion = null
                    host.toast("download failed: ${e.message ?: e.javaClass.simpleName}")
                    refresh()
                }
            }
        }, "update-download").apply { isDaemon = true }.start()
    }


    /**
     * The "allow installs for this app" dialog.
     *
     * **A dialog, not a toast.** Mido, 2026-11-04: *"I didn't like that the instructions were a
     * small popup I couldn't even read. make this case a clear dialog and as easy as possible to
     * follow. if we can have a button to go to the setting page needed that would be great"*.
     *
     * A toast cannot be re-read, cannot be acted on, and disappears while the human is still
     * squinting at it. This explains WHY the permission is wanted, names the exact switch, and
     * carries a button that opens that switch's page directly instead of describing the route.
     *
     * `retry` is invoked after the human returns, so granting the permission continues the
     * install they already tapped rather than making them find it again.
     */
    private fun showInstallPermissionDialog(retry: () -> Unit) {
        host.confirm(
            title = "Allow STMP to install updates",
            message = "Android needs your permission once before it will let an app install its " +
                "own update.\n\n" +
                "Tap Open settings, then turn on " +
                "\"Allow from this source\". Come back here and the install carries on " +
                "by itself.\n\n" +
                "This does not let anything else install on your phone — the permission is " +
                "only for STMP.",
            positive = "Open settings",
            negative = "Not now",
        ) {
            // Remembered BEFORE settings opens: the human may grant the permission and come
            // straight back, and the resume has to know what it was in the middle of.
            pendingAfterResume = retry
            if (!com.mido.phonebridge.core.ApkInstaller.openInstallSettings(context)) {
                pendingAfterResume = null
                // Neither settings page could be opened. Saying so beats launching nothing and
                // leaving the dialog looking broken.
                showProblemDialog(
                    "Cannot open settings",
                    "This build of Android would not open the install-permission page. Go to " +
                        "Settings → Apps → STMP → Install unknown apps and allow it there.",
                )
            } else {
                Debug.log("update: opened install permission settings")
            }
        }
    }


    /** A plain, readable failure dialog. Long text belongs in a dialog, never a toast. */
    private fun showProblemDialog(title: String, message: String) {
        host.confirm(
            title = title,
            message = message,
            positive = "OK",
            negative = "Close",
        ) { }
    }

    /**
     * A short, human "how long ago", for the last-checked line.
     *
     * Deliberately coarse: "just now", "5 min ago", "yesterday". A precise timestamp on an
     * update check implies a precision nobody needs, and a live-updating string would need a tick
     * this row has no business owning.
     */
    private fun agoText(at: Long): String {
        val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 60L * 24L -> "${minutes / 60L} h ago"
            else -> "${minutes / (60L * 24L)} d ago"
        }
    }

}
