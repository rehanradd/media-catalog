package com.raddstream.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.ui.screens.BottomControls
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F-06 (APP-10): the pause cast strip keyed its Lazy rows by actor name,
 * so staged cast data listing the same actor twice threw
 * IllegalArgumentException at composition and the paused player crashed.
 * Rows are keyed by index + name now: the same name twice renders two
 * chips (same label, same search target) instead of crashing.
 *
 * Fixture mirrors PlayerMockupTest's vodBase — the strip only shows while
 * paused, so the state is a paused one.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerCastDuplicateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a duplicated cast name renders two chips instead of crashing`() {
        val state = PlayerUiState(
            connected = true,
            isPlaying = false,
            positionMs = 2_730_000L,
            bufferedMs = 3_000_000L,
            durationMs = 9_540_000L,
            userVolume = 0.8f,
            pendingTitle = "Jawan",
            titleYear = 2023,
            activeRendition = PlaybackRendition(
                print = PrintInfo(resolution = "1080p"),
                playback = PlaybackInfo(label = "Fast direct"),
            ),
            qualityBand = "1080p",
            qualityAuto = true,
            autoQualityBand = "1080p",
            qualityRungs = listOf(
                LadderRung(band = "1080p", streamId = "h-1080p"),
                LadderRung(band = "720p", streamId = "h-720p"),
            ),
            castNames = listOf("Aamir Khan", "Aamir Khan"),
        )
        compose.setContent {
            RaddStreamTheme {
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

        compose.onAllNodesWithText("Aamir Khan").assertCountEquals(2)
    }
}
