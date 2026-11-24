package com.raddstream.app.diagnostics

import com.raddstream.app.diagnostics.debug.DebugDiagnostics
import com.raddstream.app.diagnostics.debug.DiagnosticsRedactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The debug recorder (APP-13, review B-1 + B-2 + R-1 + R-3): the ring
 * buffer, the recordApi typing, the capture-time redaction proof, the
 * session id, truncation, and the counter feed. Every credential value
 * below is an obviously-fake placeholder — never a real credential.
 */
class DebugDiagnosticsTest {

    @Before
    fun setUp() {
        DebugDiagnostics.resetForTests()
    }

    @After
    fun tearDown() {
        DebugDiagnostics.resetForTests()
    }

    @Test
    fun `ring buffer caps at 5000 and drops the oldest`() {
        for (i in 1..(DebugDiagnostics.MAX_EVENTS + 5)) {
            DebugDiagnostics.record(
                DiagnosticsType.API, DiagnosticsSeverity.INFO, "event $i",
            )
        }
        val snapshot = DebugDiagnostics.snapshot()
        assertEquals(DebugDiagnostics.MAX_EVENTS, snapshot.size)
        assertEquals("event 6", snapshot.first().summary)
        assertEquals("event ${DebugDiagnostics.MAX_EVENTS + 5}", snapshot.last().summary)
        assertEquals(5L, DebugDiagnostics.droppedCount.value)
    }

    @Test
    fun `events are stored in record order`() {
        DebugDiagnostics.record(DiagnosticsType.API, DiagnosticsSeverity.INFO, "first")
        DebugDiagnostics.record(DiagnosticsType.API, DiagnosticsSeverity.INFO, "second")
        val snapshot = DebugDiagnostics.snapshot()
        assertEquals(listOf("first", "second"), snapshot.map { it.summary })
    }

    @Test
    fun `clear empties the buffer and snapshot is a copy`() {
        DebugDiagnostics.record(DiagnosticsType.API, DiagnosticsSeverity.INFO, "one")
        val copy = DebugDiagnostics.snapshot()
        assertEquals(1, copy.size)
        DebugDiagnostics.clear()
        assertTrue(DebugDiagnostics.snapshot().isEmpty())
        assertEquals(1, copy.size)
    }

    @Test
    fun `recordApi types resolve calls as RESOLVER and slow calls as WARNING`() {
        DebugDiagnostics.recordApi("GET", "https://catalog.example.com/api/v2/play", 200, 120)
        DebugDiagnostics.recordApi("GET", "https://catalog.example.com/api/v1/posts", 200, 3_500)
        val snapshot = DebugDiagnostics.snapshot()
        assertEquals(DiagnosticsType.RESOLVER, snapshot[0].type)
        assertEquals(DiagnosticsSeverity.INFO, snapshot[0].severity)
        assertEquals(DiagnosticsType.API, snapshot[1].type)
        assertEquals(DiagnosticsSeverity.WARNING, snapshot[1].severity)
        assertTrue(snapshot[1].summary.contains("(slow)"))
    }

    @Test
    fun `recordApi types a 5xx as ERROR`() {
        DebugDiagnostics.recordApi("GET", "https://catalog.example.com/api/v1/posts", 503, 90)
        assertEquals(DiagnosticsSeverity.ERROR, DebugDiagnostics.snapshot().single().severity)
    }

    @Test
    fun `recordApi keeps host and path for a clean URL`() {
        DebugDiagnostics.recordApi("GET", "https://catalog.example.com/api/v1/posts", 200, 90)
        val event = DebugDiagnostics.snapshot().single()
        assertEquals("catalog.example.com", event.host)
        assertTrue(event.summary.contains("/api/v1/posts"))
    }

    @Test
    fun `recordApi drops a token-bearing URL in full`() {
        DebugDiagnostics.recordApi(
            "GET",
            "https://pub-fake123.r2.dev/file.mkv?X-Amz-Signature=fake-signature-789",
            200, 90,
        )
        val event = DebugDiagnostics.snapshot().single()
        assertEquals(null, event.host)
        assertTrue(event.summary.contains(DiagnosticsRedactor.REDACTED))
        assertFalse(event.summary.contains("fake-signature-789"))
    }

    @Test
    fun `recordApi stores the Date-header clock offset`() {
        // A Date header stamped "now" must read as a small offset.
        val now = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(System.currentTimeMillis()))
        DebugDiagnostics.recordApi("GET", "https://catalog.example.com/api/v1/posts", 200, 90, dateHeader = now)
        val offset = DebugDiagnostics.clockOffsetMs
        assertTrue("offset $offset ms is too large", kotlin.math.abs(offset) < 60_000L)
    }

    @Test
    fun `token-bearing URL and credential header leave no credential in the recorded event`() {
        // The hard-constraint proof (parent steer 08:37 PKT + review B-1):
        // a signed R2 URL plus a Bearer sync key plus a session value go
        // through the capture path, and no part of any credential value may
        // appear in ANY field of either stored event.
        val tokenUrl = "https://pub-fake123.r2.dev/file.mkv" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=fake-credential-value-111" +
            "&X-Amz-Signature=fake-signature-value-222" +
            "&token=fake-token-value-333"
        DebugDiagnostics.recordApi("GET", tokenUrl, 200, 90)
        DebugDiagnostics.record(
            DiagnosticsType.API,
            DiagnosticsSeverity.ERROR,
            "call failed",
            detail = "Authorization: Bearer fake-sync-key-123",
            error = "session=fake-session-value-456",
        )
        val events = DebugDiagnostics.snapshot()
        assertEquals(2, events.size)
        val credentialParts = listOf(
            "fake-credential-value-111", "fake-signature-value-222",
            "fake-token-value-333", "fake-sync-key-123", "fake-session-value-456",
            "AWS4-HMAC-SHA256",
        )
        for (event in events) {
            val fields = listOfNotNull(
                event.summary, event.detail, event.screen, event.title,
                event.quality, event.host, event.error,
            )
            for (part in credentialParts) {
                for (field in fields) {
                    assertFalse("credential part leaked: $part in $field", field.contains(part))
                }
            }
            assertTrue("event carries the session id", event.sessionId.isNotBlank())
            assertEquals(DebugDiagnostics.sessionId, event.sessionId)
        }
        // The API event's path is the redaction marker, not the URL.
        assertTrue(events[0].summary.contains(DiagnosticsRedactor.REDACTED))
    }

    @Test
    fun `every stored event carries the session id`() {
        assertTrue(DebugDiagnostics.sessionId.isNotBlank())
        DebugDiagnostics.record(DiagnosticsType.NAVIGATION, DiagnosticsSeverity.INFO, "Opened home")
        val event = DebugDiagnostics.snapshot().single()
        assertEquals(DebugDiagnostics.sessionId, event.sessionId)
        assertTrue(event.timestampNs != 0L)
    }

    @Test
    fun `long titles are truncated to 200 chars at record time`() {
        val longTitle = "T".repeat(250)
        DebugDiagnostics.recordPlayback(
            DiagnosticsSeverity.INFO, "Playback started: 1080p", title = longTitle,
        )
        val event = DebugDiagnostics.snapshot().single()
        assertEquals(200, event.title!!.length)
    }

    @Test
    fun `recordCounter increments the totals map and records an event`() {
        DebugDiagnostics.recordCounter("playback_attempts")
        DebugDiagnostics.recordCounter("playback_attempts")
        DebugDiagnostics.recordCounter("playback_errors")
        assertEquals(2L, DebugDiagnostics.counters.value["playback_attempts"])
        assertEquals(1L, DebugDiagnostics.counters.value["playback_errors"])
        assertTrue(
            DebugDiagnostics.snapshot().any { it.summary == "Counter playback_attempts +1" },
        )
    }

    @Test
    fun `recordNavigation strips the query`() {
        DebugDiagnostics.recordNavigation("detail/123?tab=episodes")
        val event = DebugDiagnostics.snapshot().single()
        assertEquals("detail/123", event.screen)
        assertEquals("Opened detail/123", event.summary)
        DebugDiagnostics.recordNavigation(null)
        assertEquals(1, DebugDiagnostics.snapshot().size)
    }
}
