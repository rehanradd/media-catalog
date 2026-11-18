package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Dev-cleanup (Nov 2026, D12): the settled banner names what is playing and
 * nothing else — the failover count ("after 2 failed candidates") is operator
 * history, not viewer copy, and lives in the failover log only.
 */
class PlayerSessionStoreSettledBannerTest {

    private fun twoRungStore(): PlayerSessionStore {
        val store = PlayerSessionStore()
        store.installBundle(
            PlaybackBundle(
                renditions = listOf(
                    PlaybackRendition(rank = 1, print = PrintInfo(resolution = "1080p")),
                    PlaybackRendition(rank = 2, print = PrintInfo(resolution = "720p")),
                ),
            ),
        )
        return store
    }

    @Test
    fun `settling after a failed candidate names only what is playing`() {
        val store = twoRungStore()
        store.appendFailover(
            listOf(
                FailoverRecord(
                    rank = 1,
                    releaseId = 1,
                    label = "1080p",
                    reason = "Decoder failed",
                    action = "skipped to next rank",
                ),
            ),
        )
        store.setActiveIndex(1, announceSettled = true)

        assertEquals("Playing: 720p · Standard bitrate", store.settledBanner.value)
        assertFalse(store.settledBanner.value.orEmpty().contains("failed"))
    }

    @Test
    fun `settling with no failures names only what is playing`() {
        val store = twoRungStore()
        store.setActiveIndex(0, announceSettled = true)

        assertEquals("Playing: 1080p · Standard bitrate", store.settledBanner.value)
    }

    @Test
    fun `settling on a print with no quality info names standard quality`() {
        val store = PlayerSessionStore()
        store.installBundle(
            PlaybackBundle(
                renditions = listOf(PlaybackRendition(rank = 1, print = PrintInfo())),
            ),
        )
        store.setActiveIndex(0, announceSettled = true)

        assertEquals("Playing: Standard quality", store.settledBanner.value)
    }
}
