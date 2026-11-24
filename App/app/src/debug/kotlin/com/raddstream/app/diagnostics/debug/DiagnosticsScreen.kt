package com.raddstream.app.diagnostics.debug

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.diagnostics.DiagnosticsEvent
import com.raddstream.app.diagnostics.DiagnosticsSeverity
import com.raddstream.app.diagnostics.DiagnosticsType
import com.raddstream.app.diagnostics.debug.DiagnosticsViewModel.UiState

/**
 * The debug-only diagnostics screen (APP-13): a live event timeline, a
 * problems view, and device + session info, with export/share from the
 * screen. It lives in `src/debug` and is registered only by
 * `DebugDiagnostics.addDestination` — no release build contains it, and no
 * release build can navigate to it (`Diagnostics.isEnabled()` is false).
 *
 * Leaving the screen persists the buffer to a private file (review R-1);
 * that is the only graceful-shutdown persist in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBackClick: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(uiState.notice) {
        uiState.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearNotice()
        }
    }
    // R-1: leaving the screen is the graceful-shutdown persist point.
    DisposableEffect(Unit) {
        onDispose { viewModel.persist() }
    }
    DiagnosticsContent(
        events = events,
        uiState = uiState,
        onBackClick = onBackClick,
        onQueryChange = viewModel::setQuery,
        onToggleType = viewModel::toggleType,
        onToggleSeverity = viewModel::toggleSeverity,
        onClearFilters = viewModel::clearFilters,
        onClearEvents = viewModel::clearEvents,
        onExport = { viewModel.exportResults(events) },
        onCopy = { viewModel.copyResultsJsonl(events) },
    )
}

/**
 * The pure screen content: every value arrives as a parameter, so the
 * timeline, filters, ranking and session rows are all assertable from the
 * same state the unit tests build — no composition needed to verify them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsContent(
    events: List<DiagnosticsEvent>,
    uiState: UiState,
    onBackClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleType: (DiagnosticsType) -> Unit,
    onToggleSeverity: (DiagnosticsSeverity) -> Unit,
    onClearFilters: () -> Unit,
    onClearEvents: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val counts = remember(events) { countEvents(events) }
    val problems = remember(events) { rankProblems(events) }
    val timelineVisible = remember(events, uiState) {
        filterEvents(
            events,
            uiState.query,
            uiState.selectedTypes,
            uiState.selectedSeverities,
        ).asReversed()
    }
    // The Problems tab keeps the ranking: errors newest-first, then
    // warnings newest-first (rankProblems returns display order by score).
    // Do NOT reverse — reversing it would put warnings first.
    val problemsVisible = remember(problems, uiState) {
        filterEvents(
            problems,
            uiState.query,
            uiState.selectedTypes,
            uiState.selectedSeverities,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.diagnostics_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            // R-1 back-pressure flag: the buffer filled and oldest events
            // were evicted — say so, instead of silently showing a window.
            if (uiState.droppedCount > 0L) {
                Text(
                    stringResource(R.string.diagnostics_truncated),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.diagnostics_search_hint)) },
                singleLine = true,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = uiState.selectedTypes.isEmpty(),
                    onClick = onClearFilters,
                    label = { Text(stringResource(R.string.diagnostics_filter_all)) },
                )
                for (type in DiagnosticsType.entries) {
                    FilterChip(
                        selected = type in uiState.selectedTypes,
                        onClick = { onToggleType(type) },
                        label = { Text(type.name.lowercase()) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = uiState.selectedSeverities.isEmpty(),
                    onClick = onClearFilters,
                    label = { Text(stringResource(R.string.diagnostics_filter_all)) },
                )
                FilterChip(
                    selected = DiagnosticsSeverity.ERROR in uiState.selectedSeverities,
                    onClick = { onToggleSeverity(DiagnosticsSeverity.ERROR) },
                    label = { Text(stringResource(R.string.diagnostics_filter_errors)) },
                )
                FilterChip(
                    selected = DiagnosticsSeverity.WARNING in uiState.selectedSeverities,
                    onClick = { onToggleSeverity(DiagnosticsSeverity.WARNING) },
                    label = { Text(stringResource(R.string.diagnostics_filter_warnings)) },
                )
            }
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = {
                        Text(
                            stringResource(
                                R.string.diagnostics_tab_timeline,
                                counts.total,
                            ),
                        )
                    },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        Text(
                            stringResource(
                                R.string.diagnostics_tab_problems,
                                counts.errors + counts.warnings,
                            ),
                        )
                    },
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text(stringResource(R.string.diagnostics_tab_session)) },
                )
            }
            when (tab) {
                0 -> DiagnosticsEventList(
                    events = timelineVisible,
                    emptyText = stringResource(R.string.diagnostics_empty_timeline),
                )
                1 -> DiagnosticsEventList(
                    events = problemsVisible,
                    emptyText = stringResource(R.string.diagnostics_empty_problems),
                )
                else -> DiagnosticsSessionTab(
                    sessionInfo = uiState.sessionInfo,
                    counts = counts,
                    eventsEmpty = events.isEmpty(),
                    onExport = onExport,
                    onCopy = onCopy,
                    onClearEvents = onClearEvents,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsEventList(events: List<DiagnosticsEvent>, emptyText: String) {
    if (events.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(events, key = { it.id }) { event ->
            DiagnosticsEventRow(event)
        }
    }
}

@Composable
private fun DiagnosticsEventRow(event: DiagnosticsEvent) {
    val context = listOfNotNull(
        event.screen,
        event.title,
        event.quality,
        event.host,
        event.error,
    ).joinToString(" · ")
    Column {
        Text(
            text = "[${DiagnosticsExport.eventTime(event.timestampMs)}] " +
                "${event.severity.name} ${event.type.name}: ${event.summary}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (context.isNotEmpty()) {
            Text(
                text = context,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        event.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        event.durationMs?.let { duration ->
            Text(
                text = "${duration}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsSessionTab(
    sessionInfo: DiagnosticsSessionInfo,
    counts: DiagnosticsCounts,
    eventsEmpty: Boolean,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onClearEvents: () -> Unit,
) {
    var remoteExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DiagnosticsSessionRow("App", "${sessionInfo.appVersion} (${sessionInfo.versionCode})")
        DiagnosticsSessionRow("Application ID", sessionInfo.applicationId)
        DiagnosticsSessionRow("Device", sessionInfo.deviceModel)
        DiagnosticsSessionRow("Android", sessionInfo.androidVersion)
        DiagnosticsSessionRow("Network", sessionInfo.networkType)
        DiagnosticsSessionRow("Server", sessionInfo.serverHost)
        if (sessionInfo.sessionId.isNotBlank()) {
            DiagnosticsSessionRow("Session ID", sessionInfo.sessionId)
        }
        DiagnosticsSessionRow(
            "Session started",
            if (sessionInfo.sessionStartedMs > 0) {
                DiagnosticsExport.sessionTime(sessionInfo.sessionStartedMs)
            } else {
                "—"
            },
        )
        if (sessionInfo.clockOffsetMs != 0L) {
            DiagnosticsSessionRow("Server clock offset", "${sessionInfo.clockOffsetMs}ms")
        }
        DiagnosticsSessionRow(
            "Events",
            "${counts.total} total · ${counts.errors} errors · " +
                "${counts.warnings} warnings",
        )
        for (type in DiagnosticsType.entries) {
            DiagnosticsSessionRow(type.name.lowercase(), "${counts.byType[type] ?: 0}")
        }
        if (sessionInfo.counters.isNotEmpty()) {
            for ((name, total) in sessionInfo.counters.entries.sortedBy { it.key }) {
                DiagnosticsSessionRow("Counter $name", "$total")
            }
        }
        if (sessionInfo.remoteConfig.isNotEmpty()) {
            TextButton(onClick = { remoteExpanded = !remoteExpanded }) {
                Text(
                    if (remoteExpanded) "Remote config (hide)" else "Remote config (show)",
                )
            }
            if (remoteExpanded) {
                for ((key, value) in sessionInfo.remoteConfig.entries.sortedBy { it.key }) {
                    DiagnosticsSessionRow(key, "$value")
                }
            }
        }
        Button(
            onClick = onExport,
            enabled = !eventsEmpty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.diagnostics_export))
        }
        OutlinedButton(
            onClick = onCopy,
            enabled = !eventsEmpty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.diagnostics_copy_jsonl))
        }
        OutlinedButton(
            onClick = onClearEvents,
            enabled = !eventsEmpty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.diagnostics_clear))
        }
    }
}

@Composable
private fun DiagnosticsSessionRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
