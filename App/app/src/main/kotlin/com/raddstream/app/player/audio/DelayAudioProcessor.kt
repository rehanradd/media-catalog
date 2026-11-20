package com.raddstream.app.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * REA #3 (VLC, Nov 2026): the audio delay stage — the sound shifted
 * against the picture by milliseconds, for out-of-sync host files.
 *
 * A delay line, not a resampler: output[t] = input[t - delay]. A
 * POSITIVE delay holds the audio back (the picture runs ahead of the
 * sound); a NEGATIVE delay drops the head of the audio (the sound runs
 * ahead). The sign matches the subtitle convention
 * ([com.raddstream.app.player.SubtitleUiSettings.delayMs]): positive =
 * later. Zero is a byte-exact passthrough.
 *
 * Why a processor and not `PlaybackParameters`: that only changes speed
 * and pitch — it cannot shift the audio against the video clock. This
 * processor sits in the `DefaultAudioSink` chain behind
 * [EnhancementAudioProcessor] (injected by
 * [com.raddstream.app.player.PlaybackService] through a
 * `buildAudioSink` override), so it sees the decoded PCM of this
 * playback only, on every device, with no permission and no new
 * dependency.
 *
 * Encoding-agnostic on purpose: it moves whole frames of raw bytes, so
 * 16/24/32-bit int and float all pass through unchanged — the format
 * never changes, and a delay move applies live on the next buffer with
 * no reconfigure, no restart, no rebuild. The queue holds at most the
 * delay window plus one input buffer (10 s of 48 kHz stereo float is
 * ~3.8 MB).
 *
 * Always active, even at zero delay: the sink decides the chain at
 * configure time, so a processor that only activates on a nonzero delay
 * would need a seek or a rebuild before a mid-playback slider move took
 * effect. At zero the output is the input byte for byte.
 *
 * Threading: the playback thread owns `queueInput`/`onConfigure`/
 * `onFlush` (and therefore the queue); the UI thread owns [setDelayMs],
 * which writes only a `@Volatile` field the audio thread reads. A delay
 * change retunes the line on the next queued buffer. Going more
 * negative skips the next frames as they arrive; coming back from a
 * negative delay holds the line by the overshoot instead — the skipped
 * audio cannot be un-skipped, so the hold is what brings the offset
 * back. Either way the sync error decays smoothly instead of clicking
 * or skipping.
 *
 * End of stream: the held tail is flushed into the output buffer in
 * [onQueueEndOfStream], so the last `delayMs` of audio still plays —
 * the sink drains it through repeated `getOutput` calls before
 * `isEnded` answers true.
 */
@OptIn(UnstableApi::class) // androidx.annotation.OptIn on purpose:
// UnstableApi is not a Kotlin RequiresOptIn marker, so Kotlin's own
// @OptIn is ignored (compiler warning) and lint then flags every Media3
// use below as UnsafeOptInUsageError. The androidx form is the one lint
// reads — same as PlaybackService.
class DelayAudioProcessor : BaseAudioProcessor() {

    /** Live update from the service; takes effect on the next queued buffer. */
    @Volatile
    var delayMs: Long = 0L
        private set

    fun setDelayMs(ms: Long) {
        delayMs = ms.coerceIn(-RANGE_MS, RANGE_MS)
    }

    /** Bytes of a trailing partial frame carried to the next [queueInput]. */
    private var stash: ByteArray = ByteArray(0)
    private var stashLength: Int = 0

    /** The delay line: raw audio frames waiting for their turn to play. */
    private var queue: ByteArray = ByteArray(0)
    private var queueRead: Int = 0
    private var queueWrite: Int = 0

    /** Frames skipped since the last flush, for a negative delay. */
    private var skippedTotal: Long = 0

    private fun bytesPerFrame(): Int = inputAudioFormat.bytesPerFrame

    private fun delayFrames(): Long {
        val format = inputAudioFormat
        if (format.sampleRate <= 0) return 0L
        return delayMs * format.sampleRate / 1000L
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT &&
            encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_32BIT &&
            encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        stashLength = 0
        queueRead = 0
        queueWrite = 0
        skippedTotal = 0
        // Passthrough format: the delay moves bytes, it never resamples.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = bytesPerFrame()
        if (bytesPerFrame <= 0) {
            // Unconfigured: nothing to process. Consume the input rather
            // than stalling the sink on bytes nobody will ever read.
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val inputRemaining = inputBuffer.remaining()
        val available = stashLength + inputRemaining
        val wholeFrames = available / bytesPerFrame
        if (wholeFrames == 0) {
            // Not even one frame yet: carry everything to the next call.
            appendToStash(inputBuffer, inputRemaining)
            return
        }
        val consumedBytes = wholeFrames * bytesPerFrame
        val consumedFromInput = consumedBytes - stashLength

        // Push this call's whole frames onto the delay line.
        ensureQueueCapacity(queueWrite + consumedBytes)
        var streamPos = 0
        repeat(consumedBytes) {
            queue[queueWrite++] = nextByte(inputBuffer, streamPos++)
        }
        inputBuffer.position(inputBuffer.position() + consumedFromInput)
        stashLength = 0
        appendToStash(inputBuffer, available - consumedBytes)

        val delay = delayFrames()
        // A negative delay skips the head of the line: the sound runs
        // ahead of the picture by exactly the frames never played.
        // Skipping is one-way — if the viewer dials back toward zero,
        // the overshoot is held instead (see effectiveHold below).
        val skipTarget = max(0L, -delay)
        var droppedThisCall = 0L
        if (skippedTotal < skipTarget) {
            val queuedFrames = (queueWrite - queueRead) / bytesPerFrame
            val dropFrames = minOf(skipTarget - skippedTotal, queuedFrames.toLong())
            queueRead += (dropFrames * bytesPerFrame).toInt()
            skippedTotal += dropFrames
            droppedThisCall = dropFrames
        }

        // The hold depth is the positive delay plus any overshoot from a
        // negative delay that has since been dialled back: skipped audio
        // cannot be un-skipped, so the line holds by the difference and
        // the offset lands exactly where the slider says.
        val effectiveHold = max(0L, delay) + max(0L, skippedTotal - skipTarget)

        // Emit one frame per input frame, minus the frames skipped above:
        // the held depth stays at the delay while the line runs, and
        // silence fills the gap until the line is deep enough to play
        // from.
        val toEmit = (wholeFrames - droppedThisCall).toInt()
        val output = replaceOutputBuffer(toEmit * bytesPerFrame)
        var emittedFrames = 0
        while (emittedFrames < toEmit) {
            val queuedFrames = (queueWrite - queueRead) / bytesPerFrame
            if (queuedFrames > effectiveHold) {
                repeat(bytesPerFrame) { output.put(queue[queueRead++]) }
            } else {
                repeat(bytesPerFrame) { output.put(0) }
            }
            emittedFrames++
        }
        output.flip()
        compactQueueIfDrained()
    }

    /**
     * The held tail still has to play: push every frame left on the line
     * into the output buffer, where the sink drains it through repeated
     * `getOutput` calls before `isEnded` answers true.
     */
    override fun onQueueEndOfStream() {
        val remaining = queueWrite - queueRead
        if (remaining <= 0) return
        val output = replaceOutputBuffer(remaining)
        repeat(remaining) { output.put(queue[queueRead++]) }
        output.flip()
        queueRead = 0
        queueWrite = 0
    }

    override fun onFlush() {
        stashLength = 0
        queueRead = 0
        queueWrite = 0
        skippedTotal = 0
    }

    override fun onReset() {
        stashLength = 0
        queueRead = 0
        queueWrite = 0
        skippedTotal = 0
    }

    /** One byte at absolute stream offset [pos] in this call's stream (stashed head first, then the input buffer). */
    private fun nextByte(inputBuffer: ByteBuffer, pos: Int): Byte {
        return if (pos < stashLength) {
            stash[pos]
        } else {
            inputBuffer.get(inputBuffer.position() + (pos - stashLength))
        }
    }

    /**
     * Appends [count] bytes from the input buffer's current position to the
     * stash and advances the position past them.
     */
    private fun appendToStash(inputBuffer: ByteBuffer, count: Int) {
        if (count <= 0) return
        if (stash.size < stashLength + count) {
            stash = stash.copyOf(stashLength + count)
        }
        val start = inputBuffer.position()
        for (i in 0 until count) stash[stashLength + i] = inputBuffer.get(start + i)
        stashLength += count
        inputBuffer.position(start + count)
    }

    private fun ensureQueueCapacity(needed: Int) {
        if (queue.size >= needed) return
        // The queue never needs more than the delay window plus one input
        // buffer; grow geometrically so a slider sweep does not reallocate
        // on every buffer.
        var size = max(queue.size, 4096)
        while (size < needed) size *= 2
        queue = queue.copyOf(size)
    }

    /** Once the line is fully drained, reset the cursors so it never grows without bound. */
    private fun compactQueueIfDrained() {
        if (queueRead == queueWrite) {
            queueRead = 0
            queueWrite = 0
        } else if (queueRead > 0 && queueRead * 2 > queueWrite) {
            // Half-drained: slide the remainder down so the tail never
            // walks the buffer to its end on a long stream.
            queue.copyInto(queue, 0, queueRead, queueWrite)
            queueWrite -= queueRead
            queueRead = 0
        }
    }

    companion object {
        /** The delay window: ±10 s. A wider window is a bigger queue for a correction nobody can hear. */
        const val RANGE_MS: Long = 10_000L
    }
}
