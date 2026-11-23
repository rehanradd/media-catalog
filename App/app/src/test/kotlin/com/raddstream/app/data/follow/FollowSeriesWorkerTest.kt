package com.raddstream.app.data.follow

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.raddstream.app.data.local.AppDatabase
import com.raddstream.app.data.local.FollowedSeries
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseDetail
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import javax.inject.Provider

/**
 * E2 slice 2: the daily check end to end — a fake catalog, a real
 * in-memory DAO, and the real worker built through the generated Hilt
 * assisted factory (the same pin [com.raddstream.app.data.cache.CacheSweepWorkerTest]
 * puts on the sweep: if `androidx.hilt:hilt-compiler` is ever dropped,
 * this file stops compiling instead of failing silently on a device).
 *
 * x86_64-only like every Robolectric suite (see the arm64 exclusion in
 * `app/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
class FollowSeriesWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeCatalog(
        var posts: List<Post> = emptyList(),
        var details: Map<Int, PostDetail> = emptyMap(),
    ) : FollowSeriesCatalog {
        override suspend fun postsForWork(workRef: String, cleanTitle: String?): List<Post> = posts
        override suspend fun detailFor(postId: Int): PostDetail? = details[postId]
    }

    private fun hiltStyleFactory(
        assistedFactory: FollowSeriesWorker_AssistedFactory,
    ): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == FollowSeriesWorker::class.java.name) {
                assistedFactory.create(appContext, workerParameters)
            } else {
                null
            }
    }

    private fun buildWorker(catalog: FollowSeriesCatalog): FollowSeriesWorker {
        val assistedFactory: FollowSeriesWorker_AssistedFactory =
            FollowSeriesWorker_AssistedFactory_Impl.create(
                FollowSeriesWorker_Factory.create(
                    dagger.internal.Provider { db.followedSeriesDao() },
                    dagger.internal.Provider { catalog },
                ),
            ).get()
        val worker = TestListenableWorkerBuilder.from(context, FollowSeriesWorker::class.java)
            .setWorkerFactory(hiltStyleFactory(assistedFactory))
            .build()
        assertNotNull("Hilt factory must build FollowSeriesWorker", worker)
        return worker
    }

    private fun postedNotifications(): List<Notification> {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).allNotifications
    }

    private fun onePieceDetail(vararg scopes: Pair<String?, String?>): PostDetail {
        val releases = scopes.mapIndexed { index, (season, episode) ->
            val title = if (season != null) {
                "One Piece S$season E${episode ?: "?"} 1080p"
            } else {
                "One Piece Unknown print 1080p"
            }
            ReleaseDetail(
                id = index + 1,
                releaseTitle = title,
                season = season,
                episode = episode,
            )
        }
        return PostDetail(id = 11, title = "One Piece", releases = releases)
    }

    private fun follow(lastSeenSeason: Int?, lastSeenEpisode: Int?) = runBlocking {
        db.followedSeriesDao().insert(
            FollowedSeries(
                workRef = "work:one-piece",
                cleanTitle = "One Piece",
                title = "One Piece",
                lastSeenSeason = lastSeenSeason,
                lastSeenEpisode = lastSeenEpisode,
            ),
        )
    }

    private fun lastSeen(): Pair<Int?, Int?> = runBlocking {
        val row = db.followedSeriesDao().findByWorkRef("work:one-piece")
        row?.lastSeenSeason to row?.lastSeenEpisode
    }

    @Test
    fun `hilt factory builds the worker and an empty follow list succeeds`() {
        val worker = buildWorker(FakeCatalog())
        val result = runBlocking { worker.doWork() }
        assertTrue("empty check must succeed, was $result", result is ListenableWorker.Result.Success)
        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun `a new episode notifies naming the episode and advances last seen`() {
        follow(lastSeenSeason = 2, lastSeenEpisode = 4)
        val catalog = FakeCatalog(
            posts = listOf(Post(id = 11, title = "One Piece")),
            details = mapOf(11 to onePieceDetail("2" to "5")),
        )
        val result = runBlocking { buildWorker(catalog).doWork() }
        assertTrue("check must succeed, was $result", result is ListenableWorker.Result.Success)
        val posted = postedNotifications()
        assertEquals("one notification for the one new episode", 1, posted.size)
        assertEquals("One Piece", posted[0].extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Season 2 Episode 5 is out",
            posted[0].extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(2 to 5, lastSeen())
    }

    @Test
    fun `nothing new notifies nothing and leaves last seen alone`() {
        follow(lastSeenSeason = 2, lastSeenEpisode = 5)
        val catalog = FakeCatalog(
            posts = listOf(Post(id = 11, title = "One Piece")),
            details = mapOf(11 to onePieceDetail("2" to "5")),
        )
        val result = runBlocking { buildWorker(catalog).doWork() }
        assertTrue("check must succeed, was $result", result is ListenableWorker.Result.Success)
        assertTrue(postedNotifications().isEmpty())
        assertEquals(2 to 5, lastSeen())
    }

    @Test
    fun `a first check seeds last seen silently instead of announcing old episodes`() {
        follow(lastSeenSeason = null, lastSeenEpisode = null)
        val catalog = FakeCatalog(
            posts = listOf(Post(id = 11, title = "One Piece")),
            details = mapOf(11 to onePieceDetail("2" to "5")),
        )
        val result = runBlocking { buildWorker(catalog).doWork() }
        assertTrue("check must succeed, was $result", result is ListenableWorker.Result.Success)
        assertTrue(postedNotifications().isEmpty())
        assertEquals(2 to 5, lastSeen())
    }

    @Test
    fun `unparseable releases notify nothing and move no stamp`() {
        follow(lastSeenSeason = 2, lastSeenEpisode = 5)
        val catalog = FakeCatalog(
            posts = listOf(Post(id = 11, title = "One Piece")),
            details = mapOf(11 to onePieceDetail(null to null)),
        )
        val result = runBlocking { buildWorker(catalog).doWork() }
        assertTrue("check must succeed, was $result", result is ListenableWorker.Result.Success)
        assertTrue(postedNotifications().isEmpty())
        assertEquals(2 to 5, lastSeen())
    }

    @Test
    fun `an unfollowed series is never notified`() {
        follow(lastSeenSeason = 2, lastSeenEpisode = 4)
        runBlocking { db.followedSeriesDao().deleteByWorkRef("work:one-piece") }
        val catalog = FakeCatalog(
            posts = listOf(Post(id = 11, title = "One Piece")),
            details = mapOf(11 to onePieceDetail("2" to "5")),
        )
        val result = runBlocking { buildWorker(catalog).doWork() }
        assertTrue("check must succeed, was $result", result is ListenableWorker.Result.Success)
        assertTrue(postedNotifications().isEmpty())
    }
}
