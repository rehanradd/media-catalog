package com.raddstream.app.ui

import com.raddstream.app.player.ChapterTick
import com.raddstream.app.player.chapterAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-15 (audit B-17/B-18, player fixes) — the bottom row is fixed now:
 * Play/Pause, Back 10, Forward 10, Volume, Captions/Audio, Fullscreen,
 * More, always in that order, in every context. The flexible slots and
 * their priority resolver are gone; Speed, Quality and Next Episode live
 * in More, their single owners. The chapter numbering and the gesture
 * button-equivalents below are unchanged.
 */
class PlayerFlexibleControlsTest {

    private val screensDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/screens",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private fun read(name: String): String = File(screensDir, name).readText()

    /** The bottom row, from its signature to the next top-level function. */
    private fun bottomRow(): String {
        val source = read("PlayerChrome.kt")
        val start = source.indexOf("internal fun BottomControls(")
        assertTrue("BottomControls is gone", start >= 0)
        val end = source.indexOf("\nprivate fun subtitleActive(", start)
        assertTrue("cannot find the end of BottomControls", end >= 0)
        return source.substring(start, end)
    }

    // ── P1-15: the fixed row ─────────────────────────────────────────────

    @Test
    fun `the bottom row carries exactly the seven fixed controls, in order`() {
        val row = bottomRow()
        // The order is the contract: a control that moves depending on
        // context cannot be learned, so the row is pinned by position,
        // not just by presence.
        val calls = listOf(
            "onTogglePlayPause()" to "Play/Pause",
            "onSeekBy(-10_000)" to "Back 10",
            "onSeekBy(10_000)" to "Forward 10",
            "onToggleMute()" to "Volume",
            "onShowSubtitleSheet()" to "Captions/Audio",
            "onToggleFullscreen()" to "Fullscreen",
            "onShowMoreSheet()" to "More",
        )
        var lastIndex = -1
        calls.forEach { (call, name) ->
            val index = row.indexOf(call)
            assertTrue("the bottom row lost its $name control", index >= 0)
            assertTrue("the $name control moved out of its fixed position", index > lastIndex)
            lastIndex = index
        }
        assertFalse("the bottom row must not scroll", row.contains("horizontalScroll"))
    }

    @Test
    fun `the row never resolves by priority anymore`() {
        val row = bottomRow()
        listOf(
            "resolveFlexiblePlayerControls",
            "FlexiblePlayerControl",
            "SpeedPill(",
            "QualityPill(",
            "onShowSpeedSheet",
            "onShowQualitySheet",
            "onShowAudioSheet",
            "onRequestNextEpisode",
        ).forEach { gone ->
            assertFalse("the bottom row still carries $gone", row.contains(gone))
        }
    }

    @Test
    fun `speed, quality and next episode live in More, not the row`() {
        val more = read("PlayerSheets.kt")
        assertTrue("More lost Speed", more.contains("vm.showSpeedSheet()"))
        assertTrue("More lost Quality", more.contains("vm.showQualitySheet()"))
        assertTrue("More lost Next Episode", more.contains("vm.requestNextEpisode()"))
    }

    // ── P2: the chapter numbering ────────────────────────────────────────

    private val ticks = listOf(
        ChapterTick(offsetMs = 600_000L, title = "Episode 2", fraction = 0.25f),
        ChapterTick(offsetMs = 1_200_000L, title = "Episode 3", fraction = 0.5f),
    )

    @Test
    fun `before the first tick the viewer is in chapter one`() {
        assertEquals(1 to 0L, chapterAt(ticks, 0L))
        assertEquals(1 to 0L, chapterAt(ticks, 599_999L))
    }

    @Test
    fun `passing a tick moves to the next chapter at the tick`() {
        assertEquals(2 to 600_000L, chapterAt(ticks, 600_000L))
        assertEquals(2 to 600_000L, chapterAt(ticks, 900_000L))
        assertEquals(3 to 1_200_000L, chapterAt(ticks, 1_200_000L))
    }

    @Test
    fun `a title with no ticks has one chapter`() {
        assertEquals(1 to 0L, chapterAt(emptyList(), 45_000L))
    }

    // ── P8: every gesture keeps a visible button equivalent ─────────────

    @Test
    fun `the plus-minus-10s skips are buttons not just gestures`() {
        // Centre buttons while paused…
        assertTrue(read("PlayerChrome.kt").contains("CentreSkipButton("))
        // …and More-sheet rows while playing (the centre button is
        // paused-only by design, so the sheet is the playing equivalent).
        val more = read("PlayerSheets.kt")
        assertTrue(more.contains("vm.seekBy(-10_000)"))
        assertTrue(more.contains("vm.seekBy(10_000)"))
        // P1-15: the bottom row carries the skips too, fixed — so the
        // ±10 s jump is a button in every chrome state, not only while
        // paused and not only behind More.
        val row = bottomRow()
        assertTrue(row.contains("onSeekBy(-10_000)"))
        assertTrue(row.contains("onSeekBy(10_000)"))
    }
}
