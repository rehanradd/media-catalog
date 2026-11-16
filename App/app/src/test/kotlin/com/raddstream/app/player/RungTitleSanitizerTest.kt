package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Detail review (Nov 2026, P0): the rung's painted title is a clean tier
 * name, never the server's raw codec label. The band is the row key, so
 * it names the tier; an unknown band is resolved from the label's
 * resolution words; a label with no resolution in it passes through
 * verbatim — the fallback, never an invention.
 */
class RungTitleSanitizerTest {

    @Test
    fun bandNamesTheTier() {
        assertEquals("1080p Full HD", QualityLadder.sanitizeRungTitle("1080p", "HD · X264"))
        assertEquals("1080p Full HD", QualityLadder.sanitizeRungTitle("1080p", "Full HD · X264"))
        assertEquals("4K Ultra HD", QualityLadder.sanitizeRungTitle("2160p", "4K Ultra HD · HEVC"))
        assertEquals("720p HD", QualityLadder.sanitizeRungTitle("720p", "HD - X264"))
        assertEquals("480p", QualityLadder.sanitizeRungTitle("480p", "480p"))
        assertEquals("SD", QualityLadder.sanitizeRungTitle("sd", "SD"))
    }

    @Test
    fun alreadyCleanTitleIsUnchanged() {
        assertEquals("1080p Full HD", QualityLadder.sanitizeRungTitle("1080p", "1080p Full HD"))
        assertEquals("4K Ultra HD", QualityLadder.sanitizeRungTitle("2160p", "4K Ultra HD"))
    }

    @Test
    fun unknownBandFallsBackToResolutionWords() {
        assertEquals(
            "1080p Full HD",
            QualityLadder.sanitizeRungTitle("unspecified", "Full HD · X264"),
        )
        assertEquals(
            "4K Ultra HD",
            QualityLadder.sanitizeRungTitle("unspecified", "4K Ultra HD · HEVC"),
        )
        assertEquals("720p HD", QualityLadder.sanitizeRungTitle("unspecified", "720p HD"))
    }

    @Test
    fun unparseableLabelPassesThroughVerbatim() {
        // "HD" alone names no resolution — the server label survives
        // untouched rather than being guessed at.
        assertEquals("HD · X264", QualityLadder.sanitizeRungTitle("unspecified", "HD · X264"))
        assertEquals("Weird Label", QualityLadder.sanitizeRungTitle("unspecified", "Weird Label"))
    }
}
