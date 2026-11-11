package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the ObscuredGuard component (COMPONENTS), light and dark at both font scales. */
class KitObscuredGuardScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_obscured_guard")
}
