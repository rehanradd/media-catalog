package com.raddstream.app.resolve

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
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
        // Minimal hand-rolled HTTP server: com.sun.net.httpserver is NOT on
        // the Android unit-test compile classpath (CI run 43919653116 failed
        // compileDebugUnitTestKotlin on `Unresolved reference 'sun'`), so the
        // fixture serves over a raw ServerSocket instead — pure java.net.
        val routes = mapOf(
            "/page.html" to ("text/html" to """<html><body><video src="/stream/video.mp4"></video><script>fetch("/hls/list.m3u8")</script></body></html>""".toByteArray(Charsets.UTF_8)),
            "/stream/video.mp4" to ("video/mp4" to mp4),
            "/hls/list.m3u8" to ("application/vnd.apple.mpegurl" to m3u8),
            "/hls/seg0.ts" to ("video/mp2t" to ByteArray(188) { 0x47 }),
            "/app.js" to ("text/javascript" to "console.log(1)".toByteArray(Charsets.UTF_8)),
        )
        val server = FixtureHttpServer(routes).also { it.start() }
        try {
            val base = "http://127.0.0.1:${server.port}"
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
            server.stop()
        }
    }

    /**
     * Serves canned (content-type, body) pairs over loopback. Answers 200
     * with the full body for any route it knows (the probe's Range header is
     * ignored, exactly as the fixture's contract states) and 404 otherwise.
     * One request per connection, `Connection: close`, headers read
     * byte-by-byte up to the blank line — the same shape as FakeSyncServer
     * in NewPhoneProgressPullTest, which documents why `com.sun` is out.
     */
    private class FixtureHttpServer(private val routes: Map<String, Pair<String, ByteArray>>) {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        private val acceptThread = Thread({
            while (!serverSocket.isClosed) {
                try {
                    val socket = serverSocket.accept()
                    Thread({ handle(socket) }, "fixture-http").apply { isDaemon = true }.start()
                } catch (_: SocketException) {
                    // Closed while accepting; stop quietly.
                    break
                } catch (_: IOException) {
                    // A transient accept failure must not kill the server.
                    continue
                }
            }
        }, "fixture-http-accept").also { it.isDaemon = true }

        fun start() = acceptThread.start()

        fun stop() = serverSocket.close()

        private fun handle(socket: Socket) {
            try {
                serve(socket)
            } catch (_: IOException) {
                // One bad connection must not kill the server thread.
            }
        }

        private fun serve(socket: Socket) {
            socket.use { s ->
                val input = s.getInputStream()
                val head = ByteArrayOutputStream()
                // Read byte by byte until the blank line that ends the headers.
                var tail = 0
                while (true) {
                    val byte = input.read()
                    if (byte < 0) return
                    head.write(byte)
                    tail = (tail shl 8) or byte
                    if (tail == 0x0D0A0D0A) break
                }
                val requestLine = head.toString(Charsets.US_ASCII).split("\r\n").firstOrNull()
                    ?.split(" ") ?: return
                val path = requestLine.getOrNull(1)?.substringBefore("?") ?: "/"
                val (contentType, body) = routes[path]
                    ?: ("text/plain" to "not found".toByteArray(Charsets.UTF_8))
                val status = if (routes.containsKey(path)) 200 else 404
                val out = s.getOutputStream()
                out.write(
                    "HTTP/1.1 $status OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                out.write(body)
                out.flush()
            }
        }
    }
}
