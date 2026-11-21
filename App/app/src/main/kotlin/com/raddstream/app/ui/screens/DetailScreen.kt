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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.WireText
import com.raddstream.app.network.models.parseCastNames
import com.raddstream.app.ui.components.CapabilityChip
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.DetailBodySkeleton
import com.raddstream.app.ui.components.DetailSkeleton
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.ImageFallbackCard
import com.raddstream.app.ui.components.InlineLoadFailure
import com.raddstream.app.ui.components.PosterRailSkeleton
import com.raddstream.app.ui.components.detailPrefetchUrls
import com.raddstream.app.ui.components.isKnownLanguage
import com.raddstream.app.ui.components.MediaRail
import com.raddstream.app.ui.components.Pill
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.components.RatingBadge
import com.raddstream.app.ui.components.SectionHeader
import com.raddstream.app.ui.components.prefetchPosterArt
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.navigation.DeepLinks
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.pressScale
import com.raddstream.app.ui.theme.rememberArtworkAccent
import com.raddstream.app.viewmodel.DetailNavigationEvent
import com.raddstream.app.viewmodel.DetailViewModel
import com.raddstream.app.viewmodel.QualitySheetMode
import com.raddstream.app.viewmodel.QualitySheetRow
import com.raddstream.app.viewmodel.QualitySheetState
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.ResumePoint
import com.raddstream.app.viewmodel.SeasonGroup
import com.raddstream.app.viewmodel.SeriesDownloadKind
import com.raddstream.app.viewmodel.SeriesDownloadRow
import com.raddstream.app.viewmodel.bandDisplayName
import com.raddstream.app.viewmodel.bestForLine
import com.raddstream.app.viewmodel.formatRuntimeMinutes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBackClick: () -> Unit,
    onPlayStream: (streamId: String, title: String) -> Unit,
    /** A link the server calls a page: show the provider's own page rather than fail quietly. */
    onOpenProviderPage: (streamId: String, title: String) -> Unit = { _, _ -> },
    onOpenDownloads: () -> Unit = {},
    /** 2-button detail (Nov 2026): play a file already on this device ("✓ Downloaded"). */
    onPlayLocal: (uri: String, title: String) -> Unit = { _, _ -> },
    /** Detail redesign (Nov 2026): a season row opens its sub-page. */
    onOpenSeason: (Int) -> Unit = {},
    /** An actor chip in the cast row: open that actor's titles in Search. */
    onActorClick: (String) -> Unit = {},
    /** F18: tap on a tile in the Similar titles rail. */
    onPostClick: (Post) -> Unit = {},
    /** Detail review (Nov 2026, P1): the audio line opens Languages. */
    onOpenLanguages: () -> Unit = {},
    // 2-button detail (Nov 2026): autoplay on detail open is OFF — the
    // viewer presses Play, once, and the player starts. (The hero's
    // Watch Now and the shuffle Play Something land here too, on the
    // two buttons, instead of starting playback on arrival.)
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
                is DetailNavigationEvent.PlayStream -> {
                    // 2-button detail (Nov 2026): playback is under way, so
                    // the "Starting…" guard ends here — coming back from a
                    // failed player must never leave Play stuck on it.
                    viewModel.clearPlayStarting()
                    onPlayStream(event.streamId, event.title)
                }
                is DetailNavigationEvent.OpenProviderPage -> {
                    viewModel.clearPlayStarting()
                    onOpenProviderPage(event.streamId, event.title)
                }
                is DetailNavigationEvent.OpenExternal -> openInExternalPlayer(context, event.playUrl)
                is DetailNavigationEvent.PlayLocal -> onPlayLocal(event.uri, event.title)
                is DetailNavigationEvent.OpenDownloads -> onOpenDownloads()
                is DetailNavigationEvent.DownloadNeedsPermission ->
                    downloadWithStoragePermission { viewModel.retryLastDownload() }
            }
        }
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            // 2-button detail (Nov 2026): the snackbar carries the action
            // the ViewModel named ("See downloads" / "Free space") — a tap
            // opens Downloads, and nothing ever navigates away on its own.
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = state.snackbarActionLabel,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onSnackbarAction()
            }
            viewModel.clearActionMessage()
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
                        // Detail redesign (Nov 2026): Cast lives here now,
                        // not in the player — the player is for watching,
                        // this page is for choosing. The icon shows only
                        // when a receiver is actually found and the server
                        // switch is on (state.castAvailable); most viewers
                        // never see it at all. Tapping it opens the
                        // framework's picker; the pick itself is a session,
                        // and Play then hands the title to the TV.
                        if (state.castAvailable) {
                            DetailCastButton()
                        }
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
        // Feel-instant S4, read once: `state` is a delegated property, so
        // the branch below cannot smart-cast `state.optimisticDetail` —
        // this local is what the hero actually receives.
        val optimistic = state.optimisticDetail
        when {
            // Feel-instant S4: the tap already knows the title — paint the
            // real hero from the row, and skeleton only the body the fetch
            // still owes. The warm + fetch run underneath; the header never
            // waits for them.
            state.isLoading && optimistic != null -> Column(
                Modifier.padding(detailPadding),
            ) {
                DetailHeroSection(
                    post = optimistic,
                    capabilities = emptyList(),
                    seasonCount = 0,
                    includedPill = null,
                    pageSides = Modifier.padding(horizontal = 16.dp),
                    matchPercent = state.matchPercent,
                )
                Spacer(Modifier.height(16.dp))
                DetailBodySkeleton()
            }
            state.isLoading -> DetailSkeleton(Modifier.padding(detailPadding))
            // MX #7 — Kids Lock: the refused title. No title, no poster, no
            // Play, no retry — only the words and the way back. A retry
            // would only block again, so there is none.
            state.kidsBlocked -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(detailPadding),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Not available in Kids Mode",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "This title isn't in the kids' collection. Ask a parent to turn Kids Mode off to watch it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = onBackClick) {
                            Text("Back to browse")
                        }
                    }
                }
            }
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
                /** Phase 7 (D1): "Start over" — forget where viewing stopped. */
                onStartOver = viewModel::removeProgress,
                includedPill = state.includedPill,
                deadEndReason = state.deadEndReason,
                // 2-button detail (Nov 2026): exactly two buttons — Play
                // and Download — and the labels, the enabled state and the
                // hints all come from the ViewModel, computed from the same
                // state the tap decision reads. The quality cards and the
                // print cards are gone from this page.
                playLabel = state.playLabel,
                playEnabled = state.playEnabled,
                playStarting = state.playStarting,
                onPlayPrimary = viewModel::playPrimary,
                downloadLabel = state.downloadLabel,
                downloadEnabled = state.downloadEnabled,
                onDownloadPrimary = { downloadWithStoragePermission { viewModel.onDownloadPrimary() } },
                playHint = state.playHint,
                downloadHint = state.downloadHint,
                onTryAgain = viewModel::tryAgainPlay,
                // Detail redesign (Nov 2026): a season row opens its
                // sub-page — the episode list no longer lives here.
                onOpenSeason = onOpenSeason,
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
                // REA #2: the thumbs and the Match % for this title.
                thumb = state.titleThumb,
                onThumb = viewModel::setTitleThumb,
                matchPercent = state.matchPercent,
                onShare = {
                    shareTitle(context, post)
                },
                onReport = { showReportDialog = true },
                onOpenLanguages = onOpenLanguages,
                contentPadding = detailPadding,
            )
        }
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

    // 2-button detail (Nov 2026): the quality sheet — the only place
    // quality is ever chosen. One component, two modes; the sheet closes
    // the moment a row is tapped, and the action starts at once.
    state.qualitySheet?.let { sheet ->
        DetailQualitySheet(
            sheet = sheet,
            onPlayOption = { option -> viewModel.playSheetOption(option) },
            onDownloadOption = { option ->
                downloadWithStoragePermission { viewModel.downloadSheetOption(option) }
            },
            onSeriesRow = { kind ->
                downloadWithStoragePermission { viewModel.downloadSeriesRow(kind) }
            },
            onBatchBand = { band -> viewModel.startBatchDownload(band) },
            onLockedRow = viewModel::onLockedQualityRow,
            onDismiss = viewModel::dismissQualitySheet,
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
    /** Phase 7 (D1): "Start over" — forget where viewing stopped, on the server and locally. */
    onStartOver: () -> Unit = {},
    /** What this viewer can watch of this title, as a pill — null when it has no prints. */
    includedPill: String?,
    /** Why this title cannot be played, in the viewer's words — null when it can. */
    deadEndReason: String? = null,
    /**
     * 2-button detail (Nov 2026): the two buttons, computed by the
     * ViewModel from the same state the tap decision reads — the label
     * names the quality ("Play · 720p" / "Resume S1:E3 · 23 min left ·
     * 720p" / "Play · Auto"), the hint says why a greyed button is
     * greyed, and [onTryAgain] reloads the title from the dead end.
     * [playStarting] is the press-in-flight guard: while true the Play
     * button reads "Starting…" and a second tap is ignored.
     */
    playLabel: String = "Play",
    playEnabled: Boolean = false,
    playStarting: Boolean = false,
    onPlayPrimary: () -> Unit = {},
    downloadLabel: String = "Download",
    downloadEnabled: Boolean = false,
    onDownloadPrimary: () -> Unit = {},
    playHint: String? = null,
    downloadHint: String? = null,
    onTryAgain: () -> Unit = {},
    /**
     * Detail redesign (Nov 2026): a season row opens that season's
     * sub-page, where the episodes live as plain numbered tiles. The
     * episode list no longer lives on this page at all.
     */
    onOpenSeason: (Int) -> Unit,
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
    /** REA #2: this title's thumbs vote (1 / -1 / 0) and its tap target. */
    thumb: Int = 0,
    onThumb: (Int) -> Unit = {},
    /** REA #2: the "97% Match" for this title, or null when no row scored it. */
    matchPercent: Int? = null,
    onShare: () -> Unit,
    /** Open the broken-stream report — the utility row's Report action. */
    onReport: () -> Unit = {},
    /** Open the Languages screen — the audio line's tap target. */
    onOpenLanguages: () -> Unit = {},
    contentPadding: PaddingValues,
) {
    val allPrints = (workPrints + seasons.flatMap { it.episodes.flatMap { e -> e.prints } } + seasons.flatMap { it.packedPrints })
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
                matchPercent = matchPercent,
            )
        }

        item {
            PrimaryActions(
                modifier = pageSides,
                // 2-button detail (Nov 2026): the label, the enabled
                // state and the hints all come from the ViewModel — the
                // same state the tap decision reads, so the button and
                // the tap can never disagree about this title.
                playLabel = playLabel,
                playEnabled = playEnabled,
                playStarting = playStarting,
                onPlayPrimary = onPlayPrimary,
                downloadLabel = downloadLabel,
                downloadEnabled = downloadEnabled,
                onDownloadPrimary = onDownloadPrimary,
                playHint = playHint,
                downloadHint = downloadHint,
                onTryAgain = onTryAgain,
                resume = resume,
                onStartOver = onStartOver,
                deadEndReason = deadEndReason,
            )
        }

        // Detail redesign (Nov 2026): the story comes first — Netflix and
        // Disney+ both put the synopsis directly under the actions, and
        // ours buried it under a quality ladder and two upsell cards.
        // 2-button detail (Nov 2026): the ladder is gone from this page
        // entirely — quality is chosen in the sheet, never here — and the
        // hero's included pill already states the tier.
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
                // REA #2: the thumbs show only on a title the catalog
                // named with a handle — a vote with nothing to name the
                // work by could never feed the ranker.
                showThumbs = !post.workRef.isNullOrBlank(),
                thumb = thumb,
                onThumb = onThumb,
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
        // season/episode list is complete. The per-season episode counts
        // ARE corrected now (B2): the v2 `episode_guide` carries the true
        // total per season, and the season rows read it — but the episodes
        // behind those rows are still only the capped releases, so the
        // line stays as the disclosure for the list itself.
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

        // Detail redesign (Nov 2026): a series shows only its seasons
        // here — one simple row each, "Season 1 — 12 episodes ›". The
        // episodes live on the season's own sub-page, as plain numbered
        // tiles; the episode rows and their print lists are gone from
        // this page. The per-season counts ARE corrected (B2): the v2
        // `episode_guide` carries the true total per season, and the rows
        // read it — but the sub-page tiles are still only the capped
        // releases, so the truncation line above stays as the disclosure
        // for the list itself.
        if (isSeries) {
            item(contentType = "seasons-header") {
                Box(pageSides) {
                    SectionHeader(title = "Seasons", modifier = Modifier.padding(horizontal = 0.dp))
                }
            }
            items(seasons, key = { "season-${it.number}" }) { season ->
                Box(pageSides) {
                    SeasonRow(
                        season = season,
                        onClick = { onOpenSeason(season.number) },
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
}

@Composable
private fun DetailHeroSection(
    post: PostDetail,
    capabilities: List<String>,
    seasonCount: Int,
    includedPill: String?,
    /** The page's side padding — the fallback pads itself with it; the bleed does not. */
    pageSides: Modifier,
    /** REA #2: the "97% Match" for this title, or null when no row scored it. */
    matchPercent: Int? = null,
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
                matchPercent = matchPercent,
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
            matchPercent = matchPercent,
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
internal fun detailMetaParts(post: PostDetail, seasonCount: Int, matchPercent: Int? = null): List<String> =
    listOfNotNull(
        // REA #2 (Netflix, Nov 2026): the Match % leads the line — "97%
        // Match · 2023 · Series" — and only when a row scored this title.
        // Null means no score: the line is exactly as before.
        matchPercent?.let { "$it% Match" },
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
    /** REA #2: the "97% Match" for this title, or null when no row scored it. */
    matchPercent: Int? = null,
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
        val meta = detailMetaParts(post, seasonCount, matchPercent)
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
    /** REA #2: the "97% Match" for this title, or null when no row scored it. */
    matchPercent: Int? = null,
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
                    // S5: the loading slot paints in the poster's own
                    // accent, like the rail tiles — the accent is already
                    // sampled here for the border.
                    accentTint = posterAccent,
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
            matchPercent = matchPercent,
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
 * Detail redesign (Nov 2026): the Cast button, on this page instead of in
 * the player. The same standard route-picker the player used — a
 * `MediaRouteButton` is a View, not a composable, and the framework owns
 * the picker dialog plus the button's own connected / connecting states
 * ([CastButtonFactory.setUpMediaRouteButton]). It rides the same 48dp
 * translucent circle as back and More, so it reads over any artwork; the
 * button is composed only when a receiver is actually there (the
 * ViewModel's `castAvailable`), so the bar stays clean for everyone else.
 * No prose of its own: the description comes from `detail_cast`.
 */
@Composable
private fun DetailCastButton() {
    val description = stringResource(R.string.detail_cast)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                MediaRouteButton(context).apply {
                    CastButtonFactory.setUpMediaRouteButton(context, this)
                    contentDescription = description
                }
            },
            modifier = Modifier.size(48.dp),
        )
    }
}

/**
 * 2-button detail (Nov 2026): exactly two buttons — Play (filled,
 * first) and Download (outlined, directly below). The label names the
 * quality, the hint under a button says why it is greyed or what it
 * will do, and both are at least 48dp. The quality choice lives in
 * the sheet, never here. A dead end greys Play, says why in plain
 * words, and offers [Try again] — the viewer is never left at a wall
 * with no way out. The name stays `PrimaryActions` and both buttons
 * keep `heightIn(min = 48.dp)`: SkeletonParityTest reads the source
 * for both.
 */
@Composable
private fun PrimaryActions(
    modifier: Modifier = Modifier,
    /** The Play label from the ViewModel: "Play · 720p", "Resume S1:E3 · 23 min left · 720p", "Play · Auto". */
    playLabel: String,
    playEnabled: Boolean,
    /** The press-in-flight guard: while true Play reads "Starting…" and a second tap is ignored. */
    playStarting: Boolean,
    onPlayPrimary: () -> Unit,
    /** The Download label: "Download", or "✓ Downloaded" when the file is already on this device. */
    downloadLabel: String,
    downloadEnabled: Boolean,
    onDownloadPrimary: () -> Unit,
    /** The plain line under Play: why it is greyed, or where it will play. */
    playHint: String? = null,
    /** The plain line under Download: why it is greyed, or what it will save. */
    downloadHint: String? = null,
    /** The dead end's way out: reload this title. */
    onTryAgain: () -> Unit = {},
    /** The latest scope this device stopped in — only "Start over" reads it now. */
    resume: ResumePoint? = null,
    /** Phase 7 (D1): forget where viewing stopped — only offered while a resume exists. */
    onStartOver: () -> Unit = {},
    /** Why this title cannot be played, in the viewer's words — null when it can. */
    deadEndReason: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // UI-fix P1-X1: press scale + haptic on the primary action.
        val playSource = remember { MutableInteractionSource() }
        PressHaptics(playSource)
        Button(
            onClick = onPlayPrimary,
            enabled = playEnabled && !playStarting,
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
                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (playStarting) stringResource(R.string.detail_starting) else playLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        playHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The dead end's way out, under the reason: reload the title.
        if (!playEnabled && deadEndReason != null) {
            TextButton(
                onClick = onTryAgain,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.detail_try_again))
            }
        }

        OutlinedButton(
            onClick = onDownloadPrimary,
            enabled = downloadEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = downloadLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        downloadHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * 2-button detail (Nov 2026): the quality sheet — one component, two
 * modes, in the shared [PlayerSheet] container. One row per quality
 * band, best first, with the Auto row on top in PLAY mode only. Each
 * row names its quality in plain words, shows the size when the server
 * named one (never invented), the data per hour in PLAY mode, and a
 * "Best for" line. Exactly one row carries the Recommended badge — the
 * Auto row in PLAY, 720p in DOWNLOAD. Locked rows show greyed with a
 * lock and "VIP only" and answer with the upsell when tapped; dead
 * rows show "No working link" and cannot be tapped. Host names, site
 * names, URLs, codec and HLS/m3u8 words never appear. A series
 * Download sheet shows its two rows at the top instead of quality
 * rows; a batch sheet shows the bands every ticked episode shares.
 * The sheet closes the moment a row is tapped — the row IS the
 * confirmation — and the action starts at once. (Named
 * DetailQualitySheet because the player's own in-player sheet already
 * owns the name QualitySheet in this package.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailQualitySheet(
    sheet: QualitySheetState,
    onPlayOption: (QualityOption) -> Unit,
    onDownloadOption: (QualityOption) -> Unit,
    onSeriesRow: (SeriesDownloadKind) -> Unit,
    onBatchBand: (String) -> Unit,
    onLockedRow: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    sheet.batchBands != null -> stringResource(R.string.detail_sheet_choose_save)
                    sheet.nextEpisodeRow != null || sheet.wholeSeasonRow != null -> stringResource(R.string.detail_sheet_choose_what_save)
                    sheet.mode == QualitySheetMode.PLAY -> stringResource(R.string.detail_sheet_choose_play)
                    else -> stringResource(R.string.detail_sheet_choose_save)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            sheet.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            sheet.nextEpisodeRow?.let { row ->
                DetailQualitySeriesRow(row = row, onClick = { onSeriesRow(row.kind) })
            }
            sheet.wholeSeasonRow?.let { row ->
                DetailQualitySeriesRow(row = row, onClick = { onSeriesRow(row.kind) })
            }
            val bands = sheet.batchBands
            if (bands != null) {
                for (band in bands) {
                    DetailQualityBatchRow(
                        band = band,
                        isRecommended = band == sheet.recommendedBand,
                        onClick = { onBatchBand(band) },
                    )
                }
                Text(
                    text = "Saves ${sheet.batchEpisodes?.size ?: 0} episodes at the same quality.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (sheet.nextEpisodeRow == null && sheet.wholeSeasonRow == null) {
                for (row in sheet.rows) {
                    DetailQualityRowView(
                        row = row,
                        onClick = {
                            val option = row.option ?: return@DetailQualityRowView
                            if (sheet.mode == QualitySheetMode.PLAY) onPlayOption(option)
                            else onDownloadOption(option)
                        },
                        onLocked = onLockedRow,
                    )
                }
            }
        }
    }
}

/** One quality row of the detail sheet: plain words, real numbers only. */
@Composable
private fun DetailQualityRowView(
    row: QualitySheetRow,
    onClick: () -> Unit,
    onLocked: () -> Unit,
) {
    Surface(
        onClick = { if (row.isLocked) onLocked() else onClick() },
        enabled = !row.isDead,
        shape = RoundedCornerShape(16.dp),
        color = if (row.isRecommended) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (row.isRecommended) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "★ Recommended",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (row.isLocked) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.detail_sheet_vip_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val details = listOfNotNull(row.sizeLine, row.dataPerHour)
                    .takeIf { it.isNotEmpty() }?.joinToString(" · ")
                details?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.bestFor?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.copiesNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.downloadNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.isDead) {
                    Text(
                        text = stringResource(R.string.detail_sheet_no_working_link),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One of the two top rows of a series Download sheet. */
@Composable
private fun DetailQualitySeriesRow(
    row: SeriesDownloadRow,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One band row of a batch sheet: the band every ticked episode shares. */
@Composable
private fun DetailQualityBatchRow(
    band: String,
    isRecommended: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isRecommended) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bandDisplayName(band),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isRecommended) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "★ Recommended",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                bestForLine(band)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    /** REA #2: the thumbs show only when the title has a handle to vote on. */
    showThumbs: Boolean = false,
    /** REA #2: this title's vote — 1 liked, -1 disliked, 0 no opinion. */
    thumb: Int = 0,
    /** REA #2: tap a thumb to vote; tap the same one again to clear it. */
    onThumb: (Int) -> Unit = {},
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
        // REA #2 (Netflix, Nov 2026): the thumbs — the input behind the
        // Match %. A selected thumb reads in the secondary tint, like the
        // saved heart; tapping it again clears the vote.
        if (showThumbs) {
            UtilityAction(
                icon = Icons.Default.ThumbUp,
                label = "Like",
                contentDescription = thumbActionLabel(thumb, 1),
                tint = if (thumb == 1) MaterialTheme.colorScheme.secondary else Color.Unspecified,
                onClick = { onThumb(1) },
            )
            UtilityAction(
                icon = Icons.Default.ThumbDown,
                label = "Dislike",
                contentDescription = thumbActionLabel(thumb, -1),
                tint = if (thumb == -1) MaterialTheme.colorScheme.secondary else Color.Unspecified,
                onClick = { onThumb(-1) },
            )
        }
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

/** REA #2: the spoken name of a thumb action — the current vote decides whether the tap votes or clears. */
internal fun thumbActionLabel(thumb: Int, which: Int): String =
    when {
        thumb == which && which == 1 -> "Liked — tap to clear your like"
        thumb == which && which == -1 -> "Disliked — tap to clear your dislike"
        which == 1 -> "Like this title"
        else -> "Dislike this title"
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

/**
 * Detail redesign (Nov 2026): one season, one row — "Season 1 — 12
 * episodes ›". The episodes live on the season's own sub-page, so this
 * row only says how many there are (the B2 guide total when the server
 * named one, "Full season" for a whole-season file) and where the tap
 * goes. A whole row, 48dp tall, so the target is a thumb, not a word.
 */
@Composable
private fun SeasonRow(
    season: SeasonGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Season ${season.number}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = season.episodeCountLabel,
            style = MaterialTheme.typography.bodyMedium,
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

internal fun openInExternalPlayer(context: Context, url: String) {
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
