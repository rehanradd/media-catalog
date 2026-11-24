package com.raddstream.app.diagnostics

import com.raddstream.app.diagnostics.debug.DiagnosticsCounts
import com.raddstream.app.diagnostics.debug.countEvents
import com.raddstream.app.diagnostics.debug.filterEvents
import com.raddstream.app.diagnostics.debug.rankProblems
import com.raddstream.app.diagnostics.debug.scoreProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timeline filters + Problems ranking (APP-13, review N-2 scoring).
 */
class DiagnosticsFiltersTest {

    private val now = 1_700_000_000_000L

    private fun event(
        id: Long,
        type: DiagnosticsType = DiagnosticsType.API,
        severity: DiagnosticsSeverity = DiagnosticsSeverity.INFO,
        summary: String = "event",
        title: String? = null,
        host: String? = null,
        error: String? = null,
        timestampMs: Long = now,
    ) = DiagnosticsEvent(
        id = id, timestampMs = timestampMs, type = type, severity = severity,
        summary = summary, title = title, host = host, error = error,
    )

    @Test
    fun `query matches summary title host and error case-insensitively`() {
        val events = listOf(
            event(1, summary = "GET /api/v1/posts → 200 in 90ms"),
            event(2, summary = "Playback failed", title = "Salaar", error = "network failed"),
            event(3, summary = "Download complete", host = "catalog.example.com"),
        )
        assertEquals(listOf(2L), filterEvents(events, "salaar", emptySet(), emptySet()).map { it.id })
        assertEquals(listOf(2L), filterEvents(events, "NETWORK", emptySet(), emptySet()).map { it.id })
        assertEquals(listOf(3L), filterEvents(events, "catalog", emptySet(), emptySet()).map { it.id })
        assertEquals(3, filterEvents(events, "", emptySet(), emptySet()).size)
    }

    @Test
    fun `type and severity filters compose with AND`() {
        val events = listOf(
            event(1, type = DiagnosticsType.API, severity = DiagnosticsSeverity.INFO),
            event(2, type = DiagnosticsType.PLAYBACK, severity = DiagnosticsSeverity.ERROR),
            event(3, type = DiagnosticsType.PLAYBACK, severity = DiagnosticsSeverity.WARNING),
        )
        val filtered = filterEvents(
            events, "", setOf(DiagnosticsType.PLAYBACK), setOf(DiagnosticsSeverity.ERROR),
        )
        assertEquals(listOf(2L), filtered.map { it.id })
    }

    @Test
    fun `rankProblems puts errors first and excludes INFO`() {
        val events = listOf(
            event(1, severity = DiagnosticsSeverity.INFO, summary = "info"),
            event(2, severity = DiagnosticsSeverity.WARNING, summary = "warn", timestampMs = now - 1_000),
            event(3, severity = DiagnosticsSeverity.ERROR, summary = "err", timestampMs = now - 2_000),
        )
        val ranked = rankProblems(events, nowMs = now)
        assertEquals(listOf(3L, 2L), ranked.map { it.id })
    }

    @Test
    fun `rankProblems prefers the newer error among one-offs`() {
        val events = listOf(
            event(1, severity = DiagnosticsSeverity.ERROR, summary = "old error", timestampMs = now - 3_600_000),
            event(2, severity = DiagnosticsSeverity.ERROR, summary = "new error", timestampMs = now),
        )
        val ranked = rankProblems(events, nowMs = now)
        assertEquals(listOf(2L, 1L), ranked.map { it.id })
    }

    @Test
    fun `rankProblems boosts a repeated error above a newer one-off`() {
        // Same summary three times, 1h old: 100 + 4.79 + ln(4)*2 ≈ 107.6.
        // One-off error right now: 100 + 5 + ln(2)*2 ≈ 106.4. Frequency wins.
        val events = listOf(
            event(1, severity = DiagnosticsSeverity.ERROR, summary = "same failure", timestampMs = now - 3_600_000),
            event(2, severity = DiagnosticsSeverity.ERROR, summary = "same failure", timestampMs = now - 3_600_000),
            event(3, severity = DiagnosticsSeverity.ERROR, summary = "same failure", timestampMs = now - 3_600_000),
            event(4, severity = DiagnosticsSeverity.ERROR, summary = "new failure", timestampMs = now),
        )
        val ranked = rankProblems(events, nowMs = now)
        assertEquals(1L, ranked.first().id)
        assertTrue(ranked.take(3).all { it.summary == "same failure" })
    }

    @Test
    fun `scoreProblem weights severity above recency above frequency`() {
        val error = event(1, severity = DiagnosticsSeverity.ERROR, summary = "e", timestampMs = now)
        val warning = event(2, severity = DiagnosticsSeverity.WARNING, summary = "w", timestampMs = now)
        val oldError = event(3, severity = DiagnosticsSeverity.ERROR, summary = "e", timestampMs = now - 25 * 3_600_000)
        assertTrue(scoreProblem(error, 1, now) > scoreProblem(warning, 1, now))
        assertTrue(scoreProblem(error, 1, now) > scoreProblem(oldError, 1, now))
        assertTrue(scoreProblem(error, 3, now) > scoreProblem(error, 1, now))
    }

    @Test
    fun `countEvents counts per type and severity`() {
        val events = listOf(
            event(1, type = DiagnosticsType.API, severity = DiagnosticsSeverity.ERROR),
            event(2, type = DiagnosticsType.API, severity = DiagnosticsSeverity.WARNING),
            event(3, type = DiagnosticsType.PLAYBACK, severity = DiagnosticsSeverity.INFO),
        )
        val counts: DiagnosticsCounts = countEvents(events)
        assertEquals(3, counts.total)
        assertEquals(1, counts.errors)
        assertEquals(1, counts.warnings)
        assertEquals(2, counts.byType[DiagnosticsType.API])
        assertEquals(1, counts.byType[DiagnosticsType.PLAYBACK])
    }
}
