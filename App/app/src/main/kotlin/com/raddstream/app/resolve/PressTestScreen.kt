package com.raddstream.app.resolve

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R

/**
 * The debug-only G-10 press test screen. It presses Play once per banked
 * link through the real path and taps Download once per banked link
 * through the real download entry point, and records the timings. It
 * starts nothing in the background and touches no production resolve
 * path. Unreachable in release builds: no NavGraph entry and no Settings
 * row exist unless DEBUG. Leaving the screen stops any in-flight run
 * and releases the player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PressTestScreen(
    onBackClick: () -> Unit,
    viewModel: PressTestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose { viewModel.stopRun() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearNotice()
        }
    }
    val eligible = repeatEligibleGroups(state.results, state.repeatedGroups)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.press_test_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.press_test_back))
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.press_test_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.press_test_control_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.loadError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::runAll,
                    enabled = !state.running && state.samples.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.press_test_run_all))
                }
                OutlinedButton(
                    onClick = viewModel::exportResults,
                    enabled = !state.running && state.results.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.press_test_export))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::copyResultsJson,
                    enabled = !state.running && state.results.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.press_test_copy_json))
                }
                TextButton(
                    onClick = viewModel::clearResults,
                    enabled = !state.running && state.results.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.press_test_clear))
                }
            }
            if (eligible.isNotEmpty() && !state.running) {
                Text(
                    stringResource(R.string.press_test_repeat_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (group in eligible) {
                        OutlinedButton(onClick = { viewModel.repeatGroup(group) }) {
                            Text(stringResource(R.string.press_test_repeat_group, group))
                        }
                    }
                }
            }
            if (state.running) {
                Text(
                    stringResource(R.string.press_test_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.samples, key = { it.linkId }) { sample ->
                    val result = state.results.lastOrNull { it.linkId == sample.linkId }
                    val runningHere = state.running && state.runningLinkId == sample.linkId
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            sample.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                R.string.press_test_item_meta,
                                sample.service,
                                sample.quality,
                                sample.linkId.toString(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val status = when {
                            runningHere -> stringResource(R.string.press_test_status_running)
                            result == null -> stringResource(R.string.press_test_status_pending)
                            result.playError == null && result.downloadError == null ->
                                stringResource(
                                    R.string.press_test_status_pressed,
                                    result.mintTo302Ms ?: -1,
                                    result.pressToFirstFrameMs ?: -1,
                                    result.tapToFirstByteMs ?: -1,
                                )
                            else -> stringResource(
                                R.string.press_test_status_failed,
                                result.playError ?: result.downloadError ?: "",
                            )
                        }
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
