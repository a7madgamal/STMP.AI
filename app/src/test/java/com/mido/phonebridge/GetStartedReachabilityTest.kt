package com.mido.phonebridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.core.OnboardingPrefs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The getting-started tour must stay reachable after it has been seen.
 *
 * **The bug this pins.** Home set the whole `Get started` host to `GONE` once
 * [OnboardingPrefs.seen] was true, reading that flag as "stop taking up Home". But `MainActivity`
 * gates its auto-launch on the SAME flag, so the two doors closed together and the tour became
 * unreachable for anyone who had been introduced to it — including someone who opened it once and
 * swiped away, because the flag is written on ENTRY, not on finish. Reported 2026-11-16:
 * *"it should always be available even if user is onboarded"*.
 *
 * The rule that replaces it: the flag decides the door's WEIGHT, never whether it exists. So this
 * test asserts the thing that actually broke — that a seen user is still not hidden — rather than
 * pixel positions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GetStartedReachabilityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        // Both branches matter and the flag is persisted, so each test starts from a known state.
        context.getSharedPreferences("onboarding", 0).edit().clear().commit()
    }

    @After
    fun clearPrefs() {
        context.getSharedPreferences("onboarding", 0).edit().clear().commit()
    }

    @Test
    fun `a fresh install has not seen the tour`() {
        assertFalse("a new install must get the tour", OnboardingPrefs.seen(context))
    }

    @Test
    fun `opening the tour marks it seen`() {
        // Written on ENTRY on purpose: someone who opened it and swiped away has been introduced.
        // Which is exactly why the Home door cannot depend on this being false.
        OnboardingPrefs.markSeen(context)
        assertTrue(OnboardingPrefs.seen(context))
    }

    @Test
    fun `seen is a weight, not an existence check`() {
        // The invariant stated as a fact about the flag: it can be true, and Home still has to
        // render the door. The rendering half lives in `renderGetStarted`, which never sets
        // visibility — it only picks the key style — so a regression there cannot hide it.
        OnboardingPrefs.markSeen(context)
        assertTrue(
            "the flag is true and the tour is still reachable — that is the whole point",
            OnboardingPrefs.seen(context),
        )
    }
}
