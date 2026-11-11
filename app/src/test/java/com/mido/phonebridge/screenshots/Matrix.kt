package com.mido.phonebridge.screenshots

import com.mido.phonebridge.ui.tokens.Palettes
import org.robolectric.RuntimeEnvironment

/** One cell of the screenshot matrix: theme x font scale (TESTING 4.1). */
data class Variant(val night: Boolean, val fontScale: Float) {
    val themeName: String get() = if (night) "dark" else "light"
    val scaleName: String get() = if (fontScale == 1f) "100" else "135"

    /**
     * Applies this cell to the Robolectric environment. Call it BEFORE the Activity or view is
     * built, and it stays current for [Shots] until the next call.
     */
    fun apply() {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        Palettes.clearCacheForTest()
        RuntimeEnvironment.setFontScale(fontScale)
        Matrix.current = this
    }

    override fun toString() = "$themeName/$scaleName"
}

object Matrix {
    val ALL = listOf(
        Variant(night = false, fontScale = 1f),
        Variant(night = true, fontScale = 1f),
        Variant(night = false, fontScale = 1.35f),
        Variant(night = true, fontScale = 1.35f),
    )

    /** The variant [Variant.apply] ran last; [Shots] names files from it. */
    @Volatile
    var current: Variant = ALL.first()

    /** For `@Parameterized.Parameters`. */
    fun parameters(): List<Array<Any>> = ALL.map { arrayOf<Any>(it) }
}
