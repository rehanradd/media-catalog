package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CacheAffordance] — the file's own KDoc has long claimed this class
 * pins the math, but no such file existed until Session 126. The cases here pin the
 * SC.7 living-progress ring: a real fraction when the total is known, and null —
 * never a 0% ring — whenever the total is unknown or the state is not Keeping.
 */
class CacheAffordanceTest {

    @Test
    fun `keeping progress is the saved fraction of the total`() {
        assertEquals(
            0.5f,
            CacheAffordance.keepingProgress(KeepUiState.Keeping(500L, 1000L, 100L))!!,
            0.001f,
        )
    }

    @Test
    fun `keeping progress never exceeds one`() {
        assertEquals(
            1.0f,
            CacheAffordance.keepingProgress(KeepUiState.Keeping(1500L, 1000L, 100L))!!,
            0.001f,
        )
    }

    @Test
    fun `an unknown total means no ring, not a zero ring`() {
        assertNull(CacheAffordance.keepingProgress(KeepUiState.Keeping(0L, null, 0L)))
        assertNull(CacheAffordance.keepingProgress(KeepUiState.Keeping(0L, 0L, 0L)))
    }

    @Test
    fun `only a keep in flight has a ring`() {
        assertNull(CacheAffordance.keepingProgress(KeepUiState.Unavailable))
        assertNull(
            CacheAffordance.keepingProgress(
                KeepUiState.Watching(500L, 1000L, isEstimate = false, expiringInMs = null),
            ),
        )
        assertNull(CacheAffordance.keepingProgress(KeepUiState.Stored(true, null)))
    }

    @Test
    fun `interrupted message is short enough for a toast`() {
        // Screenshot-fix D5 (2026-11-16): the old message named the saved
        // bytes and the toast cut it mid-word — "Keep interrupted — will
        // res...". The toast only needs the outcome; the byte count rides
        // on the Keep chip. 30 characters fits a toast on a narrow phone.
        val snapshot = CacheSnapshot(
            state = CacheState.INTERRUPTED,
            bytesSaved = 128_400_000L,
            totalBytes = 3_050_000_000L,
            isEstimate = false,
            bytesPerSec = 0L,
            resumable = true,
            previewExpiresAt = null,
        )
        val state = CacheAffordance.uiStateFor(snapshot, keepRequested = true)
        val text = CacheAffordance.chipText(state)
        assertEquals("Keep interrupted — will resume", text)
        assertTrue(text.length <= 30)
    }
}
