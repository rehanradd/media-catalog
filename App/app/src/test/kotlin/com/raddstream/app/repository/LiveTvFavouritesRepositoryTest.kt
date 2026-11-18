package com.raddstream.app.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.local.AppDatabase
import com.raddstream.app.data.local.LiveTvFavourite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * E2 slice 1 — Live TV favourites, the persistence half: a star is a Room
 * row keyed by the registry id, so it survives the screen that wrote it.
 * The "leave the tab, come back — still starred" acceptance is the
 * [aNewRepositoryOverTheSameDatabaseStillSeesTheStar] test: a fresh
 * repository instance over the same database is exactly what a fresh
 * Live TV visit builds.
 *
 * x86_64-only like every Robolectric suite (see the arm64 exclusion in
 * `app/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
class LiveTvFavouritesRepositoryTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository() = LiveTvFavouritesRepository(db.liveTvFavouriteDao())

    @Test
    fun toggleStarsThenUnstars() = runBlocking {
        val repo = repository()
        assertTrue("First toggle did not star the channel", repo.toggleFavorite(14))
        assertEquals(setOf(14), repo.observeFavoriteIds().first())
        assertFalse("Second toggle did not unstar the channel", repo.toggleFavorite(14))
        assertEquals(emptySet<Int>(), repo.observeFavoriteIds().first())
    }

    @Test
    fun aNewRepositoryOverTheSameDatabaseStillSeesTheStar() = runBlocking {
        repository().toggleFavorite(14)
        // A new instance over the same database — a fresh visit to the tab.
        val fresh = repository()
        assertEquals(setOf(14), fresh.observeFavoriteIds().first())
    }

    @Test
    fun starredIdsArriveNewestStarredFirst() = runBlocking {
        // Explicit stamps: two toggles in the same millisecond would tie
        // on starredAt, so the order is proven with stamps, not with luck.
        val dao = db.liveTvFavouriteDao()
        dao.insert(LiveTvFavourite(channelId = 1, starredAt = 1_000L))
        dao.insert(LiveTvFavourite(channelId = 2, starredAt = 2_000L))
        assertEquals(listOf(2, 1), dao.observeFavoriteIds().first())
        assertEquals(setOf(1, 2), repository().observeFavoriteIds().first())
    }

    @Test
    fun togglingTwiceReturnsToUnstarred() = runBlocking {
        val repo = repository()
        assertTrue(repo.toggleFavorite(9))
        assertFalse(repo.toggleFavorite(9))
        assertEquals(emptySet<Int>(), repo.observeFavoriteIds().first())
    }
}
