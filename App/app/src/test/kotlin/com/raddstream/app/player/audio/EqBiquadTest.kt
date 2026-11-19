package com.raddstream.app.player.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the RBJ biquad math (MX idea #8). The design functions are
 * pure — no Android, no Media3 — so these run on the plain JVM.
 *
 * The property under test is the cookbook itself: a +N dB section really is
 * +N dB at its centre, a shelf really is flat on its far side, and a 0 dB
 * section is the identity — because the processor stays in the chain even
 * when the feature is off, and an untouched player must sound untouched.
 */
class EqBiquadTest {

    private val sampleRate = 48_000

    @Test
    fun `a 0 dB peaking section is the identity`() {
        val section = Biquad(BiquadDesign.peaking(910f, PEAKING_Q, 0f, sampleRate))
        // A sine through the section: the output tracks the input to float
        // noise. The delta is not zero on purpose — float addition is not
        // associative, so (x + p) − p is a few ULPs off x, and the low
        // shelf's poles sit close enough to the unit circle to hold that
        // noise for a few hundred samples. 1e-4 is ~3 LSBs of 16-bit.
        for (n in 0 until 4_800) {
            val x = (0.7 * sin(2.0 * PI * 910.0 * n / sampleRate)).toFloat()
            assertEquals("sample $n", x, section.process(x), 1e-4f)
        }
    }

    @Test
    fun `a 0 dB chain is the identity`() {
        val chain = EqChain(EQ_BAND_COUNT, 2)
        chain.configure(EQ_BANDS, EqPresets.FLAT.gainsDb, sampleRate)
        val frame = floatArrayOf(0.5f, -0.25f)
        repeat(1_000) {
            chain.processFrame(frame)
            assertEquals(0.5f, frame[0], 1e-4f)
            assertEquals(-0.25f, frame[1], 1e-4f)
        }
    }

    @Test
    fun `plus 6 dB peaking is plus 6 dB at its centre`() {
        val coeffs = BiquadDesign.peaking(910f, PEAKING_Q, 6f, sampleRate)
        assertEquals(6f, BiquadDesign.magnitudeDbAt(coeffs, 910f, sampleRate), 0.05f)
    }

    @Test
    fun `a peaking cut mirrors its boost`() {
        val boost = BiquadDesign.peaking(910f, PEAKING_Q, 6f, sampleRate)
        val cut = BiquadDesign.peaking(910f, PEAKING_Q, -6f, sampleRate)
        assertEquals(-6f, BiquadDesign.magnitudeDbAt(cut, 910f, sampleRate), 0.05f)
        // Boost followed by cut at the same Q and frequency is precisely
        // flat — the cookbook's EE definition of Q.
        assertEquals(
            0f,
            BiquadDesign.magnitudeDbAt(boost, 910f, sampleRate) +
                BiquadDesign.magnitudeDbAt(cut, 910f, sampleRate),
            0.1f,
        )
    }

    @Test
    fun `a low shelf is flat on its far side and full on its near side`() {
        val coeffs = BiquadDesign.lowShelf(60f, 6f, sampleRate)
        assertEquals(6f, BiquadDesign.magnitudeDbAt(coeffs, 10f, sampleRate), 0.5f)
        assertEquals(0f, BiquadDesign.magnitudeDbAt(coeffs, 10_000f, sampleRate), 0.1f)
    }

    @Test
    fun `a high shelf mirrors the low shelf`() {
        val coeffs = BiquadDesign.highShelf(14_000f, 6f, sampleRate)
        assertEquals(6f, BiquadDesign.magnitudeDbAt(coeffs, 20_000f, sampleRate), 0.5f)
        assertEquals(0f, BiquadDesign.magnitudeDbAt(coeffs, 100f, sampleRate), 0.1f)
    }

    @Test
    fun `a hot chain stays finite and bounded`() {
        val chain = EqChain(EQ_BAND_COUNT, 2)
        chain.configure(EQ_BANDS, listOf(12f, 12f, 12f, 12f, 12f), sampleRate)
        val frame = FloatArray(2)
        repeat(48_000) { n ->
            frame[0] = (0.9 * sin(2.0 * PI * 1_000.0 * n / sampleRate)).toFloat()
            frame[1] = frame[0]
            chain.processFrame(frame)
            assertTrue("left $n", frame[0].isFinite())
            assertTrue("right $n", frame[1].isFinite())
            assertTrue("left bounded $n", kotlin.math.abs(frame[0]) < 100f)
            assertTrue("right bounded $n", kotlin.math.abs(frame[1]) < 100f)
        }
    }

    @Test
    fun `reset clears the state`() {
        val section = Biquad(BiquadDesign.peaking(910f, PEAKING_Q, 6f, sampleRate))
        repeat(100) { section.process(1f) }
        // Ringing: a zero input still produces output from the stored state.
        assertTrue(section.process(0f) != 0f)
        section.reset()
        assertEquals(0f, section.process(0f), 0f)
        assertEquals(0f, section.process(0f), 0f)
    }

    @Test
    fun `setCoefficients keeps the state`() {
        val section = Biquad(BiquadDesign.peaking(910f, PEAKING_Q, 6f, sampleRate))
        repeat(100) { section.process(1f) }
        val ringing = section.process(0f)
        section.setCoefficients(BiquadDesign.peaking(910f, PEAKING_Q, 0f, sampleRate))
        // Same state, flat coefficients: the ringing decays through the
        // identity instead of restarting from silence.
        assertTrue(section.process(0f) != 0f)
        assertTrue(ringing != 0f)
    }

    @Test
    fun `soft clip is the identity up to unity and compresses past it`() {
        assertEquals(0.5f, softClip(0.5f), 0f)
        assertEquals(-1f, softClip(-1f), 0f)
        assertEquals(1f, softClip(1f), 0f)
        // 0.9 at 200% would be 1.8 linear; the soft clip gives
        // 1 + 0.8/1.8 = 1.444… — louder, not hard-clipped at 1.0.
        assertEquals(1.4444444f, softClip(1.8f), 1e-6f)
        assertEquals(-1.4444444f, softClip(-1.8f), 1e-6f)
        // Monotonic, and bounded by the ±2.0 asymptote.
        assertTrue(softClip(1.5f) < softClip(2f))
        assertTrue(softClip(2f) < 2f)
        assertTrue(softClip(100f) > 1.9f)
        assertTrue(softClip(100f) < 2f)
    }
}
