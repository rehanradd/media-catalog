package com.raddstream.app.images

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * wsrv.nl fallback (2026-11-20): a failed proxy fetch retries once with
 * the direct URL the proxy URL carries; a success is returned untouched
 * and a non-wsrv request is never intercepted. The chain is fake — no
 * network runs here — and the result objects are Coil's own. The
 * registration itself (the `.components { add(...) }` line in
 * [com.raddstream.app.di.ImageLoaderModule]) has no public accessor to
 * assert on, so it is verified by inspection, not by this test.
 */
@RunWith(RobolectricTestRunner::class)
class WsrvFallbackInterceptorTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val tmdb =
        "https://image.tmdb.org/t/p/w500/dDYoLdHvsnvxLRgGe1tEhgeqQn2.jpg"
    private val wsrv = PosterUrlPolicy.displayUrl(tmdb, 400)

    @OptIn(ExperimentalCoilApi::class)
    private class FakeChain(
        override var request: ImageRequest,
        private val answer: (ImageRequest) -> ImageResult,
    ) : Interceptor.Chain {
        val proceeded = mutableListOf<ImageRequest>()

        override val size: Size = Size.ORIGINAL

        override fun withRequest(request: ImageRequest) = FakeChain(request, answer)

        override fun withSize(size: Size) = this

        override suspend fun proceed(request: ImageRequest): ImageResult {
            proceeded += request
            this.request = request
            return answer(request)
        }
    }

    private fun request(data: String) =
        ImageRequest.Builder(context).data(data).build()

    private fun success(request: ImageRequest) =
        SuccessResult(ColorDrawable(), request, DataSource.NETWORK)

    private fun failure(request: ImageRequest) =
        ErrorResult(null, request, IllegalStateException("proxy down"))

    @Test
    fun `a failed wsrv fetch retries once with the direct url`() = runBlocking {
        val chain = FakeChain(request(wsrv)) { req ->
            if (req.data == wsrv) failure(req) else success(req)
        }
        val result = WsrvFallbackInterceptor().intercept(chain)
        assertTrue(result is SuccessResult)
        assertEquals(listOf(wsrv, tmdb), chain.proceeded.map { it.data })
    }

    @Test
    fun `a successful wsrv fetch is returned untouched`() = runBlocking {
        val chain = FakeChain(request(wsrv)) { req -> success(req) }
        val result = WsrvFallbackInterceptor().intercept(chain)
        assertTrue(result is SuccessResult)
        assertEquals(listOf(wsrv), chain.proceeded.map { it.data })
    }

    @Test
    fun `a non-wsrv request is never intercepted`() = runBlocking {
        val chain = FakeChain(request(tmdb)) { req -> failure(req) }
        val result = WsrvFallbackInterceptor().intercept(chain)
        assertTrue(result is ErrorResult)
        assertEquals(listOf(tmdb), chain.proceeded.map { it.data })
    }

    @Test
    fun `a failed direct retry returns the direct error`() = runBlocking {
        val chain = FakeChain(request(wsrv)) { req -> failure(req) }
        val result = WsrvFallbackInterceptor().intercept(chain)
        assertTrue(result is ErrorResult)
        assertEquals(listOf(wsrv, tmdb), chain.proceeded.map { it.data })
    }

    @Test
    fun `a wsrv url with no wrapped url never retries`() = runBlocking {
        val bare = "https://wsrv.nl/?w=640&output=webp&q=72"
        val chain = FakeChain(request(bare)) { req -> failure(req) }
        val result = WsrvFallbackInterceptor().intercept(chain)
        assertTrue(result is ErrorResult)
        assertEquals(listOf(bare), chain.proceeded.map { it.data })
    }
}
