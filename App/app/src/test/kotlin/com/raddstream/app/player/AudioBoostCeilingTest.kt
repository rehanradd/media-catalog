package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MX idea #8 flipped this file's premise: the audio control offers 0..200% and this build
 * now carries the software-gain stage to reach past `Player.setVolume`'s 100% — the
 * enhancement processor in the sink chain applies the excess above unity. Every drag is
 * applied in full now, and the label reads the number the two sinks actually produce.
 *
 * The rule these tests pin is still one sentence — a control may only offer what this build
 * can apply — and [AudioBoost]'s 0..2 model is still intact, because that is the policy the
 * split ([AudioBoostUi.splitGain]) reads.
 */
class AudioBoostCeilingTest {

    @Test
    fun `with software gain the offered ceiling is the full model`() {
        assertTrue(AudioBoostUi.SOFTWARE_GAIN_AVAILABLE)
        assertEquals(AudioBoost.MAX, AudioBoostUi.MAX_OFFERED_LEVEL, 1e-6f)
        assertEquals(2f, AudioBoostUi.MAX_OFFERED_LEVEL, 1e-6f)
    }

    @Test
    fun `a request past the player ceiling is offered in full, not at the ceiling`() {
        assertEquals(1.8f, AudioBoostUi.offeredLevel(1.8f), 1e-6f)
        assertEquals("Volume 180%", AudioBoost.labelFor(AudioBoostUi.offeredLevel(1.8f)))
    }

    @Test
    fun `a stored 2.0 is offered in full now`() {
        // A level an older build stored above the old ceiling is a level
        // this build can honour — the ceiling it was reduced against is gone.
        assertEquals(2f, AudioBoostUi.offeredLevel(AudioBoost.MAX), 1e-6f)
    }

    @Test
    fun `every level the control can produce is applied in full at full system volume`() {
        val walk = (0..10).map { it / 10f }
        walk.forEach { level ->
            assertEquals(
                "requested $level must be what the two sinks apply together",
                AudioBoostUi.requestedPercent(level),
                AudioBoostUi.appliedTotalPercent(level, 1f),
            )
        }
        assertEquals(11, walk.size)
    }

    @Test
    fun `a level below the ceiling is still completely real`() {
        // At 60% system volume a 100% ask lands at 60% — the boost is headroom, not extra volume,
        // and honestSummary is what says so.
        assertEquals(60, AudioBoostUi.appliedPercent(1f, 0.6f))
    }

    @Test
    fun `the split keeps the player half inside its contract at every level`() {
        val walk = (0..20).map { it / 10f }
        walk.forEach { level ->
            val split = AudioBoostUi.splitGain(level, 1f)
            assertTrue("player half of $level must be legal", split.playerGain in 0f..1f)
            assertTrue("processor half of $level must be legal", split.dspGain in 1f..2f)
            assertEquals(
                "player x processor must be the requested total for $level",
                level,
                split.playerGain * split.dspGain,
                1e-6f,
            )
        }
        assertEquals(21, walk.size)
    }

    @Test
    fun `junk still collapses to the baseline rather than poisoning a device write`() {
        assertEquals(AudioBoost.BASELINE, AudioBoostUi.offeredLevel(Float.NaN), 1e-6f)
        assertEquals(AudioBoost.BASELINE, AudioBoostUi.offeredLevel(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(0f, AudioBoostUi.offeredLevel(-3f), 1e-6f)
    }

    /**
     * The policy object keeps modelling the excess. The split reads it — it never
     * re-derives it — so the day the gain stage changes, the rule changes in one place.
     */
    @Test
    fun `the split is at the seam, not inside the policy`() {
        assertEquals(2f, AudioBoost.MAX, 1e-6f)
        assertTrue(AudioBoost.plan(1.5f).boostActive)
        assertTrue(AudioBoost.plan(1.5f).gainDb > 0f)
        assertEquals(1f, AudioBoost.plan(1.5f).playerGain, 1e-6f)
        val split = AudioBoostUi.splitGain(1.5f, 1f)
        assertEquals(1f, split.playerGain, 1e-6f)
        assertEquals(1.5f, split.dspGain, 1e-6f)
    }
}
