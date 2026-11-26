package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for the sprite-sheet scrub thumbnails (P2-5, B-23). The
 * gate, the bucket math, the cell layout and the visibility rule are pure
 * functions of their inputs, so they are assertable without Android: no
 * ExoPlayer, no TextureView, no PixelCopy, no composition. The sheet
 * itself ([SpriteThumbnailCache]) holds a Bitmap and is exercised by the
 * player screen, not here.
 */
class ScrubSpritesTest {

    @Test
    fun `android 5_0 gets no scrub thumbnails`() {
        assertFalse(ScrubThumbnails.isSupported(sdkInt = 21))
    }

    @Test
    fun `android 6_0 gets no scrub thumbnails - PixelCopy needs 24`() {
        assertFalse(ScrubThumbnails.isSupported(sdkInt = 23))
    }

    @Test
    fun `android 7_0 gets scrub thumbnails`() {
        assertTrue(ScrubThumbnails.isSupported(sdkInt = 24))
    }

    @Test
    fun `a modern device gets scrub thumbnails`() {
        assertTrue(ScrubThumbnails.isSupported(sdkInt = 34))
    }

    @Test
    fun `position zero is the first bucket`() {
        assertEquals(0, ScrubThumbnails.bucketIndex(0L, 3_600_000L))
    }

    @Test
    fun `halfway through a title is the middle bucket`() {
        assertEquals(
            ScrubThumbnails.CELL_COUNT / 2,
            ScrubThumbnails.bucketIndex(1_800_000L, 3_600_000L),
        )
    }

    @Test
    fun `the last frame is the last bucket, not one past the sheet`() {
        assertEquals(
            ScrubThumbnails.CELL_COUNT - 1,
            ScrubThumbnails.bucketIndex(3_600_000L, 3_600_000L),
        )
    }

    @Test
    fun `a position past the end clamps to the last bucket`() {
        assertEquals(
            ScrubThumbnails.CELL_COUNT - 1,
            ScrubThumbnails.bucketIndex(9_999_999L, 3_600_000L),
        )
    }

    @Test
    fun `no duration means no bucket`() {
        assertEquals(-1, ScrubThumbnails.bucketIndex(90_000L, 0L))
        assertEquals(-1, ScrubThumbnails.bucketIndex(90_000L, -1L))
    }

    @Test
    fun `a negative position means no bucket`() {
        assertEquals(-1, ScrubThumbnails.bucketIndex(-1L, 3_600_000L))
    }

    @Test
    fun `the first cell is the sheet origin`() {
        assertEquals(0 to 0, ScrubThumbnails.cellOrigin(0))
    }

    @Test
    fun `cells fill row-major - index 8 starts row 2`() {
        assertEquals(0 to ScrubThumbnails.CELL_HEIGHT_PX, ScrubThumbnails.cellOrigin(8))
    }

    @Test
    fun `the last cell is the bottom-right corner`() {
        assertEquals(
            (ScrubThumbnails.COLUMNS - 1) * ScrubThumbnails.CELL_WIDTH_PX to
                (ScrubThumbnails.ROWS - 1) * ScrubThumbnails.CELL_HEIGHT_PX,
            ScrubThumbnails.cellOrigin(ScrubThumbnails.CELL_COUNT - 1),
        )
    }

    @Test
    fun `the sheet holds 48 cells of 160x90`() {
        assertEquals(48, ScrubThumbnails.CELL_COUNT)
        assertEquals(8 * 160, ScrubThumbnails.COLUMNS * ScrubThumbnails.CELL_WIDTH_PX)
        assertEquals(6 * 90, ScrubThumbnails.ROWS * ScrubThumbnails.CELL_HEIGHT_PX)
    }

    @Test
    fun `thumbnail shows when the target bucket has a captured frame`() {
        assertTrue(ScrubThumbnails.shouldShowThumbnail(frameAvailable = true))
    }

    @Test
    fun `thumbnail hides when the target bucket was never captured`() {
        // A scrub to unwatched territory is time-only: never a frame from
        // another scene.
        assertFalse(ScrubThumbnails.shouldShowThumbnail(frameAvailable = false))
    }
}
