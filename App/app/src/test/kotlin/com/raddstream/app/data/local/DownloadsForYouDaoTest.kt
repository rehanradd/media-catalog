package com.raddstream.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * REA #5: the "Downloads For You" ledger — insert, observe in fetch
 * order, delete by handle and by episode. x86_64-only like every
 * Robolectric suite (see the arm64 exclusion in `app/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
class DownloadsForYouDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DownloadsForYouDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadsForYouDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(postId: Int, episode: Int, handle: String, fetchedAt: Long) =
        DownloadsForYouEntry(
            postId = postId,
            season = 1,
            episode = episode,
            title = "Episode $episode",
            handle = handle,
            sizeBytes = 100_000_000L,
            fetchedAt = fetchedAt,
        )

    @Test
    fun `insert then list reads newest first`() = runBlocking {
        dao.insert(entry(postId = 7, episode = 1, handle = "h-1", fetchedAt = 100L))
        dao.insert(entry(postId = 7, episode = 2, handle = "h-2", fetchedAt = 200L))
        val rows = dao.listAll()
        assertEquals(listOf("h-2", "h-1"), rows.map { it.handle })
        assertEquals(listOf("h-2", "h-1"), dao.observeAll().first().map { it.handle })
    }

    @Test
    fun `a re-fetch replaces its own row`() = runBlocking {
        dao.insert(entry(postId = 7, episode = 1, handle = "h-1", fetchedAt = 100L))
        dao.insert(entry(postId = 7, episode = 1, handle = "h-1-new", fetchedAt = 300L))
        val rows = dao.listAll()
        assertEquals(1, rows.size)
        assertEquals("h-1-new", rows.first().handle)
        assertEquals(300L, rows.first().fetchedAt)
    }

    @Test
    fun `delete by handle and by episode remove only that row`() = runBlocking {
        dao.insert(entry(postId = 7, episode = 1, handle = "h-1", fetchedAt = 100L))
        dao.insert(entry(postId = 7, episode = 2, handle = "h-2", fetchedAt = 200L))
        dao.insert(entry(postId = 8, episode = 1, handle = "h-3", fetchedAt = 300L))
        dao.deleteByHandle("h-2")
        assertEquals(listOf("h-3", "h-1"), dao.listAll().map { it.handle })
        dao.deleteEpisode(postId = 8, season = 1, episode = 1)
        assertEquals(listOf("h-1"), dao.listAll().map { it.handle })
        assertNull(dao.listAll().firstOrNull { it.postId == 8 })
        assertTrue(dao.listAll().any { it.handle == "h-1" })
    }
}
