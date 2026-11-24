package com.raddstream.app.network

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cleartext guard (H-4, 2026-11-24), pinned without a network call:
 * account and authenticated traffic never leaves the phone on a cleartext
 * fallback session, while signed-out catalog reads on the same session carry
 * on — and a viewer-pointed HTTP server (a LAN catalog, a loopback test
 * server) is not a fallback session, so it keeps working.
 *
 * The interceptor test builds a real OkHttpClient around the guard with a
 * synthetic-200 downstream: the guard is the only thing that can refuse, so
 * a 200 proves it let the request through and a thrown IOException proves it
 * refused — with no network at all. (The fallback address is a live host, so
 * executing against it and expecting a connection failure proved the
 * runner's route to it, not the guard.)
 */
class ApiProviderCleartextAuthTest {

    private val fallback = "http://92.4.95.252"
    private val lan = "http://192.168.1.5:8080"
    private val loopback = "http://127.0.0.1:9"
    private val https = "https://raddstream.duckdns.org"

    @Test
    fun `isFallbackCleartextUrl names the fallback address only`() {
        assertTrue(isFallbackCleartextUrl("$fallback/api/v1/posts"))
        assertTrue(isFallbackCleartextUrl("http://92.4.95.252:8080/api/v1/posts"))
        assertFalse(isFallbackCleartextUrl("https://92.4.95.252/api/v1/posts"))
        assertFalse(isFallbackCleartextUrl("$lan/api/v1/posts"))
        assertFalse(isFallbackCleartextUrl("$loopback/api/v1/posts"))
        assertFalse(isFallbackCleartextUrl("$https/api/v1/posts"))
        assertFalse(isFallbackCleartextUrl("not a url"))
    }

    @Test
    fun `an authorized request on a fallback session is blocked`() {
        assertTrue(isAuthTrafficBlocked("$fallback/api/v2/play".toHttpUrl(), true, false, true))
        assertTrue(isAuthTrafficBlocked("$fallback/api/v1/posts".toHttpUrl(), true, false, true))
    }

    @Test
    fun `an account route on a fallback session is blocked even without a header`() {
        // POST /api/v2/account and /anonymous take no key but answer one.
        assertTrue(isAuthTrafficBlocked("$fallback/api/v2/account".toHttpUrl(), false, false, true))
        assertTrue(isAuthTrafficBlocked("$fallback/api/v2/account/anonymous".toHttpUrl(), false, false, true))
        assertTrue(isAuthTrafficBlocked("$fallback/api/v2/account/favorites".toHttpUrl(), false, false, true))
    }

    @Test
    fun `a vip pass header on a fallback session is blocked`() {
        assertTrue(isAuthTrafficBlocked("$fallback/api/v1/stream".toHttpUrl(), false, true, true))
    }

    @Test
    fun `a signed-out catalog read on a fallback session is not blocked`() {
        assertFalse(isAuthTrafficBlocked("$fallback/api/v1/posts".toHttpUrl(), false, false, true))
        assertFalse(isAuthTrafficBlocked("$fallback/api/v2/works/123".toHttpUrl(), false, false, true))
    }

    @Test
    fun `the fallback address is refused even without a session decision`() {
        // A viewer who typed the fallback address in by hand is refused the
        // same way as a deadman-switch session.
        assertTrue(isAuthTrafficBlocked("$fallback/api/v2/account".toHttpUrl(), true, false, false))
        assertTrue(isAuthTrafficBlocked("$fallback/api/v2/account".toHttpUrl(), false, false, false))
    }

    @Test
    fun `a viewer-pointed http server is not a fallback session`() {
        assertFalse(isAuthTrafficBlocked("$lan/api/v2/account".toHttpUrl(), true, false, false))
        assertFalse(isAuthTrafficBlocked("$loopback/api/v2/account".toHttpUrl(), true, false, false))
    }

    @Test
    fun `https is never blocked, on any session`() {
        assertFalse(isAuthTrafficBlocked("$https/api/v2/account".toHttpUrl(), true, true, true))
        assertFalse(isAuthTrafficBlocked("$https/api/v2/play".toHttpUrl(), true, false, false))
    }

    @Test
    fun `isAccountRoute covers the account surface and nothing else`() {
        assertTrue(isAccountRoute("/api/v2/account"))
        assertTrue(isAccountRoute("/api/v2/account/"))
        assertTrue(isAccountRoute("/api/v2/account/vip-pass"))
        assertFalse(isAccountRoute("/api/v2/play"))
        assertFalse(isAccountRoute("/api/v1/posts"))
        assertFalse(isAccountRoute("/api/v2/accountant"))
    }

    private fun shortCircuitClient(isFallbackSession: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(cleartextAuthGuardInterceptor(isFallbackSession))
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun `the interceptor refuses an account call on a fallback session before any socket opens`() {
        val request = Request.Builder()
            .url("$loopback/api/v2/account")
            .header("Authorization", "Bearer test-key-that-must-not-travel")
            .build()
        // The loopback URL is not the fallback address, so only the session
        // flag makes this a refusal — which is exactly the production shape:
        // the deadman switch hands the client its flag at build time.
        val error = assertThrows(IOException::class.java) {
            shortCircuitClient(isFallbackSession = true).newCall(request).execute()
        }
        assertTrue(error.message ?: "", error.message!!.contains("fallback"))
    }

    @Test
    fun `the interceptor refuses a keyless account call on a fallback session`() {
        // GET /api/v2/account is the prove-the-key route: no header here, but
        // the path alone must refuse it — a mint response carries the key.
        val request = Request.Builder()
            .url("$fallback/api/v2/account")
            .build()
        val error = assertThrows(IOException::class.java) {
            shortCircuitClient(isFallbackSession = true).newCall(request).execute()
        }
        assertTrue(error.message ?: "", error.message!!.contains("fallback"))
    }

    @Test
    fun `the interceptor lets a signed-out catalog read through on a fallback session`() {
        val request = Request.Builder()
            .url("$fallback/api/v1/posts")
            .build()
        shortCircuitClient(isFallbackSession = true).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `the interceptor is inert off a fallback session`() {
        val request = Request.Builder()
            .url("$loopback/api/v2/account")
            .header("Authorization", "Bearer test-key-that-must-not-travel")
            .build()
        shortCircuitClient(isFallbackSession = false).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `the interceptor lets the same account call through over https`() {
        val request = Request.Builder()
            .url("https://127.0.0.1:9/api/v2/account")
            .header("Authorization", "Bearer test-key-that-must-not-travel")
            .build()
        shortCircuitClient(isFallbackSession = true).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
    }
}
