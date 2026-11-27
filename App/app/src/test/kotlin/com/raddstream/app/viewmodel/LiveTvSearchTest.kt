package com.raddstream.app.viewmodel

import com.raddstream.app.network.buildRaddGson
import com.raddstream.app.network.models.LiveTvChannelsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live TV review (Nov 2026, D2 + D5): the search matches name + language
 * + country + category, and the cached list's age reads in viewer words.
 * Both are pure functions pinned against the real 759-channel payload
 * (`live-tv-channels-759.json`, the body the server served 2026-11-16).
 *
 * Citation correction: the review's prose said "100+ Urdu channels" and
 * "geo finds Geo News" — the fixture says 34 Urdu-language channels and
 * no channel named Geo at all. The defect is real either way (name-only
 * "urdu" finds 5 of the 34, name-only "pakistan" finds 1 of the 44), but
 * the counts pinned here are the fixture's, not the review's.
 */
class LiveTvSearchTest {

    private fun loadPayload(): LiveTvChannelsResponse {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("live-tv-channels-759.json")!!
        val json = stream.bufferedReader().use { it.readText() }
        return buildRaddGson().fromJson(json, LiveTvChannelsResponse::class.java)
    }

    private fun stateWith(query: String) = LiveTvViewModel.UiState(
        channels = loadPayload().channels,
        categories = loadPayload().categories,
        query = query,
    )

    @Test
    fun urduFindsAllUrduChannelsNotJustTheOnesNamedUrdu() {
        val visible = stateWith("urdu").visibleChannels
        // 34 carry the Urdu language; only 5 have "Urdu" in the name —
        // the name-only filter found 5 and hid the other 29.
        assertEquals(34, visible.size)
        assertTrue(visible.all { it.language.trim().lowercase() in setOf("ur", "urdu") })
    }

    @Test
    fun pakistanFindsAllPakistaniChannels() {
        val visible = stateWith("pakistan").visibleChannels
        // 44 carry country PK; only 1 has "Pakistan" in the name.
        assertEquals(44, visible.size)
        assertTrue(visible.all { it.country.trim().uppercase() == "PK" })
    }

    @Test
    fun sportsFindsTheSportsCategory() {
        val visible = stateWith("sports").visibleChannels
        assertEquals(8, visible.size)
        assertTrue(visible.all { it.category == "sports" })
    }

    @Test
    fun nameSearchStillWorks() {
        val visible = stateWith("dunya").visibleChannels
        assertEquals(1, visible.size)
        assertEquals("Dunya News", visible.first().name)
    }

    @Test
    fun anUnknownWordFindsNothing() {
        assertTrue(stateWith("zzz").visibleChannels.isEmpty())
    }

    @Test
    fun cacheAgeReadsInViewerWords() {
        val now = 1_793_000_000_000L
        assertEquals("just now", formatCacheAge(now - 30_000L, now))
        assertEquals("just now", formatCacheAge(now + 60_000L, now))
        assertEquals("1 minute ago", formatCacheAge(now - 60_000L, now))
        assertEquals("5 minutes ago", formatCacheAge(now - 5 * 60_000L, now))
        assertEquals("1 hour ago", formatCacheAge(now - 3_600_000L, now))
        assertEquals("3 hours ago", formatCacheAge(now - 3 * 3_600_000L, now))
        assertEquals("1 day ago", formatCacheAge(now - 86_400_000L, now))
        assertEquals("2 days ago", formatCacheAge(now - 2 * 86_400_000L, now))
        assertEquals("more than a week ago", formatCacheAge(now - 8 * 86_400_000L, now))
    }

    @Test
    fun deduplicateDropsARepeatedChannelIdAndKeepsOrder() {
        // Video-audit #9 (Nov 2026): a channel the registry files twice
        // painted twice. The first row wins, the server's order is kept.
        val channels = loadPayload().channels
        val doubled = listOf(channels[0]) + channels
        val deduped = deduplicateLiveTvChannels(doubled)
        assertEquals(channels.size, deduped.size)
        assertEquals(channels.map { it.id }, deduped.map { it.id })
    }

    @Test
    fun deduplicateKeepsSameNameDifferentLanguageChannels() {
        // Video-audit #9, the other half: "Al Jazeera English" and
        // "Al Jazeera Arabic" share a prefix and a logo and read as one
        // duplicated card — but they are two channels (ids 26 and 27 in
        // the live list), so the dedup must keep both. The visible fix
        // for the pair is the card's two-line name, not the dedup.
        val english = loadPayload().channels.first { it.name == "Al Jazeera English" }
        val arabic = loadPayload().channels.first { it.name == "Al Jazeera Arabic" }
        val deduped = deduplicateLiveTvChannels(listOf(arabic, english, arabic))
        assertEquals(listOf(arabic, english), deduped)
    }

    @Test
    fun realPayloadHasNoDuplicates() {
        // The 759-channel fixture the server served 2026-11-16 carries no
        // repeated id — the dedup is a guard for a registry that files a
        // channel twice, not a repair for this payload.
        val channels = loadPayload().channels
        assertEquals(channels.size, deduplicateLiveTvChannels(channels).size)
    }
}
