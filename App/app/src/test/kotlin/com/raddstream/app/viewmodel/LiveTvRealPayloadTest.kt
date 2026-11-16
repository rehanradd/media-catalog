package com.raddstream.app.viewmodel

import com.raddstream.app.network.buildRaddGson
import com.raddstream.app.network.models.LiveTvChannelsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screenshot-fix (2026-11-16): the Live TV tab photographed empty while
 * the server served 759 channels. The payload is the real body the server
 * returned that night (GET /api/v1/live-tv/channels, 167,364 bytes, saved
 * as `live-tv-channels-759.json`) — the parse is the one app-side step
 * that could silently drop the list, so this test pins it: the app's own
 * Gson must yield all 759 channels and all 9 categories, and the
 * ViewModel's visibleChannels must pass all 759 through with no filter
 * applied. If this test ever fails, the empty tab is a parse bug; if it
 * passes, the empty tab was the fetch (a 167 KB body against a 15 s read
 * timeout on a 2-25 KB/s connection), which the 45 s read budget, the
 * fetch retry, and the server-side gzip now cover.
 */
class LiveTvRealPayloadTest {

    private fun loadPayload(): LiveTvChannelsResponse {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("live-tv-channels-759.json")!!
        val json = stream.bufferedReader().use { it.readText() }
        return buildRaddGson().fromJson(json, LiveTvChannelsResponse::class.java)
    }

    @Test
    fun realPayloadParsesTo759ChannelsAnd9Categories() {
        val response = loadPayload()
        assertEquals(759, response.channels.size)
        assertEquals(
            listOf(
                "news",
                "religious",
                "travel",
                "sports",
                "entertainment",
                "music",
                "movies",
                "education",
                "kids",
            ),
            response.categories,
        )
        assertEquals("Dunya News", response.channels.first().name)
        assertEquals(1, response.channels.first().id)
        assertEquals(759, response.channels.map { it.id }.toSet().size)
        assertTrue(response.channels.none { it.name.isBlank() })
        assertTrue(response.channels.none { it.streamId.isBlank() })
    }

    @Test
    fun visibleChannelsPassesAll759ThroughWithNoFilter() {
        val response = loadPayload()
        val state = LiveTvViewModel.UiState(
            channels = response.channels,
            categories = response.categories,
        )
        assertEquals(759, state.visibleChannels.size)
    }

    @Test
    fun categoryFilterKeepsOnlyThatCategory() {
        val response = loadPayload()
        val newsCount = response.channels.count { it.category == "news" }
        val state = LiveTvViewModel.UiState(
            channels = response.channels,
            categories = response.categories,
            selectedCategory = "news",
        )
        assertEquals(newsCount, state.visibleChannels.size)
        assertTrue(state.visibleChannels.all { it.category == "news" })
    }

    @Test
    fun cacheRoundTripsAll759Channels() {
        val response = loadPayload()
        val json = LiveTvCache.encode(response.channels, response.categories)
        val decoded = LiveTvCache.decode(json)
        assertEquals(759, decoded?.first?.size)
        assertEquals(response.categories, decoded?.second)
    }
}
