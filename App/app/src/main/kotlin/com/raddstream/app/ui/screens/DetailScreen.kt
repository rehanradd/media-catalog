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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import com.raddstream.app.ui.components.channelMonogram
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
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.navigation.DeepLinks
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.pressScale
import com.raddstream.app.ui.theme.rememberArtworkAccent
import com.raddstream.app.viewmodel.DetailNavigationEvent
import com.raddstream.app.viewmodel.DetailViewModel
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.EpisodeProgress
import com.raddstream.app.viewmodel.QualitySheetMode
import com.raddstream.app.viewmodel.QualitySheetRow
import com.raddstream.app.viewmodel.QualitySheetState
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.ResumePoint
import com.raddstream.app.viewmodel.SHEET_BAND_ORDER
import com.raddstream.app.viewmodel.SeasonGroup
import com.raddstream.app.viewmodel.SeriesDownloadKind
import com.raddstream.app.viewmodel.SeriesDownloadRow
import com.raddstream.app.viewmodel.bandDisplayName
import com.raddstream.app.viewmodel.bestForLine
import com.raddstream.app.viewmodel.formatRuntimeMinutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
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
    // Home fix (Nov 2026): direct play — the rail tile's play button opens
    // Detail with autoplay, so the tap starts the title instead of landing
    // on the two buttons. False everywhere else: the hero's Watch Now and
    // the shuffle still land on the two buttons, and the viewer presses
    // Play, once. An explicit play tap is the only route that sets this.
    autoplay: Boolean = false,
    // 2-button detail (Nov 2026): autoplay on detail open is OFF — the
    // viewer presses Play, once, and the player starts. (The hero's
    // Watch Now and the shuffle Play Something land here too, on the
    // two buttons, instead of starting playback on arrival.)
    viewModel: DetailViewModel = hiltViewModel(),
    /**
     * Round 6 motion infra: the shared-transition scopes the NavHost
     * passes in from its SharedTransitionLayout. Null outside navigation
     * (Paparazzi previews, tests) — sharedElement() calls no-op and the
     * hero renders exactly as before, with no shared element.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Home fix (Nov 2026): the direct-play trigger — one shot, when the
    // layout has loaded and Play is enabled. The layout is the gate: firing
    // before it loads would read as "no working link". Play disabled means
    // nothing playable was found, so there is nothing to fire — the viewer
    // just sees the detail, with no spurious message. The latch keeps it
    // to one fire: a recomposition, or a later layout refresh, must never
    // start playback a second time.
    var autoplayFired by remember { mutableStateOf(false) }
    LaunchedEffect(autoplay, state.layout, state.playEnabled) {
        if (autoplay && !autoplayFired && state.layout != null && state.playEnabled) {
            autoplayFired = true
            viewModel.playPrimary()
        }
    }
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
                        Icon(painterResource(R.drawable.ic_player_back), contentDescription = "Back")
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
                                    painterResource(R.drawable.ic_player_more),
                                    contentDescription = "More options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                            ) {
                                // P0-8 (Nov 2026): "Restart" lives here now,
                                // not beside Play — Play itself reads
                                // "Resume…" while a resume exists, and the
                                // restart is a deliberate choice, not a peer
                                // of the primary action. Only while a resume
                                // exists to restart from.
                                if (state.resume != null && state.resumePlayable) {
                                    DropdownMenuItem(
                                        text = { Text("Restart") },
                                        onClick = {
                                            showMoreMenu = false
                                            viewModel.removeProgress()
                                        },
                                    )
                                }
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
                    sharedElementKey = viewModel.sharedElementKey,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
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
                            painter = painterResource(R.drawable.ic_action_lock),
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
                // DT-1: the determinate arc + stage label while a Play
                // tap resolves — published by the ViewModel, cleared on
                // navigation or failure.
                playProgress = state.playProgress,
                playStage = state.playStage,
                onPlayPrimary = viewModel::playPrimary,
                downloadLabel = state.downloadLabel,
                downloadEnabled = state.downloadEnabled,
                onDownloadPrimary = { downloadWithStoragePermission { viewModel.onDownloadPrimary() } },
                playHint = state.playHint,
                downloadHint = state.downloadHint,
                onTryAgain = viewModel::tryAgainPlay,
                // P0-9 (Nov 2026): the "Show all versions" hook — the
                // tap re-fetches the title. Full pagination is owed
                // behind this hook: getPostDetail takes no offset, so
                // no next page exists to load until the backend ships
                // a paged detail endpoint and the ViewModel a load-more.
                onShowAllVersions = { viewModel.loadDetail() },
                videoQualityText = state.videoQualityText,
                // Detail redesign (Nov 2026): a season row opens its
                // sub-page — the episode list no longer lives here.
                onOpenSeason = onOpenSeason,
                // DT-3: the episode row — the current season's episodes
                // on this page, with a per-episode download icon. The
                // download tap goes through the same storage-permission
                // wrapper as the primary Download button.
                episodeProgress = state.episodeProgress,
                episodeRowSeason = state.episodeRowSeason,
                nextEpisode = state.nextEpisode,
                onPlayEpisode = viewModel::playEpisode,
                onDownloadEpisode = { season, episode ->
                    downloadWithStoragePermission { viewModel.downloadEpisode(season, episode) }
                },
                onSelectEpisodeRowSeason = viewModel::selectEpisodeRowSeason,
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
                // P1-6 (Nov 2026): the quality selector — the "Video:"
                // row names the pick and opens the browse sheet; a locked
                // band answers with the one upgrade affordance, never a
                // chip. Play and Download act on the pick.
                qualityOptions = state.qualityOptions,
                lockedQualityBands = state.lockedQualityBands,
                selectedBand = state.selectedBand,
                onOpenQualitySheet = viewModel::openQualityBrowseSheet,
                onLockedQualityBand = viewModel::onLockedQualityRow,
                contentPadding = detailPadding,
                sharedElementKey = viewModel.sharedElementKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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

    // 2-button detail (Nov 2026): the quality sheet. One component,
    // three modes: DOWNLOAD still opens one from this page (choosing a
    // file to save needs a choice), BROWSE only picks a band, and PLAY
    // is no longer opened here — the first tap plays at once (APP-01,
    // Phase 4). The sheet closes the moment a row is tapped, and the
    // action starts at once — except BROWSE, which only selects.
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
            onSelectOption = { option -> viewModel.selectQualityBand(option.band) },
            onSelectAuto = { viewModel.selectQualityBand(LadderForScope.AUTO) },
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
                        painterResource(R.drawable.ic_action_chevron_right),
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
    /** The latest scope this device stopped in, if it has a print to play — else null.
     * Retained for API compatibility (tests + previews pass it): Play itself reads
     * "Resume…" while one exists, and Restart lives in the top-bar overflow now. */
    resume: ResumePoint?,
    /** What this viewer can watch of this title, as a pill — null when it has no prints. */
    includedPill: String?,
    /** Why this title cannot be played, in the viewer's words — null when it can. */
    deadEndReason: String? = null,
    /**
     * 2-button detail (Nov 2026): the two buttons, computed by the
     * ViewModel from the same state the tap decision reads — the label
     * is plain Play / Resume (the quality lives in the Video row
     * below, not baked into the button), the hint says why a greyed
     * button is greyed, and [onTryAgain] reloads the title from the
     * dead end. [playStarting] is the press-in-flight guard: while
     * true the Play button reads "Starting…" and a second tap is
     * ignored.
     */
    playLabel: String = "Play",
    playEnabled: Boolean = false,
    playStarting: Boolean = false,
    /**
     * DT-1, retired by P0-8 (Nov 2026): the determinate resolve progress
     * (0.20/0.55/0.85) — the Play button no longer renders a determinate
     * arc, it morphs into an indeterminate spinner reading "Finding the
     * best stream…". Retained for API compatibility (the ViewModel still
     * publishes it, tests + previews still pass it); the UI ignores it.
     */
    playProgress: Float? = null,
    /**
     * DT-1, retired by P0-8: the resolve stage's plain-words label
     * ("Finding the best copy…") — retired with the arc, for the same
     * reason ("copy" is aggregator vocabulary; the button now says
     * "Finding the best stream…"). Retained, ignored, same as above.
     */
    playStage: String? = null,
    onPlayPrimary: () -> Unit = {},
    downloadLabel: String = "Download",
    downloadEnabled: Boolean = false,
    onDownloadPrimary: () -> Unit = {},
    playHint: String? = null,
    downloadHint: String? = null,
    onTryAgain: () -> Unit = {},
    /**
     * P0-9 (Nov 2026): the truncated list's way forward — "Show all
     * versions (1,000+)". The hook for the pagination follow-up (see
     * the DetailScreen call site); the viewer taps it, the title
     * re-fetches. Null-op by default in tests and previews.
     */
    onShowAllVersions: () -> Unit = {},
    /**
     * Audit Detail #1 (Nov 2026): the Video row's value — the picked
     * band, or what Auto reaches. Null when this title has no playable
     * qualities to choose from, so the row hides. [onOpenQualitySheet]
     * opens the quality sheet in browse mode (pick, don't play).
     */
    videoQualityText: String? = null,
    /**
     * Detail redesign (Nov 2026): a season row opens that season's
     * sub-page, where the episodes live as plain numbered tiles. The
     * episode list no longer lives on this page at all.
     */
    onOpenSeason: (Int) -> Unit,
    /**
     * DT-3 (round 2, Nov 2026): the episode row — this page shows the
     * current season's episodes as a swipeable row with a per-episode
     * download icon, so a series no longer needs a sub-page visit to
     * reach an episode. The season rows below stay as the all-seasons
     * deep-dive. [episodeProgress] is keyed by (season, episode);
     * [nextEpisode] marks the next unwatched one; [episodeRowSeason]
     * is the season the row shows (a chip tap changes it); the row
     * hides when the season's episodes live only in a pack.
     */
    episodeProgress: Map<Pair<Int, Int>, EpisodeProgress> = emptyMap(),
    episodeRowSeason: Int? = null,
    nextEpisode: Pair<Int, Int>? = null,
    onPlayEpisode: (Int, Int) -> Unit = { _, _ -> },
    onDownloadEpisode: (Int, Int) -> Unit = { _, _ -> },
    onSelectEpisodeRowSeason: (Int) -> Unit = {},
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
    /**
     * P1-6 (Nov 2026): the quality selector — the playable bands, in
     * ladder order. Only the entitled bands are ever shown on this page
     * (in the "Video:" row's pick and the browse sheet it opens); a
     * locked band appears only as the one upgrade affordance. Empty
     * hides the selector entirely, so the detail goldens that pass
     * nothing keep rendering exactly as before.
     */
    qualityOptions: List<QualityOption> = emptyList(),
    /** P1-6: the bands among [qualityOptions] this viewer may not take — the top one names the upgrade affordance, never a chip. */
    lockedQualityBands: Set<String> = emptySet(),
    /** APP-02: the picked band, or null / "auto" for Auto. */
    selectedBand: String? = null,
    /** APP-02: the "Video:" row tapped — open the browse sheet. */
    onOpenQualitySheet: () -> Unit = {},
    /** P1-6: the upgrade affordance tapped — the ViewModel answers with the upsell line, same as a locked sheet row. */
    onLockedQualityBand: () -> Unit = {},
    /**
     * Round-6 motion (Nov 2026): the shared-element key + scopes for
     * the hero — the destination for the Library / Search / Home
     * posters. Null (the default) in tests and previews: the hero
     * then renders exactly as before, with no shared element.
     */
    sharedElementKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val allPrints = (workPrints + seasons.flatMap { it.episodes.flatMap { e -> e.prints } } + seasons.flatMap { it.packedPrints })
    // The dead end's way out: the similar rail is the last item on the
    // page, and the requester is what scrolls it into view from the
    // actions above. Owned here, so the button and the rail agree.
    val similarBringIntoView = remember { BringIntoViewRequester() }
    val contentScope = rememberCoroutineScope()
    // P2-12 (audit B-43): the sections' entered set — the My List pattern
    // (MyListScreen, round 6 §4), copied exactly: the set lives here,
    // above the LazyColumn, keyed on the section's fixed slot index, so a
    // section that scrolls out and back finds its index already in the
    // set and renders settled instead of replaying its stagger. The page
    // is one title's, so the set is session-scoped — a new title rebuilds
    // this content with a fresh set.
    var enteredSectionIndices by remember { mutableStateOf(setOf<Int>()) }

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
            // needs no pageSides of its own. Audit Detail #5: the stale
            // note says what survived — this banner only renders over
            // cached content, so "saved details" is always true here.
            // DT-5: the banner is a polite live region — a TalkBack
            // user hears the failed refresh, instead of silently
            // reading saved details as fresh ones.
            item(contentType = "reload-failure") {
                Box(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    InlineLoadFailure(
                        message = failure,
                        onRetry = onRetryReload,
                        staleNote = stringResource(R.string.detail_showing_saved),
                    )
                }
            }
        }

        item {
            DetailEntrance(
                index = 0,
                enteredIds = enteredSectionIndices,
                onEntered = { enteredSectionIndices = enteredSectionIndices + it },
            ) {
                DetailHeroSection(
                    post = post,
                    capabilities = capabilities,
                    seasonCount = if (isSeries) seasons.size else 0,
                    includedPill = includedPill,
                    pageSides = pageSides,
                    matchPercent = matchPercent,
                    quality = videoQualityText,
                    sharedElementKey = sharedElementKey,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        }

        item {
            DetailEntrance(
                index = 1,
                enteredIds = enteredSectionIndices,
                onEntered = { enteredSectionIndices = enteredSectionIndices + it },
            ) {
                PrimaryActions(
                    modifier = pageSides,
                    // 2-button detail (Nov 2026): the label, the enabled
                    // state and the hints all come from the ViewModel — the
                    // same state the tap decision reads, so the button and
                    // the tap can never disagree about this title.
                    playLabel = playLabel,
                    playEnabled = playEnabled,
                    playStarting = playStarting,
                    // P0-8: the resolve state still arrives from the
                    // ViewModel (cleared on navigation or failure) — the
                    // button morphs on it now instead of painting the
                    // retired determinate arc + stage label.
                    playProgress = playProgress,
                    playStage = playStage,
                    onPlayPrimary = onPlayPrimary,
                downloadLabel = downloadLabel,
                downloadEnabled = downloadEnabled,
                onDownloadPrimary = onDownloadPrimary,
                playHint = playHint,
                downloadHint = downloadHint,
                onTryAgain = onTryAgain,
                deadEndReason = deadEndReason,
                )
            }
        }

        // P1-6 (Nov 2026): the quality selector sits directly under
        // the actions — the "Video:" row names the pick, and Play /
        // Download act on it. The pre-play sheet is gone: quality is
        // chosen in the browse sheet the "Video:" row opens, never
        // before play, and the chip rail is gone with it — locked bands
        // never render as chips on this page, only as the one upgrade
        // affordance. The hero's included pill already states the tier.
        item {
            DetailEntrance(
                index = 2,
                enteredIds = enteredSectionIndices,
                onEntered = { enteredSectionIndices = enteredSectionIndices + it },
            ) {
                DetailQualitySelector(
                    modifier = pageSides,
                    qualityOptions = qualityOptions,
                    lockedBands = lockedQualityBands,
                    selectedBand = selectedBand,
                    onOpenQualitySheet = onOpenQualitySheet,
                    onLockedQualityBand = onLockedQualityBand,
                )
            }
        }

        // P0-9 (Nov 2026): the cam warning card is gone — "cam" is
        // camcorder-rip vocabulary no streamer ships to viewers. No
        // replacement card: the low-quality signal lives per print
        // (RankedPrint.isCam), not per quality band, so no chip on
        // this page can honestly carry a "Low quality source" badge —
        // a band mixes cam and clean prints. The card is dropped
        // entirely, per the audit's own fallback.

        // Detail redesign (Nov 2026): the story comes first — Netflix and
        // Disney+ both put the synopsis directly under the actions, and
        // ours buried it under a quality ladder and two upsell cards.
        // 2-button detail (Nov 2026): the ladder is gone from this page
        // as cards — quality is chosen in the Video row's browse sheet
        // above, never in a rung list — and the hero's included pill
        // already states the tier.
        val synopsis = post.synopsis?.takeIf { it.isNotBlank() }
        if (synopsis != null) {
            item { Box(pageSides) { ExpandableSynopsis(text = UserWords.stripSectionMarkers(synopsis)) } }
        }

        // P1-7 (Nov 2026): four visible actions, rest in overflow —
        // Save · Rate · Share · Download, then More [Follow, Report,
        // Similar]. Report never sits at icon parity with Save, and
        // Similar is navigation to the "More Like This" rail below, not
        // a utility. The Similar action keeps the scroll-to-rail
        // behaviour the dead end's standalone link used to carry; that
        // link is gone. Download mirrors the primary Download above —
        // same tap, same enabled state, same spoken name — so the row
        // and the button can never disagree about this title.
        item {
            DetailUtilityRow(
                modifier = pageSides,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                // E2 slice 2: the bell lives in the overflow now, and
                // only on a series the catalog named with a handle — a
                // film has no next episode, and a handle-less title has
                // nothing the daily check could ask the catalog about.
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
                downloadLabel = downloadLabel,
                downloadEnabled = downloadEnabled,
                onDownloadPrimary = onDownloadPrimary,
            )
        }

        // One consolidated "Audio & Subtitles" line, built from the
        // prints' own data — the per-print badges stay on the cards for
        // the few who expand them, but the discovery surface is this line.
        // Detail review (Nov 2026, P1): the line is tappable — it opens
        // the Languages screen, where the viewer picks their audio and
        // subtitle languages. The chevron is the affordance; the whole
        // row is the target, 48dp tall.
        // Audit Detail #4 (Nov 2026): the work's own languages, so a
        // dubbed audio track can say so instead of contradicting the
        // header ("English" work, "Audio: Hindi"). Plain val, not
        // remember: this runs in the LazyColumn's scope, which is not
        // a @Composable context, and the list is one short parse.
        val originalLanguages =
            WireText.listOf(post.language).map { it.trim() }.filter(::isKnownLanguage)
        audioSubtitleLine(allPrints, originalLanguages)?.let { line ->
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
                        painterResource(R.drawable.ic_action_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // P0-9 (Nov 2026): the server caps the detail at 1,000
        // releases — but the cap is the app's problem, not the
        // viewer's, so the engineer-facing note ("Showing the first
        // X of Y releases") is gone. Progressive disclosure instead:
        // one affordance that loads the next page of versions. The
        // per-season episode counts ARE corrected (B2): the v2
        // `episode_guide` carries the true total per season, and the
        // season rows read it — but the episodes behind those rows are
        // still only the capped releases, so the affordance stays as
        // the way forward for the list itself.
        if (post.isTruncated) {
            item(contentType = "show-all-versions") {
                TextButton(
                    onClick = onShowAllVersions,
                    modifier = pageSides.heightIn(min = 48.dp),
                ) {
                    Text("Show all versions (1,000+)")
                }
            }
        }

        // Detail redesign (Nov 2026): a series shows only its seasons
        // here — one simple row each, "Season 1 — 12 episodes ›". The
        // episodes live on the season's own sub-page, as plain numbered
        // tiles; the episode rows and their print lists are gone from
        // this page. The per-season counts ARE corrected (B2): the v2
        // `episode_guide` carries the true total per season, and the rows
        // read it — but the sub-page tiles are still only the capped
        // releases, so the "Show all versions" affordance above stays as
        // the way forward for the list itself.
        if (isSeries) {
            item(contentType = "seasons-header") {
                Box(pageSides) {
                    SectionHeader(title = "Seasons", modifier = Modifier.padding(horizontal = 0.dp))
                }
            }
            // DT-3: the current season's episodes, right on this page —
            // a horizontal swipeable row with a per-episode download
            // icon. The season rows below stay as the all-seasons
            // deep-dive (the sub-page is untouched). A season whose
            // episodes live only in a pack has no row to show: the row
            // hides, and the season row still opens the sub-page.
            val rowSeason = seasons.firstOrNull { it.number == episodeRowSeason }
            if (rowSeason != null && rowSeason.episodes.isNotEmpty()) {
                item(contentType = "episode-row") {
                    DetailEntrance(
                        index = 5,
                        enteredIds = enteredSectionIndices,
                        onEntered = { enteredSectionIndices = enteredSectionIndices + it },
                    ) {
                        EpisodeRowSection(
                            seasons = seasons,
                            selectedSeason = episodeRowSeason,
                            episodeProgress = episodeProgress,
                            nextEpisode = nextEpisode,
                            onSelectSeason = onSelectEpisodeRowSeason,
                            onPlayEpisode = onPlayEpisode,
                            onDownloadEpisode = onDownloadEpisode,
                            modifier = pageSides,
                        )
                    }
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

        // Cast, from the catalog's own scraped list — only what the server
        // actually stored, parsed into names (see parseCastNames). A title
        // with no cast data shows no section at all: no fake names, no
        // empty "Cast" header. A tap searches that actor's titles.
        val cast = parseCastNames(post.castNames)
        if (cast.isNotEmpty()) {
            item {
                DetailEntrance(
                    index = 6,
                    enteredIds = enteredSectionIndices,
                    onEntered = { enteredSectionIndices = enteredSectionIndices + it },
                ) {
                    Box(pageSides) { CastSection(cast = cast, onActorClick = onActorClick) }
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
                // DT-4 (round 2): the rail is the page's LAST item and
                // arrives via scroll, not via page entrance — it carries
                // no staggered entrance. Wrapping it in DetailEntrance
                // hid the rail from the semantics tree until its 420ms
                // delay elapsed, so DetailRedesignTest's swipe-to-rail
                // could not find "More Like This" (Android 45538692963).
                // Round-6 tried a graphicsLayer entrance instead, but
                // Paparazzi captures the spring mid-flight (LocalInspectionMode
                // is false in PaparazziPreviewTest, and the animation clock
                // does not settle the spring), so the whole rail vanished
                // from all 7 Detail goldens (records 199 + 205). No
                // entrance here: the rail renders settled, as before.
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

/**
 * Round-6 motion (Nov 2026): the hero's shared-element modifier —
 * the destination half of the "poster-<workId>" transition. It is
 * [Modifier] (a no-op) whenever the key or either scope is missing,
 * so the hero renders exactly as before until the infra's
 * SharedTransitionLayout hands the screen its scopes. Both
 * rememberSharedContentState and sharedElement are members of the
 * SharedTransitionScope (not top-level imports) — the state is
 * created on the scope, and sharedElement takes it as
 * sharedContentState. The remember call lives inside the guard, so
 * no shared-content state is created while the transition is dormant.
 */
@Composable
private fun heroSharedElementModifier(
    key: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
): Modifier {
    if (key == null || sharedTransitionScope == null || animatedVisibilityScope == null) {
        return Modifier
    }
    return with(sharedTransitionScope) {
        Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
        )
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
    /** S2 (round 2): the picked quality band for the meta line, null when the title has no quality options. */
    quality: String? = null,
    /** Round-6 motion: the shared-element key + scopes for the hero image. Null until the infra lands. */
    sharedElementKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val heroSharedElement = heroSharedElementModifier(
        key = sharedElementKey,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
    )
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
                quality = quality,
                sharedElementKey = sharedElementKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
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
                // Round-6 motion: the hero backdrop is the shared
                // element's destination — the source poster morphs
                // into this frame. A no-op until the infra lands.
                modifier = Modifier.fillMaxSize().then(heroSharedElement),
                contentScale = ContentScale.Crop,
                // Audit #9 (Nov 2026): initials only on a failed
                // backdrop load — the title block over the scrim
                // already carries the title.
                fallbackLabel = channelMonogram(detailDisplayTitle(post)),
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
            quality = quality,
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
internal fun detailMetaParts(post: PostDetail, seasonCount: Int, matchPercent: Int? = null, quality: String? = null): List<String> =
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
        // S2 (round 2, Nov 2026): the brief's meta convention is
        // year · duration · quality — "2023 · 2h 18m · 1080p". The
        // duration comes from the title's own runtime; the quality is
        // the picked band ("1080p") or "Auto · up to 4K" from
        // [DetailViewModel.videoQualityText]. Language is NOT in this
        // line any more — it lives in the Audio row below ("Audio:
        // Hindi ›"), which is where a viewer goes to change it, and a
        // meta line with five parts reads as clutter, not information.
        formatRuntimeMinutes(post.runtimeMinutes),
        quality?.takeIf { it.isNotBlank() },
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
    /** S2 (round 2): the picked quality band for the meta line ("1080p" / "Auto · up to 4K"), null when the title has no quality options. */
    quality: String? = null,
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
        val meta = detailMetaParts(post, seasonCount, matchPercent, quality)
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
    /** S2 (round 2): the picked quality band for the meta line, null when the title has no quality options. */
    quality: String? = null,
    /** Round-6 motion: the shared-element key + scopes for the poster. Null until the infra lands. */
    sharedElementKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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
        // Round-6 motion: the no-backdrop poster's shared element —
        // the same key as the backdrop, so whichever hero image is
        // shown is the transition's destination. A no-op until the
        // infra lands.
        val posterSharedElement = heroSharedElementModifier(
            key = sharedElementKey,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )
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
                )
                .then(posterSharedElement),
            contentAlignment = Alignment.Center,
        ) {
            if (!post.posterUrl.isNullOrBlank()) {
                CatalogImage(
                    url = post.posterUrl,
                    contentDescription = "${detailDisplayTitle(post)} poster",
                    modifier = Modifier.fillMaxSize(),
                    // Audit #9 (Nov 2026): initials only — the title
                    // block beside the poster already carries the title.
                    fallbackLabel = channelMonogram(detailDisplayTitle(post)),
                    onBitmap = { posterBitmap = it },
                    // S5: the loading slot paints in the poster's own
                    // accent, like the rail tiles — the accent is already
                    // sampled here for the border.
                    accentTint = posterAccent,
                )
            } else {
                ImageFallbackCard(
                    modifier = Modifier.fillMaxSize(),
                    // Audit #9 (Nov 2026): initials only — the title
                    // block beside the poster already carries the title.
                    label = channelMonogram(detailDisplayTitle(post)),
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
            quality = quality,
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
 * P2 (Nov 2026): the page carries TWO Download actions that share the
 * visible verb ("Download") and the spoken name (the full cost label,
 * "Download · 720p · 1.3 GB") — the hero pill above and the utility
 * row's mirror below, the same tap by design. A bare text or spoken-name
 * query is ambiguous once both compose, so tests scope to one action
 * by tag instead of by what it says.
 */
internal const val PrimaryDownloadPillTag = "primary-download-pill"

/** Test tag for the utility row's Download mirror (see [PrimaryDownloadPillTag]). */
internal const val UtilityDownloadTag = "utility-download"

/**
 * P0-8 (Nov 2026): exactly ONE primary CTA. Play takes the wider share
 * of the row (filled jade) and Download sits beside it as a tonal pill
 * (Disney+ shape) — the old two stacked full-width buttons made
 * two "primaries" and pushed the synopsis below the fold. Play morphs
 * while it resolves: Play → indeterminate spinner reading "Finding
 * the best stream…" → playback (Netflix-style). The fake determinate
 * arc (0.20/0.55/0.85 stages) and its stage-label row are gone — the
 * numbers were stages, not progress — and there is no separate label
 * row: the label lives inside the morphing button. The helper hints
 * under the buttons are gone too; only the reason a greyed button is
 * greyed still shows, as one line under that button (the dead-end
 * reason under Play is the F-12 handling, kept). The quality choice
 * lives in the selector above, never here — both buttons act on the
 * picked band. A dead end greys Play, says why in plain words, and
 * offers [Try again] — the viewer is never left at a wall with no way
 * out. Play reads "Resume…" while a resume exists; Restart lives in
 * the top-bar overflow. The name stays `PrimaryActions` and both
 * buttons keep `heightIn(min = 48.dp)`: SkeletonParityTest reads the
 * source for both.
 *
 * P2 (Nov 2026): the Download pill is the narrow share of the row, so
 * the full cost label ("Download · 1080p · 1.3 GB") never fits it — it
 * wrapped mid-word ("Down / load…"). The pill shows the short verb
 * only ("Download", or "Downloaded" once the file is on the device);
 * the band and size already ride the Video row above, and the full
 * label stays the button's spoken name, so TalkBack still hears the
 * cost. The pill is widened a little (Play 1.75f / Download 1.25f)
 * and its content padding tightened so the short label fits on one
 * line at PIXEL_5 width and at 1.3x font scale.
 */
@Composable
private fun PrimaryActions(
    modifier: Modifier = Modifier,
    /** The Play label from the ViewModel: "Play", "Resume S1:E3 · 23 min left". */
    playLabel: String,
    playEnabled: Boolean,
    /** The press-in-flight guard: while true Play morphs into its spinner and a second tap is ignored. */
    playStarting: Boolean,
    /** DT-1, retired by P0-8: the determinate resolve progress — kept for API compatibility, ignored (see DetailContent). */
    playProgress: Float? = null,
    /** DT-1, retired by P0-8: the stage label ("Finding the best copy…") — kept for API compatibility, ignored (see DetailContent). */
    playStage: String? = null,
    onPlayPrimary: () -> Unit,
    /** The Download label: "Download", or "✓ Downloaded" when the file is already on this device. */
    downloadLabel: String,
    downloadEnabled: Boolean,
    onDownloadPrimary: () -> Unit,
    /**
     * P0-8: only the reason a greyed Play is greyed still shows, as one
     * line under the button — the "where it will play" helper is gone.
     */
    playHint: String? = null,
    /**
     * P0-8: only the reason a greyed Download is greyed still shows, as
     * one line under the button — the "what it will save" helper is gone.
     */
    downloadHint: String? = null,
    /** The dead end's way out: reload this title. */
    onTryAgain: () -> Unit = {},
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
        // P2 (Nov 2026): the pill shows the short verb only — the full
        // cost label ("Download · 1080p · 1.3 GB", "Download S1:E2 · …")
        // never fits the narrow pill and wrapped mid-word ("Down /
        // load…"). The band and size already ride the Video row above;
        // the full label stays the button's spoken name (below), so
        // TalkBack still hears the cost.
        val downloadDisplayLabel =
            if (downloadLabel.startsWith("✓")) "Downloaded" else "Download"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPlayPrimary,
                enabled = playEnabled && !playStarting,
                interactionSource = playSource,
                modifier = Modifier
                    .weight(1.75f)
                    .heightIn(min = 48.dp)
                    .pressScale(playSource),
                shape = CircleShape,
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
                // P0-8: while a Play tap resolves, the button IS the
                // progress surface — an indeterminate spinner beside
                // "Finding the best stream…", never a bare "Starting…"
                // swap and never a full-screen spinner. The determinate
                // arc is gone: its fractions were stages, not progress.
                if (playStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Finding the best stream…",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(painterResource(R.drawable.ic_player_play_filled), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = playLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FilledTonalButton(
                onClick = onDownloadPrimary,
                enabled = downloadEnabled,
                modifier = Modifier
                    .weight(1.25f)
                    .heightIn(min = 48.dp)
                    .testTag(PrimaryDownloadPillTag)
                    .semantics { contentDescription = downloadLabel },
                shape = CircleShape,
                // P2 (Nov 2026): the default button padding (24dp a side)
                // leaves no room for the label in this narrow pill — the
                // tighter padding plus the short verb is what fits.
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_action_download_filled), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = downloadDisplayLabel,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // P0-8: the only line left under Play — why it is greyed. The
        // dead-end reason leads (F-12: the reason in the viewer's words);
        // the hint is the fallback when the page has no reason. An
        // enabled Play carries no helper: "Plays instantly…" and the
        // provider-page line are gone (the button label already says the
        // latter).
        if (!playEnabled) {
            (deadEndReason ?: playHint)?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        // P0-8: the only line left under Download — why it is greyed
        // ("Nothing to save for this title.", "Downloads are off in Kids
        // Mode."). An enabled Download carries no helper.
        if (!downloadEnabled) {
            downloadHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * P1-6 (Nov 2026): the visible quality control — a "Video:" row naming
 * the current pick, and nothing else on the page. The chip rail is gone:
 * locked bands rendered as greyed chips with a lock were a ransom row —
 * greyed chips advertising unavailability — and the rail duplicated the
 * browse sheet the "Video:" row already opens. Only entitled bands are
 * ever shown here (as the pick, and as rows in that sheet); a locked
 * band appears only as ONE upgrade affordance naming the top locked band
 * ("4K — Upgrade"), which answers with the upsell the same way a locked
 * sheet row does. Hidden entirely when the title has no playable
 * qualities. The row and the affordance are at least 48dp, like every
 * other tap target on this page.
 */
@Composable
internal fun DetailQualitySelector(
    modifier: Modifier = Modifier,
    qualityOptions: List<QualityOption>,
    lockedBands: Set<String>,
    selectedBand: String?,
    onOpenQualitySheet: () -> Unit,
    onLockedQualityBand: () -> Unit = {},
) {
    // A band must never offer a copy that cannot play — the publisher
    // filters the same way, and the row re-checks, so a stale list can
    // never paint a dead band or a provider page.
    val playable = qualityOptions.filter { it.playsHere && !it.isProviderPage && it.streamId.isNotBlank() }
    val isAuto = selectedBand == null || selectedBand == LadderForScope.AUTO
    val selection = if (isAuto) {
        stringResource(R.string.detail_quality_auto)
    } else {
        bandDisplayName(selectedBand)
    }
    // The one upgrade affordance: the highest locked band among the
    // playable ones, best-first by the sheet's own band order. A locked
    // band that cannot play (dead, provider page) names nothing — it is
    // not an upgrade, it is a dead copy.
    val topLockedBand = playable
        .filter { it.band in lockedBands }
        .minByOrNull { SHEET_BAND_ORDER.indexOf(it.band.trim().lowercase()).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        ?.band
    // Round-6 motion: the selector expands / collapses with an
    // AnimatedVisibility on the expressive spatial + effects specs —
    // it fades + rises in when quality options arrive, and out when
    // they empty, instead of popping. While empty the content is not
    // composed at all, so the "empty hides the selector" contract
    // (DetailQualitySelectorTest) holds exactly as before. The specs
    // come from RaddMotion (same M3 Expressive token values);
    // MaterialTheme.motionScheme exposes no such callables in
    // material3 1.5.0 (CI 45641979193).
    AnimatedVisibility(
        visible = playable.isNotEmpty(),
        enter = fadeIn(
            animationSpec = RaddMotion.defaultEffects(),
        ) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = RaddMotion.defaultSpatial(),
        ),
        exit = fadeOut(
            animationSpec = RaddMotion.defaultEffects(),
        ) + slideOutVertically(
            targetOffsetY = { it / 4 },
            animationSpec = RaddMotion.defaultSpatial(),
        ),
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenQualitySheet, role = Role.Button),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${stringResource(R.string.detail_video_quality)}: $selection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_action_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            topLockedBand?.let { band ->
                // The single upsell on this page: a lock + the top
                // locked band + "Upgrade", in the primary colour so it
                // reads as an action, not a muted label. The tap answers
                // with the upsell line; it never changes the pick.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onLockedQualityBand, role = Role.Button)
                        // The visible label is the short "4K — Upgrade";
                        // the full sentence is the spoken name, so
                        // TalkBack hears what the tap unlocks.
                        .semantics { contentDescription = "Upgrade to unlock ${upgradeBandShortName(band)}" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_action_lock),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${upgradeBandShortName(band)} — Upgrade",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** P1-6: the short band name the upgrade affordance carries — "4K", never "4K Ultra HD". */
internal fun upgradeBandShortName(band: String): String = when (band.trim().lowercase()) {
    "2160p", "4k", "uhd" -> "4K"
    "fullhd", "fhd" -> "1080p"
    else -> band.trim()
}

// P0-9 (Nov 2026): CamWarningCard deleted outright — see the
// call-site note in DetailContent. No badge replacement: the cam
// signal is per print, not per quality band.

/**
 * 2-button detail (Nov 2026): the quality sheet — one component, three
 * modes, in the shared [PlayerSheet] container. One row per quality
 * band, best first, with the Auto row on top in PLAY and BROWSE modes. Each
 * row names its quality in plain words, shows the size when the server
 * named one (never invented), the data per hour in PLAY mode, and a
 * "Best for" line. Exactly one row carries the Recommended badge — the
 * Auto row in PLAY, 720p in DOWNLOAD. Locked rows show greyed with a
 * lock and "VIP only" and answer with the upsell when tapped; dead
 * rows show "No working link" and cannot be tapped. Host names, site
 * names, URLs, codec and HLS/m3u8 words never appear. A series
 * Download sheet shows its two rows at the top instead of quality
 * rows; a batch sheet shows the bands every ticked episode shares.
 * APP-02 (Phase 4): BROWSE mode only picks a band — a row tap selects
 * it and closes the sheet, nothing starts. The sheet closes the moment
 * a row is tapped — the row IS the confirmation — and the action starts
 * at once. (Named DetailQualitySheet because the player's own in-player
 * sheet already owns the name QualitySheet in this package.)
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
    /** Audit Detail #1: BROWSE mode only — a row tap picks the band. */
    onSelectOption: (QualityOption) -> Unit = {},
    /** Audit Detail #1: BROWSE mode only — the Auto row picks Auto. */
    onSelectAuto: () -> Unit = {},
) {
    PlayerSheet(onDismiss = onDismiss) {
        DetailQualitySheetContent(
            sheet = sheet,
            onPlayOption = onPlayOption,
            onDownloadOption = onDownloadOption,
            onSeriesRow = onSeriesRow,
            onBatchBand = onBatchBand,
            onLockedRow = onLockedRow,
            onSelectOption = onSelectOption,
            onSelectAuto = onSelectAuto,
        )
    }
}

/**
 * Audit Detail #1 (Nov 2026): the sheet's rows without their container —
 * the title, the series/batch rows, and one [DetailQualityRowView] per
 * quality band, with the tap dispatch (PLAY plays, DOWNLOAD saves,
 * BROWSE picks). Extracted from [DetailQualitySheet] so the row taps
 * are testable without the [PlayerSheet] dialog window: Robolectric
 * clicks land in the activity window, and a tap inside a
 * ModalBottomSheet never reaches its row (the node is found and
 * displayed, the click silently does nothing). The sheet itself is
 * still only ever shown through [DetailQualitySheet].
 */
@Composable
internal fun DetailQualitySheetContent(
    sheet: QualitySheetState,
    onPlayOption: (QualityOption) -> Unit,
    onDownloadOption: (QualityOption) -> Unit,
    onSeriesRow: (SeriesDownloadKind) -> Unit,
    onBatchBand: (String) -> Unit,
    onLockedRow: () -> Unit,
    /** Audit Detail #1: BROWSE mode only — a row tap picks the band. */
    onSelectOption: (QualityOption) -> Unit = {},
    /** Audit Detail #1: BROWSE mode only — the Auto row picks Auto. */
    onSelectAuto: () -> Unit = {},
) {
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
                sheet.mode == QualitySheetMode.BROWSE -> stringResource(R.string.detail_sheet_choose_quality)
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
                        val option = row.option
                        if (option == null) {
                            // The Auto row has no option: in browse
                            // mode it is a real pick (back to Auto);
                            // in PLAY mode it is already the choice
                            // and stays a dead tap — APP-01 owns
                            // that path (see the fix note).
                            if (sheet.mode == QualitySheetMode.BROWSE) onSelectAuto()
                            return@DetailQualityRowView
                        }
                        when (sheet.mode) {
                            QualitySheetMode.PLAY -> onPlayOption(option)
                            QualitySheetMode.DOWNLOAD -> onDownloadOption(option)
                            QualitySheetMode.BROWSE -> onSelectOption(option)
                        }
                    },
                    onLocked = onLockedRow,
                )
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
                            painterResource(R.drawable.ic_action_lock),
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
                painterResource(R.drawable.ic_action_download),
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
 * P1-7 (Nov 2026): the compact borderless utility row, directly under
 * the synopsis — four visible icon-only actions, rest in overflow:
 * Save · Rate (thumbs) · Share · Download + More [Follow, Report,
 * Similar]. P2-iconfirst (Nov 2026): the visible labels are gone — they
 * shrank to an 8sp floor to fit and were the tiniest text in the app;
 * every action keeps its full spoken name for TalkBack. The old row
 * carried six or seven peer actions at icon parity; at 1.3x font scale
 * "Similar" silently dropped out of it (detailLargeFont golden, round 6)
 * — a clip, not a fit. Report is trust & safety, never a peer of Save;
 * Similar is navigation to the "More Like This" rail below, not a
 * utility; Follow is series-critical but shares Save's mental model, so
 * it lives in the menu as a labelled row, not as a sixth identical icon.
 * Every visible target is 48dp. Download mirrors the primary Download
 * above — the same tap, same enabled state, same spoken name — so the
 * row and the button can never disagree about this title.
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
    /** The primary Download's spoken name ("Download · 720p · 1.3 GB") — the utility action's too. */
    downloadLabel: String = "Download",
    /** The primary Download's enabled state — the utility action's too. */
    downloadEnabled: Boolean = true,
    /** The primary Download's tap — the utility action's too. */
    onDownloadPrimary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showOverflow by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
    ) {
        UtilityAction(
            icon = if (isFavorite) painterResource(R.drawable.ic_action_favorite_filled) else painterResource(R.drawable.ic_action_favorite_border),
            contentDescription = favoriteActionLabel(isFavorite),
            tint = if (isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onToggleFavorite,
            modifier = Modifier.weight(1f),
        )
        // REA #2 (Netflix, Nov 2026): the thumbs — the input behind the
        // Match %. One "Rate" group, two 48dp targets: the pair is one
        // of the four visible actions, not two. A selected thumb reads
        // in the secondary tint, like the saved heart; tapping it again
        // clears the vote.
        if (showThumbs) {
            UtilityRateAction(
                thumb = thumb,
                onThumb = onThumb,
                modifier = Modifier.weight(2f),
            )
        }
        UtilityAction(
            icon = painterResource(R.drawable.ic_action_share),
            contentDescription = "Share",
            onClick = onShare,
            modifier = Modifier.weight(1f),
        )
        UtilityAction(
            icon = painterResource(R.drawable.ic_action_download),
            contentDescription = downloadLabel,
            onClick = onDownloadPrimary,
            enabled = downloadEnabled,
            modifier = Modifier.weight(1f).testTag(UtilityDownloadTag),
        )
        // The overflow: everything that is not one of the four. The Box
        // anchors the menu; the action itself is a fixed 48dp target,
        // never a squeezed weight.
        Box(
            modifier = Modifier.width(48.dp),
        ) {
            UtilityAction(
                icon = painterResource(R.drawable.ic_player_more),
                contentDescription = "More actions",
                onClick = { showOverflow = true },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = showOverflow,
                onDismissRequest = { showOverflow = false },
            ) {
                if (showFollow) {
                    // S8: one bell glyph for both states — the follow
                    // state reads in the tint (secondary when
                    // following), not in a second glyph, so the menu
                    // carries one icon per meaning.
                    DropdownMenuItem(
                        text = { Text(if (isFollowed) "Following" else "Notify me") },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_action_notifications),
                                contentDescription = null,
                                tint = if (isFollowed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {
                            showOverflow = false
                            onToggleFollow()
                        },
                        modifier = Modifier.semantics { contentDescription = followActionLabel(isFollowed) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Report") },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_action_flag),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showOverflow = false
                        onReport()
                    },
                    modifier = Modifier.semantics { contentDescription = "Report a broken stream" },
                )
                if (hasSimilar) {
                    // Audit Detail #8: a recognisable related-content
                    // glyph — the 2×2 grid read as "apps". AutoAwesome
                    // is the same sparkle family as the audit's
                    // auto_awesome_movies suggestion, and the member
                    // this icons version proves.
                    DropdownMenuItem(
                        text = { Text("Similar") },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_action_star),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showOverflow = false
                            onBrowseSimilar()
                        },
                        modifier = Modifier.semantics { contentDescription = "Show similar titles" },
                    )
                }
            }
        }
    }
}

/**
 * P1-7: the "Rate" group — the thumbs are one of the four visible
 * actions, so the pair shares one slot (no label — P2-iconfirst, the
 * row is icon-only). Each thumb is still its own 48dp target; the slot
 * is twice a normal weight so the pair fits without squeezing the other
 * actions.
 */
@Composable
private fun UtilityRateAction(
    thumb: Int,
    onThumb: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = { onThumb(1) }, role = Role.Button)
                    .semantics { contentDescription = thumbActionLabel(thumb, 1) },
            ) {
                Icon(
                    painterResource(R.drawable.ic_action_thumb_up),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (thumb == 1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = { onThumb(-1) }, role = Role.Button)
                    .semantics { contentDescription = thumbActionLabel(thumb, -1) },
            ) {
                Icon(
                    painterResource(R.drawable.ic_action_thumb_down),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (thumb == -1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One borderless icon-only action in the utility row (P2-iconfirst,
 * Nov 2026): the visible label is gone — the labels shrank to an 8sp
 * floor to fit their slots, so they were the tiniest text in the app.
 * The spoken name carries the meaning: every call site passes the full
 * contentDescription ("Save to My List", "Download · 720p · 1.3 GB"),
 * so TalkBack hears exactly what the tap does. */
@Composable
private fun UtilityAction(
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
    // Audit Detail #8: an explicit onSurfaceVariant tint — the old
    // Color.Unspecified left the vectors' baked-in black in place, so
    // the icons painted near-black on black while their labels read
    // light. Selected states pass their own secondary tint instead.
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            // No visible label — the full phrase is the spoken name, so
            // TalkBack still hears what the tap does.
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = tint,
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
            painterResource(R.drawable.ic_action_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * DT-4 (round 2, partial — no BOM): Detail's entrance motion, the Home
 * rail pattern: one staggered entrance per section (index × 60ms),
 * fade + short rise on the RaddMotion springs; reduced-motion users get
 * a plain fade. Paparazzi renders with a frozen clock, so in inspection
 * mode the entrance is already over and the golden shows what the phone
 * shows after it settles. The reload-failure banner is NOT wrapped — a
 * failure notice must be visible the moment it arrives, never after a
 * delay.
 * P2-12 (audit B-43): the visibility now reads the parent's entered set
 * ([enteredIds], the My List pattern, owned by [DetailContent] above the
 * LazyColumn) instead of the wrapper's own local state — which died with
 * the recycled slot, so a section that scrolled out and back replayed
 * its entrance. Once a section's index is in the set, the section
 * renders settled.
 */
@Composable
private fun DetailEntrance(
    index: Int,
    enteredIds: Set<Int>,
    onEntered: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val inInspectionMode = LocalInspectionMode.current
    val reduceMotion = rememberReduceMotion()
    val isEntered = index in enteredIds
    LaunchedEffect(index) {
        if (inInspectionMode) return@LaunchedEffect
        if (index in enteredIds) return@LaunchedEffect
        delay(index * 60L)
        onEntered(index)
    }
    // Round-6 motion: the stagger rides the M3 Expressive tokens.
    // MaterialTheme.motionScheme does NOT expose defaultEffects() /
    // defaultSpatial() as callables in material3 1.5.0 (CI
    // 45641979193), so the specs come from RaddMotion, which carries
    // the same token values (0.8/380 spatial, 1.0/1600 effects) for
    // call sites outside a MaterialTheme scope — same springs, same
    // pixels, no double source (Theme.kt, S7).
    AnimatedVisibility(
        visible = isEntered || inInspectionMode,
        enter = fadeIn(
            animationSpec = if (reduceMotion) snap() else RaddMotion.defaultEffects(),
        ) + if (reduceMotion) {
            EnterTransition.None
        } else {
            slideInVertically(
                initialOffsetY = { it / 4 },
                animationSpec = RaddMotion.defaultSpatial(),
            )
        },
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * DT-3 (round 2, Nov 2026): the current season's episodes, on this
 * page — a horizontal swipeable row with a per-episode download icon.
 * The season chips appear only when the title has more than one season;
 * with one season there is nothing to switch between, so the header is
 * just "Episodes" + the legend. The row shows the season the ViewModel
 * published (next unwatched season first, then the resume season, then
 * Season 1) until the viewer picks another chip. A season whose
 * episodes live only in a pack renders no row at all — the caller hides
 * the whole section.
 */
@Composable
internal fun EpisodeRowSection(
    seasons: List<SeasonGroup>,
    selectedSeason: Int?,
    episodeProgress: Map<Pair<Int, Int>, EpisodeProgress>,
    nextEpisode: Pair<Int, Int>?,
    onSelectSeason: (Int) -> Unit,
    onPlayEpisode: (Int, Int) -> Unit,
    onDownloadEpisode: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val season = seasons.firstOrNull { it.number == selectedSeason } ?: return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Episodes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.detail_episode_legend),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (seasons.size > 1) {
            ScrollingChipRow(
                contentPadding = PaddingValues(0.dp),
                horizontalSpacing = 6.dp,
                fadeHeight = 48.dp,
            ) {
                items(seasons, key = { it.number }) { chipSeason ->
                    // P2-chip2027: rides RaddChip now, so the selected
                    // season wears the 8dp shape + check like every other
                    // selected filter chip (the raw FilterChip hid both).
                    RaddChip(
                        selected = chipSeason.number == selectedSeason,
                        onClick = { onSelectSeason(chipSeason.number) },
                        label = { Text("Season ${chipSeason.number}") },
                        colors = raddFilterChipColors(),
                    )
                }
            }
        }
        // Round-6 motion: the episode row crossfades + slides when the
        // season chip changes — AnimatedContent on the selected season,
        // expressive effects + spatial specs. The season is resolved
        // from the target state inside the content, so the outgoing
        // row shows the old season and the incoming row the new one.
        // In inspection mode (Paparazzi) it shows the settled season
        // with no animation, so no golden moves.
        AnimatedContent(
            targetState = selectedSeason,
            transitionSpec = {
                (fadeIn(animationSpec = RaddMotion.defaultEffects()) +
                    slideInVertically(animationSpec = RaddMotion.defaultSpatial()) { it / 4 }) togetherWith
                    (fadeOut(animationSpec = RaddMotion.defaultEffects()) +
                        slideOutVertically(animationSpec = RaddMotion.defaultSpatial()) { it / 4 })
            },
            label = "episodeRowSeason",
        ) { targetSeasonNumber ->
            val targetSeason = seasons.firstOrNull { it.number == targetSeasonNumber } ?: return@AnimatedContent
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 16.dp),
            ) {
                items(targetSeason.episodes, key = { "episode-${it.numberWithinSeason}" }) { episode ->
                    val progress = episodeProgress[targetSeason.number to episode.numberWithinSeason]
                    EpisodeCard(
                        episode = episode,
                        isWatched = progress?.watched == true,
                        isNext = nextEpisode == (targetSeason.number to episode.numberWithinSeason),
                        onPlay = { onPlayEpisode(targetSeason.number, episode.numberWithinSeason) },
                        onDownload = { onDownloadEpisode(targetSeason.number, episode.numberWithinSeason) },
                    )
                }
            }
        }
    }
}

/**
 * DT-3: one episode as a card in the swipeable row — the still (or the
 * episode-number fallback), the episode number + title, the watched
 * tick and the jade "Next" mark, and a 48dp download button. The whole
 * card plays the episode; only the download icon downloads it. The
 * spoken name is the SeasonScreen wording ("Episode 3, next to
 * watch"), so a TalkBack user hears the same sentence on both pages.
 */
@Composable
private fun EpisodeCard(
    episode: EpisodeGroup,
    isWatched: Boolean,
    isNext: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // DT-3: EpisodeGroup carries no playable flag of its own — the
    // same derivation SeasonScreen's seasonTiles uses: any print that
    // plays here or opens a provider page.
    val playable = episode.prints.any { it.playableHere || it.opensProviderPage }
    val description = when {
        !playable -> "Episode ${episode.numberWithinSeason}, no playable copy"
        isNext -> "Episode ${episode.numberWithinSeason}, next to watch"
        isWatched -> "Episode ${episode.numberWithinSeason}, watched"
        else -> "Episode ${episode.numberWithinSeason}"
    }
    // Round-6 motion: the episode card presses like the Play button —
    // the established pressScale (0.97 + haptic) on the card's own
    // interaction source, expressive effects spring. The ripple stays.
    val episodeSource = remember { MutableInteractionSource() }
    PressHaptics(episodeSource)
    Column(
        modifier = modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onPlay, role = Role.Button, interactionSource = episodeSource)
            .pressScale(episodeSource)
            .semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // CardGeometryTest: every aspect ratio comes from a
                // CardGeometry constant — the 16:9 still is
                // WIDE_ART_ASPECT, never a literal in a screen.
                .aspectRatio(CardGeometry.WIDE_ART_ASPECT),
            contentAlignment = Alignment.Center,
        ) {
            if (!episode.stillUrl.isNullOrBlank()) {
                CatalogImage(
                    url = episode.stillUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    fallbackLabel = "E${episode.numberWithinSeason}",
                )
            } else {
                ImageFallbackCard(
                    modifier = Modifier.fillMaxSize(),
                    label = "E${episode.numberWithinSeason}",
                )
            }
            if (isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_action_check_filled),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (isNext) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Next",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Episode ${episode.numberWithinSeason}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!episode.title.isNullOrBlank()) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_action_download),
                    contentDescription = "Download episode ${episode.numberWithinSeason}",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
internal fun audioSubtitleLine(
    prints: List<RankedPrint>,
    /**
     * Audit Detail #4 (Nov 2026): the work's own languages, from its
     * language column. An audio language outside that set is a dubbed
     * track, and the row says so — otherwise the row contradicts the
     * header ("English" work, "Audio: Hindi"). Empty means the work's
     * languages are unknown, so nothing is marked.
     */
    originalLanguages: List<String> = emptyList(),
): String? {
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
    val originals = originalLanguages.map { it.trim().lowercase() }.toSet()
    return buildString {
        if (audio.isNotEmpty()) {
            append("Audio: ").append(audio.joinToString(", ") { lang ->
                if (originals.isNotEmpty() && lang.lowercase() !in originals) "$lang (dubbed)" else lang
            })
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
