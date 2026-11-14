package com.mido.phonebridge.ui.tokens

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.mido.phonebridge.R

enum class FontFace(@FontRes val res: Int) {
    SANS_400(R.font.geist_regular),
    SANS_500(R.font.geist_medium),
    SANS_600(R.font.geist_semibold),
    SANS_700(R.font.geist_bold),
    MONO_400(R.font.geist_mono_regular),
    MONO_500(R.font.geist_mono_medium),
    MONO_600(R.font.geist_mono_semibold),
}

object Fonts {
    private val cache = java.util.EnumMap<FontFace, Typeface>(FontFace::class.java)

    /** Loaded once per face per process. A missing font is a packaging bug and throws. */
    fun get(ctx: Context, face: FontFace): Typeface = synchronized(cache) {
        cache[face] ?: (ResourcesCompat.getFont(ctx, face.res)
            ?: error("STMP font missing: ${face.name} (res ${face.res})"))
            .also { cache[face] = it }
    }
}
