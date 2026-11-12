package com.mido.phonebridge

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.mido.phonebridge.debug.KitHostActivity
import com.mido.phonebridge.kit.allViews
import com.mido.phonebridge.screenshots.Fixtures
import com.mido.phonebridge.screenshots.GateFixtures
import com.mido.phonebridge.screenshots.KitFixtures
import com.mido.phonebridge.screenshots.OnboardingFixtures
import com.mido.phonebridge.ui.kit.TabBar
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.widget.LinearLayout

/**
 * Every screen fixture, in one place, for the sweeps that need a laid-out tree of each one:
 * `TouchTargetTest`, `A11ySweepTest`, `LayoutSpacingTest` and the rendered `ContrastTest`.
 * `run` builds each fixture at the font scale already set by the caller, hands the laid-out root
 * (or roots) to [visit] while the activity is alive, and resets the fixtures afterwards.
 * A screen WP adds its fixtures to the matching list below.
 */
object ScreenSweep {

    /** A laid-out root to inspect; [name] is `screen/state`. */
    fun interface Visitor { fun visit(name: String, root: ViewGroup) }

    /**
     * Fixture groups. Each sweep test is parameterised over these (and the font scale), so every
     * group runs in its own fresh Robolectric environment: the fixtures share singletons, and a
     * second group built on top of the first one's leftovers is not the screen a user sees.
     */
    val groups: Map<String, (Visitor) -> Unit> = linkedMapOf(
        "kit" to ::kit, "approval" to ::approval, "notify" to ::notifyCards, "settings" to ::settings,
        "home" to ::home, "vault" to ::vault, "log" to ::log, "plugins" to ::plugins,
        "detail" to ::detail, "onboarding" to ::onboarding,
    )

    fun run(group: String, fontScale: Float, visitor: Visitor) {
        RuntimeEnvironment.setFontScale(fontScale)
        try {
            groups.getValue(group)(visitor)
        } finally {
            Fixtures.reset()
            GateFixtures.reset()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun kit(v: Visitor) {
        for (f in KitFixtures.all) {
            val a = Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
            val view = f.build(a)
            a.column.addView(
                view,
                view.layoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            idle()
            v.visit("${f.screen}/${f.state}", a.column)
        }
    }

    // WP03a: the approval gate in its main gestures and states.
    private val approvalFixtures: List<Pair<String, () -> android.app.Activity>> get() = listOf(
        "approval/hold_ssh" to { GateFixtures.launch(GateFixtures.ssh("ls -la"), "POST /ssh/kvm-box") },
        "approval/tap_probe" to {
            GateFixtures.launch(GateFixtures.view(com.mido.phonebridge.core.Verb.READ, "GET /probe"), "GET /probe")
        },
        "approval/exception_offer" to {
            GateFixtures.launch(
                GateFixtures.contacts(
                    offer = com.mido.phonebridge.ApprovalGate.ExceptionOffer(
                        com.mido.phonebridge.policy.Rule("contacts", com.mido.phonebridge.core.Verb.READ, com.mido.phonebridge.policy.Mode.DONT_DISTURB),
                        "ALWAYS NOTIFY",
                    ),
                ),
                "GET /contacts",
            )
        },
        "approval/password" to { GateFixtures.launch(GateFixtures.ssh("sudo -S true", sudo = true), "POST /ssh/kvm-box") },
    )


    private fun approval(v: Visitor) {
        for ((name, launch) in approvalFixtures) {
            val a = launch()
            idle()
            v.visit(name, a.window.decorView as ViewGroup)
            GateFixtures.reset()
        }
    }

    // WP03b: the Don't-disturb card.
    private val notifyVerbs = listOf(com.mido.phonebridge.core.Verb.READ, com.mido.phonebridge.core.Verb.EXECUTE)

    private fun notifyCards(v: Visitor) {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val wm = app.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        try {
            for ((i, verb) in notifyVerbs.withIndex()) {
                com.mido.phonebridge.notify.NotifyOverlay.show(
                    ctx = app, id = 9_300L + i, summary = "Run uptime on kvm-box and report the load",
                    label = "RUN", verb = verb, windowMs = 60_000L, facts = null, needsAi = false,
                    fetchCapMs = 0L, onOutcome = { },
                )
                idle()
                val card = org.robolectric.shadow.api.Shadow
                    .extract<org.robolectric.shadows.ShadowWindowManagerImpl>(wm).views.last() as ViewGroup
                v.visit("notify_card/${verb.name.lowercase()}", card)
                com.mido.phonebridge.notify.NotifyOverlay.dismissAll()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
            }
        } finally {
            com.mido.phonebridge.notify.NotifyOverlay.dismissAll()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
        }
    }

    // WP07: the Settings tab in the states that add controls.
    private val settingsStates: List<Pair<String, () -> Unit>> get() = listOf(
        "settings/default" to { },
        "settings/ai_local_ready" to {
            Fixtures.aiBackend(com.mido.phonebridge.policy.AiBackend.LOCAL)
            Fixtures.modelFile(com.mido.phonebridge.ai.ModelRegistry.EXPECTED_SIZE)
        },
        "settings/ai_local_failed" to {
            Fixtures.aiBackend(com.mido.phonebridge.policy.AiBackend.LOCAL)
            Fixtures.provisioner(false, null, "HTTP 503 from huggingface.co")
        },
        "settings/ai_remote_ready" to {
            Fixtures.externalModel("http://192.168.1.9:8888", "qwen3:8b", com.mido.phonebridge.policy.ExternalDialect.entries.first())
        },
        "settings/callers_and_log" to {
            Fixtures.callers(
                "192.168.1.25" to com.mido.phonebridge.callers.CallerState.Verified("home laptop"),
                "192.168.1.77" to com.mido.phonebridge.callers.CallerState.Denied,
            )
            Fixtures.debugLog(listOf("09:40:00  server started", "09:40:03  GET /help -> 200"))
        },
    )


    private fun settings(v: Visitor) {
        for ((name, setup) in settingsStates) {
            Fixtures.dropSingletons()
            setup()
            val intent = android.content.Intent(Fixtures.app, com.mido.phonebridge.debug.ScreenshotHostActivity::class.java)
                .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_FRAGMENT, OptionsFragment::class.java.name)
                .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_TAB, com.mido.phonebridge.ui.Theme.Tab.SETTINGS.name)
            val a = Robolectric.buildActivity(com.mido.phonebridge.debug.ScreenshotHostActivity::class.java, intent).setup().get()
            idle()
            // The fragment only: the host's tab bar is the shell's (WP04), not this screen's.
            v.visit(name, a.supportFragmentManager.fragments.filterIsInstance<OptionsFragment>().first().view as ViewGroup)
            Fixtures.reset()
        }
    }

    // WP04: Home states, inside the shell (the 64 dp tab bar is part of every capture).
    private val homeStates: List<Pair<String, () -> Unit>> get() = listOf(
        "home/stopped" to { Fixtures.server(Fixtures.Server.STOPPED) },
        "home/running_healthy" to { Fixtures.server(Fixtures.Server.RUNNING_HEALTHY) },
        "home/running_attention_both" to { Fixtures.serverAttention(listenerOk = false, notifOk = false) },
        "home/failed" to { Fixtures.server(Fixtures.Server.FAILED) },
        "home/audit_degraded" to { Fixtures.auditDegraded() },
        "home/gate_paranoid" to { Fixtures.gate(com.mido.phonebridge.policy.GlobalMode.PARANOID) },
        "home/gate_dont_disturb" to { Fixtures.gate(com.mido.phonebridge.policy.GlobalMode.DONT_DISTURB) },
        "home/gate_unreadable" to { Fixtures.gateUnreadable() },
        "home/tape_three" to { Fixtures.auditTape() },
        "home/updates_available" to { Fixtures.updatesAvailable() },
        "home/updates_installing" to { Fixtures.updatesInstalling() },
        "home/updates_checking" to { Fixtures.updatesChecking() },
        "home/updates_unavailable" to { Fixtures.updatesUnavailable() },
    )


    private fun home(v: Visitor) {
        for ((name, setup) in homeStates) {
            Fixtures.dropSingletons()
            setup()
            val intent = android.content.Intent(Fixtures.app, com.mido.phonebridge.debug.ScreenshotHostActivity::class.java)
                .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_FRAGMENT, HomeFragment::class.java.name)
                .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_TAB, com.mido.phonebridge.ui.Theme.Tab.HOME.name)
            val a = Robolectric.buildActivity(com.mido.phonebridge.debug.ScreenshotHostActivity::class.java, intent).setup().get()
            idle()
            // The scrolling body and the bar are visited apart: a scroll view clips its content at the bar.
            v.visit(name, com.mido.phonebridge.screenshots.firstScroll(a.window.decorView)!!.getChildAt(0) as ViewGroup)
            v.visit("$name/tabbar", a.window.decorView.allViews().filterIsInstance<TabBar>().first())
            Fixtures.reset()
        }
    }

    // WP09: Secrets, SSH and the new-device screen in the states that add controls.
    private val vaultScreenStates: List<Pair<String, () -> android.app.Activity>> get() = listOf(
        "secrets/unlocked_two" to {
            Fixtures.vault(com.mido.phonebridge.core.VaultState.State.UNLOCKABLE, true, Fixtures.secretEntries(), Fixtures.secretValues())
            Robolectric.buildActivity(SecretsActivity::class.java).setup().get()
        },
        "secrets/locked_two" to {
            Fixtures.vault(com.mido.phonebridge.core.VaultState.State.UNLOCKABLE, false, Fixtures.secretEntries())
            Robolectric.buildActivity(SecretsActivity::class.java).setup().get()
        },
        "secrets/absent" to {
            Fixtures.vault(com.mido.phonebridge.core.VaultState.State.ABSENT, false, emptyMap())
            Robolectric.buildActivity(SecretsActivity::class.java).setup().get()
        },
        "secrets/broken_orphaned" to {
            Fixtures.vault(com.mido.phonebridge.core.VaultState.State.ORPHANED, false, Fixtures.secretEntries())
            Robolectric.buildActivity(SecretsActivity::class.java).setup().get()
        },
        "ssh/unlocked_two" to {
            Fixtures.sshVault(unlocked = true)
            Robolectric.buildActivity(SshActivity::class.java).setup().get()
        },
        "ssh/locked_two" to {
            Fixtures.sshVault(unlocked = false)
            Robolectric.buildActivity(SshActivity::class.java).setup().get()
        },
        "caller/initial" to {
            Robolectric.buildActivity(
                CallerActivity::class.java,
                android.content.Intent(Fixtures.app, CallerActivity::class.java).putExtra(CallerActivity.EXTRA_IP, "100.101.7.22"),
            ).setup().get()
        },
        "caller/long_ip_wrap" to {
            Robolectric.buildActivity(
                CallerActivity::class.java,
                android.content.Intent(Fixtures.app, CallerActivity::class.java).putExtra(CallerActivity.EXTRA_IP, "2001:db8:85a3::8a2e:370:7334"),
            ).setup().get()
        },
    )


    private fun vault(v: Visitor) {
        for ((name, launch) in vaultScreenStates) {
            Fixtures.dropSingletons()
            val a = launch()
            idle()
            v.visit(name, a.window.decorView as ViewGroup)
            Fixtures.reset()
        }
    }

    // WP08: the request log, the request detail and Exceptions.
    private val logScreenStates: List<Pair<String, () -> android.app.Activity>> get() = listOf(
        "log/list" to {
            Fixtures.logList()
            Fixtures.logNoise()
            Robolectric.buildActivity(LogActivity::class.java).setup().get()
        },
        "log_detail/approved" to {
            val id = Fixtures.detailRecord("approved")
            Robolectric.buildActivity(
                LogDetailActivity::class.java,
                android.content.Intent(Fixtures.app, LogDetailActivity::class.java).putExtra(LogDetailActivity.EXTRA_ID, id),
            ).setup().get()
        },
        "exceptions/rules" to {
            Fixtures.registerNamedPlugins()
            Fixtures.exceptionsOnly(
                com.mido.phonebridge.policy.Rule("gmail", com.mido.phonebridge.core.Verb.EDIT, com.mido.phonebridge.policy.Mode.PARANOID),
                com.mido.phonebridge.policy.Rule("calendar", null, com.mido.phonebridge.policy.Mode.DONT_DISTURB),
            )
            Robolectric.buildActivity(ExceptionsActivity::class.java).setup().get()
        },
    )


    private fun log(v: Visitor) {
        for ((name, launch) in logScreenStates) {
            Fixtures.dropSingletons()
            val a = launch()
            idle()
            v.visit(name, a.window.decorView as ViewGroup)
            Fixtures.reset()
        }
    }

    // WP05: the Plugins tab in each row state, the Developer group and the add keys.
    private val pluginStates: List<Pair<String, () -> Unit>> get() {
        val attention = com.mido.phonebridge.core.PluginStatus.State.NEEDS_ATTENTION
        val unconfigured = com.mido.phonebridge.core.PluginStatus.State.OFF
        val client = { Fixtures.googleClient("fixture-client") }
        return listOf(
            "plugins/default" to { Fixtures.registerPlugins() },
            "plugins/no_google_client" to { com.mido.phonebridge.screenshots.PluginFixtures.register() },
            "plugins/google_not_set_up" to {
                client()
                com.mido.phonebridge.screenshots.PluginFixtures.registerWith(
                    Triple("google", unconfigured, "no OAuth client id configured"),
                    Triple("gmail", unconfigured, "no OAuth client id configured"),
                )
            },
            "plugins/error_and_attention" to {
                client()
                com.mido.phonebridge.screenshots.PluginFixtures.registerWith(
                    Triple("contacts", attention, "READ_CONTACTS not granted"), throwing = setOf("tts"),
                )
            },
            "plugins/switched_off" to {
                client()
                com.mido.phonebridge.screenshots.PluginFixtures.register()
                Fixtures.pluginsSwitchedOff("tts", "audio")
            },
            "plugins/ssh_two_connections" to {
                client()
                com.mido.phonebridge.screenshots.PluginFixtures.register()
                Fixtures.secretNames("ssh-kvm-box", "ssh-nas")
            },
            "plugins/developer_open" to {
                client()
                com.mido.phonebridge.screenshots.PluginFixtures.register()
                Fixtures.developerOpen(true)
            },
            "plugins/empty_registry" to { Fixtures.clearPlugins() },
        )
    }


    private fun plugins(v: Visitor) {
        for ((name, setup) in pluginStates) {
            Fixtures.dropSingletons()
            setup()
            val intent = android.content.Intent(Fixtures.app, com.mido.phonebridge.debug.ScreenshotHostActivity::class.java)
                .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_FRAGMENT, PluginsFragment::class.java.name)
                .putExtra(com.mido.phonebridge.debug.ScreenshotHostActivity.EXTRA_TAB, com.mido.phonebridge.ui.Theme.Tab.PLUGINS.name)
            val a = Robolectric.buildActivity(com.mido.phonebridge.debug.ScreenshotHostActivity::class.java, intent).setup().get()
            idle()
            v.visit(name, com.mido.phonebridge.screenshots.firstScroll(a.window.decorView)!!.getChildAt(0) as ViewGroup)
            Fixtures.reset()
        }
    }

    // WP06: the plugin detail screen in the states that add controls (4 touch-target rules, whole screen).
    private val detailStates: List<Pair<String, () -> Pair<String, String?>>> get() {
        fun fake(p: com.mido.phonebridge.core.Plugin): () -> Pair<String, String?> = {
            Fixtures.clearPlugins(); com.mido.phonebridge.core.PluginRegistry.register(p); p.id to null
        }
        val text = com.mido.phonebridge.core.PluginSetting.Text("limit", "Character cap", "Longest text.", numeric = true)
        val choice = { n: Int ->
            com.mido.phonebridge.core.PluginSetting.Choice("mode", "Voice style", "How it sounds.", (1..n).map { "Option $it" }, "Option 1")
        }
        return listOf(
            "plugin_detail/tts" to { Fixtures.registerPlugins(); "tts" to null },
            "plugin_detail/contacts_needed" to { Fixtures.contactsPermission(false); Fixtures.registerPlugins(); "contacts" to null },
            "plugin_detail/permission_optional" to fake(com.mido.phonebridge.screenshots.DetailFakePlugin(
                "camera", "Camera",
                perms = listOf(com.mido.phonebridge.core.PluginPermission("android.permission.CAMERA", "Camera", "Takes a photo.", true)),
            )),
            "plugin_detail/access_all_four" to fake(com.mido.phonebridge.screenshots.DetailFakePlugin(
                "everything", "Everything", access = com.mido.phonebridge.core.SpecialAccess.entries,
            )),
            "plugin_detail/choice_knob" to fake(com.mido.phonebridge.screenshots.DetailFakePlugin("styled", "Styled", declared = listOf(choice(3)))),
            "plugin_detail/choice_chips" to fake(com.mido.phonebridge.screenshots.DetailFakePlugin("styled", "Styled", declared = listOf(choice(6)))),
            "plugin_detail/text_unset" to fake(com.mido.phonebridge.screenshots.DetailFakePlugin("texty", "Texty", declared = listOf(text))),
            "plugin_detail/routes_long" to fake(com.mido.phonebridge.screenshots.DetailFakePlugin("long", "Long", routeCount = 14)),
            "plugin_detail/ssh_connection" to { Fixtures.registerPlugins(); "ssh" to "kvm-box" },
            "plugin_detail/unregistered" to { Fixtures.clearPlugins(); "nonexistent" to null },
        )
    }


    private fun detail(v: Visitor) {
        for ((name, setup) in detailStates) {
            Fixtures.dropSingletons()
            val (id, secret) = setup()
            val intent = android.content.Intent(Fixtures.app, PluginDetailActivity::class.java)
                .putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, id)
            secret?.let { intent.putExtra(PluginDetailActivity.EXTRA_SECRET_NAME, it) }
            val a = Robolectric.buildActivity(PluginDetailActivity::class.java, intent).setup().get()
            idle()
            v.visit(name, a.window.decorView as ViewGroup)
            Fixtures.reset()
        }
    }

    // WP10: the introduction, every step and the step 3 / step 5 variants.
    private val onboardingStates: List<Pair<String, () -> Unit>> get() = listOf(
        "onboarding/step1" to { },
        "onboarding/step2" to { },
        "onboarding/step3_mixed" to { OnboardingFixtures.permissions(true, false, true) },
        "onboarding/step3_none" to { OnboardingFixtures.permissions(false, false, false) },
        "onboarding/step3_all" to { OnboardingFixtures.permissions(true, true, true) },
        "onboarding/step4" to { },
        "onboarding/step5_generic_server_on" to { OnboardingFixtures.bridgeAddress("100.101.7.22") },
        "onboarding/step5_generic_server_off" to { OnboardingFixtures.bridgeAddress("") },
        "onboarding/step5_hermes_server_on" to { OnboardingFixtures.bridgeAddress("100.101.7.22") },
    )


    private fun onboarding(v: Visitor) {
        for ((name, setup) in onboardingStates) {
            Fixtures.dropSingletons()
            setup()
            val a = Robolectric.buildActivity(OnboardingActivity::class.java).setup().get()
            val steps = name.substringAfter("step").substringBefore("_").toInt() - 1
            repeat(steps) {
                checkNotNull(com.mido.phonebridge.screenshots.findByText(a.window.decorView, "NEXT")) { "$name: no NEXT key" }.performClick()
            }
            if (name.contains("hermes")) {
                val title = com.mido.phonebridge.screenshots.findByText(a.window.decorView, "Hermes")
                generateSequence(title) { it.parent as? View }.first { it.isClickable }.performClick()
            }
            idle()
            v.visit(name, a.window.decorView as ViewGroup)
            Fixtures.reset()
        }
    }
}
