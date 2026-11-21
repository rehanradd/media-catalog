package com.raddstream.app.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sink chain as `DefaultAudioSink` builds it (Media3 1.3.1): float
 * output is OFF in this app (the factory default — the app never calls
 * `setEnableAudioFloatOutput`), so the sink always uses the int pipeline
 * `[ToInt16, ChannelMapping, Trimming, <app chain>, SilenceSkipping,
 * Sonic]`, with our processors injected as the app chain.
 *
 * Regression test for Discover Pakistan (Robo v11, 2026-11-20): the
 * FFmpeg audio renderer decoded the AAC stream to float PCM, and the old
 * `EnhancementAudioProcessor` always returned float output — so the
 * 16-bit-only `SilenceSkippingAudioProcessor` threw
 * `UnhandledAudioFormatException` at sink configure time and every
 * audio-bearing title failed with `ERROR_CODE_AUDIO_TRACK_INIT_FAILED`,
 * on every device. These tests pin that the pipeline configures cleanly
 * for both decoder paths and stays 16-bit end to end.
 *
 * The sink's full int pipeline is `[ToInt16, ChannelMapping, Trimming,
 * Enhancement, Delay, SilenceSkipping, Sonic]` — ChannelMapping and
 * Trimming are omitted here because they are package-private in Media3
 * 1.3.1 and not instantiable from a test; both pass stereo through
 * unchanged (no channel map set, no trim), so the chain below is the
 * faithful one for the stereo case.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class AudioSinkChainTest {

    private val sampleRate = 48_000
    private val channels = 2

    /** The sink's int pipeline, in the sink's order (see the class doc). */
    private fun sinkPipeline(): AudioProcessingPipeline {
        val enhancement = EnhancementAudioProcessor()
        enhancement.setSettings(AudioEnhancementSettings.DEFAULT)
        return AudioProcessingPipeline(
            ImmutableList.of(
                ToInt16PcmAudioProcessor(),
                enhancement,
                DelayAudioProcessor(),
                SilenceSkippingAudioProcessor(),
                SonicAudioProcessor(),
            ),
        )
    }

    @Test
    fun `float input from the FFmpeg decoder configures to 16-bit output`() {
        val pipeline = sinkPipeline()
        val output = pipeline.configure(
            AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT),
        )
        assertEquals(C.ENCODING_PCM_16BIT, output.encoding)
        assertEquals(sampleRate, output.sampleRate)
        assertEquals(channels, output.channelCount)
        pipeline.flush()
        assertTrue(pipeline.isOperational)
        // 10 frames of float stereo in → 10 frames of 16-bit stereo out.
        val input = ByteBuffer.allocate(10 * channels * 4).order(ByteOrder.nativeOrder())
        repeat(10 * channels) { input.putFloat(0.25f) }
        input.flip()
        pipeline.queueInput(input)
        val out = pipeline.output
        assertEquals(10 * channels * 2, out.remaining())
    }

    @Test
    fun `16-bit input from the platform decoder configures to 16-bit output`() {
        val pipeline = sinkPipeline()
        val output = pipeline.configure(
            AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT),
        )
        assertEquals(C.ENCODING_PCM_16BIT, output.encoding)
        pipeline.flush()
        assertTrue(pipeline.isOperational)
        val input = ByteBuffer.allocate(10 * channels * 2).order(ByteOrder.nativeOrder())
        repeat(10 * channels) { input.putShort(8192) }
        input.flip()
        pipeline.queueInput(input)
        val out = pipeline.output
        assertEquals(10 * channels * 2, out.remaining())
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun `the silence skipper itself refuses float input`() {
        // The hazard these tests guard: SilenceSkippingAudioProcessor is
        // 16-bit PCM only and throws at configure time even when
        // skip-silence is disabled (the app never enables it). Any
        // processor upstream of it that outputs float breaks the sink.
        SilenceSkippingAudioProcessor().configure(
            AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT),
        )
    }
}
