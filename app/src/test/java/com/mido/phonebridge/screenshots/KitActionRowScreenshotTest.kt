package com.mido.phonebridge.screenshots

import org.junit.Test

/** Every state of the ActionRow component (COMPONENTS), light and dark at both font scales. */
class KitActionRowScreenshotTest(variant: Variant) : KitShotTest(variant) {
    @Test fun states() = shootAll("kit_action_row")
}
