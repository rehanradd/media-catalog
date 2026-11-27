package com.raddstream.app.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.R
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.player.TrackUiOption
import com.raddstream.app.ui.screens.BottomControls
import com.raddstream.app.ui.screens.CenterPlayPause
import com.raddstream.app.ui.screens.PlayerRowCompactRail
import com.raddstream.app.ui.screens.PlayerRowCompactWidth
import com.raddstream.app.ui.screens.playerControlsNeedCompact
import com.raddstream.app.ui.screens.playerSafeRailDp
import com.raddstream.app.ui.theme.RaddStreamTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 16 Nov player review fixes, pinned in code: the malformed pause/play
 * vectors lost their foot, the subtitles glyph is a caption card (no chat
 * bubble), the centre transport shows whenever the chrome is visible
 * (P2-3, audit B-20 — the 16 Nov paused-only rule is reversed), the bottom
 * row carries the single live mark, quality lives in More (P1-15, no pill
 * to dim), the control row is two groups on one shared rail, and the icons
 * are one outlined family.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerReviewFixesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val mainSrc: File get() = File(repoRoot, "App/app/src/main")
    private val drawables: File get() = File(mainSrc, "res/drawable")

    /** The 11 files the PlayerScreen split (APP-10) produced, in split order. */
    private val playerScreenFiles: List<String> = listOf(
        "PlayerScreen.kt", "PlayerGestures.kt", "PlayerHud.kt",
        "PlayerChrome.kt", "PlayerControls.kt", "PlayerCards.kt",
        "PlayerNotices.kt", "PlayerSubtitles.kt", "PlayerDialogs.kt",
        "PlayerSheets.kt", "PlayerScreenUtils.kt",
    )

    private val playerScreen: String by lazy {
        // The drift asserts read the split files concatenated, in split
        // order, so a declaration's neighbours are the same as when the
        // screen was one file.
        val screensDir = File(mainSrc, "kotlin/com/raddstream/app/ui/screens")
        playerScreenFiles.joinToString("\n") { name ->
            val file = File(screensDir, name)
            assertTrue("Player split file $name is missing at ${file.path}", file.isFile)
            file.readText()
        }
    }

    private fun drawable(name: String): String {
        val file = File(drawables, "$name.xml")
        assertTrue("$name.xml is missing at ${file.path}", file.isFile)
        return file.readText()
    }

    private fun pathCount(xml: String): Int = xml.split("android:pathData=").size - 1

    // ── the vectors ─────────────────────────────────────────────────────────

    @Test
    fun `pause and play lost the foot curve`() {
        val pause = drawable("ic_player_pause")
        assertEquals("pause must be two bars and nothing else", 2, pathCount(pause))
        assertFalse("pause still carries the foot curve", pause.contains("20.2"))
        val play = drawable("ic_player_play")
        assertEquals("play must be one triangle and nothing else", 1, pathCount(play))
        assertFalse("play still carries the foot curve", play.contains("20.2"))
    }

    @Test
    fun `subtitles is a caption card, not a chat bubble`() {
        val xml = drawable("ic_player_subtitles")
        assertEquals("subtitles must be a rect plus two caption lines", 3, pathCount(xml))
        assertFalse("subtitles still carries the bubble tail", xml.contains("19.5"))
        assertTrue("subtitles must start with the rounded rect", xml.contains("M6,4 H18"))
        assertTrue("subtitles must carry the top caption line", xml.contains("M7.5,9.5"))
    }

    @Test
    fun `fullscreen is the bolder variant`() {
        assertTrue(
            "fullscreen must use the 2.5dp stroke",
            drawable("ic_player_fullscreen").contains("android:strokeWidth=\"2.5\""),
        )
    }

    @Test
    fun `the one outlined family has a vector for every replaced control`() {
        listOf(
            "ic_player_back", "ic_player_lock", "ic_player_lock_open",
            "ic_player_more", "ic_player_volume_muted",
            "ic_player_fullscreen_exit", "ic_player_switch_source",
        ).forEach { name ->
            assertTrue("$name.xml is missing", File(drawables, "$name.xml").isFile)
        }
    }

    @Test
    fun `no filled Material icon survives in the player chrome`() {
        listOf(
            "Icons.Default.ArrowBack", "Icons.Default.Lock", "Icons.Default.ListAlt",
            "Icons.Default.MoreVert", "Icons.Default.VolumeMute",
            "Icons.Default.FullscreenExit", "Icons.Default.LockOpen",
        ).forEach { icon ->
            assertFalse("PlayerScreen still paints $icon", playerScreen.contains(icon))
        }
    }

    @Test
    fun `quality lives in More, not in a pill that can dim`() {
        // P1-15 (audit B-17): the Quality pill left the bottom row with the
        // flexible slots — Quality is a row in More now, naming the rung in
        // play. A tappable control never dims, and a sheet row has no dimmed
        // state to regress, so the old pill's rule is pinned here as the
        // row's existence, not as the pill's paint.
        assertFalse("the Quality pill is back", playerScreen.contains("fun QualityPill("))
        assertFalse("the speed pill is back", playerScreen.contains("fun SpeedPill("))
        val moreStart = playerScreen.indexOf("fun MoreSheet(")
        assertTrue("MoreSheet is gone", moreStart >= 0)
        val more = playerScreen.substring(moreStart)
        assertTrue(
            "More lost the Quality row",
            more.contains("vm.showQualitySheet()"),
        )
        assertTrue(
            "the Quality row must name the rung in play",
            more.contains("R.string.player_quality_label"),
        )
    }

    @Test
    fun `the dead live strings are gone`() {
        assertFalse("player_live_broadcast still used", playerScreen.contains("player_live_broadcast"))
        assertFalse("player_prints_tools still used", playerScreen.contains("player_prints_tools"))
    }

    // ── the shared rail, pure ───────────────────────────────────────────────

    @Test
    fun `the safe rail is 48dp, or the cutout plus 16dp when wider`() {
        assertEquals(48.dp, playerSafeRailDp(0.dp, 0.dp))
        assertEquals(48.dp, playerSafeRailDp(32.dp, 0.dp))
        assertEquals(56.dp, playerSafeRailDp(40.dp, 0.dp))
        assertEquals(56.dp, playerSafeRailDp(0.dp, 40.dp))
        assertEquals(64.dp, playerSafeRailDp(48.dp, 0.dp))
    }

    // ── the composed chrome ─────────────────────────────────────────────────

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

    private fun liveBase(isPlaying: Boolean = true) = vodBase(isPlaying).copy(
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
    fun `exactly one live mark rides the bottom row`() {
        setBottom(liveBase())
        assertEquals(
            "the bottom row must carry exactly one LIVE",
            1,
            compose.onAllNodesWithText("LIVE").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Live").assertDoesNotExist()
        compose.onNodeWithText("Live Broadcast").assertDoesNotExist()
    }

    @Test
    fun `a paused live picture reads PAUSED, never LIVE`() {
        setBottom(liveBase(isPlaying = false))
        compose.onNodeWithText("PAUSED").assertIsDisplayed()
        compose.onNodeWithText("LIVE").assertDoesNotExist()
    }

    @Test
    fun `a buffering live picture reads LIVE, never PAUSED`() {
        // Video-audit #3 (27 Nov 2026): the Madani mark flicked LIVE ↔
        // PAUSED every ~2 s on the rebuffer cycle. A stall keeps LIVE;
        // only a settled pause reads PAUSED.
        setBottom(liveBase(isPlaying = false).copy(isBuffering = true))
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        compose.onNodeWithText("PAUSED").assertDoesNotExist()
    }

    @Test
    fun `a single audio track hides the Audio field, a raw id never prints`() {
        // Video-audit #17 (27 Nov 2026): the pause line read
        // "Auto · Audio: audio_0 · Subtitles: Off". One track needs no
        // Audio field at all, and a muxer id is never printed.
        val single = TrackUiOption(
            id = "g0-t0",
            group = TrackGroup(Format.Builder().build()),
            trackIndex = 0,
            label = "audio_0",
            language = "ur",
            supported = true,
            selected = true,
        )
        setBottom(vodBase(isPlaying = false).copy(audioTracks = listOf(single)))
        compose.onNodeWithText("Audio:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("audio_0", substring = true).assertDoesNotExist()
    }

    @Test
    fun `two audio tracks name the real language, never the raw id`() {
        // Video-audit #17 (27 Nov 2026): with a choice to name, the line
        // reads the language — "Audio: Urdu" — not the muxer id.
        val group = TrackGroup(Format.Builder().build())
        val tracks = listOf(
            TrackUiOption("g0-t0", group, 0, "audio_0", "ur", true, true),
            TrackUiOption("g0-t1", group, 1, "audio_1", "eng", true, false),
        )
        setBottom(vodBase(isPlaying = false).copy(audioTracks = tracks))
        compose.onNodeWithText("Audio: Urdu", substring = true).assertIsDisplayed()
        compose.onNodeWithText("audio_0", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the compact row decision follows the 360dp arithmetic`() {
        // Video-audit #20 (27 Nov 2026): seven 48dp controls (376dp) plus
        // 48dp rails cannot fit a 360dp or 411dp phone; the compact row
        // (304dp + 16dp rails) fits 360dp with room to spare, and a wide
        // landscape frame keeps the full row.
        assertTrue(playerControlsNeedCompact(360, 48.dp))
        assertTrue(playerControlsNeedCompact(411, 48.dp))
        assertFalse(playerControlsNeedCompact(851, 48.dp))
        assertFalse(playerControlsNeedCompact(600, 48.dp))
        assertTrue(
            "the compact row plus its rails must fit the 360dp phone",
            PlayerRowCompactWidth + PlayerRowCompactRail * 2 <= 360.dp,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `all seven controls fit inside a 360dp-wide player row`() {
        // Video-audit #20 (27 Nov 2026): on the Tecno SPARK 7T the row
        // showed five icons and Fullscreen + More were off-screen. At
        // 360dp every control's bounds must sit inside the root's bounds.
        setBottom(vodBase())
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription(context.getString(R.string.player_more))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("More must sit inside the row, not past its edge", more.right <= root.right)
        assertTrue("More must sit inside the row, not before its start", more.left >= root.left)
        val fullscreen = compose.onNodeWithContentDescription(
            context.getString(R.string.player_exit_fullscreen),
        ).fetchSemanticsNode().boundsInRoot
        assertTrue("Fullscreen must sit inside the row", fullscreen.right <= root.right)
        val left = compose.onNodeWithTag("player-controls-left").fetchSemanticsNode().boundsInRoot
        assertTrue("the transport cluster must sit inside the row", left.left >= root.left)
        val right = compose.onNodeWithTag("player-controls-right").fetchSemanticsNode().boundsInRoot
        assertTrue("the tools cluster must sit inside the row", right.right <= root.right)
    }

    @Test
    fun `the control row is two groups, transport left and tools right`() {
        // P1-15 (audit B-17): the left cluster is Play/Pause, Back 10,
        // Forward 10, Volume (4); the right cluster is Captions/Audio,
        // Fullscreen, More (3). Fixed positions, same order every time.
        setBottom(vodBase())
        compose.onNodeWithTag("player-controls-left").assertIsDisplayed()
        compose.onNodeWithTag("player-controls-right").assertIsDisplayed()
        compose.onNodeWithTag("player-controls-left").onChildren().assertCountEquals(4)
        compose.onNodeWithTag("player-controls-right").onChildren().assertCountEquals(3)
    }

    @Test
    fun `a single-rung title carries no quality pill in the row`() {
        // P1-15 (audit B-17): the Quality pill left the bottom row with the
        // flexible slots — a single rung no longer buys a pill. Quality
        // lives in More now (pinned as a row in PlayerControlSemanticsTest
        // and PlayerReviewFixesTest), so the row prints no rung at all.
        setBottom(
            vodBase().copy(
                qualityAuto = false,
                qualityRungs = listOf(LadderRung(band = "1080p", streamId = "h-1080p")),
            ),
        )
        compose.onNodeWithText("1080P").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_more)).assertExists()
    }

    @Test
    fun `a paused live centre shows the play button and no skips`() {
        compose.setContent {
            RaddStreamTheme {
                CenterPlayPause(
                    state = liveBase(isPlaying = false),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                    onSeekBy = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_play_centre)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_rewind_10))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.player_forward_10))
            .assertDoesNotExist()
    }
}
