package com.mido.phonebridge

import android.content.res.Configuration
import com.mido.phonebridge.notify.CardAnchor
import com.mido.phonebridge.notify.CardAnchorStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The card anchor decides whether a prompt is reachable or invisible. In NOTIFY mode
 * silence approves, so a card restored off-screen is not a cosmetic bug — it is a
 * request approved by nobody.
 *
 * These run on a plain JVM against a real SharedPreferences, so the per-orientation
 * keys and the clamp are both checked as behaviour rather than read off the source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardAnchorTest {

    private val portrait = Configuration.ORIENTATION_PORTRAIT
    private val landscape = Configuration.ORIENTATION_LANDSCAPE

    @Test
    fun `a position inside the screen is kept exactly`() {
        val a = CardAnchor.clamp(
            x = 100, y = 200,
            screenWidth = 1080, screenHeight = 2400,
            cardWidth = 648, cardHeight = 400,
            minEdge = 42,
        )
        assertEquals(100, a.x)
        assertEquals(200, a.y)
    }

    @Test
    fun `a position from a wider display is pulled back onto this one`() {
        // Saved on a tablet in landscape, restored on a phone in portrait: x is far past
        // the right edge and y past the top. Both must come back to the nearest edge.
        val a = CardAnchor.clamp(
            x = 2000, y = 5000,
            screenWidth = 1080, screenHeight = 2400,
            cardWidth = 648, cardHeight = 400,
            minEdge = 42,
        )
        assertEquals("pulled to the right edge", 1080 - 648 - 42, a.x)
        assertEquals("pulled down to the top edge", 2400 - 400 - 42, a.y)
    }

    @Test
    fun `a card taller than the screen collapses to the edge instead of throwing`() {
        // coerceIn throws on an inverted range, so the ceiling is floored at minEdge.
        val a = CardAnchor.clamp(
            x = 500, y = 500,
            screenWidth = 300, screenHeight = 300,
            cardWidth = 900, cardHeight = 900,
            minEdge = 42,
        )
        assertEquals(42, a.x)
        assertEquals(42, a.y)
    }

    @Test
    fun `portrait and landscape keep separate anchors`() {
        val ctx = RuntimeEnvironment.getApplication()

        CardAnchorStore.save(ctx, portrait, CardAnchor(11, 22))
        CardAnchorStore.save(ctx, landscape, CardAnchor(33, 44))

        assertEquals(CardAnchor(11, 22), CardAnchorStore.load(ctx, portrait))
        assertEquals(
            "landscape must not have overwritten portrait",
            CardAnchor(33, 44),
            CardAnchorStore.load(ctx, landscape)
        )
    }

    @Test
    fun `an unset anchor is null, not a default position`() {
        val ctx = RuntimeEnvironment.getApplication()
        // A default here would be indistinguishable from a saved value, and would place
        // the card at a coordinate the user never chose.
        assertEquals(null, CardAnchorStore.load(ctx, portrait))
    }
}
