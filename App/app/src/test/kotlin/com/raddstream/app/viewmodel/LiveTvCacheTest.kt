package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** App-design audit (2026-11-12, issue #13): the last good list survives a down server. */
class LiveTvCacheTest {

    private fun channel(id: Int, name: String) = LiveTvChannel(
        id = id,
        name = name,
        category = "news",
        country = "PK",
        language = "en",
        logoUrl = "",
        streamId = "stream-$id",
    )

    @Test
    fun roundTripsChannelsAndCategories() {
        val channels = listOf(channel(36, "ABC News"), channel(37, "Bloomberg TV"))
        val json = LiveTvCache.encode(channels, listOf("news"))
        val decoded = LiveTvCache.decode(json)
        assertEquals(channels, decoded?.channels)
        assertEquals(listOf("news"), decoded?.categories)
    }

    @Test
    fun roundTripsTheSavedAtTimestamp() {
        // Live TV review D5: the notice says how old the saved list is,
        // so the timestamp has to survive the round-trip.
        val savedAt = 1_793_000_000_000L
        val json = LiveTvCache.encode(
            listOf(channel(36, "ABC News")),
            listOf("news"),
            savedAtMillis = savedAt,
        )
        assertEquals(savedAt, LiveTvCache.decode(json)?.savedAtMillis)
    }

    @Test
    fun aCacheWrittenBeforeTheTimestampReadsAsUnknownAge() {
        // Old caches carry no saved_at — the notice falls back to the
        // wording it always had, never a guessed date.
        val legacy = """{"channels":[{"id":36,"name":"ABC News","category":"news","country":"PK","language":"en","logo_url":"","stream_id":"stream-36"}],"categories":["news"]}"""
        val decoded = LiveTvCache.decode(legacy)
        assertEquals(1, decoded?.channels?.size)
        assertNull(decoded?.savedAtMillis)
    }

    @Test
    fun blankOrBrokenJsonDecodesToNothing() {
        assertNull(LiveTvCache.decode(null))
        assertNull(LiveTvCache.decode(""))
        assertNull(LiveTvCache.decode("not json"))
        assertNull(LiveTvCache.decode(LiveTvCache.encode(emptyList(), emptyList())))
    }
}
