package com.raddstream.app.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * The software gain stage MX #8 adds to the player: the volume boost past
 * 100% and the equalizer, applied to the decoded PCM inside Media3's own
 * audio chain.
 *
 * Why here and not `LoudnessEnhancer` / `DynamicsProcessing`: those are
 * `AudioEffect`s attached to an audio session — device-dependent, whole-
 * session scope, no EQ. This processor sits in the `DefaultAudioSink`
 * chain (injected by [com.raddstream.app.player.PlaybackService] through a
 * `buildAudioSink` override), so it sees the decoded samples of this
 * playback only, on every device, with no permission and no new dependency.
 * See RESEARCH-volume-eq-20261119.md for the full comparison.
 *
 * Shape of the chain (per sample, per channel):
 *
 *   input PCM (16/24/32-bit int or float) → float → ×[dspGain] → 5 RBJ
 *   biquads ([EqChain]) → [softClip] → output in the INPUT's encoding
 *   (16-bit in → 16-bit out, float in → float out; 24/32-bit in → 16-bit
 *   out).
 *
 * The output encoding follows the input on purpose — it must. Media3's
 * `DefaultAudioSink` always appends `SilenceSkippingAudioProcessor` after
 * our processors, and that processor is 16-bit PCM only: its `onConfigure`
 * throws `UnhandledAudioFormatException` on anything else, even when
 * skip-silence is disabled (the app never enables it). The first version
 * of this processor always returned float, so every audio-bearing title
 * failed at sink configure with `ERROR_CODE_AUDIO_TRACK_INIT_FAILED`
 * (Robo v11, Discover Pakistan, 2026-11-20: float 48 kHz stereo from the
 * FFmpeg AAC decoder). The int→float→int round-trip for 16-bit input is a
 * 1-LSB-level difference at most, and a gain or EQ move still applies live
 * on the next buffer — no reconfigure, no restart, no rebuild. At unity
 * gain and flat EQ the chain is the identity in exact float arithmetic
 * (a 0 dB biquad reduces to `y[n] = x[n]`), so a player that never touches
 * the feature sounds exactly as it did.
 *
 * Threading: the playback thread owns `queueInput`/`onConfigure`/`onFlush`
 * (and therefore the [EqChain] state); the UI thread owns [setSettings],
 * which writes only `@Volatile` fields the audio thread reads. A gain or
 * EQ change rebuilds the biquad coefficients on the next queued buffer,
 * keeping the filter state — the sound does not click when a slider moves.
 * A flush (seek) or reset clears the filter state: the stream
 * discontinues there, so old state would be a thump, not continuity.
 */
@OptIn(UnstableApi::class) // androidx.annotation.OptIn on purpose:
// UnstableApi is not a Kotlin RequiresOptIn marker, so Kotlin's own
// @OptIn is ignored (compiler warning) and lint then flags every Media3
// use below as UnsafeOptInUsageError. The androidx form is the one lint
// reads — same as PlaybackService.
class EnhancementAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var dspGain: Float = 1f

    @Volatile
    private var eqEnabled: Boolean = false

    @Volatile
    private var eqGains: List<Float> = EqPresets.FLAT.gainsDb

    private var chain: EqChain? = null
    private var chainGains: List<Float> = EqPresets.FLAT.gainsDb
    private var chainEnabled: Boolean = false

    /** Bytes of a trailing partial frame carried to the next [queueInput]. */
    private var stash: ByteArray = ByteArray(0)
    private var stashLength: Int = 0

    /** Live update from the service; takes effect on the next queued buffer. */
    fun setSettings(settings: AudioEnhancementSettings) {
        val gain = settings.dspGain
        dspGain = if (gain.isFinite()) gain.coerceIn(1f, 2f) else 1f
        eqEnabled = settings.eq.enabled
        eqGains = settings.eq.gainsDb
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
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate
        if (channels <= 0 || sampleRate <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        chain = EqChain(EQ_BAND_COUNT, channels).also {
            it.configure(EQ_BANDS, effectiveGains(), sampleRate)
        }
        chainGains = eqGains
        chainEnabled = eqEnabled
        stashLength = 0
        // The output encoding follows the input: 16-bit stays 16-bit, float
        // stays float, 24/32-bit int down-converts to 16-bit. It MUST NOT
        // always be float: Media3's DefaultAudioSink always appends the
        // 16-bit-only SilenceSkippingAudioProcessor after our processors,
        // and a float output throws UnhandledAudioFormatException at sink
        // configure time (Robo v11, Discover Pakistan — every audio-bearing
        // title failed, on every device). NOTE: AudioFormat's constructor
        // is (sampleRate, channelCount, encoding) — the order reads
        // backwards against the field order.
        val outputEncoding = if (encoding == C.ENCODING_PCM_FLOAT) {
            C.ENCODING_PCM_FLOAT
        } else {
            C.ENCODING_PCM_16BIT
        }
        return AudioProcessor.AudioFormat(sampleRate, channels, outputEncoding)
    }

    private fun effectiveGains(): List<Float> =
        if (eqEnabled) eqGains else EqPresets.FLAT.gainsDb

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputFormat = inputAudioFormat
        val chain = chain
        if (chain == null || inputFormat.channelCount <= 0 || inputFormat.bytesPerFrame <= 0) {
            // Unconfigured: nothing to process. Consume the input rather
            // than stalling the sink on bytes nobody will ever read.
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val channels = inputFormat.channelCount
        val bytesPerFrame = inputFormat.bytesPerFrame

        // Rebuild the coefficients when the gains moved since the last
        // buffer. The state stays: a slider move retunes the filters, it
        // does not restart them.
        val gains = effectiveGains()
        if (gains != chainGains || eqEnabled != chainEnabled) {
            chain.configure(EQ_BANDS, gains, inputFormat.sampleRate)
            chainGains = eqGains
            chainEnabled = eqEnabled
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
        // The output is written in the OUTPUT encoding (see onConfigure):
        // shorts for a 16-bit output, floats for a float output. A 16-bit
        // sample is the soft-clipped value clamped to ±1.0 first — softClip
        // can reach ±2.0 under a 200% boost, and an unclamped short would
        // wrap. This is the same clamp ToInt16PcmAudioProcessor applies.
        val outEncoding = outputAudioFormat.encoding
        val output = replaceOutputBuffer(wholeFrames * channels * bytesPerSample(outEncoding))
        val frame = FloatArray(channels)
        val gain = dspGain
        var streamPos = 0
        repeat(wholeFrames) {
            for (ch in 0 until channels) {
                frame[ch] = readSample(inputBuffer, streamPos, inputFormat.encoding)
                streamPos += bytesPerSample(inputFormat.encoding)
            }
            for (ch in 0 until channels) frame[ch] *= gain
            chain.processFrame(frame)
            for (ch in 0 until channels) {
                val processed = softClip(frame[ch])
                if (outEncoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(processed)
                } else {
                    output.putShort((processed.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                }
            }
        }
        output.flip()
        // Advance past the consumed input bytes, then keep the trailing
        // partial frame (available - consumedBytes, always < one frame) in
        // the stash for the next call. The old stash is fully consumed —
        // consumedBytes is at least one frame and the stash never holds
        // one — so it resets before the leftover is appended.
        inputBuffer.position(inputBuffer.position() + consumedFromInput)
        stashLength = 0
        appendToStash(inputBuffer, available - consumedBytes)
    }

    /**
     * Reads one sample of [encoding] at absolute stream offset [pos] in this
     * call's stream (stashed head first, then the input buffer). The input
     * buffer's position is untouched while reading — the caller advances it
     * once, after the whole frames are processed.
     */
    private fun readSample(
        inputBuffer: ByteBuffer,
        pos: Int,
        encoding: Int,
    ): Float {
        return when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val lo = nextByte(inputBuffer, pos)
                val hi = nextByte(inputBuffer, pos + 1)
                val raw = (lo and 0xFF) or ((hi and 0xFF) shl 8)
                val signed = if (raw and 0x8000 != 0) raw - 0x10000 else raw
                signed / 32768f
            }
            C.ENCODING_PCM_24BIT -> {
                val b0 = nextByte(inputBuffer, pos)
                val b1 = nextByte(inputBuffer, pos + 1)
                val b2 = nextByte(inputBuffer, pos + 2)
                val raw = (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16)
                val signed = if (raw and 0x800000 != 0) raw - 0x1000000 else raw
                signed / 8388608f
            }
            C.ENCODING_PCM_32BIT -> {
                val b0 = nextByte(inputBuffer, pos)
                val b1 = nextByte(inputBuffer, pos + 1)
                val b2 = nextByte(inputBuffer, pos + 2)
                val b3 = nextByte(inputBuffer, pos + 3)
                val raw = (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
                raw / 2147483648f
            }
            else -> { // ENCODING_PCM_FLOAT
                val bits = (nextByte(inputBuffer, pos) and 0xFF) or
                    ((nextByte(inputBuffer, pos + 1) and 0xFF) shl 8) or
                    ((nextByte(inputBuffer, pos + 2) and 0xFF) shl 16) or
                    ((nextByte(inputBuffer, pos + 3) and 0xFF) shl 24)
                Float.fromBits(bits)
            }
        }
    }

    /**
     * One byte at absolute stream offset [pos], as an unsigned 0..255
     * [Int]: from the stash while [pos] is inside it, from the input buffer
     * after it. The return is an [Int] on purpose — a [Byte] `and 0xFF`
     * does not resolve in Kotlin (0xFF is 255, outside [Byte]'s range),
     * and every caller masks bytes into sample words.
     */
    private fun nextByte(inputBuffer: ByteBuffer, pos: Int): Int {
        val byte = if (pos < stashLength) {
            stash[pos]
        } else {
            inputBuffer.get(inputBuffer.position() + (pos - stashLength))
        }
        return byte.toInt() and 0xFF
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        else -> 4 // ENCODING_PCM_FLOAT
    }

    /**
     * Appends [count] bytes from the input buffer's current position to the
     * stash and advances the position past them. The stash never holds a
     * whole frame — callers only ever stash a trailing partial frame, or
     * the whole input when it holds no whole frame yet.
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

    override fun onFlush() {
        chain?.reset()
        stashLength = 0
    }

    override fun onReset() {
        chain = null
        stashLength = 0
    }
}
