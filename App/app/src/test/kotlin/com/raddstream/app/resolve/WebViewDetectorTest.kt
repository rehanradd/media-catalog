package com.raddstream.app.resolve

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run-1 regression tests for the WebView mint detector.
 *
 * Run 1 (owner's phone, 22:00–22:13 PKT 16 Nov): filemoon downloaded ~1.5 MB
 * of video per attempt yet minted 0/3, rpmplay aborted 5/5 on `other_host`,
 * and the control set went 0/10 — the detector, not the hosts, was broken.
 * The detector only recognised URLs ending in a media extension, and the
 * players serve extensionless playlist/stream URLs while the bytes arrive
 * as .ts segments. These tests pin the fixed ranking, extraction and
 * navigation behaviour, plus a local fixture page serving a real video.
 */
class WebViewDetectorTest {

    private val bridge = "https://filemoon.sx/e/efro3wcnghdh"
    private val blocklist = listOf("ads.example.com", "tracker.example.com")

    @Test
    fun `media-extension urls rank first, in arrival order`() {
        val ranked = WebViewEngine.rankProbeCandidates(
            recorded = listOf(
                "https://cdn.example.com/app.js",
                "https://cdn.example.com/hls/list.m3u8",
                "https://cdn.example.com/stream/video.mp4?token=abc",
            ),
            extracted = emptyList(),
            downloadUrl = null,
            bridgeUrl = bridge,
            blocklist = blocklist,
        )
        assertEquals(
            listOf(
                "https://cdn.example.com/hls/list.m3u8",
                "https://cdn.example.com/stream/video.mp4?token=abc",
            ),
            ranked,
        )
    }

    @Test
    fun `extensionless playlist and stream urls rank before other recorded urls`() {
        val ranked = WebViewEngine.rankProbeCandidates(
            recorded = listOf(
                "https://cdn.example.com/abc123",
                "https://cdn.example.com/hls/playlist?token=xyz",
                "https://cdn.example.com/thumb.jpg",
            ),
            extracted = emptyList(),
            downloadUrl = null,
            bridgeUrl = bridge,
            blocklist = blocklist,
        )
        assertEquals(
            listOf(
                "https://cdn.example.com/hls/playlist?token=xyz",
                "https://cdn.example.com/abc123",
            ),
            ranked,
        )
    }

    @Test
    fun `ts segments are never candidates even when they carry video bytes`() {
        val ranked = WebViewEngine.rankProbeCandidates(
            recorded = listOf(
                "https://cdn.example.com/hls/seg0.ts",
                "https://cdn.example.com/hls/seg1.ts",
                "https://cdn.example.com/hls/list.m3u8",
            ),
            extracted = emptyList(),
            downloadUrl = null,
            bridgeUrl = bridge,
            blocklist = blocklist,
        )
        assertEquals(listOf("https://cdn.example.com/hls/list.m3u8"), ranked)
    }

    @Test
    fun `static assets, the bridge url and blocklisted hosts are never candidates`() {
        val ranked = WebViewEngine.rankProbeCandidates(
            recorded = listOf(
                bridge,
                "https://filemoon.sx/app.js",
                "https://filemoon.sx/style.css",
                "https://tracker.example.com/video.mp4",
                "https://ads.example.com/pixel.gif",
                "https://cdn.example.com/video.mp4",
            ),
            extracted = emptyList(),
            downloadUrl = null,
            bridgeUrl = bridge,
            blocklist = blocklist,
        )
        assertEquals(listOf("https://cdn.example.com/video.mp4"), ranked)
    }

    @Test
    fun `the download-listener url ranks first and duplicates collapse`() {
        val ranked = WebViewEngine.rankProbeCandidates(
            recorded = listOf("https://cdn.example.com/video.mp4"),
            extracted = listOf("https://cdn.example.com/video.mp4"),
            downloadUrl = "https://cdn.example.com/video.mp4",
            bridgeUrl = bridge,
            blocklist = blocklist,
        )
        assertEquals(listOf("https://cdn.example.com/video.mp4"), ranked)
    }

    @Test
    fun `cross-host navigation is allowed, non-http schemes are not`() {
        assertTrue(WebViewEngine.navigationAllowed("https://other.example.com/watch/abc"))
        assertTrue(WebViewEngine.navigationAllowed("http://cdn.example.net/player"))
        assertFalse(WebViewEngine.navigationAllowed("intent://scan/#Intent;scheme=foo;end"))
        assertFalse(WebViewEngine.navigationAllowed("tel:+1234567890"))
        assertFalse(WebViewEngine.navigationAllowed("blob:https://filemoon.sx/abc"))
    }

    @Test
    fun `parseExtracted reads videos anchors resources title and text`() {
        val inner = """{"videos":["blob:https://filemoon.sx/abc","https://cdn.example.com/v.mp4"],"anchors":["https://cdn.example.com/a.mkv"],"resources":["https://cdn.example.com/hls/list.m3u8","https://cdn.example.com/seg0.ts"],"title":"Watch","text":"please wait 30 seconds"}"""
        val outer = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val data = WebViewEngine.parseExtracted(outer)
        assertNotNull(data)
        assertEquals(listOf("blob:https://filemoon.sx/abc", "https://cdn.example.com/v.mp4"), data!!.videos)
        assertEquals(listOf("https://cdn.example.com/a.mkv"), data.anchors)
        assertEquals(
            listOf("https://cdn.example.com/hls/list.m3u8", "https://cdn.example.com/seg0.ts"),
            data.resources,
        )
        assertEquals("Watch", data.title)
        assertTrue(data.text.contains("please wait"))
    }

    @Test
    fun `local fixture page - ranked candidates probe ACTIVE and segments never mint`() = runBlocking {
        val mp4 = ByteArray(1024).also {
            // Minimal ftyp box at offset 4, so the probe reads the mp4 magic.
            "....ftypisom".toByteArray(Charsets.US_ASCII).copyInto(it)
        }
        val m3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg0.ts\n".toByteArray(Charsets.UTF_8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/page.html") { exchange ->
                val body = """<html><body><video src="/stream/video.mp4"></video><script>fetch("/hls/list.m3u8")</script></body></html>""".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/stream/video.mp4") { exchange ->
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(200, mp4.size.toLong())
                exchange.responseBody.use { it.write(mp4) }
            }
            createContext("/hls/list.m3u8") { exchange ->
                exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, m3u8.size.toLong())
                exchange.responseBody.use { it.write(m3u8) }
            }
            createContext("/hls/seg0.ts") { exchange ->
                val body = ByteArray(188) { 0x47 }
                exchange.responseHeaders.set("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/app.js") { exchange ->
                val body = "console.log(1)".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/javascript")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            executor = null
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            // What the WebView records while the fixture page plays: the
            // playlist, the segments, the mp4 and the static assets.
            val recorded = listOf(
                "$base/page.html",
                "$base/app.js",
                "$base/hls/seg0.ts",
                "$base/hls/list.m3u8",
                "$base/stream/video.mp4",
            )
            val ranked = WebViewEngine.rankProbeCandidates(
                recorded = recorded,
                extracted = emptyList(),
                downloadUrl = null,
                bridgeUrl = "$base/page.html",
                blocklist = emptyList(),
            )
            assertEquals(listOf("$base/hls/list.m3u8", "$base/stream/video.mp4"), ranked)
            // Probing the ranked list the way the engine does mints: the
            // playlist holds an HLS stream, the mp4 holds ftyp magic.
            val transport = OkHttpResolveTransport()
            try {
                for (url in ranked) {
                    val probe = runRangeProbe(transport, url)
                    assertEquals("probe $url", ProbeVerdict.ACTIVE, probe.verdict)
                }
            } finally {
                transport.close()
            }
        } finally {
            server.stop(0)
        }
    }
}
