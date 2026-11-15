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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.raddstream.app.brand.Brand
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.network.models.FacetsResponse
import com.raddstream.app.network.models.PanelBroadcast
import com.raddstream.app.network.models.Post
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
import com.raddstream.app.ui.components.SectionHeader
import com.raddstream.app.ui.components.SeeAllButton
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.components.prefetchHeroArt
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddSignalGold
import com.raddstream.app.update.InAppUpdate
import com.raddstream.app.viewmodel.HomeFilter
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import com.raddstream.app.viewmodel.ResumeTile
import com.raddstream.app.viewmodel.HomeViewModel
import com.raddstream.app.viewmodel.homeFilterMatches
import com.raddstream.app.viewmodel.selectHeroSection
import com.raddstream.app.viewmodel.visibleHomeRows

private data class JumpOff(val label: String, val type: String?)

/** Slides in the hero banner. Six is enough to feel like a rail, few enough that the
 *  auto-advance cycle does not outlast attention. */
private const val HERO_LIMIT = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearchClick: () -> Unit,
    /** The search bar's submit: a query goes straight to Search with it applied. */
    onSearchQuery: (String) -> Unit = { onSearchClick() },
    /** False when the operator's `feature_flags.search` is off: the entry point disappears. */
    searchEnabled: Boolean = true,
    onPostClick: (Post) -> Unit = {},
    /** Session 121 (P-26): the hero's Watch Now — opens the title and starts it. */
    onWatchNowClick: (Post) -> Unit = onPostClick,
    onBrowseType: (String?) -> Unit = {},
    /**
     * A facet chip's jump-off: the genre, industry and/or language to land
     * on Search with already applied. Null means "no filter of that kind".
     */
    onFacetSelected: (genre: String?, industry: String?, language: String?) -> Unit = { _, _, _ -> },
    onSetupServerClick: () -> Unit = {},
    /** The phone's own videos (Movies/ and Download/), listed from MediaStore. */
    onOpenLocalFiles: () -> Unit = {},
    /** Kept-for-offline titles (Media Vault). */
    onOpenDownloads: () -> Unit = {},
    /** Session 123: the check-in card opens the standalone check-in screen. */
    onOpenCheckIn: () -> Unit = {},
    /** Session 123 (HO-7): a guest's way into an account — Settings, where sign-in lives. */
    onOpenSignIn: () -> Unit = {},
    /** Mockup (Nov 2026): the header's Coin Wallet pill — the wallet/profile screen. */
    onOpenWallet: () -> Unit = {},
    /** The operator's hero knobs from the panel theme: 0 hides the hero slot. */
    heroMax: Int = HERO_LIMIT,
    /** M5 (home review, Nov 2026): manual-swipe by default — see [HeroCarousel]. */
    heroAutoplay: Boolean = false,
    /** Samsung RTL (v1.1.2, Issue 3): 8 s, not 5 — the operator's panel value
     *  still wins when published; this is the default when it has not been. */
    heroIntervalMs: Long = 8_000L,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
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

    // Mockup (Nov 2026): no top bar any more — the header (check-in pill,
    // centred logo, Coin Wallet pill) lives in HomeContent, pinned above the
    // scroll, so the Scaffold keeps only the snackbar. The refresh icon went
    // with the bar: it read as operator chrome, and a refresh control with
    // no label next to it is not a viewer control.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        HomeContent(
            state = state,
            modifier = Modifier.padding(contentPadding),
            searchEnabled = searchEnabled,
            heroMax = heroMax,
            heroAutoplay = heroAutoplay,
            heroIntervalMs = heroIntervalMs,
            onPostClick = onPostClick,
            onWatchNowClick = onWatchNowClick,
            onBrowseType = onBrowseType,
            onFacetSelected = onFacetSelected,
            onSetupServerClick = onSetupServerClick,
            onOpenLocalFiles = onOpenLocalFiles,
            onOpenDownloads = onOpenDownloads,
            onOpenCheckIn = onOpenCheckIn,
            onOpenSignIn = onOpenSignIn,
            onOpenWallet = onOpenWallet,
            onRefresh = viewModel::refresh,
            onRetrySection = viewModel::retrySection,
            onDismissBroadcast = { viewModel.dismissBroadcast(it) },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onRemoveFromContinueWatching = { viewModel.removeFromContinueWatching(it) },
            onDismissPickThree = viewModel::dismissPickThree,
            onPickThreeShown = viewModel::markPickThreeShown,
            onPickThreeVisitEnded = viewModel::endPickThreeVisit,
            onDismissTrialNote = viewModel::dismissTrialNote,
            onClaimDailyLogin = viewModel::claimDailyLogin,
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
@Composable
internal fun HomeContent(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    searchEnabled: Boolean = true,
    heroMax: Int = HERO_LIMIT,
    /** M5 (home review, Nov 2026): manual-swipe by default — see [HeroCarousel]. */
    heroAutoplay: Boolean = false,
    heroIntervalMs: Long = 8_000L,
    onPostClick: (Post) -> Unit = {},
    onWatchNowClick: (Post) -> Unit = onPostClick,
    onBrowseType: (String?) -> Unit = {},
    onFacetSelected: (genre: String?, industry: String?, language: String?) -> Unit = { _, _, _ -> },
    onSetupServerClick: () -> Unit = {},
    onOpenLocalFiles: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenCheckIn: () -> Unit = {},
    onOpenSignIn: () -> Unit = {},
    /** Mockup (Nov 2026): the header's Coin Wallet pill — the wallet/profile screen. */
    onOpenWallet: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetrySection: (String) -> Unit = {},
    onDismissBroadcast: (Int) -> Unit = {},
    onToggleFavorite: (Post) -> Unit = {},
    onRemoveFromContinueWatching: (ResumeTile) -> Unit = {},
    onDismissPickThree: () -> Unit = {},
    onPickThreeShown: () -> Unit = {},
    onPickThreeVisitEnded: () -> Unit = {},
    onDismissTrialNote: () -> Unit = {},
    onClaimDailyLogin: () -> Unit = {},
) {
    // Mockup (Nov 2026): the header replaces the M12 banner — one compact
    // row pinned above the scroll (check-in pill, centred logo, Coin Wallet
    // pill), and the scroll starts under it. The body below keeps its
    // indentation (it was one Column deep); only the nesting changed. The
    // Scaffold no longer has a top bar, so the outer Column carries the
    // status-bar inset itself — without it the header would draw under the
    // status bar.
    var showCheckInSheet by remember { mutableStateOf(false) }
    // The filter chips are UI-local state: they filter the rails already on
    // screen, so no ViewModel round-trip and no reload on a tap.
    var filter by remember { mutableStateOf(HomeFilter.All) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .then(modifier),
    ) {
        HomeHeader(
            streakCount = state.streakCount,
            serverStreak = state.serverStreak,
            streakLadder = state.streakLadder,
            coinBalance = state.coinBalance,
            signedIn = state.signedIn,
            claimedToday = state.claimedToday,
            paywallVisible = state.paywallVisible,
            onOpenSheet = { showCheckInSheet = true },
            onOpenWallet = onOpenWallet,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
        if (state.isRefreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // Mockup (Nov 2026): the filter chips ride at the top of the feed —
        // ALL / MOVIES / SERIES / ANIME / KPOP — and filter the rails in
        // place. They scroll away with the feed; the header above them stays.
        HomeFilterRow(
            selected = filter,
            onSelect = { chosen -> filter = if (chosen == filter) HomeFilter.All else chosen },
        )
        Spacer(Modifier.height(8.dp))

        // M6 (home review, Nov 2026): the home search bar is gone. It was
        // a third door to the same place — the Search tab is the empty-query
        // door and the bar only carried a typed query into it — and Netflix
        // 2026 ships no bar on Home. The first content is the hero (or the
        // filter chips when Home has loaded before). SearchField itself stays:
        // the Search screen still uses it.
        // Mockup (Nov 2026): the sync line is gone too — "Updated just now"
        // is operator chrome, not something a viewer acts on. The offline
        // banner below still covers the case that matters (no connection),
        // and homeSyncLine stays in the ViewModel, unused for now.

        // The operator's channel to every phone: a broadcast shows above the
        // catalog, even when the catalog itself failed to load — that is
        // exactly when the operator has something to say.
        state.broadcasts.forEach { broadcast ->
            BroadcastBanner(
                broadcast = broadcast,
                onDismiss = { onDismissBroadcast(broadcast.id) },
            )
            Spacer(Modifier.height(8.dp))
        }

        // Fatal error only when *every* rail failed and none has data to show.
        val anyVisible = state.sections.any { it.visible }
        if (state.fatal != null && !anyVisible) {
            ErrorState(
                message = state.fatal ?: "Couldn't reach the catalog.",
                onRetry = onRefresh,
                // The address is the one thing a person can fix themselves, and
                // Settings is four taps away. Offer it where the failure is seen.
                secondaryLabel = "Try a different address",
                onSecondary = onSetupServerClick,
            )
        } else {
            // B3 (home review, Nov 2026): the Continue Watching rows are
            // pinned under the hero, not above it — they used to sit first
            // and only exist once resume data existed, so a fresh install
            // never showed them and a new viewer never learned the feature
            // is there. The rows live below the hero now; with rows they
            // are the real rows, and without rows they are a "Start watching"
            // placeholder whose CTA opens Browse.
            val shownRows = visibleHomeRows(state.sections, state.continueWatching.isNotEmpty())
            val hasSaved = state.continueWatching.isNotEmpty() || anyVisible
            if (state.isOffline && hasSaved) {
                OfflineBanner()
                Spacer(Modifier.height(8.dp))
            }
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
            val heroSection = if (heroMax > 0) selectHeroSection(state.sections, currentCalendarYear()) else null
            val heroItems = heroSection?.items.orEmpty().take(heroMax)
            // Hero (Nov 2026): warm the hero art the moment the hero items are
            // known, so first paint has art, not placeholders. The helper is a
            // no-op for art-less items and never throws.
            val prefetchContext = LocalContext.current
            LaunchedEffect(heroItems) {
                if (heroItems.isNotEmpty()) prefetchHeroArt(prefetchContext, heroItems)
            }
            if (heroMax > 0) {
                if (heroItems.isNotEmpty()) {
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
                    )
                    Spacer(Modifier.height(12.dp))
                } else if (state.sections.any { it.loading }) {
                    // Hold the hero's footprint while the first rail lands, so the page
                    // never flashes a bare background and then jumps when it fills in.
                    HeroSkeleton()
                    Spacer(Modifier.height(12.dp))
                }
            }
            // B3: the Continue Watching slot is pinned here, under the hero,
            // and always present. With rows they are the real rows; without
            // rows it is the "Start watching" placeholder — but only once
            // the catalog has landed (anyVisible). On a blank or failed
            // load the empty/error state speaks instead, and during the first
            // load the hero skeleton above holds the slot.
            if (state.continueWatching.isNotEmpty()) {
                Column {
                    SectionHeader(
                        title = "Continue watching",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    // Mockup (Nov 2026): full-width rows, not a poster rail —
                    // thumb, title, remaining label, green progress bar. Three
                    // at most: the section is a resume list, not a catalog.
                    // Keyed on the scope, not the work: two rows of the same
                    // series are two different episodes.
                    state.continueWatching.take(3).forEach { tile ->
                        ContinueWatchingRow(
                            tile = tile,
                            onClick = { onPostClick(tile.post) },
                            // Phase 7 (D1): the × removes this scope from
                            // Continue Watching — server row first, then
                            // the local rows, with Undo on the snackbar.
                            onRemove = { onRemoveFromContinueWatching(tile) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            } else if (anyVisible) {
                StartWatchingPlaceholder(onBrowse = { onBrowseType(null) })
                Spacer(Modifier.height(8.dp))
            }
            // Gap A6: the first-run card now rides BELOW the hero — a
            // new viewer meets a movie first, and the picks are still
            // the first thing that asks anything of them.
            if (state.showPickThree) {
                // The visit latch clears when the card leaves composition:
                // the viewer left Home, or scrolled the card out of the
                // feed. Either way the visit is over, and the persisted
                // shown flag keeps the card away on the next visit.
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
                Spacer(Modifier.height(8.dp))
            }

            // The free trial note, as one dismissible line below the
            // hero instead of a card above it. A dismissed note never
            // returns.
            state.trialDaysRemaining?.let { days ->
                TrialNoteLine(
                    daysRemaining = days,
                    onDismiss = onDismissTrialNote,
                )
                Spacer(Modifier.height(8.dp))
            }

            // P1-H3: one "Browse" row, not two near-identical chip rows.
            BrowseSection(
                facets = state.facets,
                onBrowseType = onBrowseType,
                onFacetSelected = onFacetSelected,
            )
            Spacer(Modifier.height(8.dp))

            // At most 10 rows total, CW included: a panel that publishes 12
            // sections does not get 12 rails on a phone, and the first rails win.
            // Audit #18: the same title never appears twice on Home. The seen
            // set is seeded with Continue Watching and the hero, so a rail
            // never repeats a tile or a hero poster; HO-1 (hero out of its
            // own rail) is now a special case of the same rule, by work
            // identity — not by row id, because one film filed twice has
            // two row ids.
            var seenWorks = state.continueWatching
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
            renderedRows.forEachIndexed { index, section ->
                HomeRailSection(
                    section = section,
                    index = index,
                    onPostClick = onPostClick,
                    onSeeAll = {
                        if (section.industry != null) {
                            onFacetSelected(null, section.industry, null)
                        } else {
                            onBrowseType(section.type)
                        }
                    },
                    // A sorted rail has no Browse equivalent (Browse takes a
                    // type but no sort), so "See all" would silently re-sort
                    // it to freshest-first. Offer it only where the order
                    // survives the jump.
                    showSeeAll = section.browseEquivalent,
                    onRetry = { onRetrySection(section.key) },
                )
            }
            // A filter that hides every rail is not a broken catalog — say
            // which filter came up empty, and offer the way back to everything.
            // This never fires while a rail is still loading: an empty filter
            // on a half-loaded feed would blame the filter for the network.
            if (filter != HomeFilter.All && renderedRows.isEmpty() &&
                state.sections.none { it.loading } && state.fatal == null
            ) {
                EmptyState(
                    title = filter.emptyMessage,
                    message = "Try another filter, or show everything.",
                    action = {
                        OutlinedButton(onClick = { filter = HomeFilter.All }) {
                            Text("Show all")
                        }
                    },
                )
            }
            // A successful empty load is a blank page today: no rails, no
            // error, nothing to tap. Say so, and point at the one fix the
            // viewer can do — a different address. Only when no filter is
            // active: a filtered empty feed has its own message above.
            if (filter == HomeFilter.All && renderedRows.isEmpty() && state.continueWatching.isEmpty() &&
                state.sections.none { it.loading } && state.fatal == null
            ) {
                EmptyState(
                    title = "Nothing here yet",
                    message = "No titles to show right now.",
                    action = {
                        OutlinedButton(onClick = onSetupServerClick) {
                            Text("Try a different address")
                        }
                    },
                )
            }
            // Mockup (Nov 2026): Your Library rides BELOW the rails now, at
            // the bottom of the feed — the phone's own videos, and downloads.
            if (searchEnabled) {
                LibraryStrip(
                    onOpenLocalFiles = onOpenLocalFiles,
                    onOpenDownloads = onOpenDownloads,
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    if (showCheckInSheet) {
        CheckInSheet(
            streakCount = state.streakCount,
            serverStreak = state.serverStreak,
            streakLadder = state.streakLadder,
            coinBalance = state.coinBalance,
            signedIn = state.signedIn,
            claimedToday = state.claimedToday,
            isClaiming = state.isClaiming,
            claimMessage = state.claimMessage,
            onClaim = onClaimDailyLogin,
            onOpenCheckIn = { showCheckInSheet = false; onOpenCheckIn() },
            onOpenSignIn = onOpenSignIn,
            onDismiss = { showCheckInSheet = false },
        )
    }
}

/** The header's tap targets for the header test. */
internal const val CheckInPillTag = "checkInPill"
internal const val CoinWalletPillTag = "coinWalletPill"
internal const val HomeLogoTag = "homeLogo"
internal const val HomeFilterRowTag = "homeFilterRow"

/**
 * Mockup (Nov 2026): the Home header — one compact row pinned above the
 * scroll, replacing the M12 banner. Left: the check-in pill (calendar icon,
 * two lines, same states the banner had — the day number is still the
 * server's claimed count, never the device's open count — and a tap opens
 * the same [CheckInSheet]; the claim itself happens inside the sheet, so
 * the banner's inline Claim button is gone with the banner). Centre: the
 * two-line logo, RADD in white bold over STREAM in jade. Right: the Coin
 * Wallet pill (gold coin, balance), opening the wallet/profile screen.
 *
 * Free launch: while the quality gate is OFF both pills hide — the same
 * rule the banner lived by — and the centred logo stays on its own. The
 * logo is a weighted centred column, so it stays centred whether one pill,
 * both, or neither is showing.
 */
@Composable
internal fun HomeHeader(
    streakCount: Int,
    serverStreak: Int?,
    streakLadder: Map<Int, Int>,
    coinBalance: Int,
    signedIn: Boolean,
    claimedToday: Boolean,
    paywallVisible: Boolean,
    onOpenSheet: () -> Unit,
    onOpenWallet: () -> Unit,
) {
    val targetDays = streakLadder.keys.maxOrNull() ?: 7
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paywallVisible && streakLadder.isNotEmpty()) {
            Surface(
                onClick = onOpenSheet,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag(CheckInPillTag),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = "Check-in:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                !signedIn -> "Sign in"
                                claimedToday -> "Claimed"
                                signedIn && serverStreak != null -> "Day $serverStreak of $targetDays"
                                else -> "Claim today"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).testTag(HomeLogoTag),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val nameParts = Brand.name.uppercase().split(" ")
            Text(
                text = nameParts.getOrNull(0) ?: "RADD",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = nameParts.getOrNull(1) ?: "STREAM",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (paywallVisible) {
            Surface(
                onClick = onOpenWallet,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag(CoinWalletPillTag),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // No coin drawable exists in the repo: the gold circle
                    // with a black "C" is the coin. The letter is decorative
                    // — the pill's own lines already say "Coin Wallet" — so
                    // it is cleared from the accessibility tree.
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(RaddSignalGold)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "C",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                        )
                    }
                    Column {
                        Text(
                            text = "COIN WALLET",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "$coinBalance coins",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mockup (Nov 2026): the filter chips under the header — ALL / MOVIES /
 * SERIES / ANIME / KPOP — 48dp tall, horizontally scrollable, filtering the
 * rails in place. The active chip is jade-filled; tapping it again restores
 * ALL (that toggle lives in [HomeContent], where the state sits).
 */
@Composable
internal fun HomeFilterRow(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag(HomeFilterRowTag),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HomeFilter.entries) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        filter.label,
                        fontWeight = if (filter == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.height(48.dp).testTag("filterChip_${filter.name}"),
            )
        }
    }
}

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
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbModifier = Modifier
                .width(CardGeometry.CONTINUE_WATCHING_ROW_THUMB_WIDTH_DP.dp)
                .aspectRatio(CardGeometry.POSTER_ASPECT)
                .clip(RoundedCornerShape(12.dp))
            if (tile.post.posterUrl != null) {
                CatalogImage(
                    url = tile.post.posterUrl,
                    contentDescription = null,
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
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = tile.post.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface,
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Close,
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
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "You are offline — only titles saved on this device are available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * B3 — the empty Continue Watching rail. Same header as the real rail, so
 * the slot reads as the feature, not a banner: a title, one honest line,
 * and a Browse CTA. The button is a primary action, so it keeps the
 * primary container; nothing else here is jade. The CTA opens Browse with
 * no type — the "browse/all" landing — because there is no title yet to
 * narrow it by.
 */
@Composable
private fun StartWatchingPlaceholder(onBrowse: () -> Unit) {
    Column {
        SectionHeader(
            title = "Continue watching",
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Start watching",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Pick something to watch and it will wait for you here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onBrowse,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Browse titles")
                }
            }
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
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
 */
@Composable
private fun TrialNoteLine(daysRemaining: Int, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (daysRemaining == 1) {
                "Free VIP: 1 day left — after that, free playback stays at 720p."
            } else {
                "Free VIP: $daysRemaining days left — after that, free playback stays at 720p."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss trial note",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    broadcast.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                )
                if (broadcast.body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        broadcast.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
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
        icon = Icons.Default.AutoAwesome,
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
 * The check-in card's day line, as a pure function so the wording is pinned
 * by a test (HO-6): the day number is the server's claimed count, never the
 * device's open count; until the server answers, no day number is printed
 * at all; a guest reads the honest local wording. A streak past the ladder
 * reads complete, never an eighth promise. No coin amounts anywhere: the
 * claim still pays the server's ladder, but the sheet shows the streak,
 * not the price.
 */
internal fun checkInDayLine(
    signedIn: Boolean,
    serverStreak: Int?,
    streakCount: Int,
    targetDays: Int,
): String = when {
    signedIn && serverStreak != null ->
        "Day ${serverStreak.coerceIn(1, targetDays)} of $targetDays — keep the streak going."
    signedIn ->
        "Check in to keep your streak going."
    else ->
        "You've opened the app $streakCount days in a row."
}

/**
 * The claim button's label (HO-8): the mockup's plain "Check in" — the
 * button never names an amount, so there is nothing to invent and nothing
 * to keep in step with the ladder.
 */
internal fun claimButtonLabel(
    isClaiming: Boolean,
): String {
    if (isClaiming) return "Claiming…"
    return "Check in"
}

/**
 * The check-in ladder as a bottom sheet: the banner's tap target. Same state
 * and same claim as the banner — the sheet adds the ladder rows, so the
 * viewer sees what each day pays before claiming. "See all rewards" leaves
 * for the standalone check-in screen; the sheet closes first, so coming
 * back does not find it still open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckInSheet(
    streakCount: Int,
    serverStreak: Int?,
    streakLadder: Map<Int, Int>,
    coinBalance: Int,
    signedIn: Boolean,
    claimedToday: Boolean,
    isClaiming: Boolean,
    claimMessage: String?,
    onClaim: () -> Unit,
    onOpenCheckIn: () -> Unit = {},
    onOpenSignIn: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val targetDays = streakLadder.keys.maxOrNull() ?: 7
    val currentStreak = serverStreak ?: 0
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CheckInSheetBody(
            streakCount = streakCount,
            serverStreak = serverStreak,
            streakLadder = streakLadder,
            coinBalance = coinBalance,
            signedIn = signedIn,
            claimedToday = claimedToday,
            isClaiming = isClaiming,
            claimMessage = claimMessage,
            onClaim = onClaim,
            onOpenCheckIn = onOpenCheckIn,
            onOpenSignIn = onOpenSignIn,
        )
    }
}

/**
 * The 7-day ladder as circles, one per day: a claimed day is a jade disc
 * with a check, a day still to come is a dark disc with its number, and
 * the day's label sits underneath. Shared by the sheet and the standalone
 * screen, so the two can never disagree about what a claimed day looks
 * like. No amounts anywhere — the circles say which days are done, not
 * what they paid.
 */
@Composable
internal fun CheckInDayCircles(
    currentStreak: Int,
    targetDays: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (day in 1..targetDays) {
            val claimed = day <= currentStreak
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (claimed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape,
                        )
                        .semantics {
                            contentDescription = if (claimed) {
                                "Day $day claimed"
                            } else {
                                "Day $day not claimed"
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (claimed) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Text(
                            "$day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "Day $day",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (claimed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * The sheet's content on its own, so the ladder and the claim are pinned by
 * a test without opening a sheet (the Search filter sheet does the same).
 */
@Composable
internal fun CheckInSheetBody(
    streakCount: Int,
    serverStreak: Int?,
    streakLadder: Map<Int, Int>,
    coinBalance: Int,
    signedIn: Boolean,
    claimedToday: Boolean,
    isClaiming: Boolean,
    claimMessage: String?,
    onClaim: () -> Unit,
    onOpenCheckIn: () -> Unit = {},
    onOpenSignIn: () -> Unit = {},
) {
    val targetDays = streakLadder.keys.maxOrNull() ?: 7
    val currentStreak = serverStreak ?: 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            Text(
                text = "Daily check-in",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            CheckInDayCircles(
                currentStreak = currentStreak,
                targetDays = targetDays,
            )
            Text(
                text = when {
                    !signedIn -> "Sign in to check in."
                    claimedToday -> claimMessage ?: "Claimed for today."
                    else -> checkInDayLine(signedIn, serverStreak, streakCount, targetDays)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !signedIn -> {
                    Button(
                        onClick = onOpenSignIn,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Sign in to check in")
                    }
                }
                claimedToday -> {
                    Text(
                        text = claimMessage ?: "Claimed for today — come back tomorrow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Button(
                        onClick = onClaim,
                        enabled = !isClaiming,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(claimButtonLabel(isClaiming))
                    }
                }
            }
            // The mockup shows no link here, but the sheet is the Home
            // path to the standalone screen — dropping it would strand
            // the full ladder. It stays, as the one quiet row.
            TextButton(
                onClick = onOpenCheckIn,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("See all rewards")
            }
    }
}

/**
 * Your Library: the phone's own videos, and kept downloads. Two doors, both
 * inside the app — no pasted-link door, no web browsing.
 */
@Composable
internal fun LibraryStrip(
    onOpenLocalFiles: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        // Audit #15: every section heading carries the 16 dp phone margin —
        // this one used to start at x=0 with its "Y" cut off.
        SectionHeader(title = "Your Library", modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTile(
                label = "ON THIS PHONE",
                icon = Icons.Default.Folder,
                onClick = onOpenLocalFiles,
                modifier = Modifier.weight(1f),
            )
            LibraryTile(
                label = "DOWNLOADS",
                icon = Icons.Default.Download,
                onClick = onOpenDownloads,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LibraryTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        // F30 (Batch B): the tile is a tap target with no spoken name of its
        // own — its icon is decorative and its label is a child Text, so
        // Robo flagged the "Your library" row as unlabeled. The merged node
        // speaks the label. (Surface's onClick overload takes no onClickLabel
        // in this M3 version — that parameter lives on Modifier.clickable —
        // so the name rides on semantics instead.)
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * UI-fix P1-H3/H4/H5: one "Browse" row, not two near-identical chip rows
 * ("Browse by genre" over "Browse by type"). Type chips first, then the
 * catalog's own facet chips — genres, industries, and languages (the server
 * already serves `language=`; the chips waited for a Search language filter
 * to land on, and it has now). "Seasons" is a catalog ingest state, not
 * something a viewer browses for — it reads "Complete series" here and in
 * Search. Internal so HomeSectionMarginsTest can pin the 16dp margin.
 */
@Composable
internal fun BrowseSection(
    facets: FacetsResponse?,
    onBrowseType: (String?) -> Unit,
    onFacetSelected: (genre: String?, industry: String?, language: String?) -> Unit,
) {
    val chips = remember(facets) {
        val genreChips = facets?.genres.orEmpty()
            .filter { it.count > 0 }
            .map { FacetChip(label = "${it.label} · ${it.count}", genre = it.key, industry = null, language = null) }
        val industryChips = facets?.industries.orEmpty()
            .filter { it.count > 0 }
            .map { FacetChip(label = "${it.label} · ${it.count}", genre = null, industry = it.key, language = null) }
        val languageChips = facets?.languages.orEmpty()
            .filter { it.count > 0 }
            .map { FacetChip(label = "${it.label} · ${it.count}", genre = null, industry = null, language = it.key) }
        genreChips + industryChips + languageChips
    }
    Column(Modifier.padding(top = 4.dp)) {
        // Audit #15: every section heading carries the 16 dp phone margin.
        SectionHeader(title = "Browse", modifier = Modifier.padding(horizontal = 16.dp))
        // Density (2026-11-15): one scrollable pill row, not two. The type
        // jump-offs and the facet chips were two 48 dp rows with an 8 dp
        // spacer between them (104 dp); they are all navigation links to
        // the same Browse destination, so they ride one row now (48 dp).
        // The pills stay 48 dp tall on purpose: the touch target is the
        // layout size in Compose (E-T4 — 14 dp of vertical padding takes
        // the tile to >= 48 dp), and the Robo a11y runs pin 48 dp targets.
        // A 32 dp visual with a 48 dp hit area needs a TouchDelegate-style
        // mechanism this codebase does not have; shrinking the hit area
        // would regress E-T4. The shape is a true pill now (24 dp radius
        // on a 48 dp pill). There is no selected state to invert — every
        // chip navigates away, none filters in place.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .horizontalScrollPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            browseTypeTiles.forEach { tile ->
                Surface(
                    onClick = { onBrowseType(tile.type) },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = tile.label,
                        style = MaterialTheme.typography.labelLarge,
                        // E-T4: 14dp of vertical padding takes the tile to >= 48dp.
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
            chips.forEach { chip ->
                Surface(
                    onClick = { onFacetSelected(chip.genre, chip.industry, chip.language) },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

private data class FacetChip(val label: String, val genre: String?, val industry: String?, val language: String?)

/** The browse type jump-offs. "Complete series" is the viewer's word for `complete_season`. */
private val browseTypeTiles = listOf(
    JumpOff("All", null),
    JumpOff("Movies", "movie"),
    JumpOff("Series", "series"),
    JumpOff("Anime", "anime"),
    JumpOff("Complete series", "complete_season"),
)

private fun Modifier.horizontalScrollPadding(): Modifier = this.padding(horizontal = 16.dp)

@Composable
private fun HomeRailSection(
    section: HomeSection,
    index: Int = 0,
    onPostClick: (Post) -> Unit,
    onSeeAll: () -> Unit,
    showSeeAll: Boolean = true,
    onRetry: () -> Unit,
) {
    // UI-fix P1-X1: rails arrive staggered (index * 60 ms, fade + a short
    // rise — the Region screen's index * 90 pattern, extended to rails).
    // Reduced-motion users get a plain fade, never the rise.
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = RaddMotion.DurationDefault,
                delayMillis = index * 60,
            ),
        ) + if (reduceMotion) {
            EnterTransition.None
        } else {
            slideInVertically(
                initialOffsetY = { it / 4 },
                animationSpec = tween(
                    durationMillis = RaddMotion.DurationDefault,
                    delayMillis = index * 60,
                    easing = RaddMotion.EmphasizedDecelerate,
                ),
            )
        },
    ) {
    when {
        section.error != null && section.items.isEmpty() -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                SectionHeader(title = section.title)
                Text(
                    text = section.error ?: "This row could not load.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
        }
        section.loading && section.items.isEmpty() -> {
            Column {
                SectionHeader(title = section.title, modifier = Modifier.padding(horizontal = 16.dp))
                PosterRailSkeleton()
                // The loaded rail ends with this 8dp spacer — without it the
                // page grows 8dp at this rail when its rows arrive.
                Spacer(Modifier.height(8.dp))
            }
        }
        section.items.isNotEmpty() -> {
            Column {
                SectionHeader(
                    title = section.title,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    // Robo run 6: every rail's "See all" announced identically.
                    // The button speaks its section, so Movies and Series are
                    // distinct; the painted text stays the short "See all".
                    // A sorted rail has no Browse equivalent (Browse takes a
                    // type but no sort), so it gets no "See all" at all.
                    action = if (showSeeAll) {
                        { SeeAllButton(onClick = onSeeAll, contentDescription = "See all ${section.title}") }
                    } else {
                        null
                    },
                )
                val isRanked = section.sort == "trending"
                val indexedItems = section.items.mapIndexed { itemIndex, post -> itemIndex to post }
                MediaRail(items = indexedItems, key = { (_, post) -> post.id }) { (itemIndex, post) ->
                    PosterCard(
                        post = post,
                        onClick = { onPostClick(post) },
                        rank = if (isRanked && itemIndex < 10) itemIndex + 1 else null,
                    )
                }
                // The rows here are real and from this page; the refresh that failed is a
                // separate fact and has to be said, or the screen claims a freshness it
                // does not have.
                section.refreshFailure?.let { reason ->
                    InlineLoadFailure(message = reason, onRetry = onRetry)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    }
}
