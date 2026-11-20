package com.raddstream.app.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOffsetTest {

    @Test
    fun `clamp pins the offset to the same window the processor enforces`() {
        assertEquals(10_000L, AudioOffset.clamp(90_000L))
        assertEquals(-10_000L, AudioOffset.clamp(-90_000L))
        assertEquals(1_234L, AudioOffset.clamp(1_234L))
    }

    @Test
    fun `shift moves by the requested step and keeps the clamp`() {
        assertEquals(500L, AudioOffset.shift(0L, AudioOffset.COARSE_STEP_MS))
        assertEquals(-50L, AudioOffset.shift(0L, -AudioOffset.FINE_STEP_MS))
        assertEquals(10_000L, AudioOffset.shift(9_800L, AudioOffset.COARSE_STEP_MS))
    }

    @Test
    fun `reset returns to zero from either direction`() {
        assertEquals(0L, AudioOffset.reset())
    }

    @Test
    fun `quantize snaps slider noise to fifty milliseconds`() {
        assertEquals(150L, AudioOffset.quantize(149L))
        assertEquals(150L, AudioOffset.quantize(150L))
        assertEquals(-150L, AudioOffset.quantize(-149L))
        assertEquals(50L, AudioOffset.quantize(49L))
        assertEquals(-50L, AudioOffset.quantize(-49L))
    }

    @Test
    fun `format reads as a signed offset with the right precision`() {
        assertEquals("In sync", AudioOffset.format(0L))
        assertEquals("+0.1s", AudioOffset.format(100L))
        assertEquals("-0.1s", AudioOffset.format(-100L))
        assertEquals("+0.5s", AudioOffset.format(500L))
        assertEquals("+1.0s", AudioOffset.format(1_000L))
        assertEquals("-1.5s", AudioOffset.format(-1_500L))
        assertEquals("+10.0s", AudioOffset.format(10_000L))
        assertEquals("-0.05s", AudioOffset.format(-50L))
    }

    @Test
    fun `format never prints a sign the offset does not have`() {
        assertEquals("-10.0s", AudioOffset.format(-90_000L))
        assertEquals("+10.0s", AudioOffset.format(90_000L))
    }

    @Test
    fun `describe speaks in playback terms, not signs`() {
        assertEquals("Sound plays later than the picture", AudioOffset.describe(500L))
        assertEquals("Sound plays earlier than the picture", AudioOffset.describe(-500L))
        assertEquals("Sound and picture are aligned", AudioOffset.describe(0L))
    }

    @Test
    fun `the audio window matches the processor's queue cap`() {
        assertEquals(
            "the sheet and the processor must agree on the window, or the sheet offers offsets the processor clamps away",
            com.raddstream.app.player.audio.DelayAudioProcessor.RANGE_MS,
            AudioOffset.RANGE_MS,
        )
    }
}
