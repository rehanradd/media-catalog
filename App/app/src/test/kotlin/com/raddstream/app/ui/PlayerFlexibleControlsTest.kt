package com.raddstream.app.ui

import com.raddstream.app.player.ChapterTick
import com.raddstream.app.player.chapterAt
import com.raddstream.app.ui.screens.FlexiblePlayerControl
import com.raddstream.app.ui.screens.resolveFlexiblePlayerControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1/P2/P8 (player fixes) — the context-aware primary row, the chapter
 * numbering, and the gesture button-equivalents, as pure rules. The row's
 * six-button guarantee is provable by construction here: 4 permanent +
 * the first 2 of the priority list, always 2, never a seventh.
 */
class PlayerFlexibleControlsTest {

    private fun resolve(
        hasNextEpisode: Boolean = false,
        castAvailable: Boolean = false,
        speed: Float = 1f,
        hasMultipleAudioTracks: Boolean = false,
        castPathProven: Boolean = false,
    ) = resolveFlexiblePlayerControls(
        hasNextEpisode = hasNextEpisode,
        castAvailable = castAvailable,
        speed = speed,
        hasMultipleAudioTracks = hasMultipleAudioTracks,
        castPathProven = castPathProven,
    )

    // ── P1: the priority order ───────────────────────────────────────────

    @Test
    fun `a plain title keeps subtitles and quality`() {
        assertEquals(
            listOf(FlexiblePlayerControl.SUBTITLES, FlexiblePlayerControl.QUALITY),
            resolve(),
        )
    }

    @Test
    fun `a next episode takes the first flexible slot`() {
        assertEquals(
            listOf(FlexiblePlayerControl.NEXT_EPISODE, FlexiblePlayerControl.SUBTITLES),
            resolve(hasNextEpisode = true),
        )
    }

    @Test
    fun `a changed speed takes a flexible slot`() {
        assertEquals(
            listOf(FlexiblePlayerControl.SPEED, FlexiblePlayerControl.SUBTITLES),
            resolve(speed = 1.5f),
        )
    }

    @Test
    fun `multiple audio tracks take a flexible slot`() {
        assertEquals(
            listOf(FlexiblePlayerControl.AUDIO, FlexiblePlayerControl.SUBTITLES),
            resolve(hasMultipleAudioTracks = true),
        )
    }

    @Test
    fun `next episode plus speed fills both slots`() {
        assertEquals(
            listOf(FlexiblePlayerControl.NEXT_EPISODE, FlexiblePlayerControl.SPEED),
            resolve(hasNextEpisode = true, speed = 2f),
        )
    }

    @Test
    fun `the row is always exactly two flexible slots`() {
        listOf(
            resolve(),
            resolve(hasNextEpisode = true, speed = 1.5f, hasMultipleAudioTracks = true),
            resolve(castAvailable = true, speed = 0.5f),
        ).forEach { slots -> assertEquals(2, slots.size) }
    }

    // ── P1: the cast gate ────────────────────────────────────────────────

    @Test
    fun `an available cast target stays hidden while the path is unproven`() {
        // CAST_SPIKE proves the SDK wires up, not that a real receiver plays
        // a resolved URL — so a receiver in range must NOT buy a button.
        assertFalse(resolve(castAvailable = true).contains(FlexiblePlayerControl.CAST))
        assertEquals(
            listOf(FlexiblePlayerControl.SUBTITLES, FlexiblePlayerControl.QUALITY),
            resolve(castAvailable = true),
        )
    }

    @Test
    fun `once proven cast outranks speed`() {
        assertEquals(
            listOf(FlexiblePlayerControl.CAST, FlexiblePlayerControl.SPEED),
            resolve(castAvailable = true, speed = 1.5f, castPathProven = true),
        )
    }

    @Test
    fun `once proven next episode still outranks cast`() {
        assertEquals(
            listOf(FlexiblePlayerControl.NEXT_EPISODE, FlexiblePlayerControl.CAST),
            resolve(hasNextEpisode = true, castAvailable = true, castPathProven = true),
        )
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

    @Test
    fun `the plus-minus-10s skips are buttons not just gestures`() {
        // Centre buttons while paused…
        assertTrue(read("PlayerChrome.kt").contains("CentreSkipButton("))
        // …and More-sheet rows while playing (the centre button is
        // paused-only by design, so the sheet is the playing equivalent).
        val more = read("PlayerSheets.kt")
        assertTrue(more.contains("vm.seekBy(-10_000)"))
        assertTrue(more.contains("vm.seekBy(10_000)"))
    }

    @Test
    fun `the bottom row still carries exactly the six primaries`() {
        // The flexible slots render by priority, but the source must still
        // name every primary the row can show — a slot that lost its call
        // would be a button that lies.
        val row = read("PlayerChrome.kt")
        listOf(
            "onTogglePlayPause()",
            "onToggleMute()",
            "onShowSubtitleSheet()",
            "QualityPill(",
            "onToggleFullscreen()",
            "onShowMoreSheet()",
            "onShowAudioSheet()",
            "onRequestNextEpisode()",
        ).forEach { call -> assertTrue("bottom row lost $call", row.contains(call)) }
        // Speed is a pill, not an icon button: it takes the handler as a
        // value (`onShowSpeedSheet = onShowSpeedSheet`), it never calls it
        // inline — so the wiring check is the pill + the passed handler,
        // not a `onShowSpeedSheet()` call (which no pill slot ever has).
        assertTrue("bottom row lost SpeedPill(", row.contains("SpeedPill("))
        assertTrue(
            "bottom row lost the speed-sheet wiring",
            row.contains("onShowSpeedSheet = onShowSpeedSheet"),
        )
        assertFalse("the bottom row must not scroll", row.contains("horizontalScroll"))
    }
}
