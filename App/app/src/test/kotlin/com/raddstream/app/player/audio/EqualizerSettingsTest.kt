package com.raddstream.app.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the equalizer's pure rules (MX idea #8): presets, the
 * Custom transition, persistence round-trips, gain clamping, and the
 * player/DSP gain split. No Android, no Media3 — plain JVM.
 */
class EqualizerSettingsTest {

    @Test
    fun `every preset holds five gains inside the range`() {
        EqPresets.ALL.forEach { preset ->
            assertEquals(EQ_BAND_COUNT, preset.gainsDb.size)
            preset.gainsDb.forEach { gain ->
                assertTrue(gain in EQ_GAIN_MIN_DB..EQ_GAIN_MAX_DB)
            }
        }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), EqPresets.FLAT.gainsDb)
        assertEquals(listOf(6f, 4f, 1f, 0f, 0f), EqPresets.BASS_BOOST.gainsDb)
        assertEquals(listOf(0f, 0f, 1f, 4f, 6f), EqPresets.TREBLE_BOOST.gainsDb)
        assertEquals(listOf(-3f, 0f, 4f, 3f, -2f), EqPresets.VOCAL.gainsDb)
    }

    @Test
    fun `byId resolves every preset and nothing else`() {
        assertEquals(EqPresets.FLAT, EqPresets.byId("flat"))
        assertEquals(EqPresets.VOCAL, EqPresets.byId("vocal"))
        assertNull(EqPresets.byId("custom"))
        assertNull(EqPresets.byId("loudness"))
        assertNull(EqPresets.byId(""))
    }

    @Test
    fun `matching finds the preset or nothing`() {
        assertEquals(EqPresets.NORMAL, EqPresets.matching(listOf(1f, 0f, 0f, 0f, 1f)))
        assertNull(EqPresets.matching(listOf(1f, 0f, 0f, 0f, 0f)))
        assertNull(EqPresets.matching(listOf(0f, 0f, 0f, 0f, 0f, 0f)))
    }

    @Test
    fun `withPreset enables and names the preset`() {
        val state = EqState.DEFAULT.withPreset(EqPresets.BASS_BOOST)
        assertTrue(state.enabled)
        assertEquals("bass", state.presetId)
        assertEquals(listOf(6f, 4f, 1f, 0f, 0f), state.gainsDb)
    }

    @Test
    fun `a slider move off a preset becomes Custom`() {
        val state = EqState.DEFAULT.withPreset(EqPresets.FLAT).withBandGain(0, 1f)
        assertEquals(EqState.CUSTOM_ID, state.presetId)
        assertEquals(listOf(1f, 0f, 0f, 0f, 0f), state.gainsDb)
        // …unless the move lands exactly on another preset.
        val normal = EqState.DEFAULT.withPreset(EqPresets.FLAT)
            .withBandGain(0, 1f)
            .withBandGain(4, 1f)
        assertEquals("normal", normal.presetId)
    }

    @Test
    fun `band gains clamp to plus minus 12 dB and junk becomes silence`() {
        val state = EqState.DEFAULT.withBandGain(2, 13f).withBandGain(3, -20f)
        assertEquals(12f, state.gainsDb[2], 0f)
        assertEquals(-12f, state.gainsDb[3], 0f)
        assertEquals(0f, EqState.DEFAULT.withBandGain(0, Float.NaN).gainsDb[0], 0f)
        assertEquals(0f, clampEqGain(Float.POSITIVE_INFINITY), 0f)
        assertEquals(12f, clampEqGain(99f), 0f)
        assertEquals(-12f, clampEqGain(-99f), 0f)
    }

    @Test
    fun `serialize and parse round-trip for every preset on and off`() {
        (EqPresets.ALL + listOf(null)).forEach { preset ->
            listOf(true, false).forEach { on ->
                val state = if (preset == null) {
                    EqState(enabled = on, presetId = EqState.CUSTOM_ID, gainsDb = listOf(2f, -1f, 0f, 5f, -5f))
                } else {
                    EqState(enabled = on, presetId = preset.id, gainsDb = preset.gainsDb)
                }
                assertEquals(state, EqState.parse(state.serialize()))
                assertEquals(state.serialize(), EqState.parse(state.serialize()).serialize())
            }
        }
    }

    @Test
    fun `parse falls back to DEFAULT on anything it cannot read`() {
        listOf(null, "", "  ", "on", "on:flat", "on:flat:1,2", "on:flat:1,2,3,4,5,6", "maybe:flat:0,0,0,0,0", "on:flat:a,b,c,d,e").forEach { raw ->
            assertEquals("raw=$raw", EqState.DEFAULT, EqState.parse(raw))
        }
    }

    @Test
    fun `parse clamps out-of-range gains instead of rejecting the mix`() {
        val state = EqState.parse("on:flat:99,-99,0,0,0")
        assertEquals(listOf(12f, -12f, 0f, 0f, 0f), state.gainsDb)
        assertTrue(state.enabled)
    }

    @Test
    fun `parse resolves an unknown stored id from the gains`() {
        // A stored id the app never wrote: the gains are the truth.
        assertEquals("normal", EqState.parse("on:gone:1,0,0,0,1").presetId)
        assertEquals(EqState.CUSTOM_ID, EqState.parse("on:gone:1,0,0,0,0").presetId)
    }

    @Test
    fun `the split multiplies back to the requested total at every level`() {
        (0..20).map { it / 10f }.forEach { total ->
            val split = splitTotalGain(total)
            assertTrue("player half of $total", split.playerGain in 0f..1f)
            assertTrue("processor half of $total", split.dspGain in 1f..2f)
            assertEquals("product of $total", total, split.playerGain * split.dspGain, 1e-6f)
        }
    }

    @Test
    fun `the split is pure below unity and pure excess above it`() {
        assertEquals(GainSplit(0.6f, 1f), splitTotalGain(0.6f))
        assertEquals(GainSplit(1f, 1f), splitTotalGain(1f))
        assertEquals(GainSplit(1f, 1.5f), splitTotalGain(1.5f))
        assertEquals(GainSplit(1f, 2f), splitTotalGain(2f))
        assertEquals(GainSplit(0f, 1f), splitTotalGain(0f))
    }

    @Test
    fun `the split clamps junk and range`() {
        assertEquals(GainSplit(1f, 1f), splitTotalGain(Float.NaN))
        assertEquals(GainSplit(1f, 1f), splitTotalGain(Float.POSITIVE_INFINITY))
        assertEquals(GainSplit(0f, 1f), splitTotalGain(-1f))
        assertEquals(GainSplit(1f, 2f), splitTotalGain(3f))
    }

    @Test
    fun `the default state is off, flat and silent`() {
        assertFalse(EqState.DEFAULT.enabled)
        assertEquals("flat", EqState.DEFAULT.presetId)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), EqState.DEFAULT.gainsDb)
        assertEquals("off:flat:0.0,0.0,0.0,0.0,0.0", EqState.DEFAULT.serialize())
    }
}
