package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 (player fixes) — the preference-persistence rules, pinned:
 * default → override → reset → visible indicator, one test per rule.
 * These are rules, not behaviour: the ViewModel and the DataStore already
 * implement them (F-03 debounce untouched); this test is what notices if
 * a later change quietly moves one.
 */
class PlayerPreferenceRulesTest {

    // ── Speed: resets per title unless a per-title override exists ───────

    @Test
    fun `a new title with no override starts at 1x`() {
        assertEquals(1f, PlayerPreferenceRules.effectiveSpeedForNewTitle(null), 0f)
        assertTrue(PlayerPreferenceRules.speedResetsOnTitleChange(null))
    }

    @Test
    fun `a per-title override survives the title change`() {
        assertEquals(1.5f, PlayerPreferenceRules.effectiveSpeedForNewTitle(1.5f), 0f)
        assertFalse(PlayerPreferenceRules.speedResetsOnTitleChange(1.5f))
    }

    @Test
    fun `speed does not persist per title`() {
        assertFalse(PlayerPreferenceRules.persistsPerTitle(PlayerPreferenceRules.PreferenceKind.SPEED))
    }

    // ── Quality: the global default is 480p ──────────────────────────────

    @Test
    fun `the global default quality is 480p`() {
        assertEquals("480p", PlayerPreferenceRules.defaultStartBand())
        assertEquals(DEFAULT_START_BAND, PlayerPreferenceRules.defaultStartBand())
    }

    // ── Subtitle, audio, quality: persist per title ─────────────────────

    @Test
    fun `subtitle audio and quality persist per title`() {
        assertTrue(PlayerPreferenceRules.persistsPerTitle(PlayerPreferenceRules.PreferenceKind.SUBTITLE))
        assertTrue(PlayerPreferenceRules.persistsPerTitle(PlayerPreferenceRules.PreferenceKind.AUDIO))
        assertTrue(PlayerPreferenceRules.persistsPerTitle(PlayerPreferenceRules.PreferenceKind.QUALITY))
    }

    // ── Visible indicators: every kind shows its state ──────────────────

    @Test
    fun `every preference has a visible current-state indicator`() {
        assertEquals(
            PlayerPreferenceRules.VisibleIndicator.SPEED_PILL,
            PlayerPreferenceRules.visibleIndicator(PlayerPreferenceRules.PreferenceKind.SPEED),
        )
        assertEquals(
            PlayerPreferenceRules.VisibleIndicator.SUBTITLE_BUTTON,
            PlayerPreferenceRules.visibleIndicator(PlayerPreferenceRules.PreferenceKind.SUBTITLE),
        )
        assertEquals(
            PlayerPreferenceRules.VisibleIndicator.AUDIO_BUTTON,
            PlayerPreferenceRules.visibleIndicator(PlayerPreferenceRules.PreferenceKind.AUDIO),
        )
        assertEquals(
            PlayerPreferenceRules.VisibleIndicator.QUALITY_PILL,
            PlayerPreferenceRules.visibleIndicator(PlayerPreferenceRules.PreferenceKind.QUALITY),
        )
    }
}
