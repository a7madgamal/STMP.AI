package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the TopBar component (COMPONENTS), light and dark at both font scales. */
class KitTopBarScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_top_bar")
}
