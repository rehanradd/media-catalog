package com.raddstream.app.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.RaddStreamApi
import com.raddstream.app.network.models.LiveFillRequest
import com.raddstream.app.network.models.LiveFillResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Live fill (detail open): `CatalogRepository.enrichWorkDetail` is the
 * fail-open call the DetailViewModel fires in the background — any
 * failure answers null and the detail keeps the blanks it already
 * shows. The fake API is a [Proxy] over [RaddStreamApi], as in
 * [V2DetailRepositoryTest]: one `enrich` answer per test, keyed on the
 * method name.
 */
@RunWith(RobolectricTestRunner::class)
class LiveFillRepositoryTest {

    private class FakeApiProvider(
        settingsRepository: ServerSettingsRepository,
        private val api: RaddStreamApi,
    ) : ApiProvider(settingsRepository) {
        override suspend fun current(): RaddStreamApi = api
    }

    private fun repository(api: RaddStreamApi): CatalogRepository {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = ServerSettingsRepository(context)
        return CatalogRepository(FakeApiProvider(settings, api), settings)
    }

    private fun apiAnswering(answer: Any?): RaddStreamApi {
        val handler = InvocationHandler { _: Any, method: Method, _: Array<out Any>? ->
            when (method.name) {
                "enrichWorkDetail" -> answer
                else -> throw UnsupportedOperationException("unexpected ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            RaddStreamApi::class.java.classLoader,
            arrayOf(RaddStreamApi::class.java),
            handler,
        ) as RaddStreamApi
    }

    private fun apiThrowing(error: Throwable): RaddStreamApi {
        val handler = InvocationHandler { _: Any, method: Method, _: Array<out Any>? ->
            when (method.name) {
                "enrichWorkDetail" -> throw error
                else -> throw UnsupportedOperationException("unexpected ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            RaddStreamApi::class.java.classLoader,
            arrayOf(RaddStreamApi::class.java),
            handler,
        ) as RaddStreamApi
    }

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "x".toResponseBody("text/plain".toMediaType())))

    @Test
    fun `a 200 ok answer returns the filled map`() = runTest {
        val api = apiAnswering(
            LiveFillResponse(
                ok = true,
                status = "filled",
                filled = mapOf("rating" to "8.1", "synopsis" to "A story."),
                fieldsFilled = 2,
            ),
        )

        val filled = repository(api).enrichWorkDetail(workId = 42L)

        assertEquals(mapOf("rating" to "8.1", "synopsis" to "A story."), filled)
    }

    @Test
    fun `an ok false answer returns null`() = runTest {
        val api = apiAnswering(LiveFillResponse(ok = false, status = "no_match"))

        assertNull(repository(api).enrichWorkDetail(workId = 42L))
    }

    @Test
    fun `a 404 problem body returns null`() = runTest {
        val api = apiThrowing(http(404))

        assertNull(repository(api).enrichWorkDetail(postId = 7))
    }

    @Test
    fun `a 429 problem body returns null`() = runTest {
        val api = apiThrowing(http(429))

        assertNull(repository(api).enrichWorkDetail(workId = 42L))
    }

    @Test
    fun `a dead socket returns null`() = runTest {
        val api = apiThrowing(IOException("Connection reset"))

        assertNull(repository(api).enrichWorkDetail(workId = 42L))
    }

    @Test
    fun `work id wins over post id, exactly one is sent`() = runTest {
        var sent: LiveFillRequest? = null
        val handler = InvocationHandler { _: Any, method: Method, args: Array<out Any>? ->
            when (method.name) {
                "enrichWorkDetail" -> {
                    sent = args?.firstOrNull() as? LiveFillRequest
                    LiveFillResponse(ok = true, filled = mapOf("rating" to "8.1"))
                }
                else -> throw UnsupportedOperationException("unexpected ${method.name}")
            }
        }
        val api = Proxy.newProxyInstance(
            RaddStreamApi::class.java.classLoader,
            arrayOf(RaddStreamApi::class.java),
            handler,
        ) as RaddStreamApi

        val filled = repository(api).enrichWorkDetail(workId = 42L, postId = 7)

        assertEquals(mapOf("rating" to "8.1"), filled)
        assertEquals(42L, sent?.workId)
        assertNull(sent?.postId)
    }

    @Test
    fun `no id at all never calls the server`() = runTest {
        var calls = 0
        val handler = InvocationHandler { _: Any, method: Method, _: Array<out Any>? ->
            calls++
            throw UnsupportedOperationException("unexpected ${method.name}")
        }
        val api = Proxy.newProxyInstance(
            RaddStreamApi::class.java.classLoader,
            arrayOf(RaddStreamApi::class.java),
            handler,
        ) as RaddStreamApi

        assertNull(repository(api).enrichWorkDetail())
        assertEquals(0, calls)
    }
}
