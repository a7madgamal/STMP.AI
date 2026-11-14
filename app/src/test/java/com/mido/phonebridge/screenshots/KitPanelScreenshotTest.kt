package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the Panel component (COMPONENTS), light and dark at both font scales. */
class KitPanelScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_panel")
}
