package com.raddstream.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * E2 slice 2: the follow list's Room table — insert, observe, advance the
 * last-seen scope, unfollow. x86_64-only like every Robolectric suite
 * (see the arm64 exclusion in `app/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
class FollowedSeriesDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FollowedSeriesDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.followedSeriesDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun onePiece(lastSeenSeason: Int? = 2, lastSeenEpisode: Int? = 4) =
        FollowedSeries(
            workRef = "work:one-piece",
            cleanTitle = "One Piece",
            title = "One Piece",
            lastSeenSeason = lastSeenSeason,
            lastSeenEpisode = lastSeenEpisode,
        )

    @Test
    fun `follow then unfollow flips the bell`() = runBlocking {
        assertFalse(dao.isFollowed("work:one-piece").first())
        dao.insert(onePiece())
        assertTrue(dao.isFollowed("work:one-piece").first())
        assertEquals("One Piece", dao.findByWorkRef("work:one-piece")?.title)
        dao.deleteByWorkRef("work:one-piece")
        assertFalse(dao.isFollowed("work:one-piece").first())
        assertNull(dao.findByWorkRef("work:one-piece"))
    }

    @Test
    fun `updateLastSeen advances both halves of the scope together`() = runBlocking {
        dao.insert(onePiece())
        dao.updateLastSeen("work:one-piece", 2, 5)
        val row = dao.findByWorkRef("work:one-piece")
        assertEquals(2, row?.lastSeenSeason)
        assertEquals(5, row?.lastSeenEpisode)
    }

    @Test
    fun `followedOnce lists newest follows first`() = runBlocking {
        dao.insert(onePiece().copy(workRef = "work:old", followedAt = 100L))
        dao.insert(onePiece().copy(workRef = "work:new", followedAt = 200L))
        val rows = dao.followedOnce()
        assertEquals(listOf("work:new", "work:old"), rows.map { it.workRef })
    }
}
