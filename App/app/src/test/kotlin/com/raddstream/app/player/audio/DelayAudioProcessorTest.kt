package com.raddstream.app.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for the delay processor (REA idea #3). Media3
 * classes run under Robolectric — [com.raddstream.app.player.PlayerExtractorsTest]
 * proves that — but no player is built here: the processor is driven
 * directly through [AudioProcessor.configure] / `queueInput` / `output`,
 * exactly as the sink drives it.
 *
 * Byte order: the processor moves raw bytes, so the test buffers are
 * native-order 16-bit stereo PCM (4 bytes a frame). Each frame carries
 * its own index as both samples, so a dropped, held or silenced frame
 * reads as exactly what it is.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class DelayAudioProcessorTest {

    private val sampleRate = 48_000
    private val channels = 2
    private val bytesPerFrame = 4

    /** 10 ms at 48 kHz = 480 frames — small enough to hold in a test, big enough to see. */
    private val tenMsFrames = 480

    private fun processor(delayMs: Long = 0L): DelayAudioProcessor {
        val processor = DelayAudioProcessor()
        processor.setDelayMs(delayMs)
        val outputFormat = processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        // Passthrough format: the delay moves bytes, it never resamples.
        assertEquals(C.ENCODING_PCM_16BIT, outputFormat.encoding)
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

    /** [count] frames numbered from [first], left == right == the frame's index. */
    private fun frames(first: Int, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(count * bytesPerFrame).order(ByteOrder.nativeOrder())
        for (i in first until first + count) {
            buffer.putShort(i.toShort())
            buffer.putShort(i.toShort())
        }
        buffer.flip()
        return buffer
    }

    /** Every frame the processor has emitted since the last drain, as its index (or -1 for silence). */
    private fun drainFrames(processor: DelayAudioProcessor): List<Int> {
        val out = processor.output.order(ByteOrder.nativeOrder())
        val frames = mutableListOf<Int>()
        while (out.remaining() >= bytesPerFrame) {
            val left = out.short.toInt()
            val right = out.short.toInt()
            assertEquals("the two channels of one frame stay together", left, right)
            frames += if (left == 0 && right == 0) -1 else left
        }
        return frames
    }

    @Test
    fun `zero delay is a byte-exact passthrough`() {
        val processor = processor(delayMs = 0L)
        processor.queueInput(frames(first = 1, count = 100))
        assertEquals((1..100).toList(), drainFrames(processor))
    }

    @Test
    fun `a positive delay holds the audio back by the delay and pads the start with silence`() {
        val processor = processor(delayMs = 10L)
        processor.queueInput(frames(first = 1, count = tenMsFrames))
        // The first delay of audio has not played yet: silence instead.
        assertEquals(List(tenMsFrames) { -1 }, drainFrames(processor))
        processor.queueInput(frames(first = tenMsFrames + 1, count = tenMsFrames))
        // The line is full now: the first block plays, the second waits.
        assertEquals((1..tenMsFrames).toList(), drainFrames(processor))
    }

    @Test
    fun `a negative delay drops the head of the audio`() {
        val processor = processor(delayMs = -10L)
        processor.queueInput(frames(first = 1, count = tenMsFrames))
        // The whole first block is the dropped head: nothing comes out.
        assertEquals(emptyList<Int>(), drainFrames(processor))
        processor.queueInput(frames(first = tenMsFrames + 1, count = tenMsFrames))
        assertEquals((tenMsFrames + 1..2 * tenMsFrames).toList(), drainFrames(processor))
    }

    @Test
    fun `end of stream flushes the held tail`() {
        val processor = processor(delayMs = 10L)
        processor.queueInput(frames(first = 1, count = tenMsFrames))
        assertEquals(List(tenMsFrames) { -1 }, drainFrames(processor))
        processor.queueEndOfStream()
        // The held block still has to play before the stream may end.
        val tail = drainFrames(processor)
        assertEquals((1..tenMsFrames).toList(), tail)
        assertTrue("isEnded only once the tail has been read", processor.isEnded)
    }

    @Test
    fun `a delay change applies live on the next buffer`() {
        val processor = processor(delayMs = 0L)
        processor.queueInput(frames(first = 1, count = 100))
        assertEquals((1..100).toList(), drainFrames(processor))
        processor.setDelayMs(10L)
        processor.queueInput(frames(first = 101, count = tenMsFrames))
        // The line refills from empty: silence until it is deep enough.
        assertEquals(List(tenMsFrames) { -1 }, drainFrames(processor))
        processor.queueInput(frames(first = 101 + tenMsFrames, count = tenMsFrames))
        assertEquals((101..101 + tenMsFrames - 1).toList(), drainFrames(processor))
    }

    @Test
    fun `a flush re-establishes the delay from scratch`() {
        val processor = processor(delayMs = 10L)
        processor.queueInput(frames(first = 1, count = tenMsFrames))
        assertEquals(List(tenMsFrames) { -1 }, drainFrames(processor))
        processor.flush()
        processor.queueInput(frames(first = 1, count = tenMsFrames))
        // After a seek the line is empty again: silence first, as at the start.
        assertEquals(List(tenMsFrames) { -1 }, drainFrames(processor))
    }

    @Test
    fun `the delay clamps to its window`() {
        val processor = processor(delayMs = 0L)
        processor.setDelayMs(60_000L)
        assertEquals(DelayAudioProcessor.RANGE_MS, processor.delayMs)
        processor.setDelayMs(-60_000L)
        assertEquals(-DelayAudioProcessor.RANGE_MS, processor.delayMs)
    }
}
