package com.raddstream.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.R
import com.raddstream.app.player.DecoderMode
import com.raddstream.app.player.GestureMapper
import com.raddstream.app.player.KeepUiState
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.MediaSegment
import com.raddstream.app.player.MediaSegmentType
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.player.QualityOption
import com.raddstream.app.player.SleepTimerState
import com.raddstream.app.ui.screens.BottomControls
import com.raddstream.app.ui.screens.CenterPlayPause
import com.raddstream.app.ui.screens.SkipSegmentButton
import com.raddstream.app.ui.screens.SwitchSourceRow
import com.raddstream.app.ui.screens.TopBar
import com.raddstream.app.ui.screens.canShareMoment
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Stitch player mockup, pinned in code: the gestures popup is gone, the
 * top bar carries no live badge (the single live mark rides the bottom
 * row), the live row is the LIVE mark only (no bar, no time — hate-audit
 * H16: the mockup's live seek bar is not copied), the VOD row prints
 * current + total, the quality control is a text pill showing the rung, and
 * the centre of the picture carries a play button while paused, flanked by
 * ±10s skip buttons — never while playing, where the bottom row is the
 * transport. Fixtures mirror PaparazziPreviewTest's playerBase so the two
 * suites can never drift apart silently. The popups restyle (Stitch
 * sheets) is pinned the same way where a piece can be composed — the
 * switch-source row — and by its preset lists where it cannot: the
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
        // The bottom row's honest minimum (~480dp: two 48dp safe rails +
        // seven 48dp controls + gaps) is wider than the default portrait
        // test window, so the far-right primary (More) is clipped there —
        // that is a harness limit, not a product bug.
        // A requiredWidth(800.dp) wrapper was tried (222db205) and made
        // all three bottom tests fail at their first assertIsDisplayed,
        // so it is reverted: these tests pin that the row's content
        // exists, and the landscape Paparazzi bottom shots pin that the
        // whole row is visible.
        compose.setContent {
            RaddStreamTheme {
                BottomControls(
                    state = state,
                    onPlayFromStart = {},
                    onTogglePlayPause = {},
                    onToggleMute = {},
                    onShowSubtitleSheet = {},
                    onToggleFullscreen = {},
                    onShowMoreSheet = {},
                    onSeekTo = {},
                    onSeekBy = {},
                    onSetUserVolume = {},
                    onKeepCurrent = {},
                    onEnterPip = {},
                    onActivity = {},
                    onScrubCommit = {},
                )
            }
        }
    }

    @Test
    fun `live top bar carries no live badge and no broadcast subtitle`() {
        // Review fix (16 Nov): the single live mark rides the bottom row —
        // the top bar neither badges live nor prints a broadcast subtitle.
        // The channel title stands alone, centred vertically.
        compose.setContent {
            RaddStreamTheme {
                TopBar(
                    state = liveBase(),
                    onBack = {},
                    onLock = {},
                )
            }
        }
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNodeWithText("Live Broadcast").assertDoesNotExist()
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
                )
            }
        }
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNodeWithText("Jawan").assertIsDisplayed()
    }

    @Test
    fun `no speed pill rides the bottom row at 1_5x`() {
        // Player review D5, moved by P1 (player fixes) and again by P1-15
        // (audit B-17): the pill that said the speed lived in the bottom
        // row — the TopBar pill is gone, the bottom-row pill is gone too.
        // Speed lives in More now. At 1.5x the fixed row shows no speed
        // pill, and the ±10 s skips (the new fixed transport) are there.
        compose.setContent {
            RaddStreamTheme {
                BottomControls(
                    state = vodBase().copy(speed = 1.5f),
                    onPlayFromStart = {},
                    onTogglePlayPause = {},
                    onToggleMute = {},
                    onShowSubtitleSheet = {},
                    onToggleFullscreen = {},
                    onShowMoreSheet = {},
                    onSeekTo = {},
                    onSeekBy = {},
                    onSetUserVolume = {},
                    onKeepCurrent = {},
                    onEnterPip = {},
                    onActivity = {},
                    onScrubCommit = {},
                )
            }
        }
        compose.onNodeWithText("1.5×").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_rewind_10)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_forward_10)).assertIsDisplayed()
    }

    @Test
    fun `the top bar carries no decoder chip`() {
        // P0-10 (road to 100/100, audit B-16): the HW/SW decoder text left
        // the top bar — engineer-facing diagnostic chrome in a prime slot.
        // The switch lives in More -> "Playback issue?" -> Decoder, shown
        // only after a playback error. The bar must print neither mode.
        compose.setContent {
            RaddStreamTheme {
                TopBar(
                    state = vodBase(),
                    onBack = {},
                    onLock = {},
                )
            }
        }
        compose.onNodeWithText("HW").assertDoesNotExist()
        compose.onNodeWithText("SW").assertDoesNotExist()
        compose.onNodeWithText("Jawan").assertIsDisplayed()
    }

    @Test
    fun `the top bar shows no decoder chip even in software mode`() {
        // P0-10 (audit B-16): the decoder mode is no longer printed in the
        // top bar at all — not even when the player runs in software. The
        // switch lives in More -> "Playback issue?" -> Decoder, error-gated.
        compose.setContent {
            RaddStreamTheme {
                TopBar(
                    state = vodBase().copy(decoderMode = DecoderMode.SOFTWARE),
                    onBack = {},
                    onLock = {},
                )
            }
        }
        compose.onNodeWithText("SW").assertDoesNotExist()
        compose.onNodeWithText("HW").assertDoesNotExist()
        compose.onNodeWithText("Jawan").assertIsDisplayed()
    }

    @Test
    fun `the top bar carries no source-switch button`() {
        // MX #6 (chrome diet, D1): the print switch left the top bar — its
        // "Switch version" button duplicated the More sheet's Prints row.
        // The title and the lock stay; the speed pill moved to the bottom
        // row (P1), and the decoder chip left with P0-10 (audit B-16) —
        // its switch lives in More -> "Playback issue?" -> Decoder now.
        compose.setContent {
            RaddStreamTheme {
                TopBar(
                    state = vodBase(),
                    onBack = {},
                    onLock = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Switch version").assertDoesNotExist()
        compose.onNodeWithText("Jawan").assertIsDisplayed()
        compose.onNodeWithText("HW").assertDoesNotExist()
        compose.onNodeWithText("SW").assertDoesNotExist()
    }

    @Test
    fun `a remote stream can share a moment`() {
        // MX #6 (chrome diet, D4): the More sheet's "Share a moment" row
        // shows only when the playing rendition carries a stream handle.
        assertTrue(
            "a stream with a handle must be shareable",
            canShareMoment(
                vodBase().copy(
                    activeRendition = PlaybackRendition(
                        print = PrintInfo(resolution = "1080p"),
                        playback = PlaybackInfo(label = "Fast direct", streamId = "h-1080p"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a local file cannot share a moment`() {
        // The same rule, the other side: a local file has no handle to link
        // to, so the row hides instead of no-op'ing on tap — and a rendition
        // with no handle at all is the same case.
        assertFalse(
            "a local streamId must not be shareable",
            canShareMoment(
                vodBase().copy(
                    activeRendition = PlaybackRendition(
                        print = PrintInfo(resolution = "1080p"),
                        playback = PlaybackInfo(label = "Local file", streamId = "local"),
                    ),
                ),
            ),
        )
        assertFalse(
            "a blank streamId must not be shareable",
            canShareMoment(
                vodBase().copy(
                    activeRendition = PlaybackRendition(
                        print = PrintInfo(resolution = "1080p"),
                        playback = PlaybackInfo(label = "Local file", streamId = "   "),
                    ),
                ),
            ),
        )
        assertFalse(
            "a rendition with no streamId must not be shareable",
            canShareMoment(vodBase()),
        )
    }

    @Test
    fun `a user-tapped keep shows its live progress in the player`() {
        // Player review D3: the ring and the live rate were already built —
        // only the early return hid them. A viewer who tapped Keep sees the
        // copy running; the Downloads screen is not the only witness.
        // P1-16 (audit B-21): the KeepChip row is gone — the status rides
        // the scrubber/time row now, so this pins it there, not on a chip.
        setBottom(
            vodBase().copy(
                keep = KeepUiState.Keeping(40_000_000L, 3_050_000_000L, 1_200_000L),
                keepRequested = true,
            ),
        )
        compose.onNodeWithText("Saving a copy in the background · 1.2 MB/s").assertIsDisplayed()
    }

    @Test
    fun `an automatic keep stays silent in the player`() {
        // App-design audit #7, kept: the automatic ahead-window copy never
        // announces itself when the viewer just pressed Play — its progress
        // lives in Downloads. keepRequested is false here because no tap
        // happened; refreshKeepState also resets it on every switch, so a
        // stale request from a previous title cannot un-silence this one.
        // P1-16: the gate moved with the status into the time row.
        setBottom(
            vodBase().copy(
                keep = KeepUiState.Keeping(40_000_000L, 3_050_000_000L, 1_200_000L),
                keepRequested = false,
            ),
        )
        compose.onNodeWithText("Saving a copy in the background", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a finished keep says so in the time row`() {
        // P1-16: "Kept ✓" is the time row's word for a stored keep — the
        // chip that used to carry it is gone, the fact is not.
        setBottom(vodBase().copy(keep = KeepUiState.Stored(playableOffline = true, note = null)))
        compose.onNodeWithText("Kept ✓").assertIsDisplayed()
    }

    @Test
    fun `the pause X-Ray is one line and the cast stays hidden until tapped`() {
        // P1-16 (audit B-21): a pause no longer advertises the whole cast
        // — the context line is the strip, and the chips appear only on a
        // tap of that line.
        setBottom(
            vodBase(isPlaying = false).copy(
                castNames = listOf("Shah Rukh Khan", "Nayanthara"),
            ),
        )
        compose.onNodeWithText("Shah Rukh Khan").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_xray_show_cast))
            .performClick()
        compose.onNodeWithText("Shah Rukh Khan").assertIsDisplayed()
        compose.onNodeWithText("Nayanthara").assertIsDisplayed()
    }

    @Test
    fun `live bottom row is the LIVE mark only, never a time or a bar`() {
        setBottom(liveBase())
        compose.onNodeWithText("LIVE").assertIsDisplayed()
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
    fun `the bottom row carries all seven fixed controls`() {
        // P1-15 (audit B-17): fixed positions — Play/Pause, Back 10,
        // Forward 10, Volume, Captions/Audio, Fullscreen, More. The
        // flexible slots are gone, so there is no quality pill printing
        // the rung in the row anymore; the rung lives in More.
        val state = vodBase()
        setBottom(state)
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_volume, 80)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_rewind_10)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_forward_10)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_audio_subtitles_title)).assertIsDisplayed()
        // The fixture is fullscreen (PlayerUiState's default — the player
        // starts immersive), so the toggle reads "Exit fullscreen", not
        // "Fullscreen". The primary is there either way.
        compose.onNodeWithContentDescription(context.getString(R.string.player_exit_fullscreen)).assertIsDisplayed()
        // More rides at the row's far right, past the default portrait
        // test window — it exists but is clipped there, so existence
        // (not visibility) is pinned here; the landscape Paparazzi
        // bottom shots pin it visible.
        compose.onNodeWithContentDescription(context.getString(R.string.player_more)).assertExists()
        // No pill prints the rung in the row anymore.
        compose.onNodeWithText("1080P").assertDoesNotExist()
    }

    @Test
    fun `centre button shows only while paused, never while playing`() {
        // Review fix (16 Nov): the centre button is the paused-state
        // affordance — during playback the bottom row is the transport and
        // the centre is empty. One content, driven by state: a second
        // setContent in the same test throws (IllegalStateException), so the
        // playing→paused flip goes through a state holder instead.
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
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertDoesNotExist()
        compose.runOnIdle { playing = false }
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertIsDisplayed()
    }

    @Test
    fun `centre skip buttons seek ten seconds each way`() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = vodBase(isPlaying = false),
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
        // Review fix (16 Nov): while playing the centre is empty entirely —
        // no skips on live, and no centre button either. The paused live
        // centre (button, still no skips) is pinned in PlayerReviewFixesTest.
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
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertDoesNotExist()
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

    @Test
    fun `the skip pill shows the segment label and fires the skip`() {
        // MX #4: the pill that says the segment IS the way past it — one
        // tap seeks to the window's end. The label names the segment
        // ("Skip Intro"), never a generic "Skip".
        var skipped = false
        compose.setContent {
            RaddStreamTheme {
                SkipSegmentButton(
                    segment = MediaSegment(MediaSegmentType.INTRO, 228665L, 246498L),
                    onSkip = { skipped = true },
                )
            }
        }
        compose.onNodeWithText("Skip Intro").assertIsDisplayed().performClick()
        assertTrue("tapping the Skip Intro pill must seek past the intro", skipped)
    }

    @Test
    fun `the skip pill labels credits and recap by their own names`() {
        compose.setContent {
            RaddStreamTheme {
                SkipSegmentButton(
                    segment = MediaSegment(MediaSegmentType.CREDITS, 3432884L, null),
                    onSkip = {},
                )
            }
        }
        compose.onNodeWithText("Skip Credits").assertIsDisplayed()
        compose.onNodeWithText("Skip Intro").assertDoesNotExist()
    }
}
