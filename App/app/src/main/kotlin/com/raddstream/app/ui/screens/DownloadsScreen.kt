package com.raddstream.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.raddstream.app.R
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.data.cache.RetryPolicy
import com.raddstream.app.data.downloads.DeviceDownload
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.data.downloads.DownloadSpeed
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.DownloadsSkeleton
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.ImageFallbackCard
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.components.channelMonogram
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.pressScale
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
    // Phase 4 (Downloads #4): a VIP-gated keep offers an upgrade path.
    onOpenVipUpgrade: () -> Unit = {},
    // Phase 4 (Downloads #14): the empty state's action goes to browsable
    // content (Search), not back to wherever the viewer came from.
    onBrowseClick: () -> Unit = onBackClick,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // A keep tap has to ask the engine a question before it can navigate, and navigation is a
    // side effect — so it runs on the composition's own scope and dies with the screen.
    val clicks = rememberCoroutineScope()
    // D15: a completion ticks the haptic the moment a download finishes —
    // the notice itself is announced by the status row's live region (D20).
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        viewModel.completionEvents.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // Phase 4 (Downloads #7): a queued device row names its place in line —
    // 1-based among queued device rows, in list order.
    val queuedDevicePositions: Map<String, Int> = state.allDownloads
        .filterIsInstance<DownloadRow.Device>()
        .filter { it.download.state == DeviceDownloadState.QUEUED }
        .mapIndexed { index, row -> row.download.id to index + 1 }
        .toMap()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                // D18: the screen title is a display-bold headline, not a
                // default-weight title.
                title = { Text("Downloads", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_arrow_left),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_refresh),
                            // D13: refresh re-reads storage AND re-emits the
                            // lists now, so the label says what it does.
                            contentDescription = "Refresh downloads",
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        // D19: pull-to-refresh — the same refresh the top-bar button runs.
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(RaddTokens.Space.Gutter),
            verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Lg),
        ) {
            item { StorageCard(state.storage, onFreeUpSpace = viewModel::freeUpSpace) }
            item { LocalFilesEntry(onOpenLocalFiles) }
            state.statusMessage?.let { msg ->
                item {
                    // A notice the viewer can clear — and undo, when it names
                    // a sweep deletion. The old plain line persisted until it
                    // was overwritten or the screen died.
                    // D20: the notice row is the screen's polite live region
                    // — a completion or an error is announced to TalkBack
                    // the moment it lands.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
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
                            Icon(
                                painter = painterResource(R.drawable.ic_action_close),
                                contentDescription = null,
                            )
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
                    // D16 + D19: a row glides to its new place when the list
                    // changes, and a swipe runs the row's own actions —
                    // pause/resume one way, cancel/delete the other — never
                    // gesture-only, the buttons stay.
                    SwipeableDownloadRow(
                        swipeActions = downloadSwipeActions(row, viewModel),
                        modifier = Modifier.animateItem(),
                    ) {
                    when (row) {
                        is DownloadRow.Device -> DeviceDownloadRow(
                            item = row.download,
                            queuePosition = queuedDevicePositions[row.download.id],
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
                            onUpgrade = onOpenVipUpgrade,
                        )
                    }
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
                            "Files go to Movies/RaddStream and play in any video player.",
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_download),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        fillScreen = false,
                        modifier = Modifier.fillMaxWidth(),
                        action = {
                            // Phase 4 (Downloads #14): the way to fill this
                            // screen is one tap away, on browsable content —
                            // not back to wherever the viewer came from.
                            OutlinedButton(
                                onClick = onBrowseClick,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Find something to download") }
                        },
                    )
                    // Phase 4 (Downloads #14): a small preview of the
                    // populated state, so the empty screen shows what
                    // belongs here instead of only saying it.
                    DownloadsEmptyPreview()
                }
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
            .clip(RaddTokens.Shape.Card)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpenLocalFiles() }
            .padding(RaddTokens.Space.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_action_folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = RaddTokens.Space.Md),
        ) {
            Text(
                "Videos on this phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.local_library_entry_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_action_chevron_right),
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
            .clip(RaddTokens.Shape.Card)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(RaddTokens.Space.Lg),
        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_action_info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
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
            .clip(RaddTokens.Shape.Card)
            .background(MaterialTheme.colorScheme.surface)
            .padding(RaddTokens.Space.Lg),
        verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_action_download),
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
                    // Phase 4 (Downloads #9): the button explains how to
                    // free space — it deletes nothing itself, so the label
                    // says what it does instead of promising an action.
                    onClick = onFreeUpSpace,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "How to free space" },
                ) {
                    Text("HOW TO FREE SPACE")
                }
            }
        }
        if (storage == null) {
            Text("Storage information unavailable.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                style = MaterialTheme.typography.bodyMedium,
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
    queuePosition: Int? = null,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val cleanTitle = cleanDownloadTitle(item.title)
    // D8 + D16: the bar glides between ticks instead of jumping — the
    // displayed share animates to the live one on the S7 250ms spring.
    // Reduced motion: the bar snaps to the live value, no glide.
    val reduceMotion = rememberReduceMotion()
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = RaddMotion.default(),
        label = "downloadProgress",
    )
    val shownProgress = if (reduceMotion) item.progress else animatedProgress
    // D16: one press source per action — the scale tracks the real press
    // and the haptic ticks once on press-down.
    val playSource = remember { MutableInteractionSource() }
    val pauseSource = remember { MutableInteractionSource() }
    val resumeSource = remember { MutableInteractionSource() }
    val removeSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RaddTokens.Shape.Card)
            .background(MaterialTheme.colorScheme.surface)
            // A finished download is a file on this device: the whole row opens it
            // in the local player, with no server and no redirector involved. The
            // inner buttons still consume their own taps.
            .clickable(
                enabled = item.playing,
                onClickLabel = if (item.playing) "Play $cleanTitle offline" else null,
                onClick = onPlay,
            )
            .padding(RaddTokens.Space.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Md),
    ) {
        DownloadThumb(posterUrl = item.posterUrl, title = cleanTitle)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xs),
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
            when (item.state) {
                DeviceDownloadState.DOWNLOADING -> {
                    // Restyle 2026-11-13: rounded ends + a visible track — the bar
                    // reads as a progress bar, not a hairline. Jade now, so the
                    // bar reads as progress, not a grey hairline.
                    // An unknown size is an indeterminate bar, not a flat 0%: the
                    // bytes are moving, the total is what is unknown — and the
                    // completion gate still refuses to mark it READY.
                    // D17: the bar is 6dp, not the 4dp hairline — it reads at
                    // render scale.
                    if (item.bytesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { shownProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                    Text(
                        deviceProgressLine(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DeviceDownloadState.QUEUED -> {
                    // Phase 4 (Downloads #7): a queued download is waiting,
                    // not downloading at 0% — no determinate bar, and the
                    // line names its place in line when it is not next.
                    Text(
                        if (queuePosition != null && queuePosition > 1) {
                            "Queued · #$queuePosition in queue"
                        } else {
                            "Queued"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    bytesSavedLine(item.bytesDownloaded, item.bytesTotal, item.percent)?.let { saved ->
                        Text(
                            saved,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DeviceDownloadState.PAUSED, DeviceDownloadState.FAILED, DeviceDownloadState.PARTIAL -> {
                    // Phase 4 (Downloads #2): a stalled row keeps its
                    // progress — a dimmed bar at the saved share, so the
                    // viewer can see how far it got instead of losing the
                    // bar the moment it stops moving.
                    if (item.bytesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { shownProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                    Text(
                        deviceStatusLine(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // D12: a failed row names its attempt, so a retry is a
                    // counted try, not a mystery.
                    deviceAttemptLine(item.attempts)?.let { attempt ->
                        Text(
                            attempt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    bytesSavedLine(item.bytesDownloaded, item.bytesTotal, item.percent)?.let { saved ->
                        Text(
                            saved,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The stored reason, under the generic line: a dead source, a
                    // wrong content type and a full disk must not all read the same.
                    item.failureReason?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DeviceDownloadState.READY -> {
                    // D15: a finished row says so — a jade check that draws
                    // in with the S7 spring, plus "Downloaded ✓" in words.
                    // Distinct from paused/failed at a glance.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
                    ) {
                        DoneCheck()
                        Text(
                            deviceStatusLine(item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.playing) {
                IconButton(
                    onClick = onPlay,
                    interactionSource = playSource,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .pressScale(playSource)
                        .semantics { contentDescription = "Play $cleanTitle" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_play),
                        contentDescription = null,
                    )
                }
            }
            if (item.canPause) {
                IconButton(
                    onClick = onPause,
                    interactionSource = pauseSource,
                    modifier = Modifier
                        .pressScale(pauseSource)
                        .semantics { contentDescription = "Pause $cleanTitle" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_pause),
                        contentDescription = null,
                    )
                }
            }
            if (item.canResume) {
                IconButton(
                    onClick = onResume,
                    interactionSource = resumeSource,
                    modifier = Modifier
                        .pressScale(resumeSource)
                        .semantics { contentDescription = "Resume $cleanTitle" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_play),
                        contentDescription = null,
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                interactionSource = removeSource,
                modifier = Modifier
                    .pressScale(removeSource)
                    .semantics {
                        contentDescription = "${if (item.playing) "Delete" else "Cancel"} $cleanTitle"
                    },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_close),
                    contentDescription = null,
                )
            }
            PressHaptics(playSource)
            PressHaptics(pauseSource)
            PressHaptics(resumeSource)
            PressHaptics(removeSource)
        }
    }
}

/** One honest sentence per DownloadManager state. */
private fun deviceStatusLine(item: DeviceDownload): String {
    val percent = item.percent?.let { " $it%" } ?: ""
    return when (item.state) {
        // Phase 4 (Downloads #7): a queued row says it is waiting — never
        // "Queued 0%", which reads as downloading at 0%.
        DeviceDownloadState.QUEUED -> "Queued"
        DeviceDownloadState.DOWNLOADING -> "Downloading$percent"
        DeviceDownloadState.PAUSED -> "Paused — resume when you are ready"
        // D15: a finished row says so in words, with the jade check beside it.
        DeviceDownloadState.READY -> "Downloaded ✓ — plays here or in any video player"
        DeviceDownloadState.FAILED -> "Download failed — resume, or cancel and try again"
        DeviceDownloadState.PARTIAL -> "Partially saved$percent — retry to finish the missing parts"
    }
}

/**
 * Phase 4 (Downloads #2): the bytes a stalled row has already saved —
 * "300 MB of 1.3 GB saved · 23%" — so a paused or failed row keeps its
 * progress in words as well as in the dimmed bar. Null while nothing has
 * arrived yet.
 */
internal fun bytesSavedLine(bytesDownloaded: Long, bytesTotal: Long, percent: Int?): String? {
    if (bytesDownloaded <= 0L) return null
    val bytes = if (bytesTotal > 0) {
        "${formatMiB(bytesDownloaded)} of ${formatMiB(bytesTotal)} saved"
    } else {
        "${formatMiB(bytesDownloaded)} saved"
    }
    return if (percent != null) "$bytes · $percent%" else bytes
}

/**
 * The downloading line: real percent + real bytes + real speed + ETA, from
 * the same rolling rate the service reports — "calculating" until the second
 * tick, never a guessed number. Phase 4 (Downloads #1): mirrors
 * [keepProgressLine] exactly — the device row was the only one missing its
 * bytes. "Starting…" while nothing has arrived and the total is unknown;
 * bytes-alone when the total is unknown, never "X / unknown size".
 */
internal fun deviceProgressLine(item: DeviceDownload): String {
    if (item.bytesDownloaded <= 0L && item.bytesTotal <= 0L) return "Starting…"
    val percent = item.percent?.let { "$it% · " } ?: ""
    val bytes = if (item.bytesTotal > 0) {
        "${formatMiB(item.bytesDownloaded)} / ${formatMiB(item.bytesTotal)}"
    } else {
        formatMiB(item.bytesDownloaded)
    }
    // D11: a download at 0 B/s for longer than the stall window says so —
    // "calculating…" is only the first seconds, never minutes of no movement.
    val speed = if (item.stalled) {
        "Stalled — check connection"
    } else if (item.bytesPerSec > 0) {
        DownloadSpeed.formatSpeed(item.bytesPerSec) +
            (DownloadSpeed.formatEta(item.etaSeconds)?.let { " · $it" } ?: "")
    } else {
        "calculating…"
    }
    return "$percent$bytes · $speed"
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
    // Phase 4 (Downloads #4): a VIP-gated keep offers the upgrade, not Retry.
    onUpgrade: () -> Unit = {},
) {
    val cleanTitle = cleanDownloadTitle(item.title)
    // D8 + D16: same glide as the device row — the bar animates to the live
    // share on the S7 250ms spring; reduced motion snaps to the live value.
    val reduceMotion = rememberReduceMotion()
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = RaddMotion.default(),
        label = "keepProgress",
    )
    val shownProgress = if (reduceMotion) item.progress else animatedProgress
    // D16: one press source per action — the scale tracks the real press
    // and the haptic ticks once on press-down.
    val playSource = remember { MutableInteractionSource() }
    val pauseSource = remember { MutableInteractionSource() }
    val retrySource = remember { MutableInteractionSource() }
    val cancelSource = remember { MutableInteractionSource() }
    val upgradeSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RaddTokens.Shape.Card)
            .background(MaterialTheme.colorScheme.surface)
            .padding(RaddTokens.Space.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Md),
    ) {
        DownloadThumb(posterUrl = item.posterUrl, title = cleanTitle)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xs),
        ) {
            Text(cleanTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            DownloadMetaLine(
                printLabel = item.shownPrintLabel(),
                printLabelDescription = item.printLabelDescription(),
                sizeText = if (item.bytesTotal > 0) formatMiB(item.bytesTotal) else null,
            )
            when (item.state) {
                KeepState.DOWNLOADING, KeepState.NO_SPACE -> {
                    // Restyle 2026-11-13: rounded ends + a visible track, same as
                    // the active-download bar above — one progress language. Jade now.
                    // D17: the bar is 6dp, not the 4dp hairline.
                    LinearProgressIndicator(
                        progress = { shownProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                    Text(keepProgressLine(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The bar is the progress, the line is the reason: a stalled
                    // NO_SPACE row must say why, not just show a frozen bar and
                    // a button.
                    if (item.state == KeepState.NO_SPACE) {
                        Text(statusLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                KeepState.QUEUED -> {
                    // Phase 4 (Downloads #7): waiting, not downloading at
                    // 0% — no determinate bar, just the reason it waits.
                    Text(statusLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    bytesSavedLine(item.bytesDownloaded, item.bytesTotal, item.percent)?.let { saved ->
                        Text(
                            saved,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                KeepState.GATED -> {
                    // Phase 4 (Downloads #4): gated, not downloading — no
                    // bar, just the reason, same shape as the QUEUED branch.
                    // (Fix 2026-11-24: this branch was missing and the
                    // 'when' failed to compile — GATED is the 8th state.)
                    // Round-2 wart fix: the status sentence used to wrap to
                    // five narrow lines because the wide "View VIP options"
                    // pill squeezed its column. The pill now rides BELOW the
                    // text, full-width, so the sentence has the whole row.
                    Text(statusLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    bytesSavedLine(item.bytesDownloaded, item.bytesTotal, item.percent)?.let { saved ->
                        Text(
                            saved,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = onUpgrade,
                        interactionSource = upgradeSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .pressScale(upgradeSource)
                            .semantics { contentDescription = "View VIP options $cleanTitle" },
                    ) { Text("View VIP options") }
                    PressHaptics(upgradeSource)
                }
                KeepState.PAUSED, KeepState.FAILED -> {
                    // Phase 4 (Downloads #2): a stalled row keeps its
                    // progress — a dimmed bar at the saved share.
                    if (item.bytesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { shownProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                    Text(statusLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    // D12: a failed row names its attempt, so a retry is a
                    // counted try, not a mystery.
                    keepAttemptLine(item.attempt)?.let { attempt ->
                        Text(
                            attempt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    bytesSavedLine(item.bytesDownloaded, item.bytesTotal, item.percent)?.let { saved ->
                        Text(
                            saved,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The stored reason, under the generic line — same rule as
                    // the device row.
                    item.failureReason?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                KeepState.READY, KeepState.EXPIRING -> {
                    // D15: a finished keep says so — the jade check draws in
                    // with the S7 spring, plus "Downloaded ✓" in words.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
                    ) {
                        DoneCheck()
                        Text(statusLine(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    // The stored reason, under the generic line — same rule as
                    // the device row.
                    item.failureReason?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.playable) {
                IconButton(
                    onClick = onPlay,
                    interactionSource = playSource,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .pressScale(playSource)
                        .semantics { contentDescription = "Play $cleanTitle" },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_play),
                        contentDescription = null,
                    )
                }
            }
            when (item.state) {
                KeepState.FAILED, KeepState.NO_SPACE -> {
                    // Phase 4 (Downloads #3): a dead keep can be removed,
                    // not only retried forever.
                    OutlinedButton(
                        onClick = onRetry,
                        interactionSource = retrySource,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .pressScale(retrySource)
                            .semantics { contentDescription = "Retry $cleanTitle" },
                    ) { Text("Retry") }
                    IconButton(
                        onClick = onCancel,
                        interactionSource = cancelSource,
                        modifier = Modifier
                            .pressScale(cancelSource)
                            .semantics {
                                contentDescription = "Cancel $cleanTitle"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_close),
                            contentDescription = null,
                        )
                    }
                }
                KeepState.GATED -> {
                    // Phase 4 (Downloads #4): Retry cannot succeed — the
                    // gate will refuse again. The upgrade pill rides below
                    // the status text now (round-2 wart fix); the × here
                    // only dismisses the row.
                    IconButton(
                        onClick = onCancel,
                        interactionSource = cancelSource,
                        modifier = Modifier
                            .pressScale(cancelSource)
                            .semantics {
                                contentDescription = "${keepActionLabel(item.state) ?: "Dismiss"} $cleanTitle"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_close),
                            contentDescription = null,
                        )
                    }
                }
                KeepState.PAUSED -> {
                    OutlinedButton(
                        onClick = onRetry,
                        interactionSource = retrySource,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .pressScale(retrySource)
                            .semantics { contentDescription = "Resume $cleanTitle" },
                    ) { Text("Resume") }
                    IconButton(
                        onClick = onCancel,
                        interactionSource = cancelSource,
                        modifier = Modifier
                            .pressScale(cancelSource)
                            .semantics {
                                contentDescription = "${keepActionLabel(item.state) ?: "Cancel"} $cleanTitle"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_close),
                            contentDescription = null,
                        )
                    }
                }
                KeepState.DOWNLOADING -> {
                    IconButton(
                        onClick = onPause,
                        interactionSource = pauseSource,
                        modifier = Modifier
                            .pressScale(pauseSource)
                            .semantics { contentDescription = "Pause $cleanTitle" },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_player_pause),
                            contentDescription = null,
                        )
                    }
                    IconButton(
                        onClick = onCancel,
                        interactionSource = cancelSource,
                        modifier = Modifier
                            .pressScale(cancelSource)
                            .semantics {
                                contentDescription = "${keepActionLabel(item.state) ?: "Cancel"} $cleanTitle"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_close),
                            contentDescription = null,
                        )
                    }
                }
                KeepState.QUEUED -> {
                    // D19 note: a queued keep is not downloading yet — a
                    // Pause button on it is a contradiction. Only the ×.
                    IconButton(
                        onClick = onCancel,
                        interactionSource = cancelSource,
                        modifier = Modifier
                            .pressScale(cancelSource)
                            .semantics {
                                contentDescription = "${keepActionLabel(item.state) ?: "Cancel"} $cleanTitle"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_close),
                            contentDescription = null,
                        )
                    }
                }
                KeepState.READY, KeepState.EXPIRING ->
                    IconButton(
                        onClick = onCancel,
                        interactionSource = cancelSource,
                        modifier = Modifier
                            .pressScale(cancelSource)
                            .semantics {
                                contentDescription = "${keepActionLabel(item.state) ?: "Cancel"} $cleanTitle"
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_close),
                            contentDescription = null,
                        )
                    }
            }
            PressHaptics(playSource)
            PressHaptics(pauseSource)
            PressHaptics(retrySource)
            PressHaptics(cancelSource)
            PressHaptics(upgradeSource)
        }
    }
}

/**
 * The destructive/cancellation action label for a kept item:
 * "Cancel" while in-flight or waiting, "Delete" once completed on device,
 * "Dismiss" for a VIP-gated row (Phase 4, Downloads #4 — its × dismisses
 * the row; there is no Retry to label any more).
 */
internal fun keepActionLabel(state: KeepState): String? = when (state) {
    KeepState.READY, KeepState.EXPIRING -> "Delete"
    KeepState.DOWNLOADING, KeepState.QUEUED, KeepState.PAUSED -> "Cancel"
    KeepState.FAILED, KeepState.NO_SPACE -> "Retry"
    KeepState.GATED -> "Dismiss"
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
        // D15: a finished keep says so in words, with the jade check beside it.
        KeepState.READY -> "Downloaded ✓ — available offline"
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
    // D11: same stall rule as the device row — 0 B/s past the stall window
    // says so; "calculating…" is only the first seconds.
    val speed = if (item.stalled) {
        "Stalled — check connection"
    } else if (item.bytesPerSec > 0) {
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
 * Phase 4 (Downloads #14): a small preview of the populated state for the
 * empty screen — a faded sample row under a caption, so the viewer sees
 * what belongs here instead of only reading about it. Static and clearly
 * a sample: no actions, no real data.
 */
@Composable
internal fun DownloadsEmptyPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = RaddTokens.Space.Sm),
        verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
    ) {
        Text(
            "Your downloads will look like this:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RaddTokens.Shape.Card)
                .background(MaterialTheme.colorScheme.surface)
                .alpha(0.6f)
                .padding(RaddTokens.Space.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Md),
        ) {
            DownloadThumb(posterUrl = null, title = "Example title")
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xs),
            ) {
                Text(
                    "Example title",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                DownloadMetaLine(
                    printLabel = "1080p · High bitrate",
                    printLabelDescription = "Example title — 1080p · High bitrate",
                    sizeText = "1.2 GB",
                )
                LinearProgressIndicator(
                    progress = { 0.45f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
                // D11: the sample row shows a download that is MOVING — the
                // old "calculating…" was the stale copy this round replaces.
                Text(
                    "45% · 540 MB / 1.2 GB · 12.0 MB/s · about 1 min left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The row's poster slot (D10). A keep whose catalog post is already in the
 * app's cache paints its real poster; everything else paints the same
 * initials tile the poster cards use — never a fetched poster that does
 * not exist, never a fake one. The device rows carry no post id at all, so
 * they always take the tile: full art for them needs the download stores
 * to carry a poster URL at enqueue time, a data-model change owed after
 * this round.
 */
@Composable
private fun DownloadThumb(posterUrl: String?, title: String) {
    // PosterCard pattern: a null URL paints the fallback tile directly.
    // CatalogImage resolves Hilt singletons at composition (imageSizingHolder
    // via EntryPointAccessors.fromApplication), and Paparazzi's layoutlib
    // context has no Hilt application — calling it with a null URL crashes
    // the snapshot (IllegalStateException, DownloadsPaparazziLargeFontTest).
    // The tile is identical either way: CatalogImage's own null-URL path
    // renders this same ImageFallbackCard. The title already sits in the
    // text column beside the thumb, so the tile carries the initials only
    // (Audit #9, same as PosterCard) — never the title twice.
    val thumbModifier = Modifier
        .size(width = 56.dp, height = 84.dp)
        .clip(RaddTokens.Shape.Thumb)
    if (posterUrl == null) {
        ImageFallbackCard(
            label = channelMonogram(title),
            modifier = thumbModifier,
        )
    } else {
        CatalogImage(
            url = posterUrl,
            contentDescription = null,
            fallbackLabel = title,
            modifier = thumbModifier,
        )
    }
}

/**
 * D21: split a print label into its quality badge and the rest — "1080p ·
 * High bitrate" becomes badge "1080p" + "High bitrate"; "720p" is a badge
 * alone; "Rank #1" is not a quality at all, so it stays whole (the run-9
 * label rule still paints it once). Pure, so a unit test pins it.
 */
internal fun qualityBadgeParts(printLabel: String?): Pair<String?, String?> {
    if (printLabel.isNullOrBlank()) return null to null
    val firstToken = printLabel.split('·', ' ').firstOrNull()?.trim().orEmpty()
    val isQuality = firstToken.matches(Regex("""(?i)^\d{3,4}p$|^4k$|^8k$"""))
    if (!isQuality) return null to printLabel
    val rest = printLabel.removePrefix(firstToken).trim().trimStart('·').trim()
    return firstToken to rest.ifBlank { null }
}

/** D12: a failed keep names its attempt — "Attempt 2 of 6" (the engine caps retries at 6). Null when the count was never recorded. */
internal fun keepAttemptLine(attempt: Int): String? =
    if (attempt > 0) "Attempt $attempt of ${RetryPolicy.MAX_ATTEMPTS}" else null

/** D12: a failed device file names its attempt — "Attempt 2". No cap exists on this side, so none is printed. Null when the count was never recorded. */
internal fun deviceAttemptLine(attempts: Int): String? =
    if (attempts > 0) "Attempt $attempts" else null

/**
 * D15: the finished row's jade check — it draws in with the S7 spatial
 * spring the moment the row lands finished, so the completion feels
 * finished, not just stopped. Reduced motion: no pop, the check is simply
 * there.
 */
@Composable
private fun DoneCheck(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val scale = remember { Animatable(if (reduceMotion) 1f else 0.6f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) scale.animateTo(1f, animationSpec = RaddMotion.fastSpatial())
    }
    Icon(
        painter = painterResource(R.drawable.ic_action_check),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(24.dp)
            .graphicsLayer(scaleX = scale.value, scaleY = scale.value),
    )
}

/** D21: quality as a badge, never truncating text — a tonal chip, with the size beside it in the meta line. */
@Composable
private fun QualityBadge(quality: String) {
    Box(
        modifier = Modifier
            .clip(RaddTokens.Shape.Chip)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(
                horizontal = RaddTokens.Space.Sm,
                vertical = RaddTokens.Space.Micro,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = quality,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

/**
 * One meta line: the quality badge when the label carries one, then the
 * rest of the label + the file size — short, word-safe, never truncated
 * mid-word ("1080p…" died with the badge). The line keeps its own node
 * (and its spoken "title — label" line) so the run-9 label tests still
 * read it exactly.
 */
@Composable
private fun DownloadMetaLine(
    printLabel: String?,
    printLabelDescription: String,
    sizeText: String?,
) {
    val (badge, rest) = qualityBadgeParts(printLabel)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
        modifier = Modifier.semantics { contentDescription = printLabelDescription },
    ) {
        badge?.let { QualityBadge(it) }
        // The remainder flexes (weight) so the size is never the part the
        // ellipsis eats: joining them into one string truncated the size
        // to "·…" on rows with action buttons (Paparazzi, 07cae2c3). The
        // size is a separate node that always paints in full — it is the
        // only place the total shows on a gated or ready row.
        if (rest != null) {
            Text(
                rest,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        sizeText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * D19: what a swipe does on one row. Start-to-end (swipe right) is the
 * pause/resume toggle; end-to-start (swipe left) is the destructive
 * action — cancel, delete or dismiss, the same action the row's × runs.
 * Either side may be null (a queued row has nothing to pause), and a null
 * side disables that swipe direction. The swipe is a gesture shortcut,
 * never gesture-only: every action stays on a button too.
 */
private data class SwipeActions(
    val startToEnd: (() -> Unit)?,
    val startIconRes: Int?,
    val endToStart: (() -> Unit)?,
    val endIconRes: Int?,
)

private fun downloadSwipeActions(row: DownloadRow, viewModel: DownloadsViewModel): SwipeActions =
    when (row) {
        is DownloadRow.Device -> {
            val item = row.download
            SwipeActions(
                startToEnd = when (item.state) {
                    DeviceDownloadState.DOWNLOADING -> { { viewModel.pauseDownload(item) } }
                    DeviceDownloadState.PAUSED,
                    DeviceDownloadState.FAILED,
                    DeviceDownloadState.PARTIAL -> { { viewModel.resumeDownload(item) } }
                    else -> null
                },
                startIconRes = when (item.state) {
                    DeviceDownloadState.DOWNLOADING -> R.drawable.ic_player_pause
                    DeviceDownloadState.PAUSED,
                    DeviceDownloadState.FAILED,
                    DeviceDownloadState.PARTIAL -> R.drawable.ic_player_play
                    else -> null
                },
                endToStart = { viewModel.removeDownload(item) },
                endIconRes = R.drawable.ic_action_close,
            )
        }
        is DownloadRow.Keep -> {
            val item = row.item
            SwipeActions(
                startToEnd = when (item.state) {
                    KeepState.DOWNLOADING -> { { viewModel.pause(item) } }
                    KeepState.PAUSED -> { { viewModel.retry(item) } }
                    else -> null
                },
                startIconRes = when (item.state) {
                    KeepState.DOWNLOADING -> R.drawable.ic_player_pause
                    KeepState.PAUSED -> R.drawable.ic_player_play
                    else -> null
                },
                endToStart = { viewModel.cancel(item) },
                endIconRes = R.drawable.ic_action_close,
            )
        }
    }

/**
 * D19: the swipe box around one download row. The swipe runs the action
 * and the row snaps back — it never dismisses the row itself, so a swipe
 * can never silently delete a download. The background names the action
 * while the finger is down: jade pause/play one way, danger × the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableDownloadRow(
    swipeActions: SwipeActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> swipeActions.startToEnd?.invoke()
                SwipeToDismissBoxValue.EndToStart -> swipeActions.endToStart?.invoke()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The swipe is a shortcut, not a removal: always snap back.
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                val toEnd = direction == SwipeToDismissBoxValue.StartToEnd
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RaddTokens.Shape.Card)
                        .background(
                            if (toEnd) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                        )
                        .padding(horizontal = RaddTokens.Space.Lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                ) {
                    val iconRes = if (toEnd) swipeActions.startIconRes else swipeActions.endIconRes
                    iconRes?.let {
                        Icon(
                            painter = painterResource(it),
                            contentDescription = null,
                            tint = if (toEnd) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        },
        enableDismissFromStartToEnd = swipeActions.startToEnd != null,
        enableDismissFromEndToStart = swipeActions.endToStart != null,
    ) { content() }
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
