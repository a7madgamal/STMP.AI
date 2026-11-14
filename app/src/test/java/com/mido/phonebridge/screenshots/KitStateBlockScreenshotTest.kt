package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the StateBlock component (COMPONENTS), light and dark at both font scales. */
class KitStateBlockScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_state_block")
}
