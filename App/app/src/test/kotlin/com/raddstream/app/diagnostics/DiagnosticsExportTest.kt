package com.raddstream.app.diagnostics

import com.raddstream.app.diagnostics.debug.DiagnosticsExport
import com.raddstream.app.diagnostics.debug.DiagnosticsRedactor
import com.raddstream.app.diagnostics.debug.DiagnosticsSessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export builders (APP-13, review N-3 + R-5): the Markdown summary, the
 * JSONL form, the export-time second pass, and the JSONL round-trip the
 * persist path uses. Every credential value below is an obviously-fake
 * placeholder — never a real credential.
 */
class DiagnosticsExportTest {

    private val header = DiagnosticsSessionInfo(
        appVersion = "1.0.0-test",
        versionCode = 42,
        applicationId = "com.raddstream.app.debug",
        deviceModel = "Test Device",
        androidVersion = "Android 14 (SDK 34)",
        networkType = "wifi",
        serverHost = "catalog.example.com",
        sessionStartedMs = 1_700_000_000_000L,
        sessionId = "fake-session-id-1",
        clockOffsetMs = 0L,
    )

    private fun sampleEvents() = listOf(
        DiagnosticsEvent(
            id = 1L, timestampMs = 1_700_000_000_000L,
            type = DiagnosticsType.API, severity = DiagnosticsSeverity.INFO,
            summary = "GET /api/v1/posts → 200 in 90ms",
            sessionId = "fake-session-id-1",
        ),
        DiagnosticsEvent(
            id = 2L, timestampMs = 1_700_000_001_000L,
            type = DiagnosticsType.PLAYBACK, severity = DiagnosticsSeverity.ERROR,
            summary = "Playback failed: ERROR_CODE_IO",
            title = "Salaar", error = "network failed",
            sessionId = "fake-session-id-1",
        ),
    )

    @Test
    fun `buildText is Markdown with the header problems first and the timeline`() {
        val text = DiagnosticsExport.buildText(header, sampleEvents())
        // Brand.name is identity.name ("RADD Stream"), not the short name.
        assertTrue(text.contains("# RADD Stream diagnostics"))
        assertTrue(text.contains("- Session ID: fake-session-id-1"))
        assertTrue(text.contains("- Events: 2 total · 1 errors · 0 warnings"))
        assertTrue(text.contains("## Problems (1)"))
        assertTrue(text.contains("## Timeline (2)"))
        // Problems come before the timeline in the document.
        assertTrue(text.indexOf("## Problems") < text.indexOf("## Timeline"))
        assertTrue(text.contains("Playback failed: ERROR_CODE_IO"))
    }

    @Test
    fun `buildJsonLines carries a header line then one JSON object per event`() {
        val jsonl = DiagnosticsExport.buildJsonLines(header, sampleEvents())
        val lines = jsonl.trim().split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"type\": \"header\""))
        assertTrue(lines[0].contains("\"session_id\": \"fake-session-id-1\""))
        assertTrue(lines[1].contains("\"summary\": \"GET /api/v1/posts → 200 in 90ms\""))
        assertTrue(lines[2].contains("\"severity\": \"error\""))
        assertTrue(lines[2].contains("\"session_id\": \"fake-session-id-1\""))
    }

    @Test
    fun `export second pass redacts a raw presigned URL fed to the export pipeline`() {
        // Review R-5: even if a raw credential reached the export builders
        // (bypassing the recorder), the finished export must not carry it.
        val raw = DiagnosticsEvent(
            id = 9L, timestampMs = 1_700_000_002_000L,
            type = DiagnosticsType.API, severity = DiagnosticsSeverity.ERROR,
            summary = "GET https://pub-fake123.r2.dev/file.mkv?X-Amz-Signature=fake-signature-777 → 200 in 90ms",
            detail = "Authorization: Bearer fake-sync-key-888",
            sessionId = "fake-session-id-1",
        )
        val text = DiagnosticsExport.buildText(header, listOf(raw))
        assertTrue(text.contains(DiagnosticsRedactor.REDACTED))
        assertFalse(text.contains("fake-signature-777"))
        assertFalse(text.contains("fake-sync-key-888"))
        val jsonl = DiagnosticsExport.buildJsonLines(header, listOf(raw))
        assertTrue(jsonl.contains(DiagnosticsRedactor.REDACTED))
        assertFalse(jsonl.contains("fake-signature-777"))
        assertFalse(jsonl.contains("fake-sync-key-888"))
    }

    @Test
    fun `eventJsonLine round-trips through parseEventJsonLine`() {
        val event = DiagnosticsEvent(
            id = 7L, timestampMs = 1_700_000_003_000L,
            type = DiagnosticsType.DOWNLOAD, severity = DiagnosticsSeverity.WARNING,
            summary = "Download slow: \"quoted\" title\nnewline",
            detail = "detail with \\ backslash",
            screen = "downloads", title = "A Title", quality = "720p",
            host = "catalog.example.com", error = null, durationMs = 1234L,
            timestampNs = 987654321L, sessionId = "fake-session-id-1",
        )
        val parsed = DiagnosticsExport.parseEventJsonLine(DiagnosticsExport.eventJsonLine(event))
        assertEquals(event, parsed)
    }

    @Test
    fun `parseEventJsonLine returns null for malformed lines`() {
        assertNull(DiagnosticsExport.parseEventJsonLine("not json at all"))
        assertNull(DiagnosticsExport.parseEventJsonLine("{\"id\": 1}"))
        assertNull(DiagnosticsExport.parseEventJsonLine(""))
    }
}
