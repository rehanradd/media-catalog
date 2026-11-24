package com.raddstream.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.raddstream.app.player.Coverage
import com.raddstream.app.player.EpisodeCoverage
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
        // The render size comes from DeviceConfig.orientation, not from
        // the raw dimensions: hardwareConfig takes max(w, h) for
        // LANDSCAPE and min(w, h) for PORTRAIT, so swapping the
        // dimensions alone still renders portrait (proven by record run
        // 43793676923: 461x1000 with a clipped More button). The enum
        // lives in com.android.resources, not in app.cash.paparazzi —
        // importing the latter does not resolve (run 43790680404).
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            orientation = ScreenOrientation.LANDSCAPE,
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
                    onShowSpeedSheet = {},
                    onShowAudioSheet = {},
                    onRequestNextEpisode = {},
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
                // P9 (round 2): the X-Ray line renders with the episode,
                // the rung and the subtitle state — the audio part is
                // omitted (no track selected), never invented.
                isSeriesScope = true,
                activeRendition = playerRendition.copy(
                    coverage = Coverage(season = 1, ordinal = 2),
                ),
            ),
        )
    }

    // P2 (round 2): the chapter markers render — one tick behind the
    // playhead (white, on the jade played track) and two ahead (jade, on
    // the white unplayed track). Every earlier golden passes no episode
    // data, so the tick list is empty in all of them; this is the first
    // shot with a marker in it at all. Position 45:30 of 2:39:00 puts
    // the 30:00 boundary behind the playhead and 1:00:00 / 1:30:00 ahead.
    @Test
    fun playerBottomChapters() {
        shotBottom(
            playerBase().copy(
                activeRendition = playerRendition.copy(
                    coverage = Coverage(
                        episodes = listOf(
                            EpisodeCoverage(number = 2, offsetSeconds = 1800, chapterTitle = "Chapter 2"),
                            EpisodeCoverage(number = 3, offsetSeconds = 3600, chapterTitle = "Chapter 3"),
                            EpisodeCoverage(number = 4, offsetSeconds = 5400, chapterTitle = "Chapter 4"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun playerBottomPausedDuplicateCast() {
        // F-06 (APP-10): staged cast data can list the same actor twice —
        // the strip used to key rows by name and throw at composition.
        // The golden pins the fixed render: the duplicate name twice, no
        // crash, the row otherwise identical to playerBottomPaused.
        shotBottom(
            playerBase(isPlaying = false).copy(
                castNames = listOf("Shah Rukh Khan", "Shah Rukh Khan", "Nayanthara"),
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

    // P1 (player fixes): the context-aware row — a next episode exists and
    // speed is 1.5x, so the flexible slots are Next Episode + the speed pill
    // (Subtitles + Quality drop to More). The row is still exactly six.
    @Test
    fun playerBottomNextEpisodeSpeed() {
        shotBottom(
            playerBase().copy(
                nextEpisodeTitle = "Chapter 2: The Journey",
                nextEpisodeSeason = 1,
                nextEpisodeEpisode = 2,
                speed = 1.5f,
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
