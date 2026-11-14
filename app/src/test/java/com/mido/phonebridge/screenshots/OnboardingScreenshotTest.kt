package com.mido.phonebridge.screenshots

import android.view.View
import com.mido.phonebridge.OnboardingActivity
import org.junit.Test

/** Onboarding steps 1 to 5, with step 3 in three permission states and step 5 in its variants. */
class OnboardingScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun onboarding_step1() = onboarding(1, "step1")
    @Test fun onboarding_step2() = onboarding(2, "step2")
    @Test fun onboarding_step4() = onboarding(4, "step4")

    @Test fun onboarding_step3_none() {
        OnboardingFixtures.permissions(listener = false, overlay = false, battery = false)
        onboarding(3, "step3_none")
    }

    @Test fun onboarding_step3_all() {
        OnboardingFixtures.permissions(listener = true, overlay = true, battery = true)
        onboarding(3, "step3_all")
    }

    @Test fun onboarding_step3_mixed() {
        OnboardingFixtures.permissions(listener = true, overlay = false, battery = true)
        onboarding(3, "step3_mixed")
    }

    @Test fun onboarding_step5_generic_server_on() {
        OnboardingFixtures.bridgeAddress("100.101.7.22")
        onboarding(5, "step5_generic_server_on")
    }

    @Test fun onboarding_step5_generic_server_off() {
        OnboardingFixtures.bridgeAddress("")
        onboarding(5, "step5_generic_server_off")
    }

    @Test fun onboarding_step5_hermes_server_on() {
        OnboardingFixtures.bridgeAddress("100.101.7.22")
        val a = launch(OnboardingActivity::class.java)
        repeat(4) { checkNotNull(findByText(a.window.decorView, "NEXT")) { "no NEXT key" }.performClick() }
        val title = findByText(a.window.decorView, "Hermes")
        generateSequence(title) { it.parent as? View }.first { it.isClickable }.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        capture(a, "onboarding", "step5_hermes_server_on")
    }

    private fun onboarding(step: Int, state: String) {
        val a = launch(OnboardingActivity::class.java)
        repeat(step - 1) { checkNotNull(findByText(a.window.decorView, "NEXT")) { "no NEXT key" }.performClick() }
        capture(a, "onboarding", state)
    }
}
