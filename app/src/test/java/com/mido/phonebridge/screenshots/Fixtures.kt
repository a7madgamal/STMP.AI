package com.mido.phonebridge.screenshots

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.BridgeService
import com.mido.phonebridge.NotifListenerService
import com.mido.phonebridge.audit.AuditHealth
import com.mido.phonebridge.core.UpdatePrefs
import com.mido.phonebridge.core.Updater
import com.mido.phonebridge.policy.GlobalMode
import com.mido.phonebridge.policy.PolicyStore
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.TimeZone

/**
 * State setters for screenshot tests (TESTING 5). They change only in-memory or sandbox state and
 * every one has a matching [reset], called from `@After`.
 */
object Fixtures {

    /** A fixed epoch for fixtures that print an absolute time: 2026-11-11 09:40:00 UTC. */
    const val NOW_MS = 1_794_390_000_000L

    private const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private val SINGLETON_HOLDERS = listOf(
        "com.mido.phonebridge.policy.PolicyStore",
        "com.mido.phonebridge.audit.AuditLog",
        "com.mido.phonebridge.core.PluginSettingsStore",
        "com.mido.phonebridge.callers.CallerStore",
    )

    private var previousZone: TimeZone? = null

    val app: Application get() = ApplicationProvider.getApplicationContext()

    // --- clock ---------------------------------------------------------------------------------

    fun freezeClock() {
        if (previousZone == null) previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    // --- process-wide singletons (each test gets a fresh filesDir, the singletons keep the old one)

    fun dropSingletons() {
        for (name in SINGLETON_HOLDERS) {
            val cls = Class.forName(name)
            val field = cls.declaredFields.firstOrNull { it.name == "singleton" } ?: continue
            field.isAccessible = true
            field.set(null, null)
        }
    }

    // --- Home: server status ---------------------------------------------------------------------

    enum class Server { STOPPED, RUNNING_HEALTHY, RUNNING_ATTENTION, UNRESPONSIVE, FAILED }

    fun server(state: Server) {
        BridgeService.serverUp = state != Server.STOPPED && state != Server.FAILED
        BridgeService.lastError = if (state == Server.FAILED) "Could not bind port 8787: Address already in use" else ""
        BridgeService.bindAddress = if (BridgeService.serverUp) "192.168.1.100" else ""
        // Fresh probe for HEALTHY/ATTENTION, a stale one (older than 3 watchdog intervals) for UNRESPONSIVE.
        BridgeService.lastProbeAtMs = when (state) {
            Server.UNRESPONSIVE -> 1L
            else -> System.currentTimeMillis()
        }
        BridgeService.lastProbeOk = state != Server.UNRESPONSIVE
        val notifListener = state == Server.RUNNING_HEALTHY || state == Server.UNRESPONSIVE
        BridgeService.listenerRef =
            if (notifListener) Robolectric.setupService(NotifListenerService::class.java) else null
        val shadow = shadowOf(app)
        if (state == Server.RUNNING_ATTENTION) shadow.denyPermissions(POST_NOTIFICATIONS)
        else shadow.grantPermissions(POST_NOTIFICATIONS)
    }

    fun resetServer() {
        BridgeService.serverUp = false
        BridgeService.lastError = ""
        BridgeService.bindAddress = ""
        BridgeService.lastProbeAtMs = 0L
        BridgeService.lastProbeOk = false
        BridgeService.listenerRef = null
    }

    // --- gate mode -----------------------------------------------------------------------------

    fun gate(mode: GlobalMode) {
        val store = PolicyStore.from(app)
        store.set(store.get().withGlobal(mode))
    }

    /** The policy file holds bytes no parser accepts. */
    fun gateUnreadable() {
        val dir = File(app.filesDir, "policy").apply { mkdirs() }
        File(dir, "policy.json").writeText("not json {{{")
        dropSingletons()
    }

    // --- audit health --------------------------------------------------------------------------

    fun auditDegraded() = AuditHealth.markDegraded("fixture: the request log could not be written")
    fun resetAudit() = AuditHealth.clear()

    // --- updates card --------------------------------------------------------------------------

    // `System.currentTimeMillis()` is the real clock under Robolectric, and the card prints "N h ago"
    // from it, so the fixture is relative to the real now: "4 h ago" holds for any run that renders
    // within an hour of building the fixture.
    fun updatesUpToDate() {
        val at = System.currentTimeMillis() - FOUR_HOURS_MS
        UpdatePrefs.setLastCheckAt(app, at)
        Updater.lastOutcome = Updater.Outcome.UpToDate(at)
    }

    fun updatesAvailable() {
        UpdatePrefs.setLastCheckAt(app, System.currentTimeMillis() - FOUR_HOURS_MS)
        Updater.lastOutcome = Updater.Outcome.Available(
            version = "0.49.0",
            versionCode = 296,
            notes = "- Dark mode follows the system.\n- Text is set in Geist.",
            apkUrl = "https://example.invalid/stmp-v0.49.0.apk",
            sha256 = null,
            sizeBytes = 71_303_168L,
            isBeta = false,
        )
    }

    fun resetUpdates() {
        Updater.lastOutcome = null
        Updater.checking = false
    }

    private const val FOUR_HOURS_MS = 4 * 60 * 60 * 1000L

    // --- permissions ---------------------------------------------------------------------------

    fun contactsPermission(granted: Boolean) {
        val shadow = shadowOf(app)
        if (granted) shadow.grantPermissions(READ_CONTACTS) else shadow.denyPermissions(READ_CONTACTS)
    }

    // --- Settings ------------------------------------------------------------------------------

    fun aiBackend(backend: com.mido.phonebridge.policy.AiBackend, url: String = "") {
        val store = PolicyStore.from(app)
        store.set(store.get().withAiBackend(backend).withAiExternalUrl(url))
    }

    fun callers(vararg entries: Pair<String, com.mido.phonebridge.callers.CallerState>) {
        val store = com.mido.phonebridge.callers.CallerStore.from(app)
        entries.forEach { (ip, state) -> store.set(ip, state, NOW_MS) }
    }

    /**
     * Replaces the on-screen debug log with fixed lines. The ring is process-wide and stamps real
     * wall-clock times, so it is filled through its field instead of `Debug.log`.
     */
    fun debugLog(lines: List<String>) {
        val field = com.mido.phonebridge.Debug::class.java.getDeclaredField("lines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ring = field.get(null) as ArrayDeque<String>
        ring.clear()
        ring.addAll(lines)
        com.mido.phonebridge.DebugLogOpen.open = true
    }

    fun resetDebugLog() {
        val field = com.mido.phonebridge.Debug::class.java.getDeclaredField("lines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as ArrayDeque<String>).clear()
        com.mido.phonebridge.DebugLogOpen.open = false
    }

    // --- plugins -------------------------------------------------------------------------------

    /**
     * Registers the production plugin set the way `BridgeService.registerPlugins` does, on a fresh
     * registry: the registry is process-wide and its plugins hold the first test's Context.
     */
    fun registerPlugins() {
        clearPlugins()
        val service = Robolectric.buildService(BridgeService::class.java).get()
        BridgeService::class.java.getDeclaredMethod("registerPlugins")
            .apply { isAccessible = true }
            .invoke(service)
    }

    fun clearPlugins() {
        val cls = com.mido.phonebridge.core.PluginRegistry::class.java
        for (name in listOf("plugins", "routes")) {
            val f = cls.getDeclaredField(name).apply { isAccessible = true }
            when (val v = f.get(com.mido.phonebridge.core.PluginRegistry)) {
                is MutableMap<*, *> -> v.clear()
                is MutableList<*> -> v.clear()
            }
        }
        com.mido.phonebridge.core.PluginRegistry.enabledLookup = null
    }

    // --- request log -----------------------------------------------------------------------------

    /** `count` records, newest last, one minute apart from [NOW_MS]; ids are fixed. */
    fun auditRecords(count: Int): List<String> {
        val store = com.mido.phonebridge.audit.AuditLog.from(app)
        val outcomes = listOf(
            com.mido.phonebridge.audit.AuditOutcome.APPROVED,
            com.mido.phonebridge.audit.AuditOutcome.DENIED,
            com.mido.phonebridge.audit.AuditOutcome.NOTIFY_APPROVED,
            com.mido.phonebridge.audit.AuditOutcome.TIMEOUT,
        )
        return (0 until count).map { i ->
            val id = "2026-11-11T09-40-%02d-fixture%02d".format(i, i)
            store.append(auditRecord(id, NOW_MS + i * 60_000L, outcomes[i % outcomes.size], i))
            id
        }
    }

    private fun auditRecord(id: String, ts: Long, outcome: com.mido.phonebridge.audit.AuditOutcome, i: Int) =
        com.mido.phonebridge.audit.AuditRecord(
            id = id, ts = ts, outcome = outcome,
            label = "contacts.read", plugin = "contacts", verb = "READ",
            origin = "TAILNET", remote = "100.64.0.7", requester = "Work laptop",
            route = "GET /contacts/search", method = "GET", path = "/contacts/search",
            query = mapOf("q" to "sara"), summary = "Search contacts for sara ($i)",
            requestHeaders = mapOf("Host" to "192.168.1.100:8787"), requestBody = "",
            responseStatus = if (outcome == com.mido.phonebridge.audit.AuditOutcome.DENIED) 403 else 200,
            responseBody = "{\"results\":[]}", error = null,
            decision = com.mido.phonebridge.audit.Decision(
                mode = "SECURE", source = com.mido.phonebridge.audit.DecisionSource.GLOBAL, globalMode = "PARANOID",
                enqueuedAt = ts, shownAt = ts + 100, decidedAt = ts + 2_500,
            ),
            durationMs = 2_600L,
            truncated = com.mido.phonebridge.audit.Truncation(0, 0),
            noise = false, appVersionName = "0.48.19", appVersionCode = 295,
        )

    // --- exceptions ----------------------------------------------------------------------------

    fun exceptions(vararg rules: com.mido.phonebridge.policy.Rule) {
        val store = PolicyStore.from(app)
        store.set(rules.fold(store.get()) { p, r -> p.withRule(r) })
    }

    // --- WP07: Settings -----------------------------------------------------------------------

    private const val LISTENER = "com.mido.phonebridge/com.mido.phonebridge.NotifListenerService"

    /** A sparse file of [length] bytes where the on-device model lives, or none when null. */
    fun modelFile(length: Long?) {
        val f = com.mido.phonebridge.ai.ModelRegistry.modelFile(app)
        f.parentFile?.mkdirs()
        f.delete()
        if (length != null) java.io.RandomAccessFile(f, "rw").use { it.setLength(length) }
    }

    fun provisioner(running: Boolean, progress: com.mido.phonebridge.ai.ModelProvisioner.Progress?, lastError: String) =
        com.mido.phonebridge.ai.ModelProvisioner.setStateForTest(running, progress, lastError)

    /** Notification access: [granted] in Android settings, [connected] when the listener service is bound. */
    fun notificationListener(granted: Boolean, connected: Boolean) {
        android.provider.Settings.Secure.putString(
            app.contentResolver, "enabled_notification_listeners", if (granted) LISTENER else "",
        )
        BridgeService.listenerRef =
            if (connected) Robolectric.setupService(NotifListenerService::class.java) else null
    }

    fun systemAccess(overlay: Boolean, batteryExempt: Boolean, postNotifications: Boolean, phoneDnd: Boolean) {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(overlay)
        val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, batteryExempt)
        val shadow = shadowOf(app)
        if (postNotifications) shadow.grantPermissions(POST_NOTIFICATIONS) else shadow.denyPermissions(POST_NOTIFICATIONS)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(
            if (phoneDnd) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
        )
    }

    fun autoStartAndSound(autoStart: Boolean, sound: Boolean) {
        val store = PolicyStore.from(app)
        store.set(store.get().withAutoStartOnBoot(autoStart).withSoundEffects(sound))
    }

    fun externalModel(url: String, model: String, dialect: com.mido.phonebridge.policy.ExternalDialect?) {
        val store = PolicyStore.from(app)
        var p = store.get().withAiBackend(com.mido.phonebridge.policy.AiBackend.EXTERNAL).withAiExternalUrl(url)
        if (dialect != null) p = p.withAiExternalModel(model, dialect)
        store.set(p)
    }

    /** Bytes no JSON parser accepts where the caller registry lives. */
    fun callersUnreadable() {
        val dir = File(app.filesDir, "callers").apply { mkdirs() }
        File(dir, "callers.json").writeText("not json {{{")
        dropSingletons()
    }

    fun resetSettings() {
        runCatching { com.mido.phonebridge.ai.ModelRegistry.modelFile(app).delete() }
        com.mido.phonebridge.ai.ModelProvisioner.setStateForTest(false, null, "")
        android.provider.Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
    }

    // --- everything ----------------------------------------------------------------------------

    fun reset() {
        resetServer()
        resetAudit()
        resetUpdates()
        resetDebugLog()
        resetSettings()
        clearPlugins()
        previousZone?.let { TimeZone.setDefault(it) }
        previousZone = null
    }

    @Suppress("unused")
    private fun unusedContext(): Context = app
}
