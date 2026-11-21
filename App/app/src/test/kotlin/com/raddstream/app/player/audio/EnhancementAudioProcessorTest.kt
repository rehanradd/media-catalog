package com.raddstream.app.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for the enhancement processor (MX idea #8). Media3
 * classes run under Robolectric — [com.raddstream.app.player.PlayerExtractorsTest]
 * proves that — but no player is built here: the processor is driven
 * directly through [AudioProcessor.configure] / `queueInput` / `output`,
 * exactly as the sink drives it.
 *
 * Byte order: the processor reads input bytes little-endian (the Android
 * native order — every real sink hands it native-order buffers), so the
 * test buffers are native-order too.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class EnhancementAudioProcessorTest {

    private val sampleRate = 48_000
    private val channels = 2

    private fun processor(
        encoding: Int = C.ENCODING_PCM_16BIT,
        settings: AudioEnhancementSettings = AudioEnhancementSettings.DEFAULT,
    ): EnhancementAudioProcessor {
        val processor = EnhancementAudioProcessor()
        processor.setSettings(settings)
        val outputFormat = processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, encoding))
        // The output encoding follows the input (Discover Pakistan, 2026-11-21):
        // 16-bit stays 16-bit so the sink's 16-bit-only SilenceSkippingAudioProcessor
        // can configure; float stays float; 24/32-bit int down-converts to 16-bit.
        val expectedEncoding = if (encoding == C.ENCODING_PCM_FLOAT) {
            C.ENCODING_PCM_FLOAT
        } else {
            C.ENCODING_PCM_16BIT
        }
        assertEquals(expectedEncoding, outputFormat.encoding)
        assertEquals(channels, outputFormat.channelCount)
        assertEquals(sampleRate, outputFormat.sampleRate)
        // configure() only STAGES the formats — BaseAudioProcessor applies
        // them in flush() (inputAudioFormat/outputAudioFormat are set
        // there, not in configure). The sink does this after every
        // configure; without it queueInput sees NOT_SET and consumes the
        // input without producing output. Same contract Sonic follows.
        processor.flush()
        assertTrue(processor.isActive)
        return processor
    }

    /** One stereo frame of 16-bit PCM, native order. */
    private fun pcm16Frame(left: Short, right: Short): ByteBuffer =
        ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).apply {
            putShort(left)
            putShort(right)
            flip()
        }

    /** The processor's output after one queueInput, as 16-bit shorts. */
    private fun drainShorts(processor: EnhancementAudioProcessor): ShortArray {
        val out = processor.output.order(ByteOrder.nativeOrder())
        val shorts = ShortArray(out.remaining() / 2)
        for (i in shorts.indices) shorts[i] = out.short
        return shorts
    }

    /** The processor's output after one queueInput, as floats. */
    private fun drainFloats(processor: EnhancementAudioProcessor): FloatArray {
        val out = processor.output.order(ByteOrder.nativeOrder())
        val floats = FloatArray(out.remaining() / 4)
        for (i in floats.indices) floats[i] = out.float
        return floats
    }

    private fun feedPcm16(processor: EnhancementAudioProcessor, samples: List<Pair<Short, Short>>): ShortArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { (left, right) ->
            buffer.putShort(left)
            buffer.putShort(right)
        }
        buffer.flip()
        processor.queueInput(buffer)
        return drainShorts(processor)
    }

    private fun feedFloats(processor: EnhancementAudioProcessor, samples: List<Pair<Float, Float>>): FloatArray {
        val buffer = ByteBuffer.allocate(samples.size * 8).order(ByteOrder.nativeOrder())
        samples.forEach { (left, right) ->
            buffer.putFloat(left)
            buffer.putFloat(right)
        }
        buffer.flip()
        processor.queueInput(buffer)
        return drainFloats(processor)
    }

    @Test
    fun `configure preserves the input encoding`() {
        // 16-bit in → 16-bit out (the helper asserts it); float in → float out.
        processor()
        processor(encoding = C.ENCODING_PCM_FLOAT)
    }

    @Test
    fun `24-bit input down-converts to 16-bit output`() {
        processor(encoding = C.ENCODING_PCM_24BIT)
    }

    @Test
    fun `unity gain and flat EQ pass the signal through`() {
        val processor = processor()
        // A 910 Hz sine at half scale, 10 ms. The chain is the identity
        // to float noise (see EqBiquadTest for why the delta is not zero:
        // float addition is not associative, and the low shelf holds a
        // few ULPs for a few hundred samples) — plus the 16-bit round-trip
        // (short → float → short scales by 32767/32768). Tolerance is the
        // old float tolerance (1e-4) in LSBs, plus 1 LSB of quantization.
        val samples = (0 until 480).map { n ->
            val s = (0.5 * 32767.0 * sin(2.0 * PI * 910.0 * n / sampleRate)).toInt().toShort()
            s to s
        }
        val out = feedPcm16(processor, samples)
        assertEquals(samples.size * 2, out.size)
        samples.forEachIndexed { i, (s, _) ->
            assertEquals("sample $i", s.toLong(), out[i * 2].toLong(), 4L)
            assertEquals("sample $i right", s.toLong(), out[i * 2 + 1].toLong(), 4L)
        }
    }

    @Test
    fun `gain 2 on a half-scale sine gives full scale`() {
        val processor = processor(settings = AudioEnhancementSettings(dspGain = 2f))
        val samples = (0 until 480).map { n ->
            val s = (0.5 * 32767.0 * sin(2.0 * PI * 910.0 * n / sampleRate)).toInt().toShort()
            s to s
        }
        val out = feedPcm16(processor, samples)
        // Tolerance is the old float tolerance (1e-3) in LSBs, plus 1 LSB
        // of quantization — the gain assertion still pins the signal
        // (it would catch a missing gain, off by ~16,000 LSB).
        samples.forEachIndexed { i, (s, _) ->
            val expected = ((s / 32768f * 2f).coerceIn(-1f, 1f) * 32767f).toInt()
            assertEquals("sample $i", expected.toLong(), out[i * 2].toLong(), 40L)
        }
    }

    @Test
    fun `soft clip compresses instead of hard-clipping at full scale`() {
        val processor = processor(
            encoding = C.ENCODING_PCM_FLOAT,
            settings = AudioEnhancementSettings(dspGain = 2f),
        )
        // 0.9 at 200% would be 1.8 linear; the soft clip gives 1.444… —
        // louder than full scale, not a square wave at 1.0.
        val out = feedFloats(processor, listOf(0.9f to 0.9f, -0.9f to -0.9f))
        assertEquals(1.4444444f, out[0], 1e-4f)
        assertEquals(1.4444444f, out[1], 1e-4f)
        assertEquals(-1.4444444f, out[2], 1e-4f)
        assertEquals(-1.4444444f, out[3], 1e-4f)
    }

    @Test
    fun `plus 6 dB at the band centre doubles the tone`() {
        val eq = EqState(enabled = true, presetId = EqState.CUSTOM_ID, gainsDb = listOf(0f, 0f, 6f, 0f, 0f))
        // Float in: the samples below are floats, so the processor must be
        // configured for float too — a 16-bit configure would read each
        // float's bytes as two 16-bit samples (that was the 1.78× failure).
        val processor = processor(
            encoding = C.ENCODING_PCM_FLOAT,
            settings = AudioEnhancementSettings(dspGain = 1f, eq = eq),
        )
        // 100 ms of a 910 Hz sine; the filter settles in the first few ms,
        // so the RMS of the second half measures the steady-state gain.
        val samples = (0 until 4_800).map { n ->
            val v = (0.5 * sin(2.0 * PI * 910.0 * n / sampleRate)).toFloat()
            v to v
        }
        val out = feedFloats(processor, samples)
        fun rms(values: FloatArray, from: Int): Float {
            var sum = 0.0
            for (i in from until values.size) sum += values[i] * values[i]
            return sqrt(sum / (values.size - from)).toFloat()
        }
        val inRms = rms(samples.flatMap { listOf(it.first, it.second) }.toFloatArray(), 4_800)
        val outRms = rms(out, 4_800)
        assertEquals("910 Hz at +6 dB", inRms * 2f, outRms, inRms * 0.15f)
    }

    @Test
    fun `a partial frame is carried to the next call`() {
        val processor = processor()
        // Stereo 16-bit is 4 bytes a frame. Feed 1½ frames: one whole
        // frame comes out, the half frame waits in the stash.
        val first = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder()).apply {
            putShort((0.25f * 32767).toInt().toShort())
            putShort((-0.25f * 32767).toInt().toShort())
            putShort((0.5f * 32767).toInt().toShort())
            flip()
        }
        processor.queueInput(first)
        val out1 = drainShorts(processor)
        assertEquals(2, out1.size)
        // Tolerance is the old float tolerance (1e-4) in LSBs, plus 1 LSB.
        assertEquals((0.25f * 32767).toLong(), out1[0].toLong(), 4L)
        assertEquals((-0.25f * 32767).toLong(), out1[1].toLong(), 4L)
        // The remaining 2 bytes complete the second frame.
        val second = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).apply {
            putShort((-0.5f * 32767).toInt().toShort())
            flip()
        }
        processor.queueInput(second)
        val out2 = drainShorts(processor)
        assertEquals(2, out2.size)
        assertEquals((0.5f * 32767).toLong(), out2[0].toLong(), 4L)
        assertEquals((-0.5f * 32767).toLong(), out2[1].toLong(), 4L)
    }

    @Test
    fun `flush clears the filter state and drops the stash`() {
        val processor = processor()
        feedPcm16(processor, List(100) { 32767.toShort() to 32767.toShort() })
        processor.flush()
        // After a seek the old state is a thump, not continuity: silence
        // in must be silence out, from the very first sample.
        val out = feedPcm16(processor, listOf(0.toShort() to 0.toShort()))
        assertEquals(0L, out[0].toLong())
        assertEquals(0L, out[1].toLong())
    }

    @Test
    fun `settings apply live without a reconfigure`() {
        val processor = processor()
        feedPcm16(processor, listOf(16384.toShort() to 16384.toShort()))
        processor.setSettings(AudioEnhancementSettings(dspGain = 2f))
        val out = feedPcm16(processor, listOf(16384.toShort() to 16384.toShort()))
        // Half scale at 200% is full scale: 16384/32768 * 2 = 1.0 → 32767.
        assertEquals(32767L, out[0].toLong(), 2L)
        assertEquals(32767L, out[1].toLong(), 2L)
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun `an unsupported encoding is refused at configure`() {
        EnhancementAudioProcessor().configure(
            AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_8BIT),
        )
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun `a zero channel count is refused at configure`() {
        EnhancementAudioProcessor().configure(
            AudioProcessor.AudioFormat(sampleRate, 0, C.ENCODING_PCM_16BIT),
        )
    }

    @Test
    fun `an unconfigured processor consumes input instead of stalling`() {
        val processor = EnhancementAudioProcessor()
        val buffer = pcm16Frame(1000, 1000)
        processor.queueInput(buffer)
        assertEquals(4, buffer.position())
        assertEquals(0, processor.output.remaining())
    }
}
