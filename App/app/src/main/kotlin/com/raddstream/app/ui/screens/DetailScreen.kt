package com.raddstream.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.WireText
import com.raddstream.app.network.models.parseCastNames
import com.raddstream.app.ui.components.AudioBadge
import com.raddstream.app.ui.components.CapabilityChip
import com.raddstream.app.ui.components.CamBadge
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.CenteredMockupDialog
import com.raddstream.app.ui.components.DetailSkeleton
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.HdrBadge
import com.raddstream.app.ui.components.ImageFallbackCard
import com.raddstream.app.ui.components.InlineLoadFailure
import com.raddstream.app.ui.components.PosterRailSkeleton
import com.raddstream.app.ui.components.detailPrefetchUrls
import com.raddstream.app.ui.components.isKnownLanguage
import com.raddstream.app.ui.components.MediaRail
import com.raddstream.app.ui.components.Pill
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.components.PrintQualityBadge
import com.raddstream.app.ui.components.PrintSourceBadge
import com.raddstream.app.ui.components.QualityRungCard
import com.raddstream.app.ui.components.RatingBadge
import com.raddstream.app.ui.components.SectionHeader
import com.raddstream.app.ui.components.SubtitleBadge
import com.raddstream.app.ui.components.audioBadgeText
import com.raddstream.app.ui.components.hdrBadgeText
import com.raddstream.app.ui.components.prefetchPosterArt
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.ui.components.sourceBadgeText
import com.raddstream.app.ui.components.subtitleBadgeText
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.player.preferredAudioLanguages
import com.raddstream.app.ui.navigation.DeepLinks
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.pressScale
import com.raddstream.app.ui.theme.rememberArtworkAccent
import com.raddstream.app.viewmodel.DetailNavigationEvent
import com.raddstream.app.viewmodel.DetailViewModel
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.EpisodeProgress
import com.raddstream.app.viewmodel.PendingKeep
import com.raddstream.app.viewmodel.PendingSeasonKeep
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.ResumePoint
import com.raddstream.app.viewmodel.SeasonGroup
import com.raddstream.app.viewmodel.formatRuntimeMinutes
import com.raddstream.app.viewmodel.keepConfirmLine
import com.raddstream.app.viewmodel.seasonKeepConfirmLine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBackClick: () -> Unit,
    onPlayStream: (streamId: String, title: String) -> Unit,
    /** A link the server calls a page: show the provider's own page rather than fail quietly. */
    onOpenProviderPage: (streamId: String, title: String) -> Unit = { _, _ -> },
    onOpenDownloads: () -> Unit = {},
    /** An actor chip in the cast row: open that actor's titles in Search. */
    onActorClick: (String) -> Unit = {},
    /** F18: tap on a tile in the Similar titles rail. */
    onPostClick: (Post) -> Unit = {},
    /** Detail review (Nov 2026, P1): the audio line opens Languages. */
    onOpenLanguages: () -> Unit = {},
    /** Session 121 (P-26): the hero's Watch Now — start the best print once the title loads. */
    autoplay: Boolean = false,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) action?.invoke()
        else Toast.makeText(
            context,
            "Storage access is needed to save videos in Movies/RaddStream.",
            Toast.LENGTH_LONG,
        ).show()
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        // A denied notification still lets the download run; only the progress card is silent.
        action?.invoke()
    }

    fun requestNotificationsThen(action: () -> Unit) {
        val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsGrant) {
            pendingPermissionAction = action
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun downloadWithStoragePermission(action: () -> Unit) {
        val needsLegacyGrant = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyGrant) {
            pendingPermissionAction = { requestNotificationsThen(action) }
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            requestNotificationsThen(action)
        }
    }

    LaunchedEffect(viewModel.navEvents) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is DetailNavigationEvent.PlayStream -> onPlayStream(event.streamId, event.title)
                is DetailNavigationEvent.OpenProviderPage ->
                    onOpenProviderPage(event.streamId, event.title)
                is DetailNavigationEvent.OpenExternal -> openInExternalPlayer(context, event.playUrl)
                is DetailNavigationEvent.DownloadStarted -> onOpenDownloads()
                is DetailNavigationEvent.DownloadNeedsPermission ->
                    downloadWithStoragePermission { viewModel.retryLastDownload() }
            }
        }
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    // Session 121 (P-26): the hero's Watch Now. Once per title, when the title has
    // loaded: the best print starts, and a title with nothing playable (or a locked
    // print) simply stays here with the reason in front of the viewer — `playBest`
    // never navigates, so there is nothing to unwind.
    // Home-tab-after-failed-playback (Nov 2026): the once-per-title latch MUST
    // survive this screen leaving the composition. A plain `remember` is
    // discarded when Navigation Compose takes Detail off screen for the
    // Player, so coming back from a failed playback reset the latch and the
    // effect below re-fired at once — the post is already loaded and the
    // resume lookup already landed in the surviving ViewModel — and the
    // failed player re-opened within a frame. Every exit from the player
    // was undone before the viewer could reach a tab, which is exactly
    // "tapping Home does nothing". `rememberSaveable` keeps the latch in
    // the back-stack entry's saved state, so a return never re-plays; a
    // different title is a different entry, so its own autoplay is untouched.
    // Session 123 (DE-1): autoplay respects Resume — a title the viewer left
    // halfway through restarts from where they left off, not from the
    // beginning. `playResume` is the same path the Resume card's own button
    // takes, so the behaviour is identical either way in. The effect waits
    // for `resumeLoaded`: the resume lookup reads the local history in its
    // own coroutine and lands after the title does, so deciding on `post`
    // alone would almost always decide before the answer existed and play
    // from the beginning anyway.
    // The latch sits here, above the effect that reads it: it must be
    // rememberSaveable, not plain remember (see the note above) — a plain
    // remember is discarded when Detail leaves the composition for the
    // Player, so returning from a failed playback re-fired this effect.
    var autoPlayedFor by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.post?.id, state.resumeLoaded, autoplay) {
        val post = state.post ?: return@LaunchedEffect
        if (autoplay && state.resumeLoaded && autoPlayedFor != post.id) {
            autoPlayedFor = post.id
            if (state.resume != null && state.resumePlayable) viewModel.playResume() else viewModel.playBest()
        }
    }

    // Feel-instant S2: warm the backdrop + similar-rail posters when the
    // title lands, so the header and the rail below paint art, not shimmer.
    // Capped; the helper dedupes, and never runs on metered data the
    // viewer never granted. Keyed on the title and the rail, so a similar
    // load that lands after the title warms its own rows.
    LaunchedEffect(state.post?.id, state.similarTitles) {
        val urls = detailPrefetchUrls(state.post, state.similarTitles)
        if (urls.isNotEmpty()) prefetchPosterArt(context, urls)
    }

    var showReportDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // Audit #25: the bar carries only the back arrow (and
                // actions). The title appears ONCE, next to the poster
                // below — the bar used to repeat it, so the screen read
                // its own name twice.
                // Detail review (Nov 2026, P1): the bar is transparent
                // and floats over the full-bleed hero — the content runs
                // edge-to-edge underneath it (detailContentPadding top
                // is 0), so the back and More targets are 40dp
                // translucent circles that read over any artwork, not an
                // opaque bar. The insets are explicit here (windowInsets
                // = 0): the status bar via statusBarsPadding, the camera
                // cutout horizontally — the bar's own defaults must not
                // double them.
                title = {},
                // Samsung RTL (v1.1.2, Issue 9): in landscape the camera
                // cutout sits on one side, and the Scaffold's insets do not
                // carry it — the back arrow must clear the hole, not sit
                // under it. The cutout inset is zero in portrait, so this
                // changes nothing there.
                modifier = Modifier
                    .statusBarsPadding()
                    .windowInsetsPadding(
                        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                    ),
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                navigationIcon = {
                    DetailTopBarTarget(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isLoading) {
                        // Detail redesign (Nov 2026): no competitor puts
                        // a report affordance in the top bar of a title
                        // page. The function stays — it lives in the More
                        // overflow now, one tap further from the title.
                        // Detail review (Nov 2026, P1): Report stays in
                        // the More menu AND in the utility row under the
                        // synopsis — the bar for discoverability, the row
                        // for thumb reach.
                        Box {
                            DetailTopBarTarget(onClick = { showMoreMenu = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Report broken stream") },
                                    onClick = {
                                        showMoreMenu = false
                                        showReportDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
    ) { paddingValues ->
        // Issue 9: the safe-drawing insets (system bars + display cutout)
        // are what keep the content off the camera hole and the system
        // navigation in landscape; the Scaffold's padding alone does not
        // carry the cutout. Every state of this screen goes through the
        // same padding, so the skeleton and the error state cannot sit
        // under the cutout either.
        val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
        val layoutDirection = LocalLayoutDirection.current
        val detailPadding = detailContentPadding(paddingValues, safeDrawingPadding, layoutDirection)
        val post = state.post
        when {
            state.isLoading -> DetailSkeleton(Modifier.padding(detailPadding))
            state.error != null && post == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(detailPadding),
            ) {
                // F9 (Batch B): the failure names the title when the list row
                // is still in this process — a poster + title above the error,
                // instead of a bare "Couldn't load this title" for a title the
                // viewer was just looking at.
                state.fallbackPost?.let { fallback ->
                    DetailErrorHeader(post = fallback)
                    Spacer(Modifier.height(16.dp))
                }
                // D6 (screenshot-fix 2026-11-16): the error block centres in
                // the space below the header instead of hugging the top and
                // leaving a black void under the actions. The block scrolls
                // inside its own box, so a small screen or large font never
                // clips it.
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ErrorState(
                            title = "Couldn't load this title",
                            message = state.error ?: "Unable to load title.",
                            onRetry = viewModel::loadDetail,
                            // F9 (Batch A): a failed title needs a way out that is not
                            // "retry forever" — back to browse. The message itself is
                            // already specific by error type (see friendlyNetworkError in
                            // the ViewModel: offline, timeout, 429, 404 and 5xx each get
                            // their own words); this branch only adds the exit.
                            secondaryLabel = "Back to browse",
                            onSecondary = onBackClick,
                            fillScreen = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { showReportDialog = true },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .heightIn(min = 48.dp)
                                .testTag("detail_report_broken_button"),
                        ) {
                            Text("Report broken stream")
                        }
                        // D6: one honest line about the usual cause — a big
                        // title on a slow connection — so the screen answers
                        // the "why" instead of only offering Retry.
                        Text(
                            text = "Titles are large files — a weak signal is the usual cause. Retry when your connection is steadier.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, start = 24.dp, end = 24.dp),
                        )
                    }
                }
            }
            post != null -> DetailContent(
                post = post,
                seasons = state.layout?.seasons ?: emptyList(),
                workPrints = state.layout?.workPrints ?: emptyList(),
                capabilities = state.layout?.capabilities ?: emptyList(),
                isSeries = state.layout?.isSeriesLike ?: false,
                resume = state.resume.takeIf { state.resumePlayable },
                onPlayResume = viewModel::playResume,
                /** Phase 7 (D1): "Start over" — forget where viewing stopped. */
                onStartOver = viewModel::removeProgress,
                episodeProgress = state.episodeProgress,
                includedPill = state.includedPill,
                deadEndReason = state.deadEndReason,
                deadEndKeepPrint = state.deadEndKeepPrint,
                downloadedHandles = state.downloadedHandles,
                downloadingHandles = state.downloadingHandles,
                selectedSeason = state.selectedSeason,
                onSelectSeason = viewModel::selectSeason,
                onPlayPrint = { print -> viewModel.playPrint(print, external = false) },
                onOpenExternal = { print -> viewModel.playPrint(print, external = true) },
                onKeep = { print -> downloadWithStoragePermission { viewModel.keepPrint(print) } },
                onKeepSeason = { season -> viewModel.requestKeepSeason(season) },
                rungs = RungActions(
                    ladder = state.ladder,
                    selectedBand = state.selectedBand,
                    onPlay = viewModel::playLadder,
                    onKeep = { option -> downloadWithStoragePermission { viewModel.keepRung(option) } },
                    tierLabel = viewModel::tierLabelFor,
                    isLocked = viewModel::isLocked,
                    // Audit #32: the card leads with the viewer's own audio.
                    preferredAudio = preferredAudioLanguages(state.region),
                ),
                ladderScope = rungScopeLabel(state.ladderSeason, state.ladderEpisode),
                onAskRungs = viewModel::showRungsFor,
                hasRungs = { season, episode -> !viewModel.ladderFor(season, episode).isEmpty },
                onActorClick = onActorClick,
                similarTitles = state.similarTitles,
                similarLoading = state.similarLoading,
                onPostClick = onPostClick,
                reloadError = state.error,
                onRetryReload = viewModel::loadDetail,
                isFavorite = state.isFavorite,
                onToggleFavorite = viewModel::toggleFavorite,
                // E2 slice 2: the bell asks for the notification permission
                // first (the one the download service already declares —
                // no new permission), then toggles the follow. A denial
                // still follows: the row is kept, only the announcement is
                // silent until the viewer grants it in Settings.
                isFollowed = state.isFollowed,
                onToggleFollow = { requestNotificationsThen { viewModel.toggleFollow() } },
                onShare = {
                    shareTitle(context, post)
                },
                onReport = { showReportDialog = true },
                onOpenLanguages = onOpenLanguages,
                contentPadding = detailPadding,
            )
        }
    }

    state.pendingKeep?.let { pending ->
        KeepConfirmDialog(
            pending = pending,
            wifiOnly = state.wifiOnlyDownloads,
            onConfirm = viewModel::confirmKeep,
            onDismiss = viewModel::dismissKeep,
            // UI-fix P1-D9: the coins Direct Link row is gone — it opened the
            // browser in the middle of a download (the one remaining external-
            // browser route on the download path). It returns only when a bought
            // credit can download in-app through the same DownloadService.
        )
    }

    state.pendingSeasonKeep?.let { pending ->
        SeasonKeepConfirmDialog(
            pending = pending,
            wifiOnly = state.wifiOnlyDownloads,
            onConfirm = viewModel::confirmKeepSeason,
            onDismiss = viewModel::dismissKeepSeason,
        )
    }

    if (showReportDialog) {
        ReportBrokenStreamDialog(
            isSubmitting = state.isReportingIssue,
            onSubmit = { reason ->
                viewModel.reportBrokenStream(reason)
                showReportDialog = false
            },
            onDismiss = { showReportDialog = false },
        )
    }
}

/**
 * The report sheet: four reasons, one tap each. Tapping a reason submits
 * that reason as the report — the same [onSubmit] contract the free-text
 * dialog had, so the view-model call is unchanged — and the caller closes
 * the sheet. No free-text field: the mockup is categories only, and the
 * reason itself is the report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportBrokenStreamDialog(
    isSubmitting: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ReportBrokenStreamSheetBody(
            isSubmitting = isSubmitting,
            onSubmit = onSubmit,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The sheet's content on its own, so the reasons are pinned by a test
 * without opening a sheet (the check-in sheet does the same).
 */
@Composable
internal fun ReportBrokenStreamSheetBody(
    isSubmitting: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val reasons = listOf("Video won't play", "Wrong title", "Bad quality", "Something else")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Report a problem",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        for (reason in reasons) {
            Surface(
                onClick = { if (!isSubmitting) onSubmit(reason) },
                enabled = !isSubmitting,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(
            onClick = onDismiss,
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Cancel")
        }
    }
}

/**
 * The size and the quality before the download starts. The size is the one
 * the metadata ask measured — never a guess — and when the server named no
 * size the dialog says so instead of inventing one. Nothing downloads until
 * Confirm; Cancel leaves nothing behind.
 */
@Composable
internal fun KeepConfirmDialog(
    pending: PendingKeep,
    wifiOnly: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenteredMockupDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Default.Download,
        title = "Download this title?",
        primaryLabel = "Download",
        onPrimary = onConfirm,
        secondaryLabel = "Cancel",
        onSecondary = onDismiss,
    ) {
        Text(
            keepConfirmLine(pending, wifiOnly),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The whole season before the download starts: how many episodes will
 * actually download, their total size, and how many of the season this
 * viewer may not keep (VIP-only copies). Nothing downloads until
 * Confirm; Cancel leaves nothing behind — the same contract as the
 * single-download dialog above.
 */
@Composable
internal fun SeasonKeepConfirmDialog(
    pending: PendingSeasonKeep,
    wifiOnly: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenteredMockupDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Default.Download,
        title = "Download season ${pending.seasonNumber}?",
        primaryLabel = "Download",
        onPrimary = onConfirm,
        secondaryLabel = "Cancel",
        onSecondary = onDismiss,
    ) {
        Text(
            seasonKeepConfirmLine(pending, wifiOnly),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (pending.lockedCount > 0) {
            Text(
                "${pending.lockedCount} episode${if (pending.lockedCount == 1) "" else "s"} need${if (pending.lockedCount == 1) "s" else ""} VIP and won't download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The title this screen paints for a detail row: the server's display
 * title first, then the clean title, then the raw title — each stripped
 * of the year/language/type suffix the meta line already states (see
 * [UserWords.detailTitle]). One place, so the hero, the error header
 * and the report dialog can never disagree about the title's name.
 */
internal fun detailDisplayTitle(post: PostDetail): String =
    UserWords.detailTitle(
        displayTitle = post.displayTitleWire,
        cleanTitle = post.cleanTitle,
        title = post.title,
        year = post.year,
        contentType = post.contentType,
        languages = WireText.listOf(post.language),
    )

/** The same, for a list row used as the error header's fallback. */
internal fun detailDisplayTitle(post: Post): String =
    UserWords.detailTitle(
        displayTitle = post.displayTitleWire,
        cleanTitle = post.cleanTitle,
        title = post.title,
        year = post.year,
        contentType = post.contentType,
        languages = WireText.listOf(post.language),
    )

/**
 * The quality switch and the download card as one bundle: the same rows render under the work
 * and under an episode, so a series never inherits another episode's links by accident.
 *
 * Internal (not private) so the Paparazzi snapshots can build a Detail page from fake
 * state — the same reason DetailContent is internal.
 */
internal data class RungActions(
    val ladder: LadderForScope,
    val selectedBand: String?,
    val onPlay: (QualityOption) -> Unit,
    val onKeep: (QualityOption) -> Unit,
    /** The tier each rung requires ("VIP" / "Standard"), from the ViewModel. */
    val tierLabel: (QualityOption) -> String,
    /**
     * Detail review (Nov 2026, P1): true when this viewer may not take
     * the rung — the gate reserves its band or label — so the rung's
     * Download disables instead of offering a download the tap would
     * refuse. From the ViewModel's [DetailViewModel.isLocked].
     */
    val isLocked: (QualityOption) -> Boolean = { false },
    /** The viewer's preferred audio languages (audit #32) — empty orders nothing. */
    val preferredAudio: Set<String> = emptySet(),
)

/** Which scope a ladder belongs to, in the viewer's words. Null means the whole work. */
private fun rungScopeLabel(season: Int?, episode: Int?): String? = when {
    season != null && episode != null -> "Season $season · Episode $episode"
    episode != null -> "Episode $episode"
    season != null -> "Season $season"
    else -> null
}

/**
 * The detail page's content padding, safe for landscape (Samsung RTL
 * v1.1.2, Issue 9). The page is a single portrait column, and in
 * landscape its full-width Play button ran off the right edge while the
 * system navigation overlapped it: the Scaffold's padding covers the
 * status and navigation bars, but not the display cutout — on a phone
 * whose camera hole sits mid-height on one side in landscape, the
 * content ran to the physical edge and under the hole.
 *
 * Every side takes the larger of the Scaffold's ask and the safe-drawing
 * inset (system bars + cutout), so the two are never double-counted: in
 * portrait the safe-drawing sides are zero and nothing changes; in
 * landscape the cutout side wins and the content clears it. The 40 dp of
 * breathing room stays on top of the bottom (Session 121, P-22: the
 * bottom VIP card sat half-hidden behind the system nav bar when this
 * was a fixed 40 dp while the Scaffold's own bottom padding was
 * ignored). Pure so the max-per-side rule is testable without a device.
 *
 * Detail review (Nov 2026, P1): the top is 0 — the page is edge-to-edge
 * now, and the transparent top bar floats over the full-bleed hero. The
 * bar carries its own status-bar inset (statusBarsPadding), so the
 * content needs no top offset at all.
 */
internal fun detailContentPadding(
    scaffoldPadding: PaddingValues,
    safeDrawingPadding: PaddingValues,
    layoutDirection: LayoutDirection,
    baseHorizontal: Dp = 16.dp,
    bottomBreathing: Dp = 40.dp,
): PaddingValues {
    // This Compose version (BOM 2024.06.00 / foundation-layout 1.7) has no
    // calculateStartPadding/calculateEndPadding on PaddingValues, so
    // start/end resolve through the left/right members: in LTR the start
    // side is the physical left, in RTL it is the physical right.
    val safeStart = if (layoutDirection == LayoutDirection.Ltr) {
        safeDrawingPadding.calculateLeftPadding(layoutDirection)
    } else {
        safeDrawingPadding.calculateRightPadding(layoutDirection)
    }
    val safeEnd = if (layoutDirection == LayoutDirection.Ltr) {
        safeDrawingPadding.calculateRightPadding(layoutDirection)
    } else {
        safeDrawingPadding.calculateLeftPadding(layoutDirection)
    }
    return PaddingValues(
        start = maxOf(baseHorizontal, safeStart),
        // Detail review (Nov 2026, P1): edge-to-edge — the transparent
        // top bar floats over the hero, so the content takes no top
        // offset. The sides and bottom keep the max-per-side rule above.
        top = 0.dp,
        end = maxOf(baseHorizontal, safeEnd),
        bottom = maxOf(
            scaffoldPadding.calculateBottomPadding(),
            safeDrawingPadding.calculateBottomPadding(),
        ) + bottomBreathing,
    )
}

/**
 * F9 (Batch B): the failed title, named. The poster + title + year from the
 * list row the viewer tapped, so the error screen is about *this* title
 * instead of a bare failure. No Play, no prints, no promises — the row is
 * the server's own but minutes old, and only the retry below can say what
 * the title actually holds now.
 */
/**
 * Metadata summary shown when Detail fails from a cached list row, built strictly
 * from already-known catalog fields without inventing absent values.
 */
internal fun detailErrorMetadataLine(post: Post): String? {
    val langs = WireText.listOf(post.language)
        .map { it.trim() }
        .filter(::isKnownLanguage)
        .take(2)
        .joinToString(", ")
        .takeIf { it.isNotBlank() }
    val parts = listOfNotNull(
        post.yearLabel,
        post.typeLabel,
        langs,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun DetailErrorHeader(post: Post, modifier: Modifier = Modifier) {
    val title = detailDisplayTitle(post)
    val metaLine = remember(post) { detailErrorMetadataLine(post) }
    val rating = remember(post.imdbRating) { UserWords.displayRating(post.imdbRating) }
    val genres = remember(post.genreList) { post.genreList.take(3) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CatalogImage(
            url = post.posterUrl,
            contentDescription = null, // the title beside it says which title this is
            modifier = Modifier
                .width(72.dp)
                .aspectRatio(CardGeometry.POSTER_ASPECT)
                .clip(RoundedCornerShape(8.dp)),
            fallbackLabel = title,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            metaLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (rating != null || genres.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rating?.let { RatingBadge(rating = "$it IMDb") }
                    genres.take(2).forEach { genre -> CapabilityChip(genre) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
internal fun DetailContent(
    post: PostDetail,
    seasons: List<SeasonGroup>,
    workPrints: List<RankedPrint>,
    capabilities: List<String>,
    isSeries: Boolean,
    /** The latest scope this device stopped in, if it has a print to play — else null. */
    resume: ResumePoint?,
    onPlayResume: () -> Unit,
    /** Phase 7 (D1): "Start over" — forget where viewing stopped, on the server and locally. */
    onStartOver: () -> Unit = {},
    /** Per-episode progress, keyed by (season, episode). */
    episodeProgress: Map<Pair<Int, Int>, EpisodeProgress>,
    /** What this viewer can watch of this title, as a pill — null when it has no prints. */
    includedPill: String?,
    /** Why this title cannot be played, in the viewer's words — null when it can. */
    deadEndReason: String? = null,
    /**
     * Detail review (Nov 2026, P1): the dead end's Download target — the
     * best keepable print for this viewer, from the ViewModel. Null when
     * none is keepable, so the dead-end Download disables; test fixtures
     * that want an enabled Download must pass the print they expect it
     * to target — a null here never falls back to the best-ranked print.
     */
    deadEndKeepPrint: RankedPrint? = null,
    /** Handles of downloads already on this device (READY rows only). */
    downloadedHandles: Set<String> = emptySet(),
    /**
     * Detail review (Nov 2026, P1): handles of downloads moving on this
     * device right now — an episode row says "Downloading" while its
     * keep runs, so the viewer does not tap Download again on a copy
     * already coming.
     */
    downloadingHandles: Set<String> = emptySet(),
    selectedSeason: Int?,
    onSelectSeason: (Int) -> Unit,
    onPlayPrint: (RankedPrint) -> Unit,
    onOpenExternal: (RankedPrint) -> Unit,
    onKeep: (RankedPrint) -> Unit,
    /** One best copy per episode, for the whole season — asks first, downloads on Confirm. */
    onKeepSeason: (SeasonGroup) -> Unit = {},
    /** The quality switch and the download card, as one bundle the rows can reuse. */
    rungs: RungActions,
    /** "Season 2 · Episode 5", or null when these rungs are the whole work's. */
    ladderScope: String?,
    onAskRungs: (season: Int?, episode: Int?) -> Unit,
    hasRungs: (season: Int?, episode: Int?) -> Boolean,
    /** An actor chip in the cast row: open that actor's titles in Search. */
    onActorClick: (String) -> Unit = {},
    similarTitles: List<Post> = emptyList(),
    /**
     * Detail review (Nov 2026, P1): the similar rail loads after the
     * page — while it does, a rail skeleton stands in its place (below),
     * so the page does not end in a bare gap that reads as "no similar
     * titles". False once the rail answers, titles or none.
     */
    similarLoading: Boolean = false,
    onPostClick: (Post) -> Unit = {},
    /**
     * A reload of a title that is already on screen. Null in the normal case. It arrives here
     * rather than through the full-screen error because the viewer can already read the
     * synopsis, the prints and the play button — what failed is the refresh underneath them,
     * and silently keeping a stale list looks exactly like a successful one.
     */
    reloadError: String?,
    onRetryReload: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    /** E2 slice 2: the "Notify me" bell — series only, handle required (see below). */
    isFollowed: Boolean = false,
    onToggleFollow: () -> Unit = {},
    onShare: () -> Unit,
    /** Open the broken-stream report — the utility row's Report action. */
    onReport: () -> Unit = {},
    /** Open the Languages screen — the audio line's tap target. */
    onOpenLanguages: () -> Unit = {},
    contentPadding: PaddingValues,
) {
    val allPrints = (workPrints + seasons.flatMap { it.episodes.flatMap { e -> e.prints } } + seasons.flatMap { it.packedPrints })
    val bestPrint = allPrints.maxByOrNull { it.rankKey }
    // Detail review (Nov 2026, P0): the hero's Play target — the best
    // print that can actually start here. The hero shows AND plays this
    // print; the keep/download target stays the best-ranked print
    // (`bestPrint` above), unchanged.
    val heroPrint = heroPlayablePrint(allPrints) ?: bestPrint
    // UI-fix P1-D2: the file list folds to 3 until the viewer asks for all.
    // It only renders when the quality ladder cannot fold the title at all
    // (no option groups from the server) — the ladder is the choice then.
    var showAllFiles by remember { mutableStateOf(false) }
    // Detail review (Nov 2026, P2): the series quality sheet — the rung
    // card in a bottom sheet instead of inline.
    var showQualitySheet by remember { mutableStateOf(false) }
    val qualitySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The dead end's way out: the similar rail is the last item on the
    // page, and the requester is what scrolls it into view from the
    // actions above. Owned here, so the button and the rail agree.
    val similarBringIntoView = remember { BringIntoViewRequester() }
    val contentScope = rememberCoroutineScope()

    // Detail review (Nov 2026, P1): the hero bleeds full-width, so the
    // list itself carries no side padding — every item below pads itself
    // 16dp, and the hero item does not. The top stays 0 (edge-to-edge
    // under the transparent bar) and the bottom keeps the page's
    // breathing room, both from the caller's [detailContentPadding].
    val pageSides = Modifier.padding(horizontal = 16.dp)
    val listPadding = PaddingValues(
        start = 0.dp,
        top = contentPadding.calculateTopPadding(),
        end = 0.dp,
        bottom = contentPadding.calculateBottomPadding(),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        reloadError?.let { failure ->
            // InlineLoadFailure insets itself 16dp horizontally, so it
            // needs no pageSides of its own.
            item(contentType = "reload-failure") {
                InlineLoadFailure(message = failure, onRetry = onRetryReload)
            }
        }

        item {
            DetailHeroSection(
                post = post,
                capabilities = capabilities,
                seasonCount = if (isSeries) seasons.size else 0,
                includedPill = includedPill,
                pageSides = pageSides,
            )
        }

        item {
            PrimaryActions(
                modifier = pageSides,
                // Detail review (Nov 2026, P0): the hero's Play must be the
                // best print that can actually start here — the ladder's Auto
                // rung — not the best-ranked print. The old shape hid Play
                // when only the best print could not play here, while the
                // ladder below still offered Play on another rung: the two
                // regions disagreed about the same title. When nothing plays
                // here the fallback is the best-ranked print, so the
                // download-only and provider-page branches below read exactly
                // as before. This is the same decision `playBest` makes
                // (`bestPlayablePrintOf` on the gated layout), so the hero
                // button and autoplay can never disagree either.
                bestPrint = heroPrint,
                hasAnyPrint = allPrints.isNotEmpty(),
                resume = resume,
                onPlayResume = onPlayResume,
                onPlay = { heroPrint?.let(onPlayPrint) },
                onKeep = { deadEndKeepPrint?.let(onKeep) },
                onStartOver = onStartOver,
                deadEndReason = deadEndReason,
                deadEndKeepPrint = deadEndKeepPrint,
            )
        }

        // Detail redesign (Nov 2026): the story comes first — Netflix and
        // Disney+ both put the synopsis directly under the actions, and
        // ours buried it under a quality ladder and two upsell cards.
        // The ladder moves below the synopsis; the cards are gone (the
        // rung's own VIP pill is the lock, and the hero's included pill
        // already states the tier).
        val synopsis = post.synopsis?.takeIf { it.isNotBlank() }
        if (synopsis != null) {
            item { Box(pageSides) { ExpandableSynopsis(text = UserWords.stripSectionMarkers(synopsis)) } }
        }

        // Detail review (Nov 2026, P1): Save / Share / Similar / Report
        // live here, directly under the synopsis — one compact borderless
        // row for thumb reach, not three outlined buttons under the hero.
        // The Similar action keeps the scroll-to-rail behaviour the dead
        // end's standalone link used to carry; that link is gone.
        item {
            DetailUtilityRow(
                modifier = pageSides,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                // E2 slice 2: the bell shows only on a series the catalog
                // named with a handle — a film has no next episode, and a
                // handle-less title has nothing the daily check could ask
                // the catalog about.
                showFollow = isSeries && !post.workRef.isNullOrBlank(),
                isFollowed = isFollowed,
                onToggleFollow = onToggleFollow,
                onShare = onShare,
                hasSimilar = similarTitles.isNotEmpty(),
                onBrowseSimilar = {
                    contentScope.launch { similarBringIntoView.bringIntoView() }
                },
                onReport = onReport,
            )
        }

        // One consolidated "Audio & Subtitles" line, built from the
        // prints' own data — the per-print badges stay on the cards for
        // the few who expand them, but the discovery surface is this line.
        // Detail review (Nov 2026, P1): the line is tappable — it opens
        // the Languages screen, where the viewer picks their audio and
        // subtitle languages. The chevron is the affordance; the whole
        // row is the target, 48dp tall.
        audioSubtitleLine(allPrints)?.let { line ->
            item {
                Row(
                    modifier = pageSides
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onOpenLanguages, role = Role.Button),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Detail review (Nov 2026, P1): the server caps the detail at
        // 1,000 releases — the list below is the first of many, not the
        // whole work. One honest line, before the viewer assumes the
        // episode/file list is complete. The per-season episode counts
        // cannot be corrected from this signal (releases are not
        // episodes, and the v1 payload carries no per-season total), so
        // the line is the disclosure; plumbing the v2 season
        // episode_count through is a follow-up.
        if (post.isTruncated) {
            item(contentType = "truncation-note") {
                Text(
                    text = stringResource(
                        R.string.detail_truncated_releases,
                        "%,d".format(post.releases.size),
                        "%,d".format(post.totalReleases),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = pageSides,
                )
            }
        }

        // Detail review (Nov 2026, P2): seasons and episodes come
        // before the quality choice — the viewer picks what to watch
        // first, then the picture it plays in. The work-level ladder
        // below folds into a chip for a series (the sheet), so the
        // episodes are the page, not a preface to a second long card.
        if (isSeries) {
            if (seasons.size > 5) {
                item { Box(pageSides) { SeasonDropdown(seasons = seasons, selectedSeason = selectedSeason, onSelect = onSelectSeason) } }
            } else if (seasons.size > 1) {
                item { Box(pageSides) { SeasonChips(seasons = seasons, selectedSeason = selectedSeason, onSelect = onSelectSeason) } }
            }
            val season = seasons.firstOrNull { it.number == selectedSeason } ?: seasons.firstOrNull()
            if (season != null) {
                item { Box(pageSides) { SeasonHeader(season = season, onKeepSeason = onKeepSeason) } }
                if (season.packedPrints.isNotEmpty()) {
                    item {
                        Box(pageSides) {
                            PackedSeasonBanner(
                                season = season,
                                onPlay = onPlayPrint,
                                onKeep = onKeep,
                            )
                        }
                    }
                }
                items(season.episodes, key = { "ep-${season.number}-${it.numberWithinSeason}" }) { episode ->
                    // A packed season is one file: its ladder is the season's, not an episode's.
                    val episodeKey = if (episode.onlyPacked) null else episode.numberWithinSeason
                    val scopeLabel = rungScopeLabel(season.number, episodeKey)
                    Box(pageSides) {
                        EpisodeRow(
                            episode = episode,
                            progress = episodeProgress[season.number to episode.numberWithinSeason],
                            isDownloaded = episode.prints.any { print ->
                                print.links.any { link ->
                                    link.streamId.isNotBlank() && link.streamId in downloadedHandles
                                }
                            },
                            isDownloading = episode.prints.any { print ->
                                print.links.any { link ->
                                    link.streamId.isNotBlank() && link.streamId in downloadingHandles
                                }
                            },
                            onPlayPrint = onPlayPrint,
                            onOpenExternal = onOpenExternal,
                            onKeep = onKeep,
                            scopeLabel = scopeLabel,
                            canChooseQuality = hasRungs(season.number, episodeKey),
                            showQuality = ladderScope == scopeLabel && !rungs.ladder.isEmpty,
                            onChooseQuality = {
                                onAskRungs(season.number, episodeKey)
                            },
                            rungs = rungs,
                        )
                    }
                }
            }
        }

        // One row per quality band, exactly as the server collapsed the copies of this
        // title: the viewer picks a picture, never a server, and never a list of links.
        // An episode's rungs belong under that episode, not up here.
        if (!rungs.ladder.isEmpty && ladderScope == null) {
            if (isSeries) {
                item {
                    Row(modifier = pageSides.fillMaxWidth()) {
                        FilterChip(
                            selected = false,
                            onClick = { showQualitySheet = true },
                            label = { Text(qualityChipLabel(rungs.ladder)) },
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            },
                            colors = raddFilterChipColors(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            } else {
                item {
                    QualityRungCard(
                        modifier = pageSides,
                        ladder = rungs.ladder,
                        selectedBand = rungs.selectedBand,
                        onPlayRung = rungs.onPlay,
                        onKeepRung = rungs.onKeep,
                        tierLabel = rungs.tierLabel,
                        isLocked = rungs.isLocked,
                        preferredAudio = rungs.preferredAudio,
                    )
                }
            }
        }

        // A cam warning is a quality fact, not a count: the version
        // summary that used to carry it is gone, but a title whose only
        // copies are cam recordings must still say so in plain words.
        if (allPrints.any { it.isCam }) {
            item {
                Text(
                    text = "Some copies here are cam recordings — low quality.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = pageSides,
                )
            }
        }

        // Cast, from the catalog's own scraped list — only what the server
        // actually stored, parsed into names (see parseCastNames). A title
        // with no cast data shows no section at all: no fake names, no
        // empty "Cast" header. A tap searches that actor's titles.
        val cast = parseCastNames(post.castNames)
        if (cast.isNotEmpty()) {
            item { Box(pageSides) { CastSection(cast = cast, onActorClick = onActorClick) } }
        }

        if (!isSeries) {
            // The ladder already folds one best copy per band, so the
            // version list is gone — except when the server sent no
            // ladder at all for this title, in which case the prints
            // below are the only choice the viewer has.
            if (workPrints.isNotEmpty() && rungs.ladder.isEmpty) {
                // UI-fix P1-D2: a title can carry a long list of near-identical
                // file cards (Salaar has 10 posts). Show at most 3, with
                // "Show all files" for the rest — this list only renders
                // when the server sent no ladder to fold the choice.
                val shownPrints = if (showAllFiles) workPrints else workPrints.take(3)
                val workCopies = copyNumbers(workPrints)
                items(shownPrints, key = { "print-${it.releaseId}" }) { print ->
                    Box(pageSides) {
                        PrintCard(
                            print = print,
                            onPlayPrint = onPlayPrint,
                            onOpenExternal = onOpenExternal,
                            onKeep = onKeep,
                            copyIndex = workCopies[print.releaseId]?.first ?: 1,
                            copyTotal = workCopies[print.releaseId]?.second ?: 1,
                        )
                    }
                }
                // Detail review (Nov 2026, P1): the count is the work's
                // true total, not the capped list's length — the
                // truncation note above already says only the first
                // 1,000 are listed.
                val fileCount = if (post.isTruncated) post.totalReleases else workPrints.size
                if (workPrints.size > 3) {
                    item {
                        Box(pageSides) {
                            TextButton(
                                onClick = { showAllFiles = !showAllFiles },
                                // Detail review (Nov 2026, P1): the 48dp
                                // touch-target floor — the same floor the
                                // synopsis, cast and episode expanders
                                // carry. The zero side padding stays: it
                                // keeps the label aligned with the body
                                // text above it.
                                modifier = Modifier.heightIn(min = 48.dp),
                                // Detail review (Nov 2026, P1): a TextButton's
                                // default 12dp side padding indents the label
                                // ~28dp from the body text above it — the
                                // expander reads as a nested item, not a row
                                // of this list. Zero it here and on the
                                // synopsis, cast and episode expanders below.
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    if (showAllFiles) "Show fewer files"
                                    else "Show all ${"%,d".format(fileCount)} files",
                                )
                            }
                        }
                    }
                }
            }
        }

        // Detail review (Nov 2026, P1): the similar rail loads after the
        // page — while it does, a rail skeleton stands in its place, so
        // the page does not end in a bare gap that reads as "no similar
        // titles". The header is the real one (a section name, not data);
        // the rail is PosterRailSkeleton, the same shape the real rail
        // fills in with — it carries its own 16dp sides, so it needs no
        // pageSides of its own. Only while the rail is empty: once
        // titles land the skeleton is gone.
        if (similarLoading && similarTitles.isEmpty()) {
            item(contentType = "similar-loading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "More Like This", modifier = pageSides)
                    PosterRailSkeleton()
                }
            }
        }

        if (similarTitles.isNotEmpty()) {
            item(contentType = "similar-titles") {
                Column(
                    modifier = Modifier
                        .bringIntoViewRequester(similarBringIntoView)
                        .then(pageSides),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionHeader(title = "More Like This")
                    MediaRail(
                        items = similarTitles,
                        key = { it.id },
                        contentPadding = PaddingValues(0.dp),
                    ) { similar ->
                        PosterCard(
                            post = similar,
                            onClick = { onPostClick(similar) },
                        )
                    }
                }
            }
        }
    }

    // Detail review (Nov 2026, P2): the series quality sheet — the same
    // rung card as the movie page, in a bottom sheet instead of inline,
    // so the seasons and episodes stay the page. The sheet's own
    // rememberModalBottomSheetState lives above (skipPartiallyExpanded),
    // the same shape as the report dialog.
    if (showQualitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            sheetState = qualitySheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                QualityRungCard(
                    ladder = rungs.ladder,
                    selectedBand = rungs.selectedBand,
                    onPlayRung = rungs.onPlay,
                    onKeepRung = rungs.onKeep,
                    tierLabel = rungs.tierLabel,
                    isLocked = rungs.isLocked,
                    preferredAudio = rungs.preferredAudio,
                )
            }
        }
    }
}

@Composable
private fun DetailHeroSection(
    post: PostDetail,
    capabilities: List<String>,
    seasonCount: Int,
    includedPill: String?,
    /** The page's side padding — the fallback pads itself with it; the bleed does not. */
    pageSides: Modifier,
) {
    val backdropUrl = remember(post.backdropList) {
        post.backdropList.firstOrNull { it.isNotBlank() }
    }
    if (backdropUrl == null) {
        // Detail review (Nov 2026, P1): the no-backdrop fallback is
        // kept — the poster + title block, padded like any other item.
        // Screenshot-fix D1 (2026-11-16): this branch starts at y=0
        // (detailContentPadding top is 0 for the full-bleed hero), so
        // the poster + title slid under the status bar and the title
        // collided with the clock and signal icons (Resident Evil,
        // The Paradise). The fallback has no artwork to bleed, so it
        // starts below the status bar AND the floating top bar (64dp).
        Box(pageSides.statusBarsPadding().padding(top = 64.dp)) {
            PosterHeader(
                post = post,
                capabilities = capabilities,
                seasonCount = seasonCount,
                includedPill = includedPill,
            )
        }
        return
    }
    // Detail review (Nov 2026, P1): the hero bleeds full-width — a 16:9
    // backdrop with no radius, a stronger bottom scrim, and the title
    // block over the scrim. The floating mini-poster is gone: the
    // backdrop IS the artwork now.
    // Screenshot-fix D1 (2026-11-16): the title block is five rows tall
    // (title + meta + pill + rating + genres) and bottom-aligned in a
    // 16:9 hero, so on a narrow phone its top reached into the floating
    // top bar and the title ran under the back arrow (Kraven the
    // Hunter). The hero keeps a minimum height of 296dp — status bar +
    // top bar (88dp) + a two-line title block (188dp) + bottom padding
    // (16dp) + a margin — so the block's top can never enter the bar's
    // zone, whatever the backdrop's aspect gives. The backdrop still
    // bleeds edge-to-edge at the top; only the box below it grows.
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 296.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardGeometry.WIDE_ART_ASPECT),
        ) {
            CatalogImage(
                url = backdropUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                fallbackLabel = detailDisplayTitle(post),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                            0.75f to MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    ),
            )
        }
        DetailTitleBlock(
            post = post,
            capabilities = capabilities,
            seasonCount = seasonCount,
            includedPill = includedPill,
            // D1: the hero title caps at two lines — a three-line title
            // is what pushed the block into the top bar. The full title
            // stays in the block's semantics and on the no-backdrop
            // header, which keeps three lines.
            titleMaxLines = 2,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .then(pageSides)
                .padding(bottom = 16.dp),
        )
    }
}

/**
 * Detail review (Nov 2026, P1): the hero's meta line, built once and
 * shared by the full-bleed overlay and the no-backdrop poster header —
 * the two must never drift apart. Pure so the meta rules are testable
 * without a device.
 */
internal fun detailMetaParts(post: PostDetail, seasonCount: Int): List<String> =
    listOfNotNull(
        // M3 (home review, Nov 2026): the year comes from [Post.yearLabel],
        // the guarded choke point — the old inline `takeIf { it > 0 }`
        // let an impossible year straight onto the header.
        post.yearLabel,
        // Detail review (Nov 2026, P1): "Movie" adds nothing — the page
        // IS a movie. Any other type word stays: a series is named by
        // its type here and counted by its seasons below.
        post.typeLabel?.takeIf { it != "Movie" },
        // P-5 (Session 121): normalize again at the render site — a language that
        // arrives as a raw JSON array string ("[\"Tamil\", \"English\"]") reads
        // "Tamil, English". Idempotent with the mapper-side normalization.
        // F2 (Batch A): "Unknown"/"N/A" are dropped — the server sends those
        // when nothing was recorded, and a meta line must never read "Unknown".
        WireText.listOf(post.language).map { it.trim() }.filter(::isKnownLanguage)
            .joinToString(", ").takeIf { it.isNotBlank() },
        formatRuntimeMinutes(post.runtimeMinutes),
        if (seasonCount > 0) "$seasonCount ${if (seasonCount == 1) "season" else "seasons"}" else null,
    )

/**
 * Detail review (Nov 2026, P1): the title + meta + included pill +
 * rating + genre chips, shared by the full-bleed hero overlay and the
 * no-backdrop poster header — one block, two placements, so a fix to
 * the meta or the chips lands in both.
 */
@Composable
private fun DetailTitleBlock(
    post: PostDetail,
    capabilities: List<String>,
    seasonCount: Int,
    includedPill: String?,
    modifier: Modifier = Modifier,
    /** Screenshot-fix D1 (2026-11-16): the hero caps at two lines — see [DetailHeroSection]. */
    titleMaxLines: Int = 3,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Robo run 3: this title text is identical to the bar title above
        // it, so the big title carries its own spoken name.
        // Detail redesign (Nov 2026): the painted title is the cleaned
        // one — the server's display title when it sent one, else the
        // clean title with its year/language/type suffix stripped, so
        // "No Hard Feelings (2023) English Movie" paints as
        // "No Hard Feelings" above a meta line that already states them.
        val titleText = detailDisplayTitle(post)
        Text(
            text = UserWords.breakableTitle(titleText),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = "Title: $titleText" },
        )
        val meta = detailMetaParts(post, seasonCount)
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Screenshot-fix D1 (2026-11-16): the VIP pill and the IMDb badge
        // share one row now, not two stacked rows — the stacked pair was
        // 36dp of the title block's height, and that height is what pushed
        // the block's top into the floating top bar on a narrow phone.
        // Either may be absent; the row only renders what exists.
        val rating = UserWords.displayRating(post.imdbRating)?.let { "$it IMDb" }
        if (includedPill != null || rating != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                includedPill?.let { pill ->
                    // Detail review (Nov 2026, P1): the included line is a pill
                    // now, not a sentence. VIP draws the eye in the rung pill's
                    // own colours (primaryContainer); a free viewer reads the
                    // cap. The ViewModel is the only producer — it emits "VIP"
                    // or "Free · up to X" — so the VIP check is an exact match
                    // on its own word.
                    Pill(
                        text = pill,
                        backgroundColor = if (pill == "VIP") {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (pill == "VIP") {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                rating?.let { RatingBadge(rating = it) }
            }
        }
        GenreChipsRow(chips = post.genreList + capabilities)
    }
}

/** The scrollable genre + capability chips, shared by both hero shapes. */
@Composable
private fun GenreChipsRow(
    chips: List<String>,
    modifier: Modifier = Modifier,
) {
    // UI-fix P1-D5: genres + capabilities were two identical scrolling
    // pill rows under the title. One row now; capabilities ride with
    // the genres.
    // Detail review (Nov 2026, P1): the last chip hard-clipped at the
    // right edge reads as broken — the shared ScrollingChipRow fade, the
    // same treatment Search's filter rail gets, so the row reads as
    // scrollable. 22dp, not the shared 48dp: a CapabilityChip is a Pill
    // (labelSmall 11sp/16sp line height + 3dp vertical padding × 2), so
    // the fade is exactly as tall as the row it covers. The local 32dp
    // fade it replaces painted nothing: fillMaxHeight() in a
    // wrap-content Box inside a scrolling column collapses to 0px
    // (Home §3.4), and the detailMovie golden shows the last chip as
    // "E…" hard-clipped with no fade at all.
    if (chips.isEmpty()) return
    // A4-F3: rides ScrollingChipRow now — the fade and the row are the
    // shared component, not a hand-rolled copy. 6dp gaps, as before.
    ScrollingChipRow(
        modifier = modifier,
        contentPadding = PaddingValues(end = 16.dp),
        horizontalSpacing = 6.dp,
        fadeHeight = 22.dp,
        fadeTestTag = GenreChipsFadeTag,
    ) {
        items(chips) { CapabilityChip(it) }
    }
}

/** Test tag for the genre-chips right-edge fade (see [GenreChipsRow]). */
internal const val GenreChipsFadeTag = "genre-chips-fade"

@Composable
private fun PosterHeader(
    post: PostDetail,
    capabilities: List<String>,
    /** Seasons, for a series — 0 for a movie, which has none to count. */
    seasonCount: Int = 0,
    /** What this viewer can watch of this title, as a pill — null when it has no prints. */
    includedPill: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // The poster's own colour, sampled once it loads: a 1dp border in the
        // artwork accent frames the poster in its own palette. No bitmap yet
        // (still loading, gated, or failed) means no accent sample — but the
        // frame is still there, in the neutral outlineVariant, so the poster
        // always reads as a defined card against the dark background instead
        // of a flat rectangle that blends into it (Robo run 6: the unloaded
        // poster's image contrast, 1.33). A neutral frame is not a fake
        // accent sample: it claims no colour from the artwork.
        var posterBitmap by remember(post.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
        val posterAccent = rememberArtworkAccent(posterBitmap)
        Box(
            modifier = Modifier
                .width(CardGeometry.DETAIL_POSTER_WIDTH_DP.dp)
                .aspectRatio(CardGeometry.POSTER_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = if (posterBitmap != null) posterAccent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!post.posterUrl.isNullOrBlank()) {
                CatalogImage(
                    url = post.posterUrl,
                    contentDescription = "${detailDisplayTitle(post)} poster",
                    modifier = Modifier.fillMaxSize(),
                    // F6 (Batch A): the full title, not initials.
                    fallbackLabel = detailDisplayTitle(post),
                    onBitmap = { posterBitmap = it },
                )
            } else {
                ImageFallbackCard(
                    modifier = Modifier.fillMaxSize(),
                    label = detailDisplayTitle(post),
                )
            }
        }

        // Detail review (Nov 2026, P1): the title block is shared with
        // the full-bleed hero overlay — one block, two placements.
        DetailTitleBlock(
            post = post,
            capabilities = capabilities,
            seasonCount = seasonCount,
            includedPill = includedPill,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Detail review (Nov 2026, P1): one 40dp translucent circular target
 * for the transparent top bar — the same scrim-circle pattern the
 * season header's download button uses, so the back and More icons
 * read over any hero artwork.
 */
@Composable
private fun DetailTopBarTarget(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Detail review (Nov 2026, P1): the 48dp touch-target floor — the
    // back and More targets were 40dp circles. The floating bar over
    // the hero has room for the full floor, so there is no exception
    // to record here.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            content()
        }
    }
}

/**
 * Detail review (Nov 2026, P0): the hero's Play target — the best print
 * that can actually start here, or null when none can. The best-ranked
 * print is not the answer: on a gated title it can be a 4K print this
 * viewer may not play, while a 720p print below it plays fine. Pure so
 * DetailHeroPlayTest pins the matrix (playable best / playable non-best /
 * download-only / no prints).
 */
internal fun heroPlayablePrint(prints: List<RankedPrint>): RankedPrint? =
    prints.filter { it.playableHere }.maxByOrNull { it.rankKey }

/**
 * Detail review (Nov 2026, P2): the series quality chip's label — the
 * Auto band the ladder would play, or the download-only state when no
 * rung plays here. The Auto band itself may not play (a locked top
 * rung); then the first rung that does play names the quality. Pure so
 * DetailQualityChipTest pins it.
 */
internal fun qualityChipLabel(ladder: LadderForScope): String {
    val playableBand = ladder.effectiveAutoBand?.takeIf { band ->
        ladder.options.any { it.band == band && it.playsHere }
    } ?: ladder.options.firstOrNull { it.playsHere }?.band
    return if (playableBand != null) {
        "Quality: Auto ($playableBand)"
    } else {
        "Quality: Download only"
    }
}

@Composable
private fun PrimaryActions(
    modifier: Modifier = Modifier,
    bestPrint: RankedPrint?,
    hasAnyPrint: Boolean,
    /** The latest scope this device stopped in — null shows plain Play. */
    resume: ResumePoint?,
    onPlayResume: () -> Unit,
    onPlay: () -> Unit,
    onKeep: () -> Unit,
    /** Phase 7 (D1): forget where viewing stopped — only offered while a resume exists. */
    onStartOver: () -> Unit,
    /** Why this title cannot be played, in the viewer's words — null when it can. */
    deadEndReason: String? = null,
    /**
     * Detail review (Nov 2026, P1): the dead end's Download target — the
     * best keepable print for this viewer. Null disables the button: the
     * best-ranked print is not a fallback, because on a gated title it
     * can be a locked copy or a provider page.
     */
    deadEndKeepPrint: RankedPrint? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // UI-fix P1-D1: the hero button plays video, nothing else. A title
        // whose only option is the provider's own page gets a secondary row
        // instead — the big green button must never open a web page.
        val canPlayHere = resume != null || bestPrint?.playableHere == true
        val providerOnly = resume == null && bestPrint != null &&
            !bestPrint.playableHere && bestPrint.opensProviderPage
        if (canPlayHere) {
            // Hero Play Button (full width). UI-fix P1-X1: press scale +
            // haptic on the primary action.
            val playSource = remember { MutableInteractionSource() }
            PressHaptics(playSource)
            Button(
                onClick = if (resume != null) onPlayResume else onPlay,
                interactionSource = playSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .pressScale(playSource),
                // D-29: a disabled Play keeps the same shape and language as the
                // enabled one — a tonal fill, not a dead grey slab. The reason it
                // cannot start rides right under the button, so the tonal state
                // is never mistaken for a working one.
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = resume?.label ?: "Play",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (providerOnly) {
            OutlinedButton(
                onClick = onPlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Watch on the provider's page",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "This title plays on the provider's own page, with its own player and ads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // No playable copy: Play is hidden, never a dead button — and
            // Download becomes the primary, full-width and green, with the
            // reason in plain words. When no print exists at all there is
            // nothing to download either, so the button stays disabled and
            // the sentence stays out (it would promise a download that
            // does not exist).
            // Detail redesign (Nov 2026): the bare sentence is replaced by
            // the reason the ViewModel worked out — no sources, only VIP
            // copies, or no working source — plus a way out: the similar
            // rail below, instead of a dead end.
            // Detail review (Nov 2026, P1): the button targets the best
            // KEEPABLE print for this viewer — not the best-ranked one,
            // which may be a locked copy or a provider page — and it
            // disables when none is keepable, instead of offering a
            // download the tap would refuse.
            if (deadEndReason != null) {
                Text(
                    text = deadEndReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasAnyPrint) {
                Text(
                    text = stringResource(R.string.detail_not_available_stream),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onKeep,
                enabled = deadEndKeepPrint != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Download",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Phase 7 (D1): only while a resume exists — after "Start over" the
        // button above reads plain Play again, so this row must not linger.
        if (resume != null) {
            // Detail review (Nov 2026, P1): the 48dp touch-target floor.
            TextButton(
                onClick = onStartOver,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Start over")
            }
        }
    }
}

/**
 * Detail review (Nov 2026, P1): the compact borderless utility row —
 * Save / Share / Similar / Report — that sits directly under the
 * synopsis. No outlined buttons, no Download: the hero above already
 * owns Download, and a second one would be the same action twice on
 * one screen. Similar appears only when the rail below has titles;
 * Report opens the same broken-stream dialog the top bar's More menu
 * offers (kept in both places: the bar for discoverability, this row
 * for thumb reach). E2 slice 2 adds "Notify me" on a series with a
 * handle — the bell that follows the series for new episodes.
 */
@Composable
private fun DetailUtilityRow(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    showFollow: Boolean = false,
    isFollowed: Boolean = false,
    onToggleFollow: () -> Unit = {},
    onShare: () -> Unit,
    hasSimilar: Boolean,
    onBrowseSimilar: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        UtilityAction(
            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            label = if (isFavorite) "Saved" else "Save",
            contentDescription = favoriteActionLabel(isFavorite),
            tint = if (isFavorite) MaterialTheme.colorScheme.secondary else Color.Unspecified,
            onClick = onToggleFavorite,
        )
        if (showFollow) {
            UtilityAction(
                icon = if (isFollowed) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                label = if (isFollowed) "Following" else "Notify me",
                contentDescription = followActionLabel(isFollowed),
                tint = if (isFollowed) MaterialTheme.colorScheme.secondary else Color.Unspecified,
                onClick = onToggleFollow,
            )
        }
        UtilityAction(
            icon = Icons.Default.Share,
            label = "Share",
            onClick = onShare,
        )
        if (hasSimilar) {
            UtilityAction(
                icon = Icons.Default.GridView,
                label = "Similar",
                onClick = onBrowseSimilar,
            )
        }
        UtilityAction(
            icon = Icons.Default.Flag,
            label = "Report",
            onClick = onReport,
        )
    }
}

/** One borderless icon + label action in the utility row. */
@Composable
private fun RowScope.UtilityAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = Color.Unspecified,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick, role = Role.Button)
            // The visible label is the short verb; the full phrase is the
            // spoken name, so TalkBack still hears what the tap does.
            .semantics { this.contentDescription = contentDescription ?: label },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Action label for the My List toggle button under Play. */
internal fun favoriteActionLabel(isFavorite: Boolean): String =
    if (isFavorite) "Saved in My List" else "Save to My List"

/** Action label for the "Notify me" bell (E2 slice 2). */
internal fun followActionLabel(isFollowed: Boolean): String =
    if (isFollowed) "Following this series — tap to stop notifications" else "Notify me about new episodes"

/**
 * Whether this print gets the viewer somewhere: our player can start a file, or the provider's own
 * page can be opened. One owner, so the hero button and the release-row button never disagree about
 * whether a tapped row does something.
 */
private val RankedPrint.canStart: Boolean get() = playableHere || opensProviderPage

/** The button's word. A page is OPENED, a file is played, and a cam says so while it does. */
private fun RankedPrint.playLabel(): String = when {
    playableHere -> "Play"
    // Not "Open": a one-word verb hid that this leaves the app's player for the
    // provider's own page, with its player and its ads. The button says where
    // the tap goes, so the viewer chooses it knowingly.
    opensProviderPage -> "Opens the provider's page"
    else -> "Play"
}

@Composable
private fun ExpandableSynopsis(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var hasOverflow by remember { mutableStateOf(false) }
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            // D-31: the "Read more" affordance appears exactly when the text
            // is actually cut — a length gate hid it on short-but-tall text
            // and showed it on long-but-fitting text. The layout result is
            // the truth; a short synopsis that fits shows no button at all.
            onTextLayout = { result -> hasOverflow = result.hasVisualOverflow },
        )
        if (expanded || hasOverflow) {
            TextButton(
                onClick = { expanded = !expanded },
                // Detail review (Nov 2026, P1): the 48dp touch-target floor.
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (expanded) "Show less" else "Read more")
            }
        }
    }
}

/**
 * The cast row: one tappable chip per actor, in the order the catalog
 * lists them. Six chips, then a plain count of the rest: the row is a
 * doorway to the actor's titles, not a full cast list, and there is no
 * cast screen to open. A tap searches the actor in the search bar, which
 * matches `cast_names` server-side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CastSection(cast: List<String>, onActorClick: (String) -> Unit) {
    // UI-fix P1-D3: "and N more" was dead text with no cast screen to open.
    // It now expands the row in place.
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (if (expanded) cast else cast.take(6)).forEach { actor ->
                FilterChip(
                    colors = raddFilterChipColors(),
                    selected = false,
                    onClick = { onActorClick(actor) },
                    label = { Text(actor) },
                    // Robo run 2: a cast chip is 32dp tall — the touch-target
                    // floor is 48dp, so the chip itself carries it.
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (cast.size > 6) {
            TextButton(
                onClick = { expanded = !expanded },
                // Detail review (Nov 2026, P1): the 48dp touch-target floor.
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    if (expanded) "Show less" else "and ${cast.size - 6} more",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SeasonChips(seasons: List<SeasonGroup>, selectedSeason: Int?, onSelect: (Int) -> Unit) {
    Column {
        SectionHeader(title = "Seasons", modifier = Modifier.padding(horizontal = 0.dp))
        // A4-F3: rides ScrollingChipRow + RaddChip now — the fade and the
        // row are the shared component, not a hand-rolled copy. The season
        // strip gains the fade the other rows already had.
        ScrollingChipRow(
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            items(seasons) { s ->
                RaddChip(
                    colors = raddFilterChipColors(),
                    selected = selectedSeason == s.number,
                    onClick = { onSelect(s.number) },
                    label = { Text("Season ${s.number} · ${s.episodeCount}") },
                    leadingIcon = { SeasonThumbnail(season = s, modifier = Modifier.size(24.dp)) },
                )
            }
        }
    }
}

/** A season's own artwork at chip size. Null art is answered by the branded gradient, not a hole. */
@Composable
private fun SeasonThumbnail(season: SeasonGroup, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val art = season.artworkUrl
        if (art != null) {
            CatalogImage(
                url = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                fallbackLabel = "S${season.number}",
            )
        } else {
            ImageFallbackCard(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The season picker past five seasons: a dropdown, not a tab strip —
 * the Netflix pattern, which scales where a horizontal chip strip
 * stops being a picker and starts being a scroll. Five or fewer seasons
 * keep the chip strip ([SeasonChips]); above that the strip would hide
 * most seasons off the right edge.
 */
@Composable
private fun SeasonDropdown(
    seasons: List<SeasonGroup>,
    selectedSeason: Int?,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = seasons.firstOrNull { it.number == selectedSeason } ?: seasons.firstOrNull()
    Column {
        SectionHeader(title = "Seasons", modifier = Modifier.padding(horizontal = 0.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = selected?.let { "Season ${it.number} · ${it.episodeCount} episode${if (it.episodeCount == 1) "" else "s"}" }
                        ?: "Season",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                // Detail review (Nov 2026, P1): a real chevron icon, not
                // the "▾" text glyph — the glyph renders inconsistently
                // across fonts and reads as a stray character.
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                seasons.forEach { season ->
                    DropdownMenuItem(
                        text = { Text("Season ${season.number} · ${season.episodeCount} episode${if (season.episodeCount == 1) "" else "s"}") },
                        onClick = {
                            expanded = false
                            onSelect(season.number)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The selected season's header. Its image is the season's slice of the work's wide art, so a
 * multi-season show never presents the same root poster above every season's episode list.
 */
@Composable
private fun SeasonHeader(season: SeasonGroup, onKeepSeason: (SeasonGroup) -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(CardGeometry.SEASON_ART_ASPECT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val art = season.artworkUrl
        if (art != null) {
            CatalogImage(
                url = art,
                contentDescription = "Season ${season.number} artwork",
                modifier = Modifier.fillMaxSize(),
                fallbackLabel = "S${season.number}",
            )
        } else {
            ImageFallbackCard(modifier = Modifier.fillMaxSize(), label = "S${season.number}")
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f),
                        )
                    )
                )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Season ${season.number}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "${season.episodeCount} episode${if (season.episodeCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        // Download the whole season from its header: one best copy per
        // episode, asked first and downloaded on Confirm — the Prime /
        // MovieBox pattern. The packed-season banner below keeps its own
        // download for the one-file season.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { onKeepSeason(season) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download Season ${season.number}",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun PackedSeasonBanner(season: SeasonGroup, onPlay: (RankedPrint) -> Unit, onKeep: (RankedPrint) -> Unit) {
    val pack = season.packedPrints.maxByOrNull { it.rankKey } ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            pack.qualityLabel?.let { "This season is offered as one file ($it)." }
                ?: "This season is offered as one file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Playing a packed season starts the whole-season video — pick a specific episode below for per-episode files when available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Detail review (Nov 2026, P1): the 48dp touch-target floor —
            // a Material 3 button's own min height is 40dp.
            Button(
                onClick = { onPlay(pack) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play season")
            }
            OutlinedButton(
                onClick = { onKeep(pack) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download season")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpisodeRow(
    episode: EpisodeGroup,
    /** This episode's progress on this device — null when never watched. */
    progress: EpisodeProgress?,
    /** True when a READY download on this device covers one of this episode's prints. */
    isDownloaded: Boolean = false,
    /**
     * Detail review (Nov 2026, P1): true when a download on this device
     * is still moving for one of this episode's prints — the row says
     * "Downloading" while its keep runs.
     */
    isDownloading: Boolean = false,
    onPlayPrint: (RankedPrint) -> Unit,
    onOpenExternal: (RankedPrint) -> Unit,
    onKeep: (RankedPrint) -> Unit,
    /** "Season 1 · Episode 5" — the card the row opens has to say what it is the ladder of. */
    scopeLabel: String?,
    canChooseQuality: Boolean,
    showQuality: Boolean,
    onChooseQuality: () -> Unit,
    rungs: RungActions,
) {
    var expanded by remember(episode.absoluteNumber) { mutableStateOf(false) }
    val best = episode.bestPrint
    // The only copy this row can start: a download-only episode has a best
    // print but no playable one, so the triangle must not pretend otherwise.
    val playableBest = best?.takeIf { it.canStart }
    // A row that looks tappable must do something: open the episode's quality
    // ladder when it has one, else play its playable copy. A download-only
    // episode has neither — the "Download only" label below says so instead
    // of a dead tap.
    val rowAction: (() -> Unit)? = when {
        canChooseQuality -> onChooseQuality
        playableBest != null -> { { onPlayPrint(playableBest) } }
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (rowAction != null) Modifier.clickable(onClick = rowAction) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .aspectRatio(CardGeometry.WIDE_ART_ASPECT)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val still = episode.stillUrl
                if (still != null) {
                    CatalogImage(
                        url = still,
                        contentDescription = "Episode ${episode.numberWithinSeason} preview",
                        modifier = Modifier.fillMaxSize(),
                        fallbackLabel = "E${episode.numberWithinSeason}",
                    )
                } else {
                    ImageFallbackCard(modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.75f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "${episode.absoluteNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = episode.title ?: "Episode ${episode.numberWithinSeason}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val suffix = if (episode.onlyPacked) " · packed in season file" else
                    if (episode.prints.size > 1) " · ${episode.prints.size} files" else ""
                if (best != null) {
                    // The same three axes the print card leads with, condensed for the row:
                    // quality, HDR and audio. Subtitles stay on the expanded print card.
                    // Robo run 2: the same "Picture quality 720p" badge repeated on
                    // every episode row. Paint-only here: the facts are spoken once,
                    // on the Play button below — but only while a Play button exists
                    // to carry them; a download-only episode keeps its badges, its
                    // only spoken quality signal.
                    // Dev-cleanup (Nov 2026): when the best print carries no
                    // quality data at all there is no badge row — the old
                    // "No quality info" label narrated the absence of
                    // metadata, and the row already has its title plus
                    // Play/Download. Each badge also hides itself when it
                    // has nothing to say, so the row can end up empty.
                    FlowRow(
                        modifier = if (playableBest != null) Modifier.clearAndSetSemantics { } else Modifier,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PrintQualityBadge(print = best)
                        HdrBadge(print = best)
                        AudioBadge(print = best)
                    }
                }
                if (suffix.isNotBlank()) {
                    Text(
                        text = suffix.trim().removePrefix("·").trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A download already on this device is worth one jade
                // check and the word itself — the viewer should not have
                // to open the episode to learn it is already theirs.
                if (isDownloaded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Downloaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // A download in flight is worth the same honesty as a
                // finished one: the row says it is moving, so the viewer
                // does not tap Download again on a copy already coming.
                if (isDownloading && !isDownloaded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Downloading",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // The row's badge facts, spoken once on the Play button (the badge
            // row above is paint-only while this button exists): quality, HDR,
            // audio. A download-only episode has no Play button, so its badges
            // stay spoken — see the FlowRow modifier above.
            val episodeFacts = playableBest?.let { bestPrint ->
                listOfNotNull(
                    hdrBadgeText(bestPrint.hdr)?.let { "High dynamic range $it" },
                    audioBadgeText(bestPrint.dualAudio, bestPrint.languages)?.let { "Audio $it" },
                ).joinToString(prefix = ", ", separator = ", ").ifBlank { "" }
            } ?: ""
            if (playableBest != null) {
                IconButton(
                    onClick = { onPlayPrint(playableBest) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play episode ${episode.numberWithinSeason}${playableBest.qualityLabel?.let { ", $it" } ?: ""}$episodeFacts", tint = MaterialTheme.colorScheme.primary)
                }
            } else if (best != null) {
                // No playable copy on this episode: the button that would have
                // pretended to play becomes the honest word instead.
                Text(
                    text = "Download only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canChooseQuality) {
                IconButton(
                    onClick = onChooseQuality,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "Choose quality for episode ${episode.numberWithinSeason}",
                        tint = if (showQuality) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (episode.onlyPacked) {
            Pill(text = "Whole season in one file", backgroundColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The mark this device left: a bar and what is left, or "Watched"
        // when it ran to the end. A row never watched shows nothing — no
        // bar, no guess — because an empty bar would claim a position
        // nobody has.
        progress?.let { p ->
            if (p.watched) {
                Text(
                    text = "Watched",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (p.fraction != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    p.remainingLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // The collapsed ladder replaces the print list wherever the server could fold one
        // best copy per band; a ledger that cannot is still shown as before, not hidden.
        // Its extra files read as "files", not "versions" — the viewer is
        // opening files to watch, and the word matches the movie branch's
        // expander.
        if (episode.prints.size > 1 && !canChooseQuality) {
            TextButton(
                onClick = { expanded = !expanded },
                // Detail review (Nov 2026, P1): the 48dp touch-target floor.
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (expanded) "Hide files" else "Show all ${episode.prints.size} files")
            }
        }
        if (expanded) {
            val episodeCopies = copyNumbers(episode.prints)
            episode.prints.forEach { print ->
                PrintCard(
                    print = print,
                    onPlayPrint = onPlayPrint,
                    onOpenExternal = onOpenExternal,
                    onKeep = onKeep,
                    copyIndex = episodeCopies[print.releaseId]?.first ?: 1,
                    copyTotal = episodeCopies[print.releaseId]?.second ?: 1,
                )
            }
        }
        if (showQuality) {
            QualityRungCard(
                ladder = rungs.ladder,
                selectedBand = rungs.selectedBand,
                onPlayRung = rungs.onPlay,
                onKeepRung = rungs.onKeep,
                scopeTitle = scopeLabel,
                tierLabel = rungs.tierLabel,
                isLocked = rungs.isLocked,
                preferredAudio = rungs.preferredAudio,
            )
        }
    }
}

/**
 * The spoken name for a print card's best-option line. The painted text is
 * the tier plus the playability ("Standard · Plays here"), and two prints at
 * one quality share it — so the spoken line carries the print's own facts
 * (its codec/size detail line) and its version number, and each card speaks
 * differently while the painted text stays the short label. Robo run 6.
 * Pure, so PrintCardSpokenLabelTest pins it.
 */
internal fun printSpokenLabel(print: RankedPrint, copyIndex: Int, copyTotal: Int): String =
    buildString {
        append(print.bestOptionLabel.orEmpty())
        if (print.detailLine.isNotBlank()) append(" · ").append(print.detailLine)
        if (copyTotal > 1) append(", version $copyIndex of $copyTotal")
    }

/**
 * One consolidated "Audio & Subtitles" line for the detail page, built
 * from the prints' own data: the audio languages across every print
 * (in first-seen order, de-duplicated), and the subtitle languages where
 * any print carries them. Null when neither exists — a title with no
 * language data shows no line at all, never an empty "Audio:" label.
 * Pure, so DetailAudioLineTest pins it.
 */
internal fun audioSubtitleLine(prints: List<RankedPrint>): String? {
    // The server's language lists carry the literal words "Unknown" and
    // "N/A" where a print's language was never recorded — a missing value,
    // not a language — so drop those the same way the detail meta line
    // already does, instead of printing "Audio: Unknown".
    val audio = prints.flatMap { it.languages }.map { it.trim() }
        .filter { isKnownLanguage(it) }.distinct()
    val subtitles = prints.filter { it.subtitlesAvailable }
        .flatMap { it.subtitleLangs }.map { it.trim() }
        .filter { isKnownLanguage(it) }.distinct()
    // Detail review (Nov 2026, P1): the first real surround layout
    // across the prints — "5.1" or "7.1" only, never "unknown". Prints
    // arrive best-first, so this is the best print's layout when it
    // has one.
    val layout = prints.firstNotNullOfOrNull { it.channelLayout }
    if (audio.isEmpty() && subtitles.isEmpty()) return null
    return buildString {
        if (audio.isNotEmpty()) {
            append("Audio: ").append(audio.joinToString(", "))
            layout?.let { append(" · ").append(it) }
        }
        if (subtitles.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("Subtitles: ").append(subtitles.joinToString(", "))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrintCard(
    print: RankedPrint,
    onPlayPrint: (RankedPrint) -> Unit,
    onOpenExternal: (RankedPrint) -> Unit,
    onKeep: (RankedPrint) -> Unit,
    /** 1-based position among this list's prints at the same quality. */
    copyIndex: Int = 1,
    /** How many prints in this list share the quality. 1 of 1 needs no word. */
    copyTotal: Int = 1,
) {
    // Robo run 2: two prints at one quality announced identically — "Play
    // 720p", "Download video", "Open in external player", "Picture quality
    // 720p" — once per print. The version number makes each print's actions
    // distinct, and the badge facts move into the Play button's spoken label,
    // so the badges stay paint-only instead of repeating on every card. The
    // visible Play text is unchanged (CL-4); only what is spoken grows.
    val copySuffix = if (copyTotal > 1) ", version $copyIndex" else ""
    val playText = "${print.playLabel()}${print.qualityLabel?.let { " $it" } ?: ""}"
    val spokenPlay = playText + copySuffix + print.spokenBadgeFacts()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Paint-only (the quality is spoken on the Play button below): a
            // clearing modifier passed INTO the badge would lose to Pill's own
            // semantics, so the badges sit inside a cleared wrapper instead.
            Row(
                modifier = Modifier.clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrintQualityBadge(print = print)
                if (print.isCam) CamBadge()
            }
            Spacer(Modifier.weight(1f))
            print.bestOptionLabel?.let {
                // Robo run 6: two prints at one quality announced the same
                // "Standard · Plays here" line — the label is the tier plus
                // the playability, and neither names the print. The spoken
                // line carries the print's own facts (codec/size) and its
                // copy number, so each card speaks differently; the painted
                // text stays the short label. The run-3 pattern (the rung's
                // subtext carries its quality in its spoken name).
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = printSpokenLabel(print, copyIndex, copyTotal)
                    },
                )
            }
        }

        if (print.detailLine.isNotBlank()) {
            Text(print.detailLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The tier's own facts, as badges rather than one run-on sentence: a 4K HDR
        // WEB-DL print and its audio/subtitle story read in a glance here.
        // Paint-only: the same facts are spoken once, on the Play button below.
        FlowRow(
            modifier = Modifier.clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HdrBadge(print = print)
            PrintSourceBadge(print = print)
            AudioBadge(print = print)
            SubtitleBadge(print = print)
        }

        if (print.isCam) {
            Text(
                "This is a cam/bootleg recording — low quality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onPlayPrint(print) },
                enabled = print.canStart,
                modifier = Modifier
                    .weight(1f)
                    // F32 (Batch B): M3 buttons are 40dp tall by default and
                    // Robo measured this row's Play at 28dp in run 0 — under
                    // the 48dp touch-target floor. The row's buttons carry
                    // the floor themselves.
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { contentDescription = spokenPlay },
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                // CL-4: every print's Play button said bare "Play", so three
                // prints announced identically. The verb stays (a provider page
                // still says where the tap goes); the quality makes each one
                // distinct. The PrimaryActions hero button above keeps bare
                // "Play" — it is the single hero action.
                Text(playText, modifier = Modifier.clearAndSetSemantics { })
            }
            OutlinedButton(
                onClick = { onKeep(print) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = print.qualityLabel?.let { "Download $it video$copySuffix" } ?: "Download video$copySuffix")
            }
            if (print.playableHere) {
                IconButton(onClick = { onOpenExternal(print) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.OpenInNew, contentDescription = print.qualityLabel?.let { "Open $it in external player$copySuffix" } ?: "Open in external player$copySuffix",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // The disabled Play above is silent about why: a print the server
        // only offers for download says so in words, under its own buttons.
        if (!print.canStart) {
            Text(
                text = stringResource(R.string.detail_download_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Copy numbers within one quality, keyed by releaseId: the first 1080p print
 * is version 1 of 2, the second version 2 of 2, and a print alone at its quality is
 * version 1 of 1 (no suffix — nothing to distinguish it from). Prints with no
 * quality label group together under a null key; they still count apart.
 */
private fun copyNumbers(prints: List<RankedPrint>): Map<Int, Pair<Int, Int>> {
    val totals = prints.groupingBy { it.qualityLabel }.eachCount()
    val seen = mutableMapOf<String?, Int>()
    return prints.associate { print ->
        val index = (seen[print.qualityLabel] ?: 0) + 1
        seen[print.qualityLabel] = index
        print.releaseId to (index to (totals[print.qualityLabel] ?: 1))
    }
}

/**
 * The print's badge facts as one spoken tail — the same words the badges paint
 * ("High dynamic range DV", "Source WEB-DL", "Audio Hindi + English audio",
 * "Subtitles English"), or "" when it carries none. The quality itself is not
 * repeated here: the Play label already says it.
 */
private fun RankedPrint.spokenBadgeFacts(): String {
    val facts = listOfNotNull(
        hdrBadgeText(hdr)?.let { "High dynamic range $it" },
        sourceBadgeText(sourceTag)?.let { "Source $it" },
        audioBadgeText(dualAudio, languages)?.let { "Audio $it" },
        subtitleBadgeText(subtitlesAvailable, subtitlesExternal, subtitlesHardcoded, subtitleLangs),
    )
    return if (facts.isEmpty()) "" else facts.joinToString(prefix = ", ", separator = ", ")
}

// ── Platform helpers (share keeps the app's own copy honest) ────────────────
private fun shareTitle(context: Context, post: PostDetail, onShared: () -> Unit = {}) {
    val title = post.cleanTitle?.takeIf { it.isNotBlank() } ?: post.title
    // The template is the operator's words; the link is the app's own deep
    // link to the title's page — a share that only says "check this out"
    // leaves the receiver with nowhere to go.
    val text = Brand.copy("share_template", "", "title" to title) +
        "\n" + DeepLinks.detailUri(post.id)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // The chooser firing is the share, as far as this phone can know.
        // (Sharing used to report to the server for a coin grant; that
        // report is gone with coins, 2026-11-17.)
        onShared()
    } catch (_: Exception) {
        Toast.makeText(context, "No share target available", Toast.LENGTH_SHORT).show()
    }
}

private fun openInExternalPlayer(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Play stream with…"))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, "No player or browser installed", Toast.LENGTH_SHORT).show()
        }
    }
}
