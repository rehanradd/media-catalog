package com.raddstream.app.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssParserTest {

    private val sample = """
        [Script Info]
        PlayResX: 384
        PlayResY: 288

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1
        Style: Top,Arial,24,&H0000FFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,2,8,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello world
        Dialogue: 0,0:00:04.00,0:00:06.00,Top,,0,0,0,,{\an8\pos(192,50)}Top line
        Dialogue: 0,0:00:07.00,0:00:09.00,Default,,0,0,0,,{\b1\i1\c&H0000FF&}Styled{\b0\i0\r} plain
        Dialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,First\NSecond\hword
        Dialogue: 0,0:00:13.00,0:00:15.00,Default,,0,0,0,,{\fs40}Big{\k50}karaoke
        Comment: 0,0:00:16.00,0:00:18.00,Default,,0,0,0,,Not a subtitle
        Dialogue: 0,not-a-time,0:00:19.00,Default,,0,0,0,,Broken timing
        Dialogue: 0,0:00:20.00,0:00:19.00,Default,,0,0,0,,Ends before it starts
        Dialogue: 0,0:00:21.00,0:00:23.00,MissingStyle,,0,0,0,,Falls back to Default
    """.trimIndent()

    @Test
    fun parsesCuesSkippingCommentsAndBrokenLines() {
        val file = AssParser.parse(sample)
        // 8 Dialogue lines minus the broken-timing one and the ends-before-starts one.
        assertEquals(6, file.cues.size)
        assertEquals(384, file.playResX)
        assertEquals(288, file.playResY)
    }

    @Test
    fun plainDialogueKeepsTextAndTimes() {
        val cue = AssParser.parse(sample).cues[0]
        assertEquals(1_000L, cue.startMs)
        assertEquals(3_000L, cue.endMs)
        assertEquals("Hello world", cue.text)
        assertEquals(AssAlignment.BOTTOM_CENTER, cue.alignment)
        assertEquals(1, cue.segments.size)
        assertNull(cue.segments[0].colorArgb)
    }

    @Test
    fun anAndPosSetPlacement() {
        val cue = AssParser.parse(sample).cues[1]
        assertEquals(AssAlignment.TOP_CENTER, cue.alignment)
        // \pos(192,50) on a 384x288 canvas.
        assertEquals(0.5f, cue.position?.xFraction ?: -1f, 0.001f)
        assertEquals(50f / 288f, cue.position?.yFraction ?: -1f, 0.001f)
        assertEquals("Top line", cue.text)
        // The Top style is bold at 24pt.
        assertEquals(24f, cue.styleFontSizePt, 0.001f)
    }

    @Test
    fun overrideTagsSplitRunsAndReset() {
        val cue = AssParser.parse(sample).cues[2]
        assertEquals(2, cue.segments.size)
        val styled = cue.segments[0]
        assertEquals("Styled", styled.text)
        assertEquals(true, styled.bold)
        assertEquals(true, styled.italic)
        // \c&H0000FF& is BGR: blue 00, green 00, red FF — pure red.
        assertEquals(0xFFFF0000.toInt(), styled.colorArgb)
        val plain = cue.segments[1]
        assertEquals(" plain", plain.text)
        // \b0\i0\r reset the run to the style defaults.
        assertNull(plain.bold)
        assertNull(plain.italic)
        assertNull(plain.colorArgb)
    }

    @Test
    fun lineBreaksAndHardSpaces() {
        val cue = AssParser.parse(sample).cues[3]
        assertEquals("First\nSecond word", cue.text)
    }

    @Test
    fun fontSizeScaleAndKaraokeTextKept() {
        val cue = AssParser.parse(sample).cues[4]
        // \fs40 on a 20pt style is a 2x scale; \k50 is dropped, its words kept — and the
        // karaoke tag does not change the size, so the second run keeps the same scale.
        assertEquals("Bigkaraoke", cue.text)
        assertEquals(2, cue.segments.size)
        assertEquals(2.0f, cue.segments[0].fontSizeScale ?: -1f, 0.001f)
        assertEquals("karaoke", cue.segments[1].text)
        assertEquals(2.0f, cue.segments[1].fontSizeScale ?: -1f, 0.001f)
    }

    @Test
    fun missingStyleFallsBackToDefault() {
        val cue = AssParser.parse(sample).cues[5]
        assertEquals("Falls back to Default", cue.text)
        assertEquals(20f, cue.styleFontSizePt, 0.001f)
    }

    @Test
    fun activeCuesRespectDelay() {
        val file = AssParser.parse(sample)
        // +1000 ms delay shows text later: at 1500 ms the first cue is not yet showing.
        assertTrue(file.activeCues(1_500, delayMs = 1_000).isEmpty())
        assertEquals(1, file.activeCues(2_500, delayMs = 1_000).size)
        // -1000 ms delay shows text earlier.
        assertEquals(1, file.activeCues(500, delayMs = -1_000).size)
    }

    @Test
    fun colorConversionHandlesAlphaAndBgrOrder() {
        // &HAABBGGRR&: alpha 00 = opaque, channels BGR.
        assertEquals(0xFFFF0000.toInt(), assColorToArgb("&H0000FF&"))
        assertEquals(0xFF00FF00.toInt(), assColorToArgb("&H00FF00&"))
        assertEquals(0xFF0000FF.toInt(), assColorToArgb("&HFF0000&"))
        // Alpha 0x80 in ASS means half transparent → Android alpha 0x7F.
        assertEquals(0x7FFF0000.toInt(), assColorToArgb("&H800000FF&"))
        // SSA v4 has no alpha: 6 digits, opaque.
        assertEquals(0xFFFF0000.toInt(), assColorToArgb("&H0000FF"))
        assertNull(assColorToArgb("&HZZZZZZ&"))
        assertNull(assColorToArgb(null))
        assertNull(assColorToArgb(""))
    }

    @Test
    fun darkColourGuard() {
        // The Robo run-4 lesson: dark text on a dark frame is unreadable.
        assertFalse(assColorIsReadable(0xFF000000.toInt()))
        assertFalse(assColorIsReadable(0xFF800000.toInt()))
        assertTrue(assColorIsReadable(0xFFFFFFFF.toInt()))
        assertTrue(assColorIsReadable(0xFFFFFF00.toInt()))
    }

    @Test
    fun overlayCuesCarryStyleThrough() {
        val file = AssParser.parse(sample)
        val overlay = file.activeOverlayCues(1_500, delayMs = 0)
        assertEquals(1, overlay.size)
        assertEquals("Hello world", overlay[0].text)
        assertEquals(AssAlignment.BOTTOM_CENTER, overlay[0].alignment)
        assertEquals(20f, overlay[0].styleFontSizePt, 0.001f)
        // The Default style is not bold; the overlay carries that through (not the viewer's).
        assertEquals(false, overlay[0].styleBold)
        assertEquals(0xFFFFFFFF.toInt(), overlay[0].stylePrimaryArgb)
        // The Top style IS bold at 24pt — the overlay carries the file's weight, not the
        // viewer's, exactly as the embedded-styles toggle does for native tracks.
        val top = file.activeOverlayCues(4_500, delayMs = 0)[0]
        assertEquals(true, top.styleBold)
        assertEquals(24f, top.styleFontSizePt, 0.001f)
        assertEquals(6, file.cueBoundaries().size)
        assertEquals(1_000L to 3_000L, file.cueBoundaries()[0])
    }
}
