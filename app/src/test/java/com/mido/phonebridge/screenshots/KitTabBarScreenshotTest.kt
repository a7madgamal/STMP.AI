package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the TabBar component (COMPONENTS), light and dark at both font scales. */
class KitTabBarScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_tab_bar")
}
