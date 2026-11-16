package com.raddstream.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Screenshot-fix (2026-11-16): the read-timeout budget per catalog path.
 * The 15 s default killed the big bodies mid-download on the owner's
 * 2-25 KB/s connection — the v2 works detail is a 441 KB payload and the
 * Live TV channel list is 167 KB — so every attempt died and the detail
 * retry burned all 4 attempts before showing "Couldn't load this title".
 * The big bodies get longer budgets; everything else keeps the 15 s
 * default, so a dead small call still fails fast.
 */
class ApiProviderTimeoutTest {

    @Test
    fun worksDetailGets60Seconds() {
        assertEquals(60, readTimeoutSecondsForPath("/api/v2/works/8521"))
        assertEquals(60, readTimeoutSecondsForPath("/api/v2/works/1"))
    }

    @Test
    fun liveTvChannelsGet45Seconds() {
        assertEquals(45, readTimeoutSecondsForPath("/api/v1/live-tv/channels"))
    }

    @Test
    fun v1DetailGets30Seconds() {
        assertEquals(30, readTimeoutSecondsForPath("/api/v1/posts/70430"))
        assertEquals(30, readTimeoutSecondsForPath("/api/v1/posts/11204"))
    }

    @Test
    fun listSearchStreamAndPlayStayOnThe15SecondDefault() {
        assertEquals(15, readTimeoutSecondsForPath("/api/v1/posts"))
        assertEquals(15, readTimeoutSecondsForPath("/api/v1/stream"))
        assertEquals(15, readTimeoutSecondsForPath("/api/v2/search"))
        assertEquals(15, readTimeoutSecondsForPath("/api/v2/play"))
        assertEquals(15, readTimeoutSecondsForPath("/api/v1/live-tv/channels/1"))
        assertEquals(15, readTimeoutSecondsForPath("/api/v1/app/remote-config"))
    }
}
