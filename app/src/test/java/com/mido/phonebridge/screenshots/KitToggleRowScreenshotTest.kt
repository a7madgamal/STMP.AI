package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the ToggleRow component (COMPONENTS 26), light and dark at both font scales. */
class KitToggleRowScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_toggle_row")
}
