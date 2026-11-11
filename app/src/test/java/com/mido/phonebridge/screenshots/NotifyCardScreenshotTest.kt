package com.mido.phonebridge.screenshots

import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.notify.NotifyOverlay
import org.junit.Test
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

/**
 * The small overlay card. Only the no-AI card is captured: `summarising`, `ready_low` and
 * `ready_high` need `AiGateway.summarize`, which runs on a worker thread with no test seam.
 */
class NotifyCardScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test
    fun notify_card_plain() {
        ShadowSettings.setCanDrawOverlays(true)
        NotifyOverlay.show(
            ctx = Fixtures.app, id = 7001L, summary = "Search contacts for sara", label = "SEARCH CONTACTS",
            verb = Verb.READ, windowMs = 5_000L, facts = null, needsAi = false, fetchCapMs = 0L,
            onOutcome = { },
        )
        ShadowLooper.idleMainLooper()
        Shots.screen("notify_card", "plain")
        NotifyOverlay.dismissAll()
        ShadowLooper.idleMainLooper()
    }
}
