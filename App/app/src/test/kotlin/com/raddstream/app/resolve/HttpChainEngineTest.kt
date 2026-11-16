package com.raddstream.app.resolve

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-POST chain over a fake transport — no network in unit tests. */
class HttpChainEngineTest {

    private class FakeTransport(
        private val posts: Map<String, TransportResponse>,
        private val probe: TransportResponse = TransportResponse(
            206,
            mapOf("content-type" to "video/mp4"),
            ByteArray(0),
        ),
    ) : ResolveHttpTransport {
        val postUrls = ArrayList<String>()
        val postBodies = ArrayList<String>()
        val postHeaders = ArrayList<Map<String, String>>()

        override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): TransportResponse {
            postUrls.add(url)
            postBodies.add(jsonBody)
            postHeaders.add(headers)
            return posts[url] ?: TransportResponse(404, emptyMap(), ByteArray(0))
        }

        override suspend fun getRange(url: String, headers: Map<String, String>): TransportResponse = probe

        override fun close() {}
    }

    private fun jsonResponse(body: String) =
        TransportResponse(200, mapOf("content-type" to "application/json"), body.toByteArray(Charsets.UTF_8))

    private fun statusResponse(status: Int) = TransportResponse(status, emptyMap(), ByteArray(0))

    @Test
    fun `happy path posts twice in order then probes the mint`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                "https://new3.filepress.lat/api/file/downlaod/" to jsonResponse("""{"status":true,"data":"tok123"}"""),
                "https://new3.filepress.lat/api/file/downlaod2/" to jsonResponse("""{"status":true,"data":["https://cdn.example.com/my file.mp4"]}"""),
            ),
        )
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertTrue(result is ChainResult.Minted)
        val minted = result as ChainResult.Minted
        assertEquals("https://cdn.example.com/my%20file.mp4", minted.mintedUrl)
        assertEquals(ProbeVerdict.ACTIVE, minted.probe.verdict)
        assertEquals(
            listOf(
                "https://new3.filepress.lat/api/file/downlaod/",
                "https://new3.filepress.lat/api/file/downlaod2/",
            ),
            transport.postUrls,
        )
        assertTrue(transport.postBodies[0].contains("\"id\":\"646fcb392a2127d45896d91f\""))
        assertTrue(transport.postBodies[1].contains("\"id\":\"tok123\""))
        assertTrue(transport.postBodies[0].contains("\"method\":\"indexDownlaod\""))
        assertTrue(transport.postBodies[0].contains("\"captchaValue\":null"))
    }

    @Test
    fun `403 on the first POST is blocked`() = runBlocking {
        val transport = FakeTransport(
            mapOf("https://new3.filepress.lat/api/file/downlaod/" to statusResponse(403)),
        )
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertEquals(ChainResult.Failed("download: blocked:403", null), result)
    }

    @Test
    fun `404 on the first POST is dead`() = runBlocking {
        val transport = FakeTransport(
            mapOf("https://new3.filepress.lat/api/file/downlaod/" to statusResponse(404)),
        )
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertEquals(ChainResult.Failed("download: dead:404", null), result)
    }

    @Test
    fun `api error vocabulary passes through verbatim`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                "https://new3.filepress.lat/api/file/downlaod/" to
                    jsonResponse("""{"status":false,"data":"METHOD_HIDDEN"}"""),
            ),
        )
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertEquals(ChainResult.Failed("download: METHOD_HIDDEN", null), result)
    }

    @Test
    fun `empty token and empty link list fail`() = runBlocking {
        val emptyToken = FakeTransport(
            mapOf(
                "https://new3.filepress.lat/api/file/downlaod/" to jsonResponse("""{"status":true,"data":""}"""),
            ),
        )
        assertEquals(
            ChainResult.Failed("download: empty token", null),
            HttpChainEngine(emptyToken).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f"),
        )
        val emptyLinks = FakeTransport(
            mapOf(
                "https://new3.filepress.lat/api/file/downlaod/" to jsonResponse("""{"status":true,"data":"tok123"}"""),
                "https://new3.filepress.lat/api/file/downlaod2/" to jsonResponse("""{"status":true,"data":[]}"""),
            ),
        )
        assertEquals(
            ChainResult.Failed("download2: empty link list", null),
            HttpChainEngine(emptyLinks).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f"),
        )
    }

    @Test
    fun `run-1 regression - a 301 on the first POST is followed by re-POSTing to the Location`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                "https://new3.filepress.lat/api/file/downlaod/" to TransportResponse(
                    301,
                    mapOf("location" to "https://new4.filepress.lat/api/file/downlaod/"),
                    ByteArray(0),
                ),
                "https://new4.filepress.lat/api/file/downlaod/" to jsonResponse("""{"status":true,"data":"tok123"}"""),
                "https://new3.filepress.lat/api/file/downlaod2/" to jsonResponse("""{"status":true,"data":["https://cdn.example.com/f.mkv"]}"""),
            ),
        )
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertTrue(result is ChainResult.Minted)
        // The redirect target got the same POST body, not a GET.
        assertEquals(
            listOf(
                "https://new3.filepress.lat/api/file/downlaod/",
                "https://new4.filepress.lat/api/file/downlaod/",
                "https://new3.filepress.lat/api/file/downlaod2/",
            ),
            transport.postUrls,
        )
        assertTrue(transport.postBodies[1].contains("\"id\":\"646fcb392a2127d45896d91f\""))
        // The Referer was re-based to the redirect target's origin.
        assertEquals("https://new4.filepress.lat", transport.postHeaders[1]["Referer"])
    }

    @Test
    fun `a relative Location resolves against the request URL`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                "https://new3.filepress.lat/api/file/downlaod/" to TransportResponse(
                    302,
                    mapOf("location" to "/api/file/downlaod-alt/"),
                    ByteArray(0),
                ),
            ),
        )
        // The fake has no entry for the resolved URL, so it 404s — but the
        // point is the engine asked the resolved URL, not the Location raw.
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertEquals(ChainResult.Failed("download: dead:404", null), result)
        assertEquals(
            listOf(
                "https://new3.filepress.lat/api/file/downlaod/",
                "https://new3.filepress.lat/api/file/downlaod-alt/",
            ),
            transport.postUrls,
        )
    }

    @Test
    fun `a redirect with no Location fails as http-301`() = runBlocking {
        val transport = FakeTransport(
            mapOf("https://new3.filepress.lat/api/file/downlaod/" to statusResponse(301)),
        )
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertEquals(ChainResult.Failed("download: http:301", null), result)
        assertEquals(1, transport.postUrls.size)
    }

    @Test
    fun `a redirect loop fails after the hop cap`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                "https://new3.filepress.lat/api/file/downlaod/" to TransportResponse(
                    301,
                    mapOf("location" to "https://new3.filepress.lat/api/file/downlaod/"),
                    ByteArray(0),
                ),
            ),
        )
        val result = HttpChainEngine(transport).resolve("https://filebee.xyz/file/646fcb392a2127d45896d91f")
        assertEquals(ChainResult.Failed("download: redirect_loop", null), result)
        assertEquals(HttpChainEngine.MAX_REDIRECTS + 1, transport.postUrls.size)
    }

    @Test
    fun `a url without a file id fails before any POST`() = runBlocking {
        val transport = FakeTransport(emptyMap())
        val result = HttpChainEngine(transport).resolve("https://1fichier.com/?8pced1qb5np5i8bj84cf")
        assertEquals(ChainResult.Failed("no_file_id", null), result)
        assertTrue(transport.postUrls.isEmpty())
    }
}
