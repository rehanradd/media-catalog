package com.raddstream.app.diagnostics.debug

import com.raddstream.app.diagnostics.DiagnosticsEvent
import com.raddstream.app.diagnostics.DiagnosticsSeverity
import com.raddstream.app.diagnostics.DiagnosticsType
import kotlin.math.ln

/**
 * Pure timeline functions — no Android dependencies, no recorder access —
 * so the unit tests pin them without Compose or a ViewModel.
 *
 * Filter semantics: the query, the type set and the severity set compose
 * with AND. An empty query matches everything; an empty type or severity set
 * means "no filter on that axis" (the UI's "All" state).
 */
fun filterEvents(
    events: List<DiagnosticsEvent>,
    query: String,
    types: Set<DiagnosticsType>,
    severities: Set<DiagnosticsSeverity>,
): List<DiagnosticsEvent> {
    val needle = query.trim().lowercase()
    return events.filter { event ->
        (types.isEmpty() || event.type in types) &&
            (severities.isEmpty() || event.severity in severities) &&
            (needle.isEmpty() || matchesQuery(event, needle))
    }
}

private fun matchesQuery(event: DiagnosticsEvent, needle: String): Boolean {
    return event.summary.lowercase().contains(needle) ||
        (event.detail?.lowercase()?.contains(needle) == true) ||
        (event.title?.lowercase()?.contains(needle) == true) ||
        (event.host?.lowercase()?.contains(needle) == true) ||
        (event.error?.lowercase()?.contains(needle) == true) ||
        (event.screen?.lowercase()?.contains(needle) == true)
}

/**
 * The Problems view, ranked by score (review N-2):
 * `score = severityWeight * 10 + recency * 5 + frequency * 2`, where
 * severityWeight is 10 for ERROR and 1 for WARNING, recency is 1 for an
 * event right now falling linearly to 0 at 24h old, and frequency is
 * ln(1 + count) of events sharing the same summary (a problem that keeps
 * happening ranks above a one-off). INFO never appears here. Sorted by
 * score descending, then timestamp descending. `nowMs` is a parameter so
 * the ranking is deterministic in tests.
 */
fun rankProblems(
    events: List<DiagnosticsEvent>,
    nowMs: Long = System.currentTimeMillis(),
): List<DiagnosticsEvent> {
    val problems = events.filter { it.isProblem }
    if (problems.isEmpty()) return emptyList()
    val frequencies = problems.groupingBy { it.summary }.eachCount()
    return problems.sortedWith(
        compareByDescending<DiagnosticsEvent> { scoreProblem(it, frequencies[it.summary] ?: 1, nowMs) }
            .thenByDescending { it.timestampMs },
    )
}

/** The N-2 score for one problem event. Exposed for tests. */
fun scoreProblem(event: DiagnosticsEvent, frequency: Int, nowMs: Long): Double {
    val severityWeight = when (event.severity) {
        DiagnosticsSeverity.ERROR -> 10.0
        DiagnosticsSeverity.WARNING -> 1.0
        DiagnosticsSeverity.INFO -> 0.0
    }
    val ageHours = ((nowMs - event.timestampMs).coerceAtLeast(0L)).toDouble() / 3_600_000.0
    val recency = (1.0 - ageHours / 24.0).coerceIn(0.0, 1.0)
    val frequencyWeight = ln(1.0 + frequency.toDouble())
    return severityWeight * 10.0 + recency * 5.0 + frequencyWeight * 2.0
}

/** Counts per type and per severity, for the Session tab and the export header. */
data class DiagnosticsCounts(
    val total: Int,
    val errors: Int,
    val warnings: Int,
    val byType: Map<DiagnosticsType, Int>,
)

fun countEvents(events: List<DiagnosticsEvent>): DiagnosticsCounts {
    return DiagnosticsCounts(
        total = events.size,
        errors = events.count { it.severity == DiagnosticsSeverity.ERROR },
        warnings = events.count { it.severity == DiagnosticsSeverity.WARNING },
        byType = DiagnosticsType.entries.associateWith { type ->
            events.count { it.type == type }
        },
    )
}
