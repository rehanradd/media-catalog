package com.raddstream.app.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order (biquad) section, Robert Bristow-Johnson Audio-EQ-Cookbook
 * formulae (public-domain, the same ones FFmpeg `af_biquads`, Web Audio and
 * JUCE use). Coefficients are normalized so a0 == 1 and the section runs the
 * cookbook's Direct Form 1:
 *
 *   y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] − a1·y[n-1] − a2·y[n-2]
 *
 * A 0 dB section is the identity in exact float arithmetic (its b and a
 * coefficients are bit-identical), so a flat equalizer changes nothing —
 * [EqBiquadTest] pins that, because the processor stays in the chain even
 * when the feature is off.
 */
data class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float,
)

/** Pure coefficient design: no state, no Android, unit-testable on the JVM. */
object BiquadDesign {

    /**
     * Peaking (parametric) section centred on [frequencyHz], Q [q], [gainDb].
     * Q is the EE definition the cookbook adjusts so that a +N dB boost
     * followed by a −N dB cut at the same Q and frequency is precisely flat.
     */
    fun peaking(frequencyHz: Float, q: Float, gainDb: Float, sampleRateHz: Int): BiquadCoefficients {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequencyHz / sampleRateHz
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha / a
        return BiquadCoefficients(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (a1 / a0).toFloat(),
            (a2 / a0).toFloat(),
        )
    }

    /**
     * Low shelf with the cookbook's shelf slope S = 1 — the steepest slope
     * that stays monotonic. Below [frequencyHz] the gain tends to [gainDb];
     * above it, to 0 dB.
     */
    fun lowShelf(frequencyHz: Float, gainDb: Float, sampleRateHz: Int): BiquadCoefficients {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequencyHz / sampleRateHz
        val alpha = shelfAlpha(w0)
        val cosW0 = cos(w0)
        val sqrtA = sqrt(a)
        val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val a2 = (a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha
        return BiquadCoefficients(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (a1 / a0).toFloat(),
            (a2 / a0).toFloat(),
        )
    }

    /** High shelf, the mirror of [lowShelf]: above [frequencyHz] the gain tends to [gainDb]. */
    fun highShelf(frequencyHz: Float, gainDb: Float, sampleRateHz: Int): BiquadCoefficients {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequencyHz / sampleRateHz
        val alpha = shelfAlpha(w0)
        val cosW0 = cos(w0)
        val sqrtA = sqrt(a)
        val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha)
        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * alpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
        val a2 = (a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * alpha
        return BiquadCoefficients(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (a1 / a0).toFloat(),
            (a2 / a0).toFloat(),
        )
    }

    /**
     * Shelf alpha for S = 1: (1/S − 1) is 0, so the cookbook's
     * `sin(w0)/2 · sqrt((A + 1/A)(1/S − 1) + 2)` reduces to `sin(w0)/2 · sqrt(2)`.
     */
    private fun shelfAlpha(w0: Double): Double =
        sin(w0) / 2.0 * sqrt(2.0)

    /**
     * |H(e^jw)| at [frequencyHz] in dB, from the transfer function — the
     * property the tests assert (a +6 dB peaking section really is +6 dB at
     * its centre, a shelf really is flat on its far side).
     */
    fun magnitudeDbAt(coeffs: BiquadCoefficients, frequencyHz: Float, sampleRateHz: Int): Float {
        val w = 2.0 * PI * frequencyHz / sampleRateHz
        val cosW = cos(w)
        val cos2W = cos(2.0 * w)
        val b0 = coeffs.b0.toDouble()
        val b1 = coeffs.b1.toDouble()
        val b2 = coeffs.b2.toDouble()
        val a1 = coeffs.a1.toDouble()
        val a2 = coeffs.a2.toDouble()
        // |H|² = (b0² + b1² + b2² + 2·b0·b1·cos w + 2·b1·b2·cos w + 2·b0·b2·cos 2w)
        //       / (1 + a1² + a2² + 2·a1·cos w + 2·a1·a2·cos w + 2·a2·cos 2w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 +
            2.0 * (b0 * b1 + b1 * b2) * cosW + 2.0 * b0 * b2 * cos2W
        val den = 1.0 + a1 * a1 + a2 * a2 +
            2.0 * (a1 + a1 * a2) * cosW + 2.0 * a2 * cos2W
        return (10.0 * ln(num / den) / ln(10.0)).toFloat()
    }
}

/**
 * One stateful biquad section. Coefficients may be replaced live
 * ([setCoefficients] keeps the state — the standard practice when a slider
 * moves, so the sound does not click); [reset] clears the state, which the
 * processor calls on flush (a seek discontinues the stream).
 */
class Biquad(coefficients: BiquadCoefficients = IDENTITY) {

    private var coeffs: BiquadCoefficients = coefficients
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun setCoefficients(next: BiquadCoefficients) {
        coeffs = next
    }

    fun process(x: Float): Float {
        val c = coeffs
        val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    companion object {
        /** 0 dB everywhere: b == a, so [process] returns its input exactly. */
        val IDENTITY = BiquadCoefficients(1f, 0f, 0f, 0f, 0f)
    }
}

/**
 * The whole equalizer as a cascade: one [Biquad] per band per channel.
 * Created once per channel count; [configure] rebuilds the coefficients when
 * the sample rate or the gains change, keeping the state.
 */
class EqChain(
    private val bandCount: Int,
    private val channelCount: Int,
) {
    private val sections: Array<Array<Biquad>> =
        Array(bandCount) { Array(channelCount) { Biquad() } }

    fun configure(
        bands: List<EqBand>,
        gainsDb: List<Float>,
        sampleRateHz: Int,
    ) {
        require(bands.size == bandCount && gainsDb.size == bandCount) {
            "EQ chain holds $bandCount bands"
        }
        bands.forEachIndexed { band, spec ->
            val coeffs = when (spec.type) {
                EqBandType.LOW_SHELF -> BiquadDesign.lowShelf(spec.frequencyHz.toFloat(), gainsDb[band], sampleRateHz)
                EqBandType.PEAKING -> BiquadDesign.peaking(spec.frequencyHz.toFloat(), PEAKING_Q, gainsDb[band], sampleRateHz)
                EqBandType.HIGH_SHELF -> BiquadDesign.highShelf(spec.frequencyHz.toFloat(), gainsDb[band], sampleRateHz)
            }
            for (ch in 0 until channelCount) sections[band][ch].setCoefficients(coeffs)
        }
    }

    /** One interleaved frame, processed in place. */
    fun processFrame(frame: FloatArray) {
        for (ch in 0 until channelCount) {
            var sample = frame[ch]
            for (band in 0 until bandCount) sample = sections[band][ch].process(sample)
            frame[ch] = sample
        }
    }

    fun reset() {
        sections.forEach { band -> band.forEach { it.reset() } }
    }
}

/**
 * Soft clip: the identity up to ±1.0, then a rational curve that is
 * continuous at 1.0 with slope 1 and approaches ±2.0 asymptotically. A 200%
 * boost on an already-loud master compresses instead of hard-clipping —
 * the harsh digital clipping Sonic's `scaleSamples` produces is exactly
 * what this avoids. It cannot create headroom that is not there: a signal
 * already at full scale still gets louder and denser, never cleaner.
 */
fun softClip(x: Float): Float {
    val ax = kotlin.math.abs(x)
    if (ax <= 1f) return x
    val e = ax - 1f
    val compressed = 1f + e / (1f + e)
    return if (x < 0f) -compressed else compressed
}
