package com.raddstream.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Feel-instant S3c: the on-device search index — prefix search, popularity
 * order, and the delete-then-insert dedupe. x86_64-only like every
 * Robolectric suite (see the arm64 exclusion in `app/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
class CatalogFtsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CatalogFtsDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.catalogFtsDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(
        title: String,
        year: Int? = 2023,
        type: String? = "movie",
        popularity: Int = 1,
    ) = CatalogFtsEntity(
        title = title,
        year = year,
        type = type,
        posterUrl = null,
        popularity = popularity,
    )

    @Test
    fun `a prefix query finds the title by its first letters`() = runBlocking {
        dao.upsert(listOf(entry("Avengers: Endgame", popularity = 5), entry("Avatar", popularity = 9)))
        val found = dao.search("aven*", 10)
        assertEquals(listOf("Avengers: Endgame"), found.map { it.title })
    }

    @Test
    fun `results order by popularity then title`() = runBlocking {
        dao.upsert(
            listOf(
                entry("Dune", popularity = 2),
                entry("Dune: Part Two", popularity = 8),
                entry("Dunkirk", popularity = 8),
            ),
        )
        val found = dao.search("dun*", 10)
        assertEquals(listOf("Dune: Part Two", "Dunkirk", "Dune"), found.map { it.title })
    }

    @Test
    fun `a query with no match answers empty, never an error`() = runBlocking {
        dao.upsert(listOf(entry("Inception")))
        assertTrue(dao.search("zzzz*", 10).isEmpty())
    }

    @Test
    fun `re-seeding the same title replaces it instead of doubling it`() = runBlocking {
        dao.upsert(listOf(entry("Jawan", popularity = 1)))
        dao.upsert(listOf(entry("Jawan", popularity = 7)))
        assertEquals(1, dao.count())
        assertEquals(7, dao.search("jawan*", 10).single().popularity)
    }

    @Test
    fun `the same title in a different year is a different row`() = runBlocking {
        dao.upsert(listOf(entry("Dune", year = 1984), entry("Dune", year = 2021)))
        assertEquals(2, dao.count())
    }

    @Test
    fun `a yearless re-seed still dedupes instead of doubling`() = runBlocking {
        dao.upsert(listOf(entry("Untitled", year = null)))
        dao.upsert(listOf(entry("Untitled", year = null, popularity = 4)))
        assertEquals(1, dao.count())
        assertEquals(4, dao.search("untitled*", 10).single().popularity)
    }

    @Test
    fun `top by popularity answers the most covered titles first`() = runBlocking {
        dao.upsert(
            listOf(
                entry("Small", popularity = 1),
                entry("Big", popularity = 40),
                entry("Mid", popularity = 9),
            ),
        )
        assertEquals(listOf("Big", "Mid", "Small"), dao.topByPopularity(10).map { it.title })
    }
}
