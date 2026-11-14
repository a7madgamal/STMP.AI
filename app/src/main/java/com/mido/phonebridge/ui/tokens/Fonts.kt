package com.mido.phonebridge.ui.tokens

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.mido.phonebridge.R

/**
 * The bundled faces, by role.
 *
 * **This is the paper set.** The Instrument kit replaced these with Geist, which changed the
 * app's whole voice: Atkinson Hyperlegible is chosen for legibility at small sizes, JetBrains
 * Mono NL for exact payloads, and Handlee for the stamp identity. A design-token layer is
 * worth having; losing those three was not the point of it.
 *
 * Each entry is a real bundled weight, never a synthesised bold — a fake bold blurs, and on a
 * screen whose job is to be read carefully before a security decision that is a defect.
 */
enum class FontFace(val res: Int) {
    SANS_400(R.font.atkinson_regular),
    SANS_600(R.font.atkinson_bold),
    MONO_400(R.font.jetbrains_mono_nl_regular),
    MONO_600(R.font.jetbrains_mono_nl_bold),
    DISPLAY(R.font.handlee),
}

/**
 * Loaded once per process.
 *
 * `ResourcesCompat.getFont` re-inflates on every call, which for a screen with thirty labels is
 * thirty decodes. A missing resource throws rather than silently falling back to a system face:
 * a silent fallback would undo the whole typographic identity of the app and never appear in a
 * test.
 */
object Fonts {
    private val cache = HashMap<FontFace, Typeface>()

    @Synchronized
    fun get(ctx: Context, face: FontFace): Typeface =
        cache[face] ?: ResourcesCompat.getFont(ctx, face.res)
            ?.also { cache[face] = it }
            ?: error("STMP font missing: ${face.name} not found in APK")

    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun clearCacheForTest() = cache.clear()
}
