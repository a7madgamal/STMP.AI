package com.mido.phonebridge

import androidx.test.core.app.ApplicationProvider
import com.mido.phonebridge.ui.Theme
import com.mido.phonebridge.ui.tokens.Palette
import com.mido.phonebridge.ui.tokens.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Palette fields and `stmp_*` colour resources must agree, in both themes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaletteResourcesTest {

    private val nonRoleResources = setOf("stmp_nav_bar")

    private fun snake(camel: String) = camel.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }

    private fun fieldNames(): List<String> =
        Palette::class.constructors.first().parameters.mapNotNull { it.name }.filter { it != "isDark" }

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `every palette field has a resource in both themes`() {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val res = ctx().resources
            for (f in fieldNames()) {
                val id = res.getIdentifier("stmp_${snake(f)}", "color", ctx().packageName)
                assertTrue("no colour stmp_${snake(f)} (night=$night)", id != 0)
            }
        }
    }

    @Test
    fun `every stmp colour resource has a palette field`() {
        val xml = listOf("src/main/res/values/colors.xml", "../app/src/main/res/values/colors.xml")
            .map { File(it) }.first { it.exists() }.readText()
        val names = Regex("""<color name="(stmp_[a-z0-9_]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue("scan found no stmp_ colours", names.isNotEmpty())
        val fields = fieldNames().map { "stmp_${snake(it)}" }.toSet()
        val orphans = names.filterNot { it in nonRoleResources || it.startsWith("stmp_launcher_") || it in fields }
        assertEquals("resources with no Palette field: $orphans", emptyList<String>(), orphans)
    }

    @Test
    fun `the palette follows the night qualifier`() {
        RuntimeEnvironment.setQualifiers("+notnight")
        Palettes.clearCacheForTest()
        assertEquals(false, Palettes.of(ctx()).isDark)
        RuntimeEnvironment.setQualifiers("+night")
        assertEquals(true, Palettes.of(ctx()).isDark)
    }

    @Test
    fun `accent and danger differ in both themes`() {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            Palettes.clearCacheForTest()
            assertNotEquals("ACCENT == DANGER (night=$night)", Theme.ACCENT, Theme.DANGER)
        }
    }
}
