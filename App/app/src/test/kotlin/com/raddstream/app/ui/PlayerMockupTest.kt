package com.raddstream.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.R
import com.raddstream.app.player.GestureMapper
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.player.QualityOption
import com.raddstream.app.player.SleepTimerState
import com.raddstream.app.ui.screens.BottomControls
import com.raddstream.app.ui.screens.CenterPlayPause
import com.raddstream.app.ui.screens.SwitchSourceRow
import com.raddstream.app.ui.screens.TopBar
import com.raddstream.app.ui.screens.subtitleStatusLabel
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Stitch player mockup, pinned in code: the gestures popup is gone, the
 * top bar badges live, the live row is the LIVE mark only (no bar, no time
 * — hate-audit H16: the mockup's live seek bar is not copied), the VOD row
 * prints current + total, the quality control is a text pill showing the
 * rung, and the centre of the picture carries a play/pause button flanked
 * by ±10s skip buttons. Fixtures mirror PaparazziPreviewTest's playerBase
 * so the two suites can never drift apart silently. The popups restyle
 * (Stitch sheets) is pinned the same way where a piece can be composed —
 * the switch-source row — and by its preset lists where it cannot: the
 * sheets themselves are private and take the ViewModel, so their chrome
 * lives in PlayerControlSemanticsTest's source-drift test.
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
        compose.onNodeWithText("Live Broadcast").assertIsDisplayed()
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
    fun `live bottom row is the LIVE mark only, never a time or a bar`() {
        setBottom(liveBase())
        compose.onNodeWithText("Live").assertIsDisplayed()
        // The fixture's duration is 2:00:00 and its position 1:00:00 — a live
        // row must print neither: a live window has no position to show.
        compose.onNodeWithText("1:00:00").assertDoesNotExist()
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
        // The fixture is fullscreen (PlayerUiState's default — the player
        // starts immersive), so the toggle reads "Exit fullscreen", not
        // "Fullscreen". The primary is there either way.
        compose.onNodeWithContentDescription(context.getString(R.string.player_exit_fullscreen)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_more)).assertIsDisplayed()
        // The pill itself prints the rung as text, not an icon.
        compose.onNodeWithText("1080P").assertIsDisplayed()
    }

    @Test
    fun `centre button shows pause while playing, play while paused`() {
        // One content, driven by state: a second setContent in the same
        // test throws (IllegalStateException), so the pause→play flip
        // goes through a state holder instead.
        var playing by mutableStateOf(true)
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = vodBase(isPlaying = playing),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                    onSeekBy = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertIsDisplayed()
        compose.runOnIdle { playing = false }
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertIsDisplayed()
    }

    @Test
    fun `centre skip buttons seek ten seconds each way`() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = vodBase(isPlaying = true),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                    onSeekBy = { seeks += it },
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_rewind_10))
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.player_forward_10))
            .performClick()
        org.junit.Assert.assertEquals(listOf(-10_000L, 10_000L), seeks)
    }

    @Test
    fun `live centre shows no skip buttons`() {
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = liveBase(),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                    onSeekBy = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_rewind_10))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_forward_10))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertIsDisplayed()
    }

    @Test
    fun `centre button never shows while buffering`() {
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = vodBase(isPlaying = true).copy(isBuffering = true),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                    onSeekBy = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertDoesNotExist()
    }

    @Test
    fun `switch source row shows size and the playing pill on the active print`() {
        var tapped = false
        compose.setContent {
            RaddStreamTheme {
                SwitchSourceRow(
                    title = "1080p",
                    sizeText = QualityOption.formatBytes(1024L * 1024 * 1024),
                    playing = true,
                    onClick = { tapped = true },
                )
            }
        }
        compose.onNodeWithText("1080p").assertIsDisplayed()
        compose.onNodeWithText("1.0 GB").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.player_playing)).assertIsDisplayed()
        compose.onNodeWithText("1080p").performClick()
        org.junit.Assert.assertTrue(tapped)
    }

    @Test
    fun `switch source row shows no pill and no size when the print is not playing or unmeasured`() {
        compose.setContent {
            RaddStreamTheme {
                SwitchSourceRow(
                    title = "720p",
                    sizeText = null,
                    playing = false,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithText("720p").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.player_playing)).assertDoesNotExist()
    }

    @Test
    fun `the speed sheet offers the mockup six speeds`() {
        org.junit.Assert.assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f),
            GestureMapper.SHEET_PRESETS,
        )
    }

    @Test
    fun `the sleep sheet offers the mockup four presets`() {
        org.junit.Assert.assertEquals(
            listOf(15L, 30L, 45L, 60L).map { it * 60_000L },
            SleepTimerState.SHEET_PRESETS_MS,
        )
    }
}
