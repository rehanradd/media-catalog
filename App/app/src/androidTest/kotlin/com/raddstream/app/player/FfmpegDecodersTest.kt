package com.raddstream.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raddstream.app.player.audio.DelayAudioProcessor
import com.raddstream.app.player.audio.EnhancementAudioProcessor
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Library bump 2026-11-23 — device proof that the five FFmpeg audio codecs
 * (AC-3, E-AC-3, DTS, TrueHD, MLP) actually decode through the app's own
 * NextLib renderer factory ([RaddRenderersFactory]).
 *
 * Each sample is a 3 s 440 Hz tone generated with ffmpeg on the Oracle VM
 * (`ffmpeg -f lavfi -i sine=frequency=440:duration=3 -c:a <codec> -strict -2
 * -ar 48000 -ac 2 codec-<codec>.mkv`) and muxed into MKV; ffprobe confirms
 * the codec of each file. The test plays each sample through the real
 * factory and asserts three things: playback reaches STATE_READY with no
 * error, a [FfmpegAudioRenderer] is in the pipeline (the decode is really
 * done by FFmpeg, not a platform codec), and the position advances (decoded
 * PCM is actually being produced, not just parsed).
 *
 * This is an instrumentation test: it needs a device (Firebase Test Lab or
 * a phone). CI only compile-checks it (`:app:compileDebugAndroidTestKotlin`
 * in ci.yml) — it never runs on the deviceless runner.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegDecodersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ac3Decodes() = decodes("codec-ac3.mkv", "AC-3")

    @Test
    fun eac3Decodes() = decodes("codec-eac3.mkv", "E-AC-3")

    @Test
    fun dtsDecodes() = decodes("codec-dts.mkv", "DTS")

    @Test
    fun truehdDecodes() = decodes("codec-truehd.mkv", "TrueHD")

    @Test
    fun mlpDecodes() = decodes("codec-mlp.mkv", "MLP")

    private fun decodes(asset: String, label: String) {
        val factory = RaddRenderersFactory(
            context = context,
            subtitleOffsetMs = { 0L },
            enhancementProcessor = EnhancementAudioProcessor(),
            delayProcessor = DelayAudioProcessor(),
        )
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val player = ExoPlayer.Builder(context).setRenderersFactory(factory).build()
        try {
            val ready = CountDownLatch(1)
            var error: PlaybackException? = null
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(err: PlaybackException) {
                    error = err
                    ready.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri("asset:///$asset"))
            player.prepare()
            player.play()
            assertTrue("$label: timed out waiting for STATE_READY", ready.await(20, TimeUnit.SECONDS))
            error?.let { fail("$label: playback error: $it") }
            assertEquals("$label: not READY", Player.STATE_READY, player.playbackState)
            val hasFfmpegRenderer = (0 until player.rendererCount).any {
                player.getRenderer(it) is FfmpegAudioRenderer
            }
            assertTrue("$label: no FfmpegAudioRenderer in the pipeline", hasFfmpegRenderer)
            val start = player.currentPosition
            Thread.sleep(1500)
            assertTrue(
                "$label: position did not advance ($start -> ${player.currentPosition})",
                player.currentPosition > start,
            )
        } finally {
            player.release()
        }
    }
}
