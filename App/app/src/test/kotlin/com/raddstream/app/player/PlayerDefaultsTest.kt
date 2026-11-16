package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player defaults in Settings (plan §5 step 10, P36): the autoplay-next switch
 * gates the auto-advance, and the default-quality band is a standing decision
 * that outranks Auto's pick but never an explicit tap.
 */
class PlayerDefaultsTest {

    private val nextBundle = NextEpisodeAction.NextBundleRequest(
        workKey = "w1",
        postId = 1,
        season = 1,
        nextEpisode = 2,
        episodeId = null,
    )
    private val inFile = NextEpisodeAction.InFileSeek(
        positionMs = 60_000L,
        nextEpisode = 2,
        fromLearned = false,
    )
    private val rungs = listOf("1080p", "720p", "480p")

    @Test
    fun `autoplay on advances to the next bundle`() {
        assertTrue(shouldAutoAdvance(true, nextBundle))
    }

    @Test
    fun `autoplay off ends on the Next Episode button, no auto-advance`() {
        assertFalse(shouldAutoAdvance(false, nextBundle))
    }

    @Test
    fun `an in-file seek never auto-advances, switch or no switch`() {
        assertFalse(shouldAutoAdvance(true, inFile))
        assertFalse(shouldAutoAdvance(false, inFile))
    }

    @Test
    fun `a movie ending advances nowhere`() {
        assertFalse(shouldAutoAdvance(true, NextEpisodeAction.StopHere))
        assertFalse(shouldAutoAdvance(true, NextEpisodeAction.Unknown))
    }

    @Test
    fun `default band applies when Play was Auto`() {
        assertEquals("720p" to false, resolveStartBand("1080p", true, "720p", rungs))
    }

    @Test
    fun `an explicit tap outranks the default band`() {
        assertEquals("1080p" to false, resolveStartBand("1080p", false, "720p", rungs))
    }

    @Test
    fun `an explicit Auto default keeps Auto's pick`() {
        assertEquals("1080p" to true, resolveStartBand("1080p", true, "auto", rungs))
    }

    @Test
    fun `a default band missing from this ladder is ignored`() {
        assertEquals("1080p" to true, resolveStartBand("1080p", true, "2160p", rungs))
    }

    @Test
    fun `no staged band falls back to the default, then the first rung`() {
        assertEquals("720p" to false, resolveStartBand(null, true, "720p", rungs))
        assertEquals("1080p" to true, resolveStartBand(null, true, "auto", rungs))
    }

    // ── 480p default start (owner order 07:01 PKT 16 Nov) ───────────────────

    @Test
    fun `no saved default starts at 480p with Auto on`() {
        assertEquals("480p" to true, resolveStartBand(null, true, "auto", rungs, defaultBandSet = false))
        assertEquals("480p" to true, resolveStartBand(null, true, "", rungs, defaultBandSet = false))
    }

    @Test
    fun `480p start keeps Auto on on a taller ladder`() {
        assertEquals(
            "480p" to true,
            resolveStartBand(
                null, true, "auto",
                listOf("2160p", "1080p", "720p", "480p"), defaultBandSet = false,
            ),
        )
    }

    @Test
    fun `a start band missing from this ladder falls back to 480p with Auto on`() {
        // The production case is a tapped rung the quality gate filtered out of
        // the ladder before this ran: Auto climbs only within the allowed rungs,
        // since the director is armed on the filtered list.
        assertEquals(
            "480p" to true,
            resolveStartBand("1080p", false, "auto", listOf("720p", "480p"), defaultBandSet = false),
        )
    }

    @Test
    fun `no saved default and no 480p rung falls back to the first rung`() {
        assertEquals(
            "1080p" to true,
            resolveStartBand(null, true, "auto", listOf("1080p", "720p"), defaultBandSet = false),
        )
    }

    @Test
    fun `no saved default never overrides an explicit tap`() {
        assertEquals("720p" to false, resolveStartBand("720p", false, "auto", rungs, defaultBandSet = false))
    }

    @Test
    fun `a saved default still wins over the 480p start`() {
        assertEquals("720p" to false, resolveStartBand(null, true, "720p", rungs, defaultBandSet = false))
    }

    // ── Session 153: the player waits out the slow resolve ───────────────

    @Test
    fun `the read timeout clears the measured resolve ceiling`() {
        // The redirector's live resolve is 6-12 s measured; a 10 s read
        // timeout fired while the 302 was still being minted, before Media3
        // ever touched the file.
        assertTrue(MEDIA_READ_TIMEOUT_MS > 12_000)
        assertTrue(MEDIA_CONNECT_TIMEOUT_MS < MEDIA_READ_TIMEOUT_MS)
    }
}
