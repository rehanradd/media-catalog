package com.raddstream.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raddstream.app.player.KeepUiState
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.ui.screens.BottomControls
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test

/**
 * The player bottom row, rendered the way the player actually runs: landscape.
 * The row's honest minimum is ~447dp (two 48dp safe rails + six 48dp primaries
 * + the quality pill), which overflows the portrait PIXEL_5 frame the rest of
 * PaparazziPreviewTest uses — a portrait golden would enshrine a clipped More
 * button. These four shots live here, on a landscape PIXEL_5, so the goldens
 * show the whole row. Fixtures mirror PaparazziPreviewTest's playerBase so
 * the two suites can never drift apart silently.
 */
class PlayerLandscapePaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(
        // Paparazzi 1.3.5 has no orientation knob on DeviceConfig — the
        // frame is landscape because it is wider than it is tall, the way
        // the framework derives orientation from the display metrics.
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = DeviceConfig.PIXEL_5.screenHeight,
            screenHeight = DeviceConfig.PIXEL_5.screenWidth,
        ),
    )

    /** Dark-first, like the shipped app: RaddStreamTheme defaults to the brand scheme. */
    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    private val playerRendition = PlaybackRendition(
        print = PrintInfo(resolution = "1080p"),
        playback = PlaybackInfo(label = "Fast direct"),
    )

    private fun playerBase(
        isPlaying: Boolean = true,
        positionMs: Long = 2_730_000L,
        durationMs: Long = 9_540_000L,
    ) = PlayerUiState(
        connected = true,
        isPlaying = isPlaying,
        positionMs = positionMs,
        bufferedMs = 3_000_000L,
        durationMs = durationMs,
        userVolume = 0.8f,
        pendingTitle = "Jawan",
        titleYear = 2023,
        activeRendition = playerRendition,
        qualityBand = "1080p",
        qualityAuto = true,
        autoQualityBand = "1080p",
        qualityRungs = listOf(
            LadderRung(band = "1080p", streamId = "h-1080p"),
            LadderRung(band = "720p", streamId = "h-720p"),
        ),
    )

    private fun shotBottom(state: PlayerUiState) {
        paparazzi.snapshot {
            themed {
                BottomControls(
                    state = state,
                    onPlayFromStart = {},
                    onTogglePlayPause = {},
                    onToggleMute = {},
                    onShowSubtitleSheet = {},
                    onShowQualitySheet = {},
                    onToggleFullscreen = {},
                    onShowMoreSheet = {},
                    onSeekTo = {},
                    onSetUserVolume = {},
                    onSetAudioBoostLevel = {},
                    onKeepCurrent = {},
                    onEnterPip = {},
                    onActivity = {},
                    onScrubCommit = {},
                )
            }
        }
    }

    @Test
    fun playerBottomPlaying() {
        shotBottom(playerBase())
    }

    @Test
    fun playerBottomPaused() {
        shotBottom(
            playerBase(isPlaying = false).copy(
                castNames = listOf("Shah Rukh Khan", "Nayanthara", "Vijay Sethupathi"),
                keep = KeepUiState.Stored(playableOffline = true, note = null),
            ),
        )
    }

    @Test
    fun playerBottomLive() {
        // F29: a live HLS playlist can report a finite DVR-window duration
        // (Discover Pakistan: 2:00:12) — the shot must show the LIVE mark
        // and no seekable scrubber, whatever the duration claims. The
        // Stitch mockup's live seek bar + elapsed time is NOT copied here
        // (hate-audit H16); the mark is the whole live row.
        shotBottom(
            playerBase().copy(
                isLive = true,
                durationMs = 7_200_000L,
                positionMs = 3_600_000L,
            ),
        )
    }

    @Test
    fun playerBottomSingleRung() {
        // 25c: one rung only — the Quality primary stays visible, in full
        // white (review fix, 16 Nov: a tappable control never dims),
        // instead of vanishing.
        shotBottom(
            playerBase().copy(
                qualityAuto = false,
                qualityRungs = listOf(LadderRung(band = "1080p", streamId = "h-1080p")),
            ),
        )
    }
}
