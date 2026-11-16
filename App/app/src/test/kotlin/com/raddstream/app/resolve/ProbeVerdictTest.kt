package com.raddstream.app.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Range-probe verdict logic, mirroring the server's `probe_range`. */
class ProbeVerdictTest {

    private val empty = ByteArray(0)
    private val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x42, 0x82)
    private val ftyp = byteArrayOf(0x00, 0x00, 0x00, 0x00, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    private val hls = "#EXTM3U\n#EXT-X-VERSION:3".toByteArray(Charsets.UTF_8)
    private val html = "<html><body>nope</body></html>".toByteArray(Charsets.UTF_8)

    @Test
    fun `206 video is active with no note`() {
        val result = probeVerdict(206, "video/mp4", empty, 512L)
        assertEquals(ProbeVerdict.ACTIVE, result.verdict)
        assertNull(result.note)
        assertEquals(512L, result.contentLength)
    }

    @Test
    fun `206 html is unknown`() {
        val result = probeVerdict(206, "text/html", html, 512L)
        assertEquals(ProbeVerdict.UNKNOWN, result.verdict)
    }

    @Test
    fun `200 video is active with full_body`() {
        val result = probeVerdict(200, "video/mp4", empty, 1024L)
        assertEquals(ProbeVerdict.ACTIVE, result.verdict)
        assertEquals("full_body", result.note)
    }

    @Test
    fun `200 html is unknown, not blocked`() {
        val result = probeVerdict(200, "text/html", html, 512L)
        assertEquals(ProbeVerdict.UNKNOWN, result.verdict)
    }

    @Test
    fun `challenge and dead statuses map`() {
        assertEquals(ProbeVerdict.BLOCKED, probeVerdict(403, "text/html", html, 512L).verdict)
        assertEquals(ProbeVerdict.BLOCKED, probeVerdict(401, "text/html", html, 512L).verdict)
        assertEquals(ProbeVerdict.BLOCKED, probeVerdict(429, "text/html", html, 512L).verdict)
        assertEquals(ProbeVerdict.DEAD, probeVerdict(404, "text/html", html, 512L).verdict)
        assertEquals(ProbeVerdict.DEAD, probeVerdict(410, "text/html", html, 512L).verdict)
        assertEquals(ProbeVerdict.ERROR, probeVerdict(500, "text/html", html, 512L).verdict)
        assertEquals(ProbeVerdict.ERROR, probeVerdict(522, "text/html", html, 512L).verdict)
        assertEquals(ProbeVerdict.UNKNOWN, probeVerdict(302, "text/html", html, 512L).verdict)
    }

    @Test
    fun `container magic proves media despite the content-type`() {
        assertEquals(ProbeVerdict.ACTIVE, probeVerdict(206, "application/octet-stream", ebml, 512L).verdict)
        assertEquals(ProbeVerdict.ACTIVE, probeVerdict(206, "application/octet-stream", ftyp, 512L).verdict)
    }

    @Test
    fun `hls playlist proves media`() {
        val result = probeVerdict(206, "text/plain", hls, 512L)
        assertEquals(ProbeVerdict.ACTIVE, result.verdict)
        val byType = probeVerdict(206, "application/vnd.apple.mpegurl", empty, 512L)
        assertEquals(ProbeVerdict.ACTIVE, byType.verdict)
    }

    @Test
    fun `content-type parameters are stripped`() {
        val result = probeVerdict(206, "video/mp4; charset=utf-8", empty, 512L)
        assertEquals(ProbeVerdict.ACTIVE, result.verdict)
        assertEquals("video/mp4", result.contentType)
    }
}
