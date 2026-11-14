package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Chips component (COMPONENTS), light and dark at both font scales. */
class KitChipsScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_chips")
}
