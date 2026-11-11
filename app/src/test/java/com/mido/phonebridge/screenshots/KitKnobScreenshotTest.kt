package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Knob component (COMPONENTS), light and dark at both font scales. */
class KitKnobScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_knob")
}
