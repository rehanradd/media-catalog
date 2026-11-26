package com.raddstream.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.install.model.ActivityResult as PlayCoreActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.network.models.FacetsResponse
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.network.models.PanelBroadcast
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.network.models.currentCalendarYear
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.CenteredMockupDialog
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.ErrorState
import com.raddstream.app.ui.components.HeroCarousel
import com.raddstream.app.ui.components.HeroSkeleton
import com.raddstream.app.ui.components.ImageFallbackCard
import com.raddstream.app.ui.components.InlineLoadFailure
import com.raddstream.app.ui.components.MediaRail
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.PosterRailSkeleton
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.RaddChipStyle
import com.raddstream.app.ui.components.ScrollingChipRow
import com.raddstream.app.ui.components.SectionHeader
import com.raddstream.app.ui.components.SeeAllButton
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.components.homeRailPrefetchUrls
import com.raddstream.app.ui.components.prefetchHeroArt
import com.raddstream.app.ui.components.prefetchPosterArt
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddStreamMark
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.update.InAppUpdate
import kotlinx.coroutines.delay
import com.raddstream.app.viewmodel.HomeFilter
import com.raddstream.app.viewmodel.HomeMood
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import com.raddstream.app.viewmodel.LiveTvViewModel
import com.raddstream.app.viewmodel.ResumeTile
import com.raddstream.app.viewmodel.HomeViewModel
import com.raddstream.app.viewmodel.continueWatchingMatchFor
import com.raddstream.app.viewmodel.homeFilterMatches
import com.raddstream.app.viewmodel.homeSyncLine
import com.raddstream.app.viewmodel.selectHeroSection
import com.raddstream.app.viewmodel.visibleHomeRows

/** Slides in the hero banner. Six is enough to feel like a rail, few enough that the
 *  auto-advance cycle does not outlast attention. */
private const val HERO_LIMIT = 6

/**
 * UI/UX audit #1 (Nov 2026): the hero is for discovery, not resume — a
 * title already in Continue Watching never headlines the hero, so the top
 * slot stops repeating the card directly above it. The exclusion is by
 * work identity (one film filed twice has two row ids), and it happens
 * BEFORE the take, so the rail backfills from its own later rows instead
 * of showing a shorter hero. Pure, so the exclusion is testable without
 * a composition.
 */
internal fun heroItemsExcludingContinueWatching(
    heroSection: HomeSection?,
    continueWatching: List<ResumeTile>,
    heroMax: Int,
): List<Post> {
    if (heroSection == null || heroMax <= 0) return emptyList()
    val cwKeys = continueWatching.mapTo(HashSet()) {
        UserWords.workKey(it.post.workRef, it.post.displayTitle, it.post.year)
    }
    return heroSection.items
        .filterNot { UserWords.workKey(it.workRef, it.displayTitle, it.year) in cwKeys }
        .take(heroMax)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    onSearchClick: () -> Unit,
    /** The search bar's submit: a query goes straight to Search with it applied. */
    onSearchQuery: (String) -> Unit = { onSearchClick() },
    onPostClick: (Post) -> Unit = {},
    /** Session 121 (P-26): the hero's Watch Now — opens the title and starts it. */
    onWatchNowClick: (Post) -> Unit = onPostClick,
    /**
     * Home fix (Nov 2026): direct play — the rail tile's play button.
     * Opens Detail with autoplay, so the tap starts the title instead of
     * landing on the two buttons. The graph wires it to
     * [NavHostController.navigateToPost] with autoplay.
     */
    onPlayClick: (Post) -> Unit = {},
    /**
     * REA #4 (Netflix, Nov 2026): the "Play Something" shuffle — a weighted
     * random pick from the "For you" pool, opened and started at once. The
     * screen picks through the ViewModel; a null pick (no row yet) means
     * the button is never offered, so this callback never fires with
     * nothing behind it.
     */
    onPlaySomethingClick: (Post) -> Unit = {},
    onBrowseType: (String?, String?) -> Unit = { _, _ -> },
    /**
     * A facet chip's jump-off: the genre, industry and/or language to land
     * on Search with already applied. Null means "no filter of that kind".
     */
    onFacetSelected: (genre: String?, industry: String?, language: String?) -> Unit = { _, _, _ -> },
    onSetupServerClick: () -> Unit = {},
    /**
     * S4(c) (2027 Home fix): the fatal error's tertiary action — the
     * Settings screen, where the server address and everything else about
     * the catalog lives. The graph wires it to the Settings route.
     */
    onOpenSettingsClick: () -> Unit = {},
    /**
     * P0-4 (2027 Home fix): the header avatar — Settings today, where the
     * account lives; the Profile hub (P0-1) takes this over. Defaults to
     * the Settings door, so the header needs no new NavGraph wiring.
     */
    onProfileClick: () -> Unit = onOpenSettingsClick,
    /**
     * P0-2 (2027 Home fix): the Live TV rail's destination. NavGraph wires
     * this to Screen.LiveTv; until then it is a no-op and the rail still
     * renders its channels.
     */
    onLiveTvClick: () -> Unit = {},
    /** P0-2: the Live TV rail reads the existing Live TV data, cache-first. */
    liveTvViewModel: LiveTvViewModel = hiltViewModel(),
    /** The operator's hero knobs from the panel theme: 0 hides the hero slot. */
    heroMax: Int = HERO_LIMIT,
    /** M5 (home review, Nov 2026): manual-swipe by default — see [HeroCarousel]. */
    heroAutoplay: Boolean = false,
    /** Samsung RTL (v1.1.2, Issue 3): 8 s, not 5 — the operator's panel value
     *  still wins when published; this is the default when it has not been. */
    heroIntervalMs: Long = 8_000L,
    /** MX #7 — Kids Lock: when on, Home shows only kid-appropriate rows
     *  (the state carries the filtered view) and wears the exit banner. */
    kidsMode: Boolean = false,
    /** MX #7: the banner's Exit — the graph raises the PIN dialog. */
    onExitKidsMode: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    /**
     * Round 6 motion infra: the shared-transition scopes the NavHost
     * passes in from its SharedTransitionLayout. Null outside navigation
     * (Paparazzi previews, tests) — sharedElement() calls no-op and the
     * screen renders exactly as before.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val state by viewModel.uiState.collectAsState()
    // P0-2: the Live TV rail's channels, from the existing Live TV data.
    val liveTvState by liveTvViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Phase 7 (D1): one tap removes a Continue Watching tile; the way back is
    // the snackbar, not a dialog the viewer had to answer first. Dismissing
    // the snackbar without tapping Undo lets the removal stand.
    LaunchedEffect(state.removedResumeTitle) {
        val title = state.removedResumeTitle ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Removed $title from Continue Watching",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoRemoveFromContinueWatching()
        } else {
            viewModel.clearRemovedResumeNotice()
        }
    }

    // Play Core in-app updates: the launcher the update flow returns
    // through. `pendingFlexibleUpdateUrl` holds the URL of a flexible flow
    // already running — if Play reports it failed, the download page is the
    // way back, and the dialog was already dismissed when the flow started,
    // so the URL has to live here, not in state. A cancelled immediate flow
    // needs nothing: its dialog was never dismissed, so it is still there.
    var pendingFlexibleUpdateUrl by remember { mutableStateOf<String?>(null) }
    val updateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val url = pendingFlexibleUpdateUrl
        pendingFlexibleUpdateUrl = null
        if (url != null && result.resultCode == PlayCoreActivityResult.RESULT_IN_APP_UPDATE_FAILED) {
            openDownload(context, url)
        }
    }

    // Mockup (Nov 2026): no top bar any more — the header (the centred
    // logo on its own; the check-in and Coin Wallet pills were removed
    // 2026-11-17 with coins and daily check-in) lives in HomeContent,
    // pinned above the scroll, so the Scaffold keeps only the snackbar. The refresh icon went
    // with the bar: it read as operator chrome, and a refresh control with
    // no label next to it is not a viewer control.
    // Home review (Nov 2026): the Scaffold adds NO window insets of its
    // own — its default contentWindowInsets would add the status bar to
    // contentPadding.top, and HomeContent already carries its own
    // statusBarsPadding(), so the bar was applied twice (measured on a
    // Pixel 5: logo top 106dp against ~30dp for one inset). The outer
    // NavGraph Scaffold already owns the bottom inset (nav bar).
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
    ) { contentPadding ->
        HomeContent(
            state = state,
            modifier = Modifier.padding(contentPadding),
            heroMax = heroMax,
            heroAutoplay = heroAutoplay,
            heroIntervalMs = heroIntervalMs,
            onPostClick = onPostClick,
            onWatchNowClick = onWatchNowClick,
            onPlayClick = onPlayClick,
            // REA #4: the shuffle lives on the "For you" row header — the
            // pool it draws from — and only while the row exists. A null
            // pick hides the button; the callback fires only with a title.
            onPlaySomething = { viewModel.playSomething()?.let(onPlaySomethingClick) },
            onBrowseType = onBrowseType,
            onFacetSelected = onFacetSelected,
            onSetupServerClick = onSetupServerClick,
            onOpenSettingsClick = onOpenSettingsClick,
            onSearchClick = onSearchClick,
            onProfileClick = onProfileClick,
            onLiveTvClick = onLiveTvClick,
            liveTvChannels = liveTvState.channels,
            liveTvFavoriteIds = liveTvState.favoriteChannelIds,
            onToggleLiveTvFavorite = { liveTvViewModel.toggleFavorite(it) },
            kidsMode = kidsMode,
            onExitKidsMode = onExitKidsMode,
            onRefresh = viewModel::refresh,
            onRetrySection = viewModel::retrySection,
            onDismissBroadcast = { viewModel.dismissBroadcast(it) },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onRemoveFromContinueWatching = { viewModel.removeFromContinueWatching(it) },
            onDismissPickThree = viewModel::dismissPickThree,
            onPickThreeShown = viewModel::markPickThreeShown,
            onPickThreeVisitEnded = viewModel::endPickThreeVisit,
            onDismissTrialNote = viewModel::dismissTrialNote,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        )

        state.updateNotice?.let { update ->
            // A required update the viewer walked away from mid-flow
            // restarts itself instead of asking for another tap.
            LaunchedEffect(update.url, update.required) {
                if (update.required) {
                    InAppUpdate.resumeImmediateUpdateIfNeeded(context, updateLauncher) { }
                }
            }
            AppUpdateDialog(
                required = update.required,
                sizeBytes = update.sizeBytes,
                // Play Core first, download page when Play has nothing:
                // a sideloaded build always takes the download page, and
                // that is the live path until there is a Play listing. The
                // dialog dismisses only once the viewer is on their way —
                // a flexible flow dismisses when Play takes over (the
                // download runs in the background), a download dismisses
                // when the page opens, and an immediate flow never
                // dismisses: if the viewer cancels it, the dialog must
                // still be there, because a required update has no "Later".
                onUpdateNow = {
                    InAppUpdate.startUpdate(
                        context = context,
                        launcher = updateLauncher,
                        required = update.required,
                        downloadUrl = update.url,
                        onPlayFlowStarted = {
                            if (!update.required) {
                                pendingFlexibleUpdateUrl = update.url
                                viewModel.dismissUpdate()
                            }
                        },
                        onDownloadOpened = { viewModel.dismissUpdate() },
                    )
                },
                onDismiss = viewModel::dismissUpdate,
            )
        }
    }
}

/**
 * The Home page content, stateless: everything the scrollable column shows,
 * driven by [HomeUiState] plus callbacks. HomeScreen owns the ViewModel, the
 * Scaffold, the snackbar and the update dialog; this owns the layout, so it
 * can be rendered in Paparazzi from fake state with no ViewModel, no
 * navigation and no network. Behaviour is unchanged — this is a pure move
 * of the Column body out of HomeScreen (2026-11-15).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeContent(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    heroMax: Int = HERO_LIMIT,
    /** M5 (home review, Nov 2026): manual-swipe by default — see [HeroCarousel]. */
    heroAutoplay: Boolean = false,
    heroIntervalMs: Long = 8_000L,
    onPostClick: (Post) -> Unit = {},
    onWatchNowClick: (Post) -> Unit = onPostClick,
    /**
     * Home fix (Nov 2026): direct play — the rail tile's play button.
     * Opens Detail with autoplay, so the tap starts the title instead of
     * landing on the two buttons. Only the rail tiles pass it (via
     * [HomeRailSection]); everywhere else the tile stays a doorway.
     */
    onPlayClick: (Post) -> Unit = {},
    /**
     * REA #4: the "Play Something" shuffle, offered only on the "For you"
     * row header — the pool it draws from. Null everywhere else, so no
     * other rail grows a button that plays a title it did not pick.
     */
    onPlaySomething: (() -> Unit)? = null,
    onBrowseType: (String?, String?) -> Unit = { _, _ -> },
    onFacetSelected: (genre: String?, industry: String?, language: String?) -> Unit = { _, _, _ -> },
    onSetupServerClick: () -> Unit = {},
    /**
     * S4(c) (2027 Home fix): the fatal error's tertiary action — the
     * Settings screen. Passed through from [HomeScreen].
     */
    onOpenSettingsClick: () -> Unit = {},
    /**
     * P0-4: the header's search glyph — the Search tab (HomeScreen's
     * onSearchClick, wired by NavGraph). The focused-field deep link is a
     * later P1; the glyph opens the tab.
     */
    onSearchClick: () -> Unit = {},
    /** P0-4: the header avatar — see [HomeScreen.onProfileClick]. */
    onProfileClick: () -> Unit = {},
    /** P0-2: the Live TV rail's destination — see [HomeScreen.onLiveTvClick]. */
    onLiveTvClick: () -> Unit = {},
    /** P0-2: the Live TV rail's channels, from [LiveTvViewModel]. */
    liveTvChannels: List<LiveTvChannel> = emptyList(),
    /** P0-2: the starred channel ids, so the rail's stars read live. */
    liveTvFavoriteIds: Set<Int> = emptySet(),
    /** P0-2: the rail star's tap. */
    onToggleLiveTvFavorite: (Int) -> Unit = {},
    /** MX #7 — Kids Lock: the banner and the filtered view ride this flag. */
    kidsMode: Boolean = false,
    /** MX #7: the banner's Exit — the graph raises the PIN dialog. */
    onExitKidsMode: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetrySection: (String) -> Unit = {},
    onDismissBroadcast: (Int) -> Unit = {},
    onToggleFavorite: (Post) -> Unit = {},
    onRemoveFromContinueWatching: (ResumeTile) -> Unit = {},
    onDismissPickThree: () -> Unit = {},
    onPickThreeShown: () -> Unit = {},
    onPickThreeVisitEnded: () -> Unit = {},
    onDismissTrialNote: () -> Unit = {},
    /**
     * Round 6 (motion): the shared-element scopes — see [HomeScreen].
     * Both null by default; dormant until infra wires them.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    // Mockup (Nov 2026): the header replaces the M12 banner — one compact
    // row pinned above the scroll (the centred logo on its own, since the
    // check-in and Coin Wallet pills were removed 2026-11-17), and the
    // scroll starts under it. The body below keeps its indentation (it was
    // one Column deep); only the nesting changed. The Scaffold no longer
    // has a top bar, so the outer Column carries the status-bar inset
    // itself — without it the header would draw under the status bar.
    // The filter chips are UI-local state: they filter the rails already on
    // screen, so no ViewModel round-trip and no reload on a tap.
    var filter by remember { mutableStateOf(HomeFilter.All) }
    // P0-5: the mood row is gone as a filter (one taxonomy: the sticky
    // category chips). The [HomeMoodRow] composable stays for its standalone
    // Paparazzi golden and a future single Mood rail — it is no longer
    // wired into the feed, and no rail is mood-filtered.
    // P0-5: the status strip's sync row dismisses for this visit only — a
    // fresh sync brings the line back on its own.
    var syncDismissed by remember { mutableStateOf(false) }
    // Home fix (Nov 2026): the feed is a LazyColumn now — the old scrolling
    // Column composed every rail at once, so first paint waited on the whole
    // catalog and a low-end phone held all of it in memory. The lazy list
    // composes only the rows on screen and recycles the rest, which is the
    // main scroll/perf fix. The header reads the lazy list's offset for its
    // scrolled plate, where the scroll state used to live.
    val listState = rememberLazyListState()
    // Adaptive (Nov 2026): a tablet (expanded width, 840dp+) gets wider rail
    // cards — the 120dp phone card leaves a tablet rail mostly empty.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val railCardWidthDp = if (screenWidthDp >= 840) 180 else CardGeometry.RAIL_CARD_WIDTH_DP
    // P0-6: spotlight rails (the hero-adjacent first rail, the Top 10
    // chart) keep the old 150dp card; every other rail is the baseline.
    val spotlightRailWidthDp = if (screenWidthDp >= 840) 180 else CardGeometry.SPOTLIGHT_RAIL_CARD_WIDTH_DP

    // The feed's shared facts, computed once per state/filter — not per
    // item, and not inside the lazy list (a LaunchedEffect inside a lazy
    // item would restart every time the item scrolled in and out of view).
    val anyVisible = state.visibleSections.any { it.visible }
    // Fatal error only when *every* rail failed and none has data to show —
    // the strip and Continue Watching stay off that screen (below).
    val isFatal = state.fatal != null && !anyVisible
    val shownRows = visibleHomeRows(state.visibleSections, state.visibleContinueWatching.isNotEmpty())
    val hasSaved = state.visibleContinueWatching.isNotEmpty() || anyVisible
    // The banner rides the first rail that actually has rows, so it is real
    // catalog data and not a hand-picked fake "featured" set — except a
    // stale panel hero loses the slot to "Recently added" ([selectHeroSection]):
    // the operator's curated list once showed 1963 films while the newest
    // feed walked 2027s. heroMax 0 hides the slot entirely — no carousel,
    // no skeleton — because the operator asked for no hero, not for a hero
    // that never loads.
    // HO-1: the hero IS that rail's presentation, so its posters are
    // de-duplicated out of the rail below — the same six titles must
    // not appear twice on one screen.
    val heroSection = if (heroMax > 0) selectHeroSection(state.visibleSections, currentCalendarYear()) else null
    // Audit #1: the hero never repeats a Continue Watching title — the
    // exclusion happens before the take, so the rail backfills.
    val heroItems = heroItemsExcludingContinueWatching(heroSection, state.visibleContinueWatching, heroMax)
    // At most 10 rows total, CW included: a panel that publishes 12
    // sections does not get 12 rails on a phone, and the first rails win.
    // Audit #18: the same title never appears twice on Home. The seen
    // set is seeded with Continue Watching and the hero, so a rail
    // never repeats a tile or a hero poster; HO-1 (hero out of its
    // own rail) is now a special case of the same rule, by work
    // identity — not by row id, because one film filed twice has
    // two row ids.
    var seenWorks = state.visibleContinueWatching
        .map { UserWords.workKey(it.post.workRef, it.post.displayTitle, it.post.year) }
        .toSet() + heroItems.map { UserWords.workKey(it.workRef, it.displayTitle, it.year) }
    // Mockup (Nov 2026): the filter chips hide non-matching rails in
    // place — the hero, Continue Watching, Pick 3 and the library
    // above are not rails and are never filtered, only these rows.
    val renderedRows = shownRows.filter { homeFilterMatches(it, filter) }
        .filter { it.visible || it.loading || it.error != null }
        .map { section ->
            val (kept, next) = UserWords.dedupeAcrossRails(
                section.items,
                seenWorks,
            ) { post -> UserWords.workKey(post.workRef, post.displayTitle, post.year) }
            seenWorks = next
            section.copy(items = kept)
        }

    // Hero (Nov 2026): warm the hero art the moment the hero items are
    // known, so first paint has art, not placeholders. The helper is a
    // no-op for art-less items and never throws.
    val prefetchContext = LocalContext.current
    LaunchedEffect(heroItems) {
        if (heroItems.isNotEmpty()) prefetchHeroArt(prefetchContext, heroItems)
    }
    // Feel-instant S2: warm the rails' art the moment rows are known,
    // so scrolling into a rail finds posters, not shimmer — first two
    // posters per visible rail, capped. The helper dedupes, and never
    // runs on metered data the viewer never granted.
    LaunchedEffect(state.visibleSections) {
        val urls = homeRailPrefetchUrls(state.visibleSections)
        if (urls.isNotEmpty()) prefetchPosterArt(prefetchContext, urls)
    }

    // Freshness (home fix, Nov 2026): "Updated X ago" — shown only when the
    // cache is stale (older than 30 minutes) or the phone is offline, so a
    // fresh Home carries no chrome and a stale one says so plainly. The
    // line is [homeSyncLine]: offline names the age of the saved rows, so
    // a stale cache never passes as fresh.
    val nowMs = System.currentTimeMillis()
    val syncLine = homeSyncLine(state.lastHomeSyncMs, state.isOffline, nowMs)
    val isStale = state.lastHomeSyncMs?.let { nowMs - it > 30 * 60 * 1000L } ?: false
    val showSyncLine = syncLine != null && (isStale || state.isOffline)

    // Pull-to-refresh (home fix, Nov 2026; migrated to PullToRefreshBox
    // with the library bump): the refresh icon went with the top bar, so a
    // stale Home had no user-initiated recovery — only Retry on a failed
    // rail. A pull now calls the same refresh the ViewModel already had.
    // The Box drives the indicator from the ViewModel's isRefreshing and
    // fires onRefresh on the pull gesture — no pull state to observe and
    // nothing to dismiss by hand. The pull indicator replaces the top
    // progress bar.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .then(modifier),
    ) {
        HomeHeader(
            scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0,
            onSearchClick = onSearchClick,
            onProfileClick = onProfileClick,
        )
        // MX #7 — Kids Lock: the slim banner that says the mode is on and
        // offers the PIN-guarded way out. It sits above the scroll, next to
        // the header, so it is always in reach and never scrolls away.
        if (kidsMode) {
            KidsModeBanner(onExitKidsMode = onExitKidsMode)
        }
        // Pull-to-refresh: PullToRefreshBox (material3 1.3.0+, here via the
        // bumped Compose BOM 2025.11.01). The Box owns the nested scroll
        // and the spinner; isRefreshing comes from the ViewModel and
        // onRefresh is the pull gesture's refresh signal.
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(HomeFeedTag),
        ) {
        // P0-5 (2027 Home fix): the feed order is status strip → Continue
        // Watching → hero → sticky chips → My List → Rail 1 → Live TV →
        // Pick 3 → rails 2+ → Browse. Nothing non-content sits above Rail 1.
        // The strip and Continue Watching stay off the fatal screen: the
        // error owns that page, with the operator's broadcast above it.
        if (!isFatal) {
            // P1-5 (re-rate 6, evidence gap #6): ONE status strip, one row
            // max — ops and billing messages as a single compact one-line
            // row, only when it matters, never as feed rows above Rail 1.
            // Priority: operator broadcast (outage/announcement) > offline
            // > free-trial note > stale sync line. Only the winner renders;
            // dismissing it reveals the next one on the next composition
            // (each dismiss stays persistent per item, as before) — so
            // "Live TV is here" + "Free VIP: 5 days left." can never stack
            // above Continue Watching again. Each dismissible row keeps its
            // 48dp dismiss (the pinned touch floor — the audit's 32dp visual
            // ideal yields to it); the sync row dismisses for this visit,
            // the offline row is live state and clears itself when the phone
            // is back online.
            val firstBroadcast = state.broadcasts.firstOrNull()
            val trialDays = state.trialDaysRemaining
            val showStatusStrip = firstBroadcast != null ||
                (state.isOffline && hasSaved) ||
                trialDays != null ||
                (showSyncLine && !syncDismissed)
            if (showStatusStrip) {
                item(key = "statusStrip") {
                    when {
                        firstBroadcast != null -> HomeStatusRow(
                            text = firstBroadcast.title,
                            onDismiss = { onDismissBroadcast(firstBroadcast.id) },
                            dismissDescription = "Dismiss announcement",
                        )
                        state.isOffline && hasSaved -> OfflineBanner()
                        trialDays != null -> TrialNoteLine(
                            daysRemaining = trialDays,
                            onDismiss = onDismissTrialNote,
                        )
                        showSyncLine && !syncDismissed -> HomeStatusRow(
                            text = syncLine ?: "",
                            onDismiss = { syncDismissed = true },
                            dismissDescription = "Dismiss sync notice",
                        )
                    }
                    Spacer(Modifier.height(RaddTokens.Space.Xs))
                }
            }
            // Continue Watching sits ABOVE the hero now — the resume row is
            // the highest-intent content for a returning viewer (YouTube puts
            // it top; Netflix promotes in-progress into the billboard). The
            // hero is discovery, second. Only when populated: an empty
            // Continue Watching is no row, and the "Start watching" line
            // below covers only a viewer with history and nothing resumable.
            if (state.visibleContinueWatching.isNotEmpty()) {
                item(key = "continueWatching") {
                    Column {
                        SectionHeader(
                            title = "Continue watching",
                            modifier = Modifier.padding(horizontal = RaddTokens.Space.Gutter),
                        )
                        // Mockup (Nov 2026): full-width rows, not a poster rail —
                        // thumb, title, remaining label, green progress bar. Three
                        // at most: the section is a resume list, not a catalog.
                        // Keyed on the scope, not the work: two rows of the same
                        // series are two different episodes.
                        state.visibleContinueWatching.take(3).forEach { tile ->
                            ContinueWatchingRow(
                                tile = tile,
                                onClick = { onPostClick(tile.post) },
                                // Phase 7 (D1): the × removes this scope from
                                // Continue Watching — server row first, then
                                // the local rows, with Undo on the snackbar.
                                onRemove = { onRemoveFromContinueWatching(tile) },
                            )
                        }
                        Spacer(Modifier.height(RaddTokens.Space.Sm))
                    }
                }
            } else if (anyVisible && state.hasWatchHistory) {
                item(key = "startWatching") {
                    StartWatchingPlaceholder(onBrowse = { onBrowseType(null, null) })
                    Spacer(Modifier.height(RaddTokens.Space.Sm))
                }
            }
        }
        // P0-5: the hero is second content now, after Continue Watching —
        // the plan's old §0.2 order (hero first) put discovery ahead of the
        // resume row a returning viewer actually wants. The hero renders
        // only when it has items, or while it holds its footprint for
        // loading — on a fatal screen it renders nothing, so the fatal
        // branch below is unaffected.
        // The carousel is 240/280dp (home fix, Nov 2026) — down from
        // 320/400dp — so the first rail peeks over the fold.
        if (heroMax > 0) {
            if (heroItems.isNotEmpty()) {
                item(key = "hero") {
                    HeroCarousel(
                        posts = heroItems,
                        onPostClick = onPostClick,
                        onWatchNow = { post -> onWatchNowClick(post) },
                        isFavorite = { post ->
                            post.id in state.favoriteIds ||
                                (post.workRef != null && post.workRef in state.favoriteWorkRefs)
                        },
                        onToggleFavorite = { post -> onToggleFavorite(post) },
                        autoplay = heroAutoplay,
                        intervalMs = heroIntervalMs,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
                // Screenshot-fix D7 (2026-11-16): 6dp, not 12dp — the
                // gap between the dots and the next row was the second
                // dead gap in the hero stack.
                item(key = "heroSpacer") {
                    Spacer(Modifier.height(RaddTokens.Space.Xxs))
                }
            } else if (state.visibleSections.any { it.loading }) {
                item(key = "heroSkeleton") {
                    // Hold the hero's footprint while the first rail lands, so the page
                    // never flashes a bare background and then jumps when it fills in.
                    HeroSkeleton()
                }
                // D7: same 6dp as the loaded hero — the skeleton must
                // hold the same footprint, not a taller one.
                item(key = "heroSkeletonSpacer") {
                    Spacer(Modifier.height(RaddTokens.Space.Xxs))
                }
            }
        }
        // H3 (2027 Home fix, round 2): the filter chips are sticky now —
        // they sit directly under the hero and stay pinned under the
        // header while the feed scrolls, so the navigation is always in
        // reach (plan §0.2: "sticky category chips"). They used to scroll
        // away with the feed. ALL / MOVIES / SERIES / ANIME / KPOP filter
        // the rails in place; tapping the active chip restores ALL.
        stickyHeader(key = "filter") {
            HomeFilterRow(
                selected = filter,
                onSelect = { chosen -> filter = if (chosen == filter) HomeFilter.All else chosen },
            )
        }
        // Screenshot-fix D8 (2026-11-16): 4dp, not 8dp — the chips row is
        // 48dp already, and the gap under it is dead space before the rails.
        item(key = "filterSpacer") {
            Spacer(Modifier.height(RaddTokens.Space.Xs))
        }

        // M6 (home review, Nov 2026): the home search bar is gone. It was
        // a third door to the same place — the Search tab is the empty-query
        // door and the bar only carried a typed query into it — and Netflix
        // 2026 ships no bar on Home. The first content is the hero (or the
        // filter chips when Home has loaded before). SearchField itself stays:
        // the Search screen still uses it.

        // Fatal error only when *every* rail failed and none has data to show.
        if (isFatal) {
            // H1: the operator's channel stays above the error — a broadcast
            // is exactly what the operator has to say when the catalog is
            // down, and there is no Continue Watching here to displace.
            state.broadcasts.forEach { broadcast ->
                item(key = "broadcast:${broadcast.id}") {
                    BroadcastBanner(
                        broadcast = broadcast,
                        onDismiss = { onDismissBroadcast(broadcast.id) },
                    )
                    Spacer(Modifier.height(RaddTokens.Space.Sm))
                }
            }
            item(key = "fatal") {
                ErrorState(
                    message = state.fatal ?: "Couldn't reach the catalog.",
                    onRetry = onRefresh,
                    // Audit #6: the block is centred in the viewport, not
                    // stranded at the top — a lazy item's fillMaxSize is a
                    // no-op against infinite constraints, so the item
                    // itself takes the viewport height.
                    // H5: the error announces itself (polite live region,
                    // no merge — the Retry button keeps its own node).
                    modifier = Modifier.fillParentMaxHeight().semantics { liveRegion = LiveRegionMode.Polite },
                    // Dev-cleanup (Nov 2026): Home's fatal error names its own
                    // screen instead of the shared "Something went wrong".
                    title = stringResource(R.string.home_fatal_title),
                    // Audit #5: "Try a different address" is backend
                    // jargon — the button says what it does, and the
                    // line under it says what an address is. The address
                    // is the one thing a person can fix themselves, and
                    // Settings is four taps away. Offer it where the failure is seen.
                    secondaryLabel = "Change server address",
                    secondaryHint = "Titles load from this address — if it changed, update it here.",
                    onSecondary = onSetupServerClick,
                    // S4(c): the Settings path — the server address and
                    // everything else about the catalog lives there.
                    tertiaryLabel = "Open Settings",
                    onTertiary = onOpenSettingsClick,
                )
            }
        } else {
            // P0-5: the offline banner, the broadcasts, the sync line and
            // the trial note live in the status strip above the hero now —
            // nothing non-content sits in this branch above Rail 1.
            // H11 (2027 Home fix, round 2): the My List strip — the viewer's
            // saved titles as a rail just above rail 1, so the saved titles
            // are one tap away instead of a tab away. Only when the viewer
            // has saved something; an empty My List is no strip, not an
            // empty rail. No dedupe against the rails below: the same title
            // legitimate, like Netflix.
            if (state.visibleSavedPosts.isNotEmpty()) {
                item(key = "myListStrip") {
                    MyListStrip(
                        savedPosts = state.visibleSavedPosts,
                        continueWatching = state.visibleContinueWatching,
                        onPostClick = onPostClick,
                        onPlayClick = onPlayClick,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }

            // E (home fix, Nov 2026): Pick 3 rides BELOW the first rail
            // now — it used to ride below the hero, so a new viewer met
            // the card before any rail. The first rail is first content;
            // the card follows it (after the Live TV rail below). When no
            // rail renders (empty feed), the card rides right after the
            // hero instead — it still onboards. The first rail is emitted
            // here, not in the loop below, so the card sits between rail 1
            // and rail 2. P0-6: rail 1 is hero-adjacent, so it keeps the
            // 150dp spotlight card.
            renderedRows.firstOrNull()?.let { section ->
                item(key = "rail:${section.key}") {
                    HomeRailSection(
                        section = section,
                        index = 0,
                        onPostClick = onPostClick,
                        onPlayClick = onPlayClick,
                        itemWidthDp = spotlightRailWidthDp,
                        onSeeAll = {
                            if (section.industry != null) {
                                onFacetSelected(null, section.industry, null)
                            } else {
                                onBrowseType(section.type, section.sort)
                            }
                        },
                        // H10: a rail ordered by a sort Browse cannot reproduce
                        // has no "See all" — it would silently re-sort to
                        // freshest-first. The trending sort IS reproducible
                        // (Browse takes a sort now), so that rail keeps its
                        // "See all". Offer it only where the order survives
                        // the jump.
                        showSeeAll = section.browseEquivalent,
                        onRetry = { onRetrySection(section.key) },
                        // REA #4: the shuffle rides only the "For you" header —
                        // the pool it draws from. Every other rail passes null
                        // and keeps its header exactly as it was.
                        onPlaySomething = if (section.forYou) onPlaySomething else null,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }
            // P0-2: the Live TV rail — the replacement destination for the
            // Live TV tab. Favourites first, then the rest; a tap opens the
            // Live TV destination (onLiveTvClick), the star toggles in place.
            // Only when channels have loaded; an empty Live TV is no rail.
            if (liveTvChannels.isNotEmpty()) {
                item(key = "liveTvRail") {
                    LiveTvRail(
                        channels = liveTvChannels,
                        favoriteChannelIds = liveTvFavoriteIds,
                        onLiveTvClick = onLiveTvClick,
                        onToggleFavorite = onToggleLiveTvFavorite,
                    )
                }
            }
            // Gap A6: the first-run card — one-shot, after first browse,
            // only when CW + favorites are empty (see showPickThree). The
            // visit latch clears when the card leaves composition: the
            // viewer left Home, or scrolled the card out of the feed.
            // Either way the visit is over, and the persisted shown flag
            // keeps the card away on the next visit.
            if (state.showPickThree) {
                item(key = "pickThree") {
                    DisposableEffect(Unit) {
                        onDispose { onPickThreeVisitEnded() }
                    }
                    PickThreeCard(
                        candidates = state.pickThreeCandidates,
                        isFavorite = { post ->
                            post.id in state.favoriteIds ||
                                (post.workRef != null && post.workRef in state.favoriteWorkRefs)
                        },
                        onToggleFavorite = { post -> onToggleFavorite(post) },
                        onPostClick = onPostClick,
                        onDismiss = onDismissPickThree,
                        onShown = onPickThreeShown,
                    )
                    Spacer(Modifier.height(RaddTokens.Space.Sm))
                }
            }

            // P0-5: the trial note lives in the status strip above the hero
            // now — it is not a feed row. The Browse row is the LAST row
            // now (below the rails, see below) — facets are the way out of
            // the feed, not a row in the middle of it.
            // The remaining rails (rail 2 onward) — the first rail was
            // emitted above, so the Live TV rail and Pick 3 sit between
            // rail 1 and rail 2. The category filter already narrowed them
            // above. P0-6: a trending (Top 10) rail keeps the 150dp
            // spotlight card; every other rail is the 120dp baseline.
            // The stagger index continues from 1, so the entrance
            // animation does not restart at the first rail.
            renderedRows.drop(1).forEachIndexed { dropIndex, section ->
                val index = dropIndex + 1
                item(key = "rail:${section.key}") {
                    HomeRailSection(
                        section = section,
                        index = index,
                        onPostClick = onPostClick,
                        onPlayClick = onPlayClick,
                        itemWidthDp = if (section.sort == "trending") spotlightRailWidthDp else railCardWidthDp,
                        onSeeAll = {
                            if (section.industry != null) {
                                onFacetSelected(null, section.industry, null)
                            } else {
                                onBrowseType(section.type, section.sort)
                            }
                        },
                        // H10: a rail ordered by a sort Browse cannot reproduce
                        // has no "See all" — it would silently re-sort to
                        // freshest-first. The trending sort IS reproducible
                        // (Browse takes a sort now), so that rail keeps its
                        // "See all". Offer it only where the order survives
                        // the jump.
                        showSeeAll = section.browseEquivalent,
                        onRetry = { onRetrySection(section.key) },
                        // REA #4: the shuffle rides only the "For you" header —
                        // the pool it draws from. Every other rail passes null
                        // and keeps its header exactly as it was.
                        onPlaySomething = if (section.forYou) onPlaySomething else null,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }
            // P0-5: the Browse row is the LAST row — the facets are the way
            // out of the feed, not a row in the middle of it. A5 (user
            // decision 04:42 PKT 20 Nov): it carries only the server's facet
            // chips — its type jump-offs (All / Movies / Series / Anime)
            // duplicated the filter row above, so they are gone; one filter
            // row only.
            item(key = "browse") {
                BrowseSection(
                    facets = state.facets,
                    onFacetSelected = onFacetSelected,
                )
                Spacer(Modifier.height(RaddTokens.Space.Sm))
            }
            // A filter that hides every rail is not a broken catalog — say
            // which filter came up empty, and offer the way back to everything.
            // This never fires while a rail is still loading: an empty filter
            // on a half-loaded feed would blame the filter for the network.
            if (filter != HomeFilter.All && renderedRows.isEmpty() &&
                state.visibleSections.none { it.loading } && state.fatal == null
            ) {
                item(key = "emptyFilter") {
                    EmptyState(
                        // H5: the empty-filter result announces itself.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        title = filter.emptyMessage,
                        message = "Try another filter, or show everything.",
                        action = {
                            OutlinedButton(onClick = { filter = HomeFilter.All }) {
                                Text("Show all")
                            }
                        },
                    )
                }
            }
            // A successful empty load is a blank page today: no rails, no
            // error, nothing to tap. Say so, and point at the content:
            // the primary action seeks titles (Audit #4 — the old sole
            // action was backend jargon with no path to anything to
            // watch), and the address stays as the secondary fix, named
            // for what it does (Audit #5). Only when no filter is
            // active: a filtered empty feed has its own message above.
            if (filter == HomeFilter.All && renderedRows.isEmpty() && state.visibleContinueWatching.isEmpty() &&
                state.visibleSections.none { it.loading } && state.fatal == null
            ) {
                item(key = "empty") {
                    // H6: the art is an illustration — the mark at 64dp
                    // inside a jade hairline ring, not a bare 56dp icon.
                    // (The viewport-centring wrapper is dropped: a Box
                    // around this state breaks the empty-state click test
                    // under Robolectric — the Button stops receiving its
                    // click — in both the fillParentMaxHeight and the
                    // screen-fraction forms. The art + the Browse CTA
                    // carry H6; centring waits for a mechanism the test
                    // can click through.)
                    EmptyState(
                        title = "Nothing here yet",
                        message = "No titles to show right now.",
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(120.dp) // token-exempt: empty-state art size, fixed illustration geometry
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape,
                                    )
                                    .border(
                                        width = 1.dp, // token-exempt: hairline ring, not spacing
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                RaddStreamMark(
                                    diameter = 64.dp, // token-exempt: empty-state art size
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        action = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { onBrowseType(null, null) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Browse titles")
                                }
                                Spacer(Modifier.height(RaddTokens.Space.Sm))
                                TextButton(onClick = onSetupServerClick) {
                                    Text("Change server address")
                                }
                                Text(
                                    text = "Titles load from this address — if it changed, update it here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        },
                    )
                }
            }
            // Home fix (Nov 2026): the Library strip is gone. Its two doors
            // — On this phone, Downloads — are reachable from Settings (a
            // persistent tab), and Downloads links to Local Files too; the
            // strip sat below all the rails, where almost no one scrolled,
            // and its original reason (the paste-a-link door) is gone.
            item(key = "bottomSpacer") {
                Spacer(Modifier.height(RaddTokens.Space.SectionGap))
            }
        }
        }
        }
    }
}

/** The header's tap targets for the header test. */
internal const val HomeLogoTag = "homeLogo"
internal const val HomeFilterRowTag = "homeFilterRow"
/** H9: the mood row, so tests can find it without the labels. */
internal const val HomeMoodRowTag = "homeMoodRow"
/** The feed itself, so tests can scroll it deterministically by index. */
internal const val HomeFeedTag = "homeFeed"

/**
 * The Home header (P0-4, 2027 Home fix): a single-line logo left — the
 * mark at 24dp cap height plus the wordmark on one line — with the escape
 * hatches on the right: search glyph (the Search tab), cast, profile
 * avatar. The centred two-line wordmark is gone: users know what app
 * they opened, and the top bar's job is escape hatches, not brand
 * vanity. The solid background plate once scrolled stays — a transparent
 * header lets bright poster text collide behind the logo.
 */
@Composable
internal fun HomeHeader(
    scrolled: Boolean = false,
    onSearchClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (scrolled) MaterialTheme.colorScheme.background
                else Color.Transparent,
            )
            .padding(horizontal = RaddTokens.Space.Gutter, vertical = RaddTokens.Space.Xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.testTag(HomeLogoTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
        ) {
            RaddStreamMark(
                diameter = 24.dp, // token-exempt: logo cap height, fixed brand geometry
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = Brand.name.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_search),
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            HomeCastButton()
            IconButton(
                onClick = onProfileClick,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp) // token-exempt: avatar circle, fixed brand geometry
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_user),
                        contentDescription = "Profile",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp), // token-exempt: avatar glyph, fixed icon geometry
                    )
                }
            }
        }
    }
}

/**
 * P0-4: the cast route-picker on Home, the same standard MediaRouteButton
 * Detail uses — the framework owns the picker dialog plus the button's own
 * connected/connecting states. Composed only when Cast can initialise on
 * this device (runCatching, the app's own CastSpike pattern): a phone
 * without Play Services gets search + avatar only, never a dead button or
 * a crash — and under Robolectric the probe fails the same way, so the
 * header tests stay green. No receiver around is not an error: the picker
 * says so itself.
 */
@Composable
private fun HomeCastButton() {
    val context = LocalContext.current
    val castAvailable = remember(context) {
        runCatching {
            CastContext.getSharedInstance(context.applicationContext)
            true
        }.getOrDefault(false)
    }
    if (!castAvailable) return
    // The description is read here, in the composable context — the
    // AndroidView factory below is not one, so stringResource cannot run
    // inside it.
    val description = stringResource(R.string.detail_cast)
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { factoryContext ->
                MediaRouteButton(factoryContext).apply {
                    CastButtonFactory.setUpMediaRouteButton(factoryContext, this)
                    contentDescription = description
                }
            },
            modifier = Modifier.size(48.dp),
        )
    }
}

/**
 * P0-5: one status-strip row — a compact one-line message on the tonal
 * surface with an explicit 48dp dismiss (the pinned touch floor; the
 * audit's 32dp visual ideal yields to it, see the strip comment in
 * [HomeContent]). P1-5: [HomeContent] renders only the highest-priority
 * row (broadcast > offline > trial > sync) — one row max, never stacked.
 * The strip shows each row only when its message matters: a broadcast,
 * a stale/offline sync line, the trial note (which rides the existing
 * [TrialNoteLine] instead), the offline banner (live state, no
 * dismiss — it clears when the phone is back online).
 */
@Composable
private fun HomeStatusRow(
    text: String,
    onDismiss: () -> Unit,
    dismissDescription: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RaddTokens.Space.Gutter),
        shape = RaddTokens.Shape.Input,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = RaddTokens.Space.Md, top = RaddTokens.Space.Xs, bottom = RaddTokens.Space.Xs, end = RaddTokens.Space.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_close),
                    contentDescription = dismissDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The Live TV rail, so tests can find it without the title. */
internal const val LiveTvRailTag = "homeLiveTvRail"

/** P0-2: how many channels the Home rail shows — a rail, not the grid. */
private const val LIVE_TV_RAIL_LIMIT = 10

/** P0-2: the rail's channel-card width — a 16:9 logo plate + name + meta. */
private const val LIVE_TV_RAIL_CARD_WIDTH_DP = 160

/**
 * P0-2 (2027 Home fix): the Live TV rail — the replacement destination
 * for the Live TV tab. A contextual rail after Rail 1, interleaved with
 * the other contextual rails: favourites first (the channels the viewer
 * starred), then the rest. A tap opens the Live TV destination
 * ([onLiveTvClick]); the star toggles in place. The tiles are the
 * Live TV screen's own [LiveTvChannelCard], so the LIVE badge, the logo
 * plate and the meta line are the same card in both places. No rail while
 * the channels have not loaded — an empty Live TV is no rail, not an
 * empty rail.
 */
@Composable
internal fun LiveTvRail(
    channels: List<LiveTvChannel>,
    favoriteChannelIds: Set<Int>,
    onLiveTvClick: () -> Unit,
    onToggleFavorite: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) return
    // Favourites first, then the rest, in registry order — the rail leads
    // with the channels the viewer already chose.
    val railChannels = channels
        .sortedByDescending { it.id in favoriteChannelIds }
        .take(LIVE_TV_RAIL_LIMIT)
    Column(modifier = modifier.testTag(LiveTvRailTag)) {
        SectionHeader(
            title = "Live TV",
            modifier = Modifier.padding(horizontal = RaddTokens.Space.Gutter),
            action = {
                SeeAllButton(
                    onClick = onLiveTvClick,
                    contentDescription = "See all Live TV",
                )
            },
        )
        MediaRail(
            items = railChannels,
            key = { channel -> channel.id },
            itemWidthDp = LIVE_TV_RAIL_CARD_WIDTH_DP,
        ) { channel ->
            LiveTvChannelCard(
                channel = channel,
                onClick = onLiveTvClick,
                isFavorite = channel.id in favoriteChannelIds,
                onFavoriteClick = { onToggleFavorite(channel.id) },
            )
        }
        Spacer(Modifier.height(RaddTokens.Space.Sm))
    }
}

/**
 * Mockup (Nov 2026): the filter chips under the header — ALL / MOVIES /
 * SERIES / ANIME / KPOP — 48dp tall, horizontally scrollable, filtering the
 * rails in place. The active chip is jade-filled; tapping it again restores
 * ALL (that toggle lives in [HomeContent], where the state sits). A4-F3:
 * rides [ScrollingChipRow] + [RaddChip] now — the fade and the row are the
 * shared component, not a hand-rolled copy.
 */
@Composable
internal fun HomeFilterRow(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrollingChipRow(
        modifier = modifier,
        rowTestTag = HomeFilterRowTag,
    ) {
        items(HomeFilter.entries) { filter ->
            RaddChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        filter.label,
                        fontWeight = if (filter == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                modifier = Modifier.testTag("filterChip_${filter.name}"),
            )
        }
    }
}

/**
 * H9 (2027 Home fix, round 2): the mood row — "Something short", "Feel
 * good", "Mind-bending", "Dark & gritty" — a discovery prompt, not a
 * navigation. Rides [ScrollingChipRow] + [RaddChip] like the category
 * row, so the fade and the 48dp touch floor are the shared component.
 * Tapping the active mood clears it.
 *
 * P0-5 (2027 Home fix): the feed no longer wires this row in — one
 * taxonomy (the sticky category chips), moods folded into rail headers
 * or a single Mood rail instead of a second filter system. The composable
 * stays for its standalone Paparazzi golden and that future Mood rail.
 *
 * Award lift: the row animates in with the S7 spring when the category
 * changes — a fresh `filter` value recreates the entrance state, so the
 * row settles in instead of appearing pasted on. In inspection mode it
 * renders immediately, so the golden shows what the phone shows after
 * the entrance. Reduced-motion users get a plain fade, never the rise —
 * the same guard [HomeRailSection] uses.
 */
@Composable
internal fun HomeMoodRow(
    selected: HomeMood?,
    onSelect: (HomeMood) -> Unit,
    /** The category filter, so the row re-enters when the category changes. */
    filter: HomeFilter,
    modifier: Modifier = Modifier,
) {
    val inInspectionMode = LocalInspectionMode.current
    val reduceMotion = rememberReduceMotion()
    var visible by remember(filter) { mutableStateOf(inInspectionMode) }
    LaunchedEffect(filter) {
        if (inInspectionMode) return@LaunchedEffect
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
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
    ) {
        ScrollingChipRow(
            modifier = modifier,
            rowTestTag = HomeMoodRowTag,
        ) {
            items(HomeMood.entries) { mood ->
                RaddChip(
                    selected = mood == selected,
                    onClick = { onSelect(mood) },
                    label = {
                        Text(
                            mood.label,
                            fontWeight = if (mood == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.testTag("moodChip_${mood.name}"),
                )
            }
        }
    }
}

/**
 * H11 (2027 Home fix, round 2): the My List strip — the viewer's saved
 * titles as a rail just above rail 1, so the saved titles are one tap away
 * instead of a tab away. A doorway, not a second My List: the tiles open
 * Detail, the play button starts the work at once (APP-06 one-tap play,
 * is no remove × on the tiles — a × on a horizontal rail is a
 * slipping-thumb trap, and the My List screen already owns removal. No
 * dedupe against the rails below: the same title in "My List" and a
 * genre rail is legitimate, like Netflix. Progress comes only from a
 * real Continue Watching row ([continueWatchingMatchFor]) — never
 * invented. No "See all" on the header: the full list lives in the
 * Library tab, not on a Browse route, and the bottom nav already
 * reaches it. The strip renders only when `savedPosts` is non-empty;
 * an empty My List is no strip, not an empty rail. The entrance matches
 * the rails (S7 spring, inspection-mode immediate, reduced-motion
 * fade-only) — the strip is a rail, so it enters like one.
 */
@Composable
internal fun MyListStrip(
    savedPosts: List<Post>,
    continueWatching: List<ResumeTile>,
    onPostClick: (Post) -> Unit,
    onPlayClick: (Post) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Round 6 (motion): the shared-element scopes — see [HomeScreen].
     * Both null by default; dormant until infra wires them.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val inInspectionMode = LocalInspectionMode.current
    val reduceMotion = rememberReduceMotion()
    var visible by remember { mutableStateOf(inInspectionMode) }
    LaunchedEffect(Unit) {
        if (inInspectionMode) return@LaunchedEffect
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
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
    ) {
        Column(modifier = modifier.testTag(MyListStripTag)) {
            SectionHeader(
                title = "My List",
                modifier = Modifier.padding(horizontal = RaddTokens.Space.Gutter),
            )
            MediaRail(
                items = savedPosts,
                key = { post -> post.id },
                itemWidthDp = CardGeometry.RAIL_CARD_WIDTH_DP,
            ) { post ->
                // H11: the progress of the real Continue Watching row for
                // this title, if one exists — the "S1:E3 · 20 min left"
                // label and the bar. A title not being watched shows the
                // poster with no progress, never an invented one.
                val match = continueWatchingMatchFor(post, continueWatching)
                PosterCard(
                    post = post,
                    onClick = { onPostClick(post) },
                    // APP-06 one-tap play, same as the My List screen.
                    onPlayClick = { onPlayClick(post) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Audit #8: the compact line (year · rating) fits the
                    // 120dp rail card; the type stays in the tile's spoken
                    // sentence and on Detail. Two title lines, like the
                    // rails, so long titles do not truncate mid-word.
                    compactMeta = true,
                    titleMaxLines = 2,
                    progressFraction = match?.progressFraction,
                    progressLabel = match?.resumeLabel,
                )
            }
            Spacer(Modifier.height(RaddTokens.Space.Sm))
        }
    }
}

/** H11: the My List strip, so tests can find it without the title. */
internal const val MyListStripTag = "homeMyListStrip"

/**
 * Mockup (Nov 2026): one Continue Watching row — full width, thumb, title,
 * remaining label, green progress bar, and the 48dp remove ×. Tapping the
 * row resumes the title; the × removes the scope (Undo on the snackbar,
 * same as before).
 */
@Composable
private fun ContinueWatchingRow(
    tile: ResumeTile,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RaddTokens.Space.Gutter, vertical = RaddTokens.Space.Xs),
        shape = RaddTokens.Shape.Card,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(RaddTokens.Space.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbModifier = Modifier
                .width(CardGeometry.CONTINUE_WATCHING_ROW_THUMB_WIDTH_DP.dp)
                .aspectRatio(CardGeometry.POSTER_ASPECT)
                .clip(RaddTokens.Shape.Input)
            if (tile.post.posterUrl != null) {
                CatalogImage(
                    url = tile.post.posterUrl,
                    contentDescription = null,
                    // A4-F1: the one call site missing a label — a failed
                    // or hung thumb painted a labelless tile while the
                    // no-URL branch below already named the title.
                    fallbackLabel = tile.post.displayTitle,
                    modifier = thumbModifier,
                )
            } else {
                ImageFallbackCard(
                    label = tile.post.displayTitle,
                    modifier = thumbModifier,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RaddTokens.Space.Md),
                verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xs),
            ) {
                Text(
                    text = tile.post.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // E2 Ratings (Nov 2026): the rail used to show where viewing
                // stopped and nothing about the title itself. The rating
                // rides one small line under the title — "★ 8.6" — and
                // only when the row actually carries one (rows written
                // before DB v12, and titles the catalog never rated, paint
                // nothing rather than a 0.0).
                UserWords.displayRating(tile.post.imdbRating)?.let { rating ->
                    Text(
                        text = "★ $rating",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                tile.resumeLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                tile.progressFraction?.let { fraction ->
                    // Round 6 (motion): the bar glides to its value on a
                    // spring — animateFloatAsState starts at the target on
                    // first composition, so a golden still shows the
                    // settled fraction. The semantics keep the true value.
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction.coerceIn(0f, 1f),
                        animationSpec = if (rememberReduceMotion()) snap() else RaddMotion.defaultSpatial(),
                        label = "continueWatchingProgress",
                    )
                    LinearProgressIndicator(
                        progress = { animatedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp) // token-exempt: progress-bar thickness, not spacing
                            .clip(RoundedCornerShape(50))
                            .semantics {
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    fraction,
                                    0f..1f,
                                )
                                contentDescription = tile.resumeLabel
                                    ?: "Continue watching progress"
                            },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface,
                    )
                }
            }
            // Audit #12: the remove is an explicit 48dp button in the
            // row's top-end corner — the same size and corner as the
            // broadcast dismiss and the trial-note dismiss — not
            // vertically centred, where it read as a third, different
            // placement.
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp).align(Alignment.Top),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_close),
                    contentDescription = "Remove ${tile.post.displayTitle} from Continue Watching",
                )
            }
        }
    }
}

/**
 * The operator's published APK is opened by the system browser/download manager via
 * ACTION_VIEW. Installing stays the platform's job — this app never asks for
 * REQUEST_INSTALL_PACKAGES and never starts an install itself.
 */
private fun openDownload(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "No browser is available to open this download.", Toast.LENGTH_LONG).show()
    false
}

/**
 * One announcement from the admin panel. The severity picks the container: the
 * operator's urgent note reads urgent on the phone too, and an info note stays
 * quiet. A broadcast is plain text from the panel — never a link, never a button —
 * so there is nothing to tap except the dismiss.
 */
/**
 * The offline strip. It only renders when something saved is on screen —
 * an offline phone with nothing saved gets the error state, not a banner
 * over a blank page. The copy says what is true: the rails below are the
 * network's and may have failed, while Continue Watching is this device's
 * own saved titles and still plays.
 */
@Composable
private fun OfflineBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RaddTokens.Space.Gutter),
        shape = RaddTokens.Shape.Input,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "You are offline — only titles saved on this device are available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = RaddTokens.Space.Md, vertical = RaddTokens.Space.Md),
        )
    }
}

/**
 * B3 (home review, Nov 2026): the empty Continue Watching rail — one
 * quiet line, never a big card. A new viewer has no history; the old
 * placeholder card hijacked the fold for a feature with nothing to show.
 * The clock icon says what the row is, the text says what to do, and
 * Browse opens Browse with no type. There is no "Continue watching"
 * heading above it — the heading belongs to the real rail, with rows
 * under it. A4-F2: the caller gates this on [HomeUiState.hasWatchHistory],
 * so it never shows on a first run — there it read as an error on a
 * populated Home, and Pick 3 already onboards a new viewer.
 */
@Composable
private fun StartWatchingPlaceholder(onBrowse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Screenshot-fix D7 (2026-11-16): 8dp vertical, not 12dp —
            // the third dead gap in the hero stack, above this row.
            .padding(horizontal = RaddTokens.Space.Gutter, vertical = RaddTokens.Space.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_action_history),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp), // token-exempt: icon size, not spacing
        )
        Text(
            text = "No active shows — start exploring picks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onBrowse) {
            Text(
                text = "Browse",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Gap A6 — the first-run "Pick 3 you like" card, redesigned (home review,
 * Nov 2026): three poster thumbnails in a row, a heart on each poster, the
 * poster opens the title. The old text rows showed no artwork, so the card
 * read as a chore list; the posters ARE the choice now. The heart saves
 * each one to My List, and "Not now" retires the card. Nothing here
 * promises personalisation — the picks ARE the start of My List, and the
 * card says exactly that.
 *
 * One-shot: the first display latches the card for the whole Home visit
 * ([onShown] fires from a LaunchedEffect) — the viewer can read it, pick
 * from it, or dismiss it. The visit ends when the card leaves composition
 * (Home left, or the card scrolled out of the feed), and the persisted
 * shown flag then keeps it away for good. The first build retired the
 * card on display instead, so it vanished within a frame of appearing
 * and no user or Robo run ever saw it; the latch is the fix.
 */
@Composable
internal fun PickThreeCard(
    candidates: List<Post>,
    isFavorite: (Post) -> Boolean,
    onToggleFavorite: (Post) -> Unit,
    onPostClick: (Post) -> Unit,
    onDismiss: () -> Unit,
    onShown: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onShown() }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RaddTokens.Shape.Card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = RaddTokens.Space.Gutter),
    ) {
        Column(modifier = Modifier.padding(RaddTokens.Space.Gutter), verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm)) {
            Text(
                text = "Pick 3 you like",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // F30 (Batch B): a heading, so the card has a screen-reader
                // label — Robo flagged this row as unlabeled.
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Tap the heart on three titles to start your My List.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
            ) {
                candidates.take(3).forEach { post ->
                    PosterCard(
                        post = post,
                        modifier = Modifier.weight(1f),
                        onClick = { onPostClick(post) },
                        isFavorite = isFavorite(post),
                        onToggleFavorite = { onToggleFavorite(post) },
                    )
                }
            }
            // M9 (home review, Nov 2026): "Not now" is a real button, not a
            // link — an outlined button with a 48dp minimum height, the
            // same floor the file's other dismissals use. A TextButton here
            // read as small print and measured ~40dp tall.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Not now") }
            }
        }
    }
}

/**
 * UI-fix P0-3: the trial note as one dismissible line, not a card. The free
 * trial is granted silently; without this line the viewer loses VIP features
 * after 3 days with no prior explanation. A dismissed note never returns.
 * The line names no quality: with the quality gate off, free playback is
 * uncapped (4K proven free 2026-11-17), so a 720p claim would be false —
 * the Settings account card follows the same rule (AccountCardCopy).
 *
 * Audit #13: the bare line read as stray small print sitting in the
 * content flow — it rides a tonal surface now, the same treatment as the
 * Live TV banner and the offline strip. Audit #12: the dismiss is an
 * explicit 48dp button in the line's end corner, like the other two
 * dismissals on this screen.
 */
@Composable
private fun TrialNoteLine(daysRemaining: Int, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RaddTokens.Space.Gutter),
        shape = RaddTokens.Shape.Input,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = RaddTokens.Space.Md, top = RaddTokens.Space.Xs, bottom = RaddTokens.Space.Xs, end = RaddTokens.Space.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (daysRemaining == 1) {
                    "Free VIP: 1 day left."
                } else {
                    "Free VIP: $daysRemaining days left."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_close),
                    contentDescription = "Dismiss trial note",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BroadcastBanner(broadcast: PanelBroadcast, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = when (broadcast.severity.lowercase()) {
        "urgent", "critical" -> scheme.errorContainer to scheme.onErrorContainer
        "warning" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().padding(horizontal = RaddTokens.Space.Gutter),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = RaddTokens.Space.Gutter, top = RaddTokens.Space.Md, bottom = RaddTokens.Space.Md, end = RaddTokens.Space.Xs),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    broadcast.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                )
                if (broadcast.body.isNotBlank()) {
                    Spacer(Modifier.height(RaddTokens.Space.Micro))
                    Text(
                        broadcast.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer,
                    )
                }
            }
            // Audit #12: the dismiss is an explicit 48dp button in the
            // banner's top-end corner — the same size and corner as the
            // Continue Watching remove and the trial-note dismiss.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_close),
                    contentDescription = "Dismiss announcement",
                    tint = onContainer,
                )
            }
        }
    }
}

@Composable
/**
 * The update dialog. A required update — this build is below the operator's
 * minimum — offers no "Later" and cannot be dismissed: the viewer cannot
 * keep using a build the operator has withdrawn.
 */
internal fun AppUpdateDialog(
    required: Boolean,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit,
    sizeBytes: Long = 0,
) {
    CenteredMockupDialog(
        onDismissRequest = if (required) ({}) else onDismiss,
        icon = ImageVector.vectorResource(R.drawable.ic_action_update),
        title = Brand.copy(
            if (required) "update_required_title" else "update_title",
            if (required) "Update required" else "A new version is ready",
        ),
        primaryLabel = Brand.copy("update_now", "Update"),
        onPrimary = onUpdateNow,
        secondaryLabel = if (required) null else "Later",
        onSecondary = onDismiss,
    ) {
        Text(
            Brand.copy(
                if (required) "update_required_message" else "update_message",
                if (required) {
                    "${Brand.name} needs an update to keep working. Download the new version to continue."
                } else {
                    "${Brand.name} has published a newer version. Download it to keep everything current."
                },
                "name" to Brand.name,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // A viewer on mobile data wants to know what the tap costs
        // before tapping it. No size published, no line — an absent
        // number is not a zero-byte download.
        if (sizeBytes > 0) {
            Text(
                text = "Download size: ${Formatter.formatShortFileSize(LocalContext.current, sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * MX #7 — Kids Lock: the slim banner that says Kids Mode is on and offers
 * the way out. One row, pinned above the feed: a lock, the words, and an
 * Exit button that raises the PIN dialog — the mode never ends on a tap
 * alone. The jade accent is the same one the player uses for its own
 * locked state, so a locked screen reads locked everywhere.
 */
@Composable
private fun KidsModeBanner(
    onExitKidsMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RaddTokens.Space.Gutter)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = RaddTokens.Space.Md, vertical = RaddTokens.Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_action_lock),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp), // token-exempt: icon size, not spacing
        )
        Text(
            text = "Kids Mode is on",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onExitKidsMode) {
            Text("Exit")
        }
    }
}

/**
 * Home fix (Nov 2026): the Library strip is gone (item 8). Its two doors —
 * On this phone, Downloads — are reachable from Settings (a persistent
 * tab), and Downloads links to Local Files too; the strip sat below all
 * the rails, where almost no one scrolled, and its original reason (the
 * paste-a-link door) is gone. The LibraryStrip and LibraryTile
 * composables were removed with it — nothing else referenced them.
 */

/**
 * UI-fix P1-H3/H4/H5: one "Browse" row of the catalog's own facet chips —
 * genres, industries, and languages (the server already serves
 * `language=`; the chips waited for a Search language filter to land on,
 * and it has now). A5 (user decision 04:42 PKT 20 Nov): the type
 * jump-offs (All / Movies / Series / Anime) are gone — they duplicated
 * the filter row above, and Home has one filter row only. Internal so
 * HomeSectionMarginsTest can pin the 16dp margin.
 */
@Composable
internal fun BrowseSection(
    facets: FacetsResponse?,
    onFacetSelected: (genre: String?, industry: String?, language: String?) -> Unit,
) {
    val chips = remember(facets) { browseFacetChips(facets) }
    Column(Modifier.padding(top = RaddTokens.Space.Xs)) {
        // Audit #15: every section heading carries the 16 dp phone margin.
        SectionHeader(title = "Browse", modifier = Modifier.padding(horizontal = RaddTokens.Space.Gutter))
        // Density (2026-11-15), revised by the home review: one scrollable
        // pill row, not two. The type jump-offs and the facet chips were two
        // 48 dp rows with an 8 dp spacer between them (104 dp); they are
        // all navigation links to the same Browse destination, so they ride
        // one row now (48 dp). The pills stay 48 dp tall on purpose: the
        // touch target is the layout size in Compose (E-T4 — 16 dp of
        // vertical padding takes the tile to >= 48 dp with the smaller
        // labelMedium text), and the Robo a11y runs pin 48 dp targets.
        // A 32 dp visual with a 48 dp hit area needs a TouchDelegate-style
        // mechanism this codebase does not have; shrinking the hit area
        // would regress E-T4. The shape is a true pill now (24 dp radius
        // on a 48 dp pill). There is no selected state to invert — every
        // chip navigates away, none filters in place.
        // Review redesign (Nov 2026): the chips are unfilled — transparent
        // with a 1 dp outline — so this row reads as navigation links and
        // never as a second filter system next to the filled filter row
        // above it. The right edge fades instead of hard-clipping the
        // last chip mid-word. A4-F3: rides [ScrollingChipRow] +
        // [RaddChip] (Navigation) now — the fade and the row are the
        // shared component, not a hand-rolled copy.
        ScrollingChipRow {
            items(chips) { chip ->
                RaddChip(
                    selected = false,
                    onClick = { onFacetSelected(chip.genre, chip.industry, chip.language) },
                    label = {
                        Text(
                            text = chip.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    style = RaddChipStyle.Navigation,
                )
            }
        }
    }
}

/** One facet chip. Internal (not private) so BrowseFacetChipsTest can read the labels. */
internal data class FacetChip(val label: String, val genre: String?, val industry: String?, val language: String?)

/**
 * The Browse row's facet chips from `GET /api/v1/facets`, pure so the
 * placeholder rule is testable without a composition: genres, industries,
 * and languages, each with a live count. A facet whose key or label is a
 * placeholder — the server serves a language facet literally labelled
 * "Unknown" with 60,226 posts behind it — is dropped, not drawn: a chip
 * that promises "Unknown · 60226" is a broken promise to browse for a
 * language nobody recorded.
 */
internal fun browseFacetChips(facets: FacetsResponse?): List<FacetChip> {
    val genreChips = facets?.genres.orEmpty()
        .filter { it.count > 0 && WireText.isKnownValue(it.key) && WireText.isKnownValue(it.label) }
        .map { FacetChip(label = "${it.label} · ${it.count}", genre = it.key, industry = null, language = null) }
    val industryChips = facets?.industries.orEmpty()
        .filter { it.count > 0 && WireText.isKnownValue(it.key) && WireText.isKnownValue(it.label) }
        .map { FacetChip(label = "${it.label} · ${it.count}", genre = null, industry = it.key, language = null) }
    val languageChips = facets?.languages.orEmpty()
        .filter { it.count > 0 && WireText.isKnownValue(it.key) && WireText.isKnownValue(it.label) }
        .map { FacetChip(label = "${it.label} · ${it.count}", genre = null, industry = null, language = it.key) }
    return genreChips + industryChips + languageChips
}

/**
 * REA #4 (Netflix, Nov 2026): the "Play Something" shuffle on the "For
 * you" header — one tap, one title, no decision tax. Same shape as the
 * [SeeAllButton] beside it (text button, primary, 48dp floor) so the
 * header reads as one row of actions, not a button and an afterthought.
 * Text-only on purpose: the shuffle icon lives in material-icons-extended,
 * which this module does not depend on, and the words say what the tap
 * does without one.
 */
@Composable
private fun PlaySomethingButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = RaddTokens.Space.Md, vertical = RaddTokens.Space.Xs),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            stringResource(R.string.player_play_something),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun HomeRailSection(
    section: HomeSection,
    index: Int = 0,
    onPostClick: (Post) -> Unit,
    /** Home fix (Nov 2026): direct play — the tile's play button. */
    onPlayClick: (Post) -> Unit = {},
    /** Adaptive (Nov 2026): the rail's cell width — wider on a tablet. */
    itemWidthDp: Int = CardGeometry.RAIL_CARD_WIDTH_DP,
    onSeeAll: () -> Unit,
    showSeeAll: Boolean = true,
    onRetry: () -> Unit,
    /** REA #4: the "Play Something" shuffle for the "For you" row only. */
    onPlaySomething: (() -> Unit)? = null,
    /**
     * Round 6 (motion): the shared-element scopes — see [HomeScreen].
     * Both null by default; dormant until infra wires them.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    // UI-fix P1-X1: rails arrive staggered (index * 60 ms, fade + a short
    // rise — the Region screen's index * 90 pattern, extended to rails).
    // Reduced-motion users get a plain fade, never the rise.
    // Home fix (Nov 2026): the entrance is spring-based now (M3 Expressive
    // springs — [RaddMotion.defaultSpatial] for the rise,
    // [RaddMotion.defaultEffects] for the fade), not a tween: the old
    // tween + EmphasizedDecelerate easing was the only non-spring motion
    // on Home. The stagger stays — a short delay before the rail becomes
    // visible, since a spring carries no delay of its own. (A lazy item
    // that scrolls out and back re-plays its entrance — the list recycles
    // the slot — a subtle fade, the standard lazy-list trade.)
    // Audit #3: the stagger is a real-time delay, and a snapshot renders
    // with a frozen clock — Paparazzi never advances it — so in a golden
    // every rail stays invisible, skeletons included, and the loading
    // state reads as half a dead black page. On a phone the delays are
    // 0–540 ms and the skeletons paint at once; the gap is the preview,
    // not the app. In inspection mode the entrance is already over: the
    // rail renders immediately, so the golden shows what the phone shows.
    // Paparazzi does not set inspection mode itself, so the snapshot test
    // provides it (CompositionLocalProvider, per the Paparazzi docs).
    // Runtime behaviour is unchanged.
    val inInspectionMode = LocalInspectionMode.current
    val reduceMotion = rememberReduceMotion()
    var visible by remember { mutableStateOf(inInspectionMode) }
    LaunchedEffect(Unit) {
        if (inInspectionMode) return@LaunchedEffect
        delay(index * 60L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
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
    ) {
    when {
        section.error != null && section.items.isEmpty() -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = RaddTokens.Space.Gutter, vertical = RaddTokens.Space.Md)) {
                SectionHeader(title = section.title)
                Text(
                    text = section.error ?: "This row could not load.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(RaddTokens.Space.Sm))
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
        }
        section.loading && section.items.isEmpty() -> {
            Column {
                SectionHeader(title = section.title, modifier = Modifier.padding(horizontal = RaddTokens.Space.Gutter))
                PosterRailSkeleton()
                // The loaded rail ends with this 8dp spacer — without it the
                // page grows 8dp at this rail when its rows arrive.
                Spacer(Modifier.height(RaddTokens.Space.Sm))
            }
        }
        section.items.isNotEmpty() -> {
            Column {
                SectionHeader(
                    title = section.title,
                    modifier = Modifier.padding(horizontal = RaddTokens.Space.Gutter),
                    // E2 "For you": the reason line under the rail title
                    // ("Because you watched One Piece"). Ordinary rails
                    // carry no subtitle, so nothing else moves.
                    subtitle = section.subtitle,
                    // Robo run 6: every rail's "See all" announced identically.
                    // The button speaks its section, so Movies and Series are
                    // distinct; the painted text stays the short "See all".
                    // H10: a rail ordered by a sort Browse cannot reproduce
                    // gets no "See all" at all — the trending sort is
                    // reproducible, so that rail keeps its.
                    // REA #4: the "For you" header also carries the shuffle —
                    // the pool it draws from — beside "See all" when the row
                    // has one; other rails keep the single action they had.
                    action = when {
                        section.forYou && onPlaySomething != null -> {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PlaySomethingButton(onClick = onPlaySomething)
                                    if (showSeeAll) {
                                        SeeAllButton(
                                            onClick = onSeeAll,
                                            contentDescription = "See all ${section.title}",
                                        )
                                    }
                                }
                            }
                        }
                        showSeeAll -> {
                            { SeeAllButton(onClick = onSeeAll, contentDescription = "See all ${section.title}") }
                        }
                        else -> null
                    },
                )
                val isRanked = section.sort == "trending"
                val indexedItems = section.items.mapIndexed { itemIndex, post -> itemIndex to post }
                MediaRail(
                    items = indexedItems,
                    key = { (_, post) -> post.id },
                    itemWidthDp = itemWidthDp,
                ) { (itemIndex, post) ->
                    PosterCard(
                        post = post,
                        onClick = { onPostClick(post) },
                        // Home fix (Nov 2026): direct play — the tile's
                        // play button opens Detail with autoplay.
                        onPlayClick = { onPlayClick(post) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        rank = if (isRanked && itemIndex < 10) itemIndex + 1 else null,
                        // Audit #8: the full "2023 · ★ 7.0 · Movie" line
                        // truncates the type word mid-word ("Mo…") on a
                        // 120dp rail card — the compact line drops it, so
                        // year · rating fits. The type stays in the tile's
                        // spoken sentence and on Detail. Two title lines
                        // for the same reason: one line truncates long
                        // titles mid-word, and the slot stays uniform
                        // because every tile in the rail gets two.
                        compactMeta = true,
                        titleMaxLines = 2,
                        // REA #2: the "For you" row's Match % rides its
                        // tiles; every other rail carries no scores, so
                        // this reads null there and paints nothing.
                        matchPercent = section.matchScores[post.id],
                    )
                }
                // The rows here are real and from this page; the refresh that failed is a
                // separate fact and has to be said, or the screen claims a freshness it
                // does not have.
                section.refreshFailure?.let { reason ->
                    InlineLoadFailure(message = reason, onRetry = onRetry)
                }
                Spacer(Modifier.height(RaddTokens.Space.Sm))
            }
        }
    }
    }
}
