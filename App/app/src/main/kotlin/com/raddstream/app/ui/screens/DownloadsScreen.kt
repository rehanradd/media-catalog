package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.raddstream.app.R
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.data.downloads.DeviceDownload
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.data.downloads.DownloadSpeed
import com.raddstream.app.ui.components.DownloadsSkeleton
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.viewmodel.DownloadRow
import com.raddstream.app.viewmodel.DownloadsViewModel
import com.raddstream.app.viewmodel.KeepState
import com.raddstream.app.viewmodel.KeepRoute
import com.raddstream.app.viewmodel.KeptItem
import com.raddstream.app.viewmodel.StorageInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBackClick: () -> Unit,
    onPlay: (KeptItem) -> Unit = {},
    onPlayLocal: (uri: String, title: String) -> Unit = { _, _ -> },
    onOpenLocalFiles: () -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // A keep tap has to ask the engine a question before it can navigate, and navigation is a
    // side effect — so it runs on the composition's own scope and dies with the screen.
    val clicks = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh storage")
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { StorageCard(state.storage, onFreeUpSpace = viewModel::freeUpSpace) }
            item { LocalFilesEntry(onOpenLocalFiles) }
            state.statusMessage?.let { msg ->
                item {
                    // A notice the viewer can clear — and undo, when it names
                    // a sweep deletion. The old plain line persisted until it
                    // was overwritten or the screen died.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.canUndoSweep) {
                            TextButton(
                                onClick = viewModel::undoSweep,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Undo delete" },
                            ) {
                                Text("Undo")
                            }
                        }
                        IconButton(
                            onClick = viewModel::clearStatus,
                            modifier = Modifier.semantics { contentDescription = "Dismiss notice" },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                }
            }

            if (state.isLoading) {
                item { DownloadsSkeleton() }
            }

            if (state.hasAnything) {
                item {
                    NoticeCard(DOWNLOADS_QUIET_LINE)
                }
                items(
                    state.allDownloads,
                    key = { row ->
                        when (row) {
                            is DownloadRow.Device -> "device-${row.download.id}"
                            is DownloadRow.Keep -> "keep-${row.item.id}"
                        }
                    },
                ) { row ->
                    when (row) {
                        is DownloadRow.Device -> DeviceDownloadRow(
                            item = row.download,
                            onPlay = { row.download.localUri?.let { onPlayLocal(it, row.download.title) } },
                            onPause = { viewModel.pauseDownload(row.download) },
                            onResume = { viewModel.resumeDownload(row.download) },
                            onRemove = { viewModel.removeDownload(row.download) },
                        )
                        is DownloadRow.Keep -> KeptItemRow(
                            item = row.item,
                            // Local bytes first, the redirector handle only if the engine could not
                            // name a complete file: a keep that is really on the device needs no
                            // network, no resolver round-trip, and no live upstream to play.
                            onPlay = {
                                clicks.launch {
                                    when (val route = viewModel.playRoute(row.item)) {
                                        is KeepRoute.Local -> onPlayLocal(route.uri, row.item.title)
                                        is KeepRoute.Stream -> {
                                            // Batch C: warm the handle in parallel
                                            // with the navigation, so the player
                                            // 302s from the banked answer.
                                            viewModel.warmStreamHandle(route.handle)
                                            onPlay(row.item)
                                        }
                                        KeepRoute.NothingPlayable -> viewModel.reportUnplayable()
                                    }
                                }
                            },
                            onCancel = { viewModel.cancel(row.item) },
                            onRetry = { viewModel.retry(row.item) },
                            onPause = { viewModel.pause(row.item) },
                        )
                    }
                }
            }

            if (!state.hasAnything && !state.isLoading) {
                item {
                    // A4-F7: the empty state wraps its own content instead of
                    // sitting in a fixed 240dp box. Robo v7 (v7-11.png) caught
                    // the old shape half-cutting the "Find something to
                    // download" pill: EmptyState's fillScreen padding (32dp)
                    // left the 240dp box 176dp of room for ~206dp of content
                    // (40dp icon + title + 3-line message + 48dp button), so
                    // the button text clipped at the box edge. Wrapping the
                    // content (fillScreen = false, the F5 My List pattern)
                    // makes the item ~246dp tall and the whole stack —
                    // top bar + storage + local-files + empty state — fits
                    // above the fold on a 360×640dp viewport with room to
                    // spare, whatever the message wraps to.
                    EmptyState(
                        title = "Nothing downloaded yet",
                        message = "Use the Download button on a movie or episode. " +
                            "Files go to Movies/RaddStream and play anywhere; downloads play here.",
                        icon = {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        fillScreen = false,
                        modifier = Modifier.fillMaxWidth(),
                        action = {
                            // A downloads tab nobody has used yet is not a dead end: the way
                            // to fill it is one tap away, on the title they came from.
                            OutlinedButton(
                                onClick = onBackClick,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Find something to download") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalFilesEntry(onOpenLocalFiles: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpenLocalFiles() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        ) {
            Text(
                "Videos on this phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.local_library_entry_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun NoticeCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StorageCard(
    storage: StorageInfo?,
    onFreeUpSpace: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (storage == null) {
                    "Device storage"
                } else {
                    "${formatGib(storage.usedBytes)} of ${formatGib(storage.totalBytes)} used"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (storage != null) {
                TextButton(
                    onClick = onFreeUpSpace,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Free up space" },
                ) {
                    Text("FREE UP SPACE")
                }
            }
        }
        if (storage == null) {
            Text("Storage information unavailable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        LinearProgressIndicator(
            progress = { storage.usedFraction },
            modifier = Modifier.fillMaxWidth(),
            color = if (storage.lowSpace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        if (storage.lowSpace) {
            Text(
                "Low space — a download needs room for the whole file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// Robo run 9: the print label is painted only when it says something the
// title does not — and it speaks with the title, so two files sharing one
// quality label never speak identically. Internal for the run-9 label test.
//
// Stitch Downloads mockup (2026-11-15): the row is a thumb + title +
// "quality · size" + a jade progress bar with real percent/speed, and icon
// actions on the right (play when finished, pause while downloading, × to
// remove). The status pill is gone — the status line already says the state
// in words, so the pill said it twice.
@Composable
internal fun DeviceDownloadRow(
    item: DeviceDownload,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val cleanTitle = cleanDownloadTitle(item.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            // A finished download is a file on this device: the whole row opens it
            // in the local player, with no server and no redirector involved. The
            // inner buttons still consume their own taps.
            .clickable(
                enabled = item.playing,
                onClickLabel = if (item.playing) "Play $cleanTitle offline" else null,
                onClick = onPlay,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DownloadThumb()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = cleanTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            DownloadMetaLine(
                printLabel = item.shownPrintLabel(),
                printLabelDescription = item.printLabelDescription(),
                // Dev-cleanup (Nov 2026): an unknown total paints no size
                // segment at all — the old lowercase "unknown size" read as
                // a broken value, and the progress bar already says the
                // download is moving.
                sizeText = if (item.bytesTotal > 0) formatMiB(item.bytesTotal) else null,
            )
            if (item.state == DeviceDownloadState.DOWNLOADING ||
                item.state == DeviceDownloadState.QUEUED
            ) {
                // Restyle 2026-11-13: rounded ends + a visible track — the bar
                // reads as a progress bar, not a hairline. Jade now, so the
                // bar reads as progress, not a grey hairline.
                // An unknown size is an indeterminate bar, not a flat 0%: the
                // bytes are moving, the total is what is unknown — and the
                // completion gate still refuses to mark it READY.
                if (item.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                }
                Text(
                    deviceProgressLine(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    deviceStatusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // The stored reason, under the generic line: a dead source, a
                // wrong content type and a full disk must not all read the same.
                item.failureReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.playing) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .semantics { contentDescription = "Play $cleanTitle" },
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
            }
            if (item.canPause) {
                IconButton(
                    onClick = onPause,
                    modifier = Modifier.semantics { contentDescription = "Pause $cleanTitle" },
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                }
            }
            if (item.canResume) {
                IconButton(
                    onClick = onResume,
                    modifier = Modifier.semantics { contentDescription = "Resume $cleanTitle" },
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics {
                    contentDescription = "${if (item.playing) "Delete" else "Cancel"} $cleanTitle"
                },
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }
    }
}

/** One honest sentence per DownloadManager state. */
private fun deviceStatusLine(item: DeviceDownload): String {
    val percent = item.percent?.let { " $it%" } ?: ""
    return when (item.state) {
        DeviceDownloadState.QUEUED -> "Queued$percent"
        DeviceDownloadState.DOWNLOADING -> "Downloading$percent"
        DeviceDownloadState.PAUSED -> "Paused — resume when you are ready"
        DeviceDownloadState.READY -> "Saved on device — plays here or in any video player"
        DeviceDownloadState.FAILED -> "Download failed — resume, or cancel and try again"
        DeviceDownloadState.PARTIAL -> "Partially saved$percent — retry to finish the missing parts"
    }
}

/** The downloading line: real percent + real speed + ETA, from the same rolling rate the service reports — "calculating" until the second tick, never a guessed number (Audit #12). */
private fun deviceProgressLine(item: DeviceDownload): String {
    val parts = mutableListOf<String>()
    item.percent?.let { parts += "$it%" }
    val speed = if (item.bytesPerSec > 0) {
        DownloadSpeed.formatSpeed(item.bytesPerSec) +
            (DownloadSpeed.formatEta(item.etaSeconds)?.let { " · $it" } ?: "")
    } else {
        "calculating…"
    }
    parts += speed
    return parts.joinToString(" · ")
}

// Robo run 9: the print label is painted only when it says something the
// title does not — and it speaks with the title, so two keeps sharing one
// rank label ("Rank #1") never speak identically. Internal for the run-9
// label test.
//
// Stitch Downloads mockup (2026-11-15): same row as a device download —
// thumb + title + "quality · size" + jade progress, icon actions on the
// right (play when finished, × to remove). A failed keep keeps its text
// "Retry" button: the mockup has no failed row, and the label test pins
// the word. The status pill is gone, same as the device row.
@Composable
internal fun KeptItemRow(
    item: KeptItem,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit = {},
) {
    val cleanTitle = cleanDownloadTitle(item.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DownloadThumb()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(cleanTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            DownloadMetaLine(
                printLabel = item.shownPrintLabel(),
                printLabelDescription = item.printLabelDescription(),
                sizeText = if (item.bytesTotal > 0) formatMiB(item.bytesTotal) else null,
            )
            if (item.state == KeepState.DOWNLOADING || item.state == KeepState.QUEUED || item.state == KeepState.NO_SPACE) {
                // Restyle 2026-11-13: rounded ends + a visible track, same as
                // the active-download bar above — one progress language. Jade now.
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
                Text(keepProgressLine(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The bar is the progress, the line is the reason: a stalled
                // NO_SPACE row and a waiting QUEUED row must say why, not
                // just show a frozen bar and a button.
                if (item.state == KeepState.NO_SPACE || item.state == KeepState.QUEUED) {
                    Text(statusLine(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Text(statusLine(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                // The stored reason, under the generic line — same rule as
                // the device row.
                item.failureReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.playable) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .semantics { contentDescription = "Play $cleanTitle" },
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
            }
            when (item.state) {
                KeepState.FAILED, KeepState.NO_SPACE, KeepState.GATED -> OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Retry $cleanTitle" },
                ) { Text("Retry") }
                KeepState.PAUSED -> {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Resume $cleanTitle" },
                    ) { Text("Resume") }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.semantics {
                            contentDescription = "${keepActionLabel(item.state) ?: "Cancel"} $cleanTitle"
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
                KeepState.DOWNLOADING, KeepState.QUEUED -> {
                    IconButton(
                        onClick = onPause,
                        modifier = Modifier.semantics { contentDescription = "Pause $cleanTitle" },
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.semantics {
                            contentDescription = "${keepActionLabel(item.state) ?: "Cancel"} $cleanTitle"
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
                KeepState.READY, KeepState.EXPIRING ->
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.semantics {
                            contentDescription = "${keepActionLabel(item.state) ?: "Cancel"} $cleanTitle"
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
            }
        }
    }
}

/**
 * The destructive/cancellation action label for a kept item:
 * "Cancel" while in-flight or waiting, "Delete" once completed on device.
 */
internal fun keepActionLabel(state: KeepState): String? = when (state) {
    KeepState.READY, KeepState.EXPIRING -> "Delete"
    KeepState.DOWNLOADING, KeepState.QUEUED, KeepState.PAUSED -> "Cancel"
    KeepState.FAILED, KeepState.NO_SPACE, KeepState.GATED -> "Retry"
}

/** One honest sentence per state. No provenance, no invented progress. */
private fun statusLine(item: KeptItem): String {
    val percent = item.percent?.let { " $it%" } ?: ""
    return when (item.state) {
        KeepState.QUEUED -> "Queued for download$percent"
        KeepState.DOWNLOADING -> "Downloading$percent"
        KeepState.PAUSED -> "Paused"
        KeepState.NO_SPACE -> "Not enough space to finish — free room and retry"
        KeepState.GATED -> "Locked to VIP — this quality needs a VIP plan"
        KeepState.READY -> "Saved on device — available offline"
        KeepState.EXPIRING -> "Kept — expires in ${formatRemaining(item.remainingMillis(System.currentTimeMillis()))}"
        KeepState.FAILED -> "Download failed — retry to resume"
    }
}

/**
 * The keep downloading line: real percent + real bytes + real speed + ETA, from the same rolling
 * rate the device row reports — "calculating" until the second tick, never a guessed number.
 * Dev-cleanup (Nov 2026): while nothing has arrived and the total is unknown there are no
 * byte counts to show — the old "0 KB / unknown size · calculating…" read as a broken
 * counter. The line is just "Starting…" until the first bytes land.
 */
internal fun keepProgressLine(item: KeptItem): String {
    if (item.bytesDownloaded <= 0L && item.bytesTotal <= 0L) return "Starting…"
    val percent = item.percent?.let { "$it% · " } ?: ""
    // The total is unknown: the bytes-so-far stand alone, never "X / unknown
    // size" — the lowercase "unknown size" read as a broken value (B3).
    val bytes = if (item.bytesTotal > 0) {
        "${formatMiB(item.bytesDownloaded)} / ${formatMiB(item.bytesTotal)}"
    } else {
        formatMiB(item.bytesDownloaded)
    }
    val speed = if (item.bytesPerSec > 0) {
        DownloadSpeed.formatSpeed(item.bytesPerSec) +
            (DownloadSpeed.formatEta(item.etaSeconds)?.let { " · $it" } ?: "")
    } else {
        "calculating…"
    }
    return "$percent$bytes · $speed"
}

/** "2 h 14 m", "42 min", "less than a minute" — the countdown an EXPIRING row speaks. */
internal fun formatRemaining(remainingMillis: Long?): String {
    // Dev-cleanup (Nov 2026): an unknown countdown is "less than a minute",
    // never the bare word "soon" — "Kept — expires in soon" is a broken
    // sentence. Near-unreachable today (EXPIRING needs a non-null expiry),
    // but wrong if it ever fires.
    val millis = remainingMillis ?: return "less than a minute"
    if (millis <= 0L) return "less than a minute"
    val totalMinutes = millis / 60_000L
    if (totalMinutes < 1L) return "less than a minute"
    if (totalMinutes < 60L) return "$totalMinutes min"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (minutes == 0L) "$hours h" else "$hours h $minutes m"
}

/**
 * The quiet line under the download list, in the viewer's words — the
 * Stitch Downloads mockup's line, verbatim. Internal so the mockup test
 * pins it.
 */
internal const val DOWNLOADS_QUIET_LINE = "Downloads play here, even after the app closes."

/**
 * The title a download row paints: the stored title with a trailing
 * "(YYYY)" stripped — the same rule Detail paints with, as far as the
 * row's data reaches. A download row carries no year, type or languages
 * (the engine task stores the title only), so only the year strips here;
 * "Scary Movie" keeps its Movie and "The English Patient" keeps its
 * English, because the rule never strips a word it cannot prove is a
 * suffix. The full year/language/type strip needs the post plumbed to
 * the row — a data-model change, not this restyle.
 */
internal fun cleanDownloadTitle(title: String): String =
    UserWords.stripTitleSuffixes(title, year = null, contentType = null, languages = emptyList())

/**
 * The row's poster slot. Neither download system stores poster art on
 * the row (the engine task keeps the title only; a public file keeps its
 * name), so the slot is a neutral 2:3 tile with a film icon — never a
 * fetched poster, never a fake one. Real art needs the row to carry a
 * poster URL, a data-model change.
 */
@Composable
private fun DownloadThumb() {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 84.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * One meta line: the quality label when it says something the title does
 * not, then the file size. The label keeps its own node (and its spoken
 * "title — label" line) so the run-9 label tests still read it exactly.
 */
@Composable
private fun DownloadMetaLine(
    printLabel: String?,
    printLabelDescription: String,
    sizeText: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (printLabel != null) {
            Text(
                printLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = printLabelDescription },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        sizeText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** "2.4 GB", "32 GB" — a whole number of gigabytes reads without its ".0". */
private fun formatGib(bytes: Long): String {
    val gib = bytes / 1_000_000_000.0
    return if (gib == gib.toLong().toDouble()) "${gib.toLong()} GB" else "%.1f GB".format(gib)
}
private fun formatMiB(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}
