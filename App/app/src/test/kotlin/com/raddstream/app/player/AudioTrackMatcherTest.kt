package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-only coverage for the dual-audio policy. No Robolectric: the matcher takes plain
 * Kotlin types precisely so the ladder of rules can be walked with nothing but strings,
 * which is also what the packager gave us to work with.
 */
class AudioTrackMatcherTest {

    private fun track(
        id: String,
        language: String? = null,
        label: String? = null,
        selected: Boolean = false,
        supported: Boolean = true,
    ) = AudioCandidate(
        id = id,
        language = language,
        label = label,
        selected = selected,
        supported = supported,
    )

    // ── exact tag match ──────────────────────────────────────────────────────────

    @Test
    fun `exact three letter tag wins`() {
        val tracks = listOf(
            track("g0-t0", language = "eng", label = "English"),
            track("g0-t1", language = "hin", label = "Hindi"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.match(tracks, preferred = "hin"))
    }

    @Test
    fun `two letter preference matches a three letter tag`() {
        val tracks = listOf(
            track("g0-t0", language = "hin", label = "Audio 1"),
            track("g0-t1", language = "eng", label = "Audio 2"),
        )
        assertEquals("g0-t0", AudioTrackMatcher.match(tracks, preferred = "hi"))
    }

    @Test
    fun `three letter preference matches a two letter tag`() {
        val tracks = listOf(
            track("g0-t0", language = "en", label = "Audio 1"),
            track("g0-t1", language = "hi", label = "Audio 2"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.match(tracks, preferred = "hin"))
    }

    @Test
    fun `messy tags are normalised`() {
        val tracks = listOf(
            track("padded", language = " HI ", label = "Padded"),
            track("region", language = "hin-IN", label = "Region"),
            track("underscore", language = "hi_IN", label = "Underscored"),
        )
        assertEquals("padded", AudioTrackMatcher.match(tracks, preferred = "Hindi"))
        assertEquals("padded", AudioTrackMatcher.match(tracks, preferred = "hi"))
        assertEquals("padded", AudioTrackMatcher.match(tracks, preferred = "hin"))
        assertNull(AudioTrackMatcher.match(tracks, preferred = "ta"))
    }

    @Test
    fun `canonicalLanguageCode folds the forms this catalog emits`() {
        assertEquals("hin", AudioTrackMatcher.canonicalLanguageCode("hi"))
        assertEquals("hin", AudioTrackMatcher.canonicalLanguageCode("Hi-IN"))
        assertEquals("hin", AudioTrackMatcher.canonicalLanguageCode(" Hindi "))
        assertEquals("jpn", AudioTrackMatcher.canonicalLanguageCode("jap"))
        assertEquals("zho", AudioTrackMatcher.canonicalLanguageCode("chi-CN"))
        assertEquals("fra", AudioTrackMatcher.canonicalLanguageCode("fre"))
        assertNull(AudioTrackMatcher.canonicalLanguageCode("   "))
        assertNull(AudioTrackMatcher.canonicalLanguageCode(null))
    }

    @Test
    fun `displayName is the readable name for a raw atom`() {
        assertEquals("Hindi", AudioTrackMatcher.displayName("hi"))
        assertEquals("Tamil", AudioTrackMatcher.displayName("TAM"))
        assertNull(AudioTrackMatcher.displayName("zzz"))
    }

    // "Unknown" labels (Nov 2026): a muxer that never recorded a language
    // writes "und", and the catalog writes "Unknown" / "N/A" — both are
    // missing values, not languages, so the track sheet shows the bare
    // track label instead of "und" or "Unknown".

    @Test
    fun `displayableLanguageTag drops placeholders and keeps real tags`() {
        assertNull(AudioTrackMatcher.displayableLanguageTag(null))
        assertNull(AudioTrackMatcher.displayableLanguageTag(""))
        assertNull(AudioTrackMatcher.displayableLanguageTag("   "))
        assertNull(AudioTrackMatcher.displayableLanguageTag("und"))
        assertNull(AudioTrackMatcher.displayableLanguageTag("UND"))
        assertNull(AudioTrackMatcher.displayableLanguageTag("Unknown"))
        assertNull(AudioTrackMatcher.displayableLanguageTag("unknown"))
        assertNull(AudioTrackMatcher.displayableLanguageTag("N/A"))
        assertEquals("Hindi", AudioTrackMatcher.displayableLanguageTag("Hindi"))
        assertEquals("hi", AudioTrackMatcher.displayableLanguageTag("hi"))
        assertEquals("English", AudioTrackMatcher.displayableLanguageTag("  English  "))
    }

    // ── label fallback ───────────────────────────────────────────────────────────

    @Test
    fun `label carries the language when no tag is present`() {
        val tracks = listOf(
            track("g0-t0", label = "English"),
            track("g0-t1", label = "Hindi (Dubbed)"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.match(tracks, preferred = "hin"))
        assertEquals("g0-t0", AudioTrackMatcher.match(tracks, preferred = "en"))
    }

    @Test
    fun `bare code inside a label counts as a language claim`() {
        val tracks = listOf(
            track("g0-t0", label = "[EN] Original"),
            track("g0-t1", label = "[HI] Dubbed"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.match(tracks, preferred = "hin"))
        assertEquals("g0-t0", AudioTrackMatcher.match(tracks, preferred = "eng"))
    }

    @Test
    fun `a declared tag outranks a label claim on another track`() {
        val tracks = listOf(
            track("g0-t0", label = "Hindi Audio"),
            track("g0-t1", language = "hin", label = "Track 2"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.match(tracks, preferred = "hi"))
    }

    @Test
    fun `a foreign tag is not overruled by its own label decoration`() {
        // Real packs mislabel: the atom says English, the label says Hindi. The atom wins,
        // so the user stays on audio that is actually what the container promises.
        val tracks = listOf(
            track("g0-t0", language = "eng", label = "Hindi Dubbed"),
            track("g1-t0", language = "eng", label = "English"),
        )
        assertNull(AudioTrackMatcher.match(tracks, preferred = "hin"))
    }

    @Test
    fun `an unknown preference is matched as a word, never as a substring`() {
        val tracks = listOf(track("g0-t0", label = "Surrounded by reverb"))
        assertNull(AudioTrackMatcher.match(tracks, preferred = "und"))
    }

    // ── fallback language ────────────────────────────────────────────────────────

    @Test
    fun `fallback language is used when the preference is absent`() {
        val tracks = listOf(
            track("g0-t0", language = "eng", label = "English"),
            track("g0-t1", language = "tam", label = "Tamil"),
        )
        assertEquals("g0-t0", AudioTrackMatcher.match(tracks, preferred = "hin", fallback = "en"))
    }

    @Test
    fun `preference beats fallback when both exist`() {
        val tracks = listOf(
            track("g0-t0", language = "eng", label = "English"),
            track("g0-t1", language = "hin", label = "Hindi"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.match(tracks, preferred = "hi", fallback = "en"))
    }

    @Test
    fun `a satisfied preference does not fall through to the fallback`() {
        val tracks = listOf(
            track("g0-t0", language = "hin", label = "Hindi", selected = true),
            track("g0-t1", language = "eng", label = "English"),
        )
        assertNull(AudioTrackMatcher.match(tracks, preferred = "hin", fallback = "eng"))
    }

    // ── nothing to do ────────────────────────────────────────────────────────────

    @Test
    fun `no match at all is null, not a guess`() {
        val tracks = listOf(
            track("g0-t0", language = "eng", label = "English"),
            track("g0-t1", language = "spa", label = "Spanish"),
        )
        assertNull(AudioTrackMatcher.match(tracks, preferred = "jpn"))
    }

    @Test
    fun `the already selected track is never reselected`() {
        val tracks = listOf(
            track("g0-t0", language = "eng", label = "English"),
            track("g0-t1", language = "hin", label = "Hindi", selected = true),
        )
        assertNull(AudioTrackMatcher.match(tracks, preferred = "hi"))
        assertNull(AudioTrackMatcher.match(tracks, preferred = "hin"))
    }

    @Test
    fun `an empty track list is null`() {
        assertNull(AudioTrackMatcher.match(emptyList(), preferred = "hin", fallback = "eng"))
    }

    @Test
    fun `a blank or missing preference is null`() {
        val tracks = listOf(track("g0-t0", language = "hin", label = "Hindi"))
        assertNull(AudioTrackMatcher.match(tracks, preferred = null))
        assertNull(AudioTrackMatcher.match(tracks, preferred = "   "))
        assertNull(AudioTrackMatcher.match(tracks, preferred = "", fallback = ""))
    }

    @Test
    fun `an unsupported track is never recommended`() {
        val tracks = listOf(
            track("g0-t0", language = "hin", label = "Hindi", supported = false),
            track("g0-t1", language = "eng", label = "English"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.match(tracks, preferred = "hin", fallback = "eng"))
        assertNull(AudioTrackMatcher.match(tracks, preferred = "hin"))
    }

    // ── swap-audio gesture ───────────────────────────────────────────────────────

    @Test
    fun `cycle steps to the next track and wraps at the end`() {
        val tracks = listOf(
            track("g0-t0", language = "hin", label = "Hindi"),
            track("g0-t1", language = "eng", label = "English"),
        )
        assertEquals("g0-t1", AudioTrackMatcher.cycle(tracks))
        assertEquals("g0-t0", AudioTrackMatcher.cycle(tracks, currentId = "g0-t1"))
    }

    @Test
    fun `cycle follows the selected flag when no hint is given`() {
        val tracks = listOf(
            track("g0-t0", language = "hin", label = "Hindi"),
            track("g0-t1", language = "eng", label = "English", selected = true),
            track("g0-t2", language = "tam", label = "Tamil"),
        )
        assertEquals("g0-t2", AudioTrackMatcher.cycle(tracks))
        assertEquals("g0-t1", AudioTrackMatcher.cycle(tracks, currentId = "g0-t0"))
    }

    @Test
    fun `cycle is off when there is nothing to swap to`() {
        val single = listOf(track("g0-t0", language = "hin", label = "Hindi"))
        assertNull(AudioTrackMatcher.cycle(single))
        assertNull(AudioTrackMatcher.cycle(emptyList()))
        val onePlayable = listOf(
            track("g0-t0", language = "hin", label = "Hindi"),
            track("g0-t1", language = "eng", label = "English", supported = false),
        )
        assertNull(AudioTrackMatcher.cycle(onePlayable))
    }

    @Test
    fun `cycle skips an unsupported track instead of landing on silence`() {
        val tracks = listOf(
            track("g0-t0", language = "hin", label = "Hindi"),
            track("g0-t1", language = "eng", label = "English", supported = false),
            track("g0-t2", language = "tam", label = "Tamil"),
        )
        assertEquals("g0-t2", AudioTrackMatcher.cycle(tracks, currentId = "g0-t0"))
    }

    // ── raw track ids (video-audit #17, 27 Nov 2026) ────────────────────────────
    // A muxer-generated id ("audio_0") is not a name: the status line must
    // never print one, and the display name must prefer the real language.

    @Test
    fun `a muxer-generated id is a raw id, a human label is not`() {
        assertTrue(AudioTrackMatcher.isRawTrackId("audio_0"))
        assertTrue(AudioTrackMatcher.isRawTrackId("audio_1"))
        assertTrue(AudioTrackMatcher.isRawTrackId("sub_0"))
        assertTrue(AudioTrackMatcher.isRawTrackId("track-2"))
        assertTrue(AudioTrackMatcher.isRawTrackId("AUDIO_0"))
        assertFalse(AudioTrackMatcher.isRawTrackId("Hindi"))
        assertFalse(AudioTrackMatcher.isRawTrackId("Hindi Dubbed"))
        assertFalse(AudioTrackMatcher.isRawTrackId("Audio 1"))
        assertFalse(AudioTrackMatcher.isRawTrackId("English [HD]"))
        assertFalse(AudioTrackMatcher.isRawTrackId(null))
        assertFalse(AudioTrackMatcher.isRawTrackId(""))
    }

    @Test
    fun `displayAudioName prefers the language and never prints a raw id`() {
        // The audit's own case: Madani's track carries label "audio_0"
        // and language "ur" — the line reads Urdu, never audio_0.
        assertEquals("Urdu", AudioTrackMatcher.displayAudioName("ur", "audio_0"))
        assertEquals("Urdu", AudioTrackMatcher.displayAudioName("urd", "audio_0"))
        assertEquals("Hindi", AudioTrackMatcher.displayAudioName("hin", "Hindi Dubbed"))
        assertEquals("English", AudioTrackMatcher.displayAudioName(null, "English"))
        assertNull(AudioTrackMatcher.displayAudioName(null, "audio_0"))
        assertNull(AudioTrackMatcher.displayAudioName("und", "sub_0"))
        assertNull(AudioTrackMatcher.displayAudioName(null, null))
    }
}
