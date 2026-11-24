package com.raddstream.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P3 (round 2) — the poster-luminance contrast source: the pure
 * luminance math and the scrim scale. Robolectric (Bitmaps need the
 * Android runtime shadows); no Compose runtime needed — the painter half
 * (rememberPosterLuminance) is covered by the render: a null luminance
 * reads the 1.0x scrims, which the goldens pin byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
class ArtworkAccentTest {

    private fun solidBitmap(argb: Int): Bitmap =
        Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    @Test
    fun `a white poster is luminance one`() {
        assertEquals(1f, posterLuminance(solidBitmap(Color.WHITE)) ?: -1f, 0.01f)
    }

    @Test
    fun `a black poster is luminance zero`() {
        assertEquals(0f, posterLuminance(solidBitmap(Color.BLACK)) ?: -1f, 0.01f)
    }

    @Test
    fun `a mid-gray poster is about point two one`() {
        // sRGB 0.5 linearises to ~0.216 (WCAG) — near the scale's midpoint.
        assertEquals(0.216f, posterLuminance(solidBitmap(0xFF808080.toInt())) ?: -1f, 0.02f)
    }

    @Test
    fun `no bitmap is no luminance`() {
        assertNull(posterLuminance(null))
    }

    @Test
    fun `no poster keeps the current scrim`() {
        assertEquals(1f, scrimScaleFor(null), 0.001f)
    }

    @Test
    fun `dark art lightens the scrim, bright art strengthens it`() {
        assertEquals(0.75f, scrimScaleFor(0f), 0.001f)
        assertEquals(1f, scrimScaleFor(0.5f), 0.001f)
        assertEquals(1.25f, scrimScaleFor(1f), 0.001f)
    }
}
