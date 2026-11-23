@file:androidx.annotation.OptIn(UnstableApi::class)
// Library bump 2026-11-23: the file-level marker must be the ANDROIDX
// OptIn — kotlin.OptIn satisfies the compiler but lint's
// UnsafeOptInUsageError only reads androidx.annotation.OptIn
// (AGP 8.9, CI 45094024591).
package com.raddstream.app.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import com.raddstream.app.player.subtitles.OffsetSubtitleDecoderFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Library bump 2026-11-23: the app's renderer factory — a NAMED subclass of
 * NextLib's [NextRenderersFactory] (Media3 1.11.1 + NextLib 1.11.1-0.19.0).
 *
 * It is named, not an anonymous `object :`, on purpose: kotlinc 2.2.21
 * rejects the anonymous form — it resolves the supertype's `this` argument
 * to the factory's Companion (CI 45090384520) — while the class-header
 * supertype call compiles. The two overrides are the service's own: the
 * subtitle-offset text renderer and the enhancement/delay audio sink.
 * Everything else is exactly as NextLib's factory builds it, including the
 * FfmpegAudioRenderer (AC-3, E-AC-3, DTS, TrueHD, MLP) under
 * EXTENSION_RENDERER_MODE_PREFER.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class RaddRenderersFactory(
    context: Context,
    private val subtitleOffsetMs: () -> Long,
    private val enhancementProcessor: AudioProcessor,
    private val delayProcessor: AudioProcessor,
) : NextRenderersFactory(context) {

    /**
     * TextRenderer is the one renderer we replace: it is final and knows no
     * subtitle offset, so its decoder gets a wrapper that holds each cue block back by
     * the live sync value (see OffsetSubtitleDecoderFactory). Every other renderer stays
     * exactly as the factory builds it.
     */
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            TextRenderer(
                output,
                outputLooper,
                OffsetSubtitleDecoderFactory { subtitleOffsetMs() },
            ),
        )
    }

    /**
     * MX idea #8 + REA #3: the audio sink carries the enhancement
     * processor — the volume boost past 100% and the equalizer —
     * and behind it the delay processor, applied to the decoded
     * PCM in Media3's own chain. This is the documented
     * injection point (`DefaultAudioSink.Builder.setAudioProcessors`):
     * there is no `ExoPlayer.Builder.setAudioProcessors` in Media3.
     * Everything else is the default sink, built exactly as the
     * factory builds it. The processor instances are the service's
     * shared ones, so a rebuild re-injects the same mix and the
     * same offset.
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(enhancementProcessor, delayProcessor))
            .build()
    }
}
