package com.mido.phonebridge.screenshots

import com.mido.phonebridge.debug.KitHostActivity
import com.mido.phonebridge.ui.kit.Wordmark
import org.junit.Test
import org.robolectric.Robolectric

/** The in-app logo (COMPONENTS 29): glyph only, and glyph with the Geist word, on `body`. */
class WordmarkScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun shoot(state: String, withText: Boolean, iconDp: Int) {
        val a = Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
        a.column.addView(Wordmark.create(a, iconDp = iconDp, withText = withText))
        Shots.view(a.column, "wordmark", state)
    }

    @Test fun icon_only() = shoot("icon_only", withText = false, iconDp = 32)
    @Test fun with_text() = shoot("with_text", withText = true, iconDp = 20)
}
