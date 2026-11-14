package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Tape component (COMPONENTS), light and dark at both font scales. */
class KitTapeScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_tape")
}
