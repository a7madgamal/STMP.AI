package com.mido.phonebridge.screenshots

import android.view.ViewGroup
import android.widget.LinearLayout
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.debug.KitHostActivity
import com.mido.phonebridge.ui.PluginRowModel
import com.mido.phonebridge.ui.kit.PluginRow
import com.mido.phonebridge.ui.kit.Panel
import org.junit.Test
import org.robolectric.Robolectric

/** PluginRow in each of the states of WP05 6.1, light and dark at both font scales. */
class KitPluginRowScreenshotTest(variant: Variant) : ShotTest(variant) {

    private fun model(state: PluginStatus.State, detail: String, enabled: Boolean = true) =
        PluginRowModel.of(FixturePlugin("tts", "Speak", state, detail), enabled, hasGoogleClient = true)

    @Test
    fun states() {
        val a = Robolectric.buildActivity(KitHostActivity::class.java).setup().get()
        val models = listOf(
            model(PluginStatus.State.ERROR, "Status unavailable: boom"),
            model(PluginStatus.State.NEEDS_ATTENTION, "listener not connected"),
            model(PluginStatus.State.OFF, "no connections configured"),
            model(PluginStatus.State.OK, "POST /tts"),
            model(PluginStatus.State.OK, "", enabled = false),
        )
        val panel = Panel.create(a)
        for (m in models) panel.addView(PluginRow.create(a, m, {}, {}, {}))
        a.column.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Shots.view(a.column, "kit_plugin_row", "states")
    }
}
