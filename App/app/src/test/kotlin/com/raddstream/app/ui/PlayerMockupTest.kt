package com.raddstream.app.ui

import android.content.Context
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.R
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.ui.screens.BottomControls
import com.raddstream.app.ui.screens.CenterPlayPause
import com.raddstream.app.ui.screens.TopBar
import com.raddstream.app.ui.screens.subtitleStatusLabel
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Stitch player mockup, pinned in code: the gestures popup is gone, the
 * top bar badges live, the live row prints elapsed + LIVE with no total,
 * the VOD row prints current + total, the quality control is a text pill
 * showing the rung, and the centre of the picture carries a play/pause
 * button. Fixtures mirror PaparazziPreviewTest's playerBase so the two
 * suites can never drift apart silently.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerMockupTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun vodBase(isPlaying: Boolean = true) = PlayerUiState(
        connected = true,
        isPlaying = isPlaying,
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
    )

    private fun liveBase() = vodBase().copy(
        isLive = true,
        pendingTitle = "Madani Channel Urdu",
        titleYear = null,
        durationMs = 7_200_000L,
        positionMs = 3_600_000L,
        activeRendition = PlaybackRendition(
            print = PrintInfo(resolution = "1080p"),
            playback = PlaybackInfo(label = "Live", playUrl = "https://example.com/live.m3u8"),
        ),
    )

    private fun setBottom(state: PlayerUiState) {
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
    }

    @Test
    fun `live top bar shows the LIVE badge and the broadcast subtitle`() {
        compose.setContent {
            RaddStreamTheme {
                TopBar(
                    state = liveBase(),
                    onBack = {},
                    onLock = {},
                    onShowPrintSheet = {},
                )
            }
        }
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        compose.onNodeWithText("Live Broadcast • HLS").assertIsDisplayed()
        compose.onNodeWithText("Madani Channel Urdu").assertIsDisplayed()
    }

    @Test
    fun `vod top bar shows no LIVE badge`() {
        compose.setContent {
            RaddStreamTheme {
                TopBar(
                    state = vodBase(),
                    onBack = {},
                    onLock = {},
                    onShowPrintSheet = {},
                )
            }
        }
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNodeWithText("Jawan").assertIsDisplayed()
    }

    @Test
    fun `live bottom row shows elapsed time and LIVE, never a total`() {
        setBottom(liveBase())
        compose.onNodeWithText("1:00:00").assertIsDisplayed()
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        // The fixture's duration is 2:00:00 — a live row must never print it.
        compose.onNodeWithText("2:00:00").assertDoesNotExist()
    }

    @Test
    fun `vod bottom row shows current and total time`() {
        setBottom(vodBase())
        compose.onNodeWithText("45:30").assertIsDisplayed()
        compose.onNodeWithText("2:39:00").assertIsDisplayed()
    }

    @Test
    fun `the bottom row carries all six primaries and the quality pill prints the rung`() {
        val state = vodBase()
        setBottom(state)
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_volume, 80)).assertIsDisplayed()
        val subtitles = context.getString(
            R.string.player_subtitles_label,
            subtitleStatusLabel(state, context.getString(R.string.player_off)),
        )
        compose.onNodeWithContentDescription(subtitles).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            context.getString(R.string.player_quality_label, "1080P"),
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_fullscreen)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_more)).assertIsDisplayed()
        // The pill itself prints the rung as text, not an icon.
        compose.onNodeWithText("1080P").assertIsDisplayed()
    }

    @Test
    fun `centre button shows pause while playing, play while paused`() {
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = vodBase(isPlaying = true),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertIsDisplayed()
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = vodBase(isPlaying = false),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertIsDisplayed()
    }

    @Test
    fun `centre button never shows while buffering`() {
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = vodBase(isPlaying = true).copy(isBuffering = true),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertDoesNotExist()
    }
}
