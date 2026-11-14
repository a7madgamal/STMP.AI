package com.mido.phonebridge.screenshots

import com.mido.phonebridge.OnboardingActivity
import org.junit.Test

/** Onboarding steps 1 to 5. */
class OnboardingScreenshotTest(variant: Variant) : ShotTest(variant) {

    @Test fun onboarding_step1() = onboarding(1)
    @Test fun onboarding_step2() = onboarding(2)
    @Test fun onboarding_step3() = onboarding(3)
    @Test fun onboarding_step4() = onboarding(4)
    @Test fun onboarding_step5() = onboarding(5)

    private fun onboarding(step: Int) {
        val a = launch(OnboardingActivity::class.java)
        repeat(step - 1) { findByText(a.window.decorView, "NEXT")?.performClick() }
        capture(a, "onboarding", "step$step")
    }
}
