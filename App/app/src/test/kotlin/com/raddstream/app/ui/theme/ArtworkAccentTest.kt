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
 * luminance math. Robolectric (Bitmaps need the Android runtime shadows);
 * no Compose runtime needed. P2-6 (audit B-24): the scrim scale is gone
 * — the player scrims are a standard fixed gradient now, so there is no
 * scrimScaleFor to pin; the luminance math stays, tested here.
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
}
