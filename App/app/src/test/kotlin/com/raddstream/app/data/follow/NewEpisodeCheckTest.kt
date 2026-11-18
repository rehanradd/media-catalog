package com.raddstream.app.data.follow

import com.raddstream.app.network.models.ReleaseDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2 slice 2: the pure half of the follow check — which release is newest,
 * and whether it is newer than what the viewer already saw. No Android, no
 * network, no Room, so this runs as plain JUnit.
 */
class NewEpisodeCheckTest {

    private fun release(id: Int, season: String?, episode: String?, title: String) =
        ReleaseDetail(
            id = id,
            releaseTitle = title,
            season = season,
            episode = episode,
        )

    @Test
    fun `newest scope is the highest season then episode`() {
        val releases = listOf(
            release(1, "1", "10", "One Piece S01 E10 1080p"),
            release(2, "2", "4", "One Piece S02 E04 1080p"),
            release(3, "2", "5", "One Piece S02 E05 1080p"),
        )
        assertEquals(EpisodeScope(2, 5), NewEpisodeCheck.newestScope(releases))
    }

    @Test
    fun `season and episode read from the S02 E05 label grammar`() {
        val releases = listOf(
            release(1, "S02", "E05", "One Piece S02 E05 1080p WEB-DL"),
        )
        assertEquals(EpisodeScope(2, 5), NewEpisodeCheck.newestScope(releases))
    }

    @Test
    fun `a release with no season never counts`() {
        val releases = listOf(
            release(1, null, null, "One Piece Unknown print 1080p"),
            release(2, "2", "5", "One Piece S02 E05 1080p"),
        )
        assertEquals(EpisodeScope(2, 5), NewEpisodeCheck.newestScope(releases))
    }

    @Test
    fun `no season anywhere means no scope`() {
        val releases = listOf(
            release(1, null, null, "One Piece Unknown print 1080p"),
            release(2, null, null, "One Piece Batch 720p"),
        )
        assertNull(NewEpisodeCheck.newestScope(releases))
    }

    @Test
    fun `a whole-season pack outranks any single episode of its season`() {
        val releases = listOf(
            release(1, "3", "2", "One Piece S03 E02 1080p"),
            release(2, "3", null, "One Piece Season 3 Complete 1080p"),
        )
        assertEquals(EpisodeScope(3, null), NewEpisodeCheck.newestScope(releases))
    }

    @Test
    fun `a later season outranks a pack of the earlier one`() {
        val releases = listOf(
            release(1, "2", null, "One Piece Season 2 Complete 1080p"),
            release(2, "3", "1", "One Piece S03 E01 1080p"),
        )
        assertEquals(EpisodeScope(3, 1), NewEpisodeCheck.newestScope(releases))
    }

    @Test
    fun `isNew needs strictly newer`() {
        assertTrue(NewEpisodeCheck.isNew(EpisodeScope(2, 4), EpisodeScope(2, 5)))
        assertFalse(NewEpisodeCheck.isNew(EpisodeScope(2, 5), EpisodeScope(2, 5)))
        assertFalse(NewEpisodeCheck.isNew(EpisodeScope(2, 5), EpisodeScope(2, 4)))
        assertTrue(NewEpisodeCheck.isNew(EpisodeScope(2, 5), EpisodeScope(3, null)))
        assertFalse(NewEpisodeCheck.isNew(EpisodeScope(3, null), EpisodeScope(2, 9)))
    }

    @Test
    fun `isNew never fires without a newest or a last seen`() {
        assertFalse(NewEpisodeCheck.isNew(EpisodeScope(2, 5), null))
        assertFalse(NewEpisodeCheck.isNew(null, EpisodeScope(2, 5)))
        assertFalse(NewEpisodeCheck.isNew(null, null))
    }

    @Test
    fun `the notification names the episode, or the season for a pack`() {
        assertEquals("Season 2 Episode 5 is out", EpisodeScope(2, 5).label())
        assertEquals("Season 3 is out", EpisodeScope(3, null).label())
    }
}
