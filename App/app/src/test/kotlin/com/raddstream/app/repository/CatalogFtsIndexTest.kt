package com.raddstream.app.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.local.AppDatabase
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.RaddStreamApi
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.PostDetailResponse
import com.raddstream.app.network.models.PostsResponse
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Feel-instant S3c: the seeding funnel — every list page and every detail
 * the repository serves lands in the on-device index, so the next search
 * for that title answers without the network. The fake transport serves
 * fixed rows; what is pinned is that the repository indexed them, not the
 * transport.
 *
 * x86_64-only like every Robolectric suite (see the arm64 exclusion in
 * `app/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
class CatalogFtsIndexTest {

    private class FakeApiProvider(
        settingsRepository: ServerSettingsRepository,
        private val posts: List<Post>,
        private val detail: PostDetail?,
    ) : ApiProvider(settingsRepository) {
        override suspend fun current(): RaddStreamApi {
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(RaddStreamApi::class.java),
            ) { _, method, args ->
                val continuation = args!!.last() as Continuation<Any?>
                when (method.name) {
                    "getPosts" -> continuation.resume(PostsResponse(results = posts))
                    "getPostDetail" -> continuation.resume(
                        PostDetailResponse(success = detail != null, data = detail),
                    )
                    else -> continuation.resumeWithException(
                        UnsupportedOperationException("unused in this test: ${method.name}"),
                    )
                }
                COROUTINE_SUSPENDED
            } as RaddStreamApi
        }
    }

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

    private fun repository(posts: List<Post>, detail: PostDetail?): CatalogRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ServerSettingsRepository(context)
        return CatalogRepository(FakeApiProvider(settings, posts, detail), settings, db.catalogFtsDao())
    }

    @Test
    fun `a served list page lands in the index`() = runBlocking {
        val repository = repository(
            posts = listOf(
                Post(id = 101, title = "Jawan", cleanTitle = "Jawan", year = 2023, contentType = "movie"),
                Post(id = 102, title = "Dune: Part Two", cleanTitle = "Dune: Part Two", year = 2024, contentType = "movie"),
            ),
            detail = null,
        )
        repository.getPosts()
        val dao = db.catalogFtsDao()
        assertEquals(listOf("Jawan"), dao.search("jaw*", 10).map { it.title })
        assertEquals(listOf("Dune: Part Two"), dao.search("dune*", 10).map { it.title })
    }

    @Test
    fun `a served detail lands in the index too`() = runBlocking {
        val repository = repository(
            posts = emptyList(),
            detail = PostDetail(id = 55, title = "Pathaan", year = 2023, contentType = "movie"),
        )
        repository.getPostDetail(55)
        assertEquals(
            listOf("Pathaan"),
            db.catalogFtsDao().search("\"path\"*", 10).map { it.title },
        )
    }

    @Test
    fun `an index failure never fails the list it rode in on`() = runBlocking {
        // The DAO is closed before the call, so every index write throws —
        // the list still comes back, because the index is a passenger, not
        // the load.
        val repository = repository(
            posts = listOf(
                Post(id = 101, title = "Jawan", cleanTitle = "Jawan", year = 2023, contentType = "movie"),
            ),
            detail = null,
        )
        db.close()
        val response = repository.getPosts()
        assertTrue(response.results.map { it.title }.contains("Jawan"))
    }
}
