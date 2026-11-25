package com.raddstream.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.MutableStateFlow
import com.raddstream.app.data.UserRegion
import com.raddstream.app.data.local.LocalVideoFile
import com.raddstream.app.data.local.groupVideosIntoFolders
import com.raddstream.app.network.models.FacetEntry
import com.raddstream.app.network.models.FacetsResponse
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.network.models.PanelDisclaimer
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseDetail
import com.raddstream.app.ui.components.LiveTvLoadingSkeleton
import com.raddstream.app.ui.components.PosterGridSkeleton
import com.raddstream.app.ui.components.SearchResultsSkeleton
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.AccountOverviewUiState
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.HistoryRow
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import com.raddstream.app.viewmodel.LanguagesUiState
import com.raddstream.app.viewmodel.ListSort
import com.raddstream.app.viewmodel.MyListFilter
import com.raddstream.app.viewmodel.RegionUiState
import com.raddstream.app.viewmodel.ReleaseLayoutBuilder
import com.raddstream.app.viewmodel.ResumeTile
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.SeasonGroup
import com.raddstream.app.viewmodel.TypeGroup
import com.raddstream.app.viewmodel.VipMethod
import com.raddstream.app.viewmodel.VipPlan
import com.raddstream.app.viewmodel.VipUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robo #10 (APK 757, main d5537b19) FAILED on exactly one crash:
 * `IllegalStateException: Vertically scrollable component was measured with
 * an infinity maximum height constraints` — a lazy layout (or a scrollable
 * column) measured with unbounded height, i.e. a vertical scroller nested
 * inside another vertical scroller, or inside a wrap-content ancestor that
 * hands it infinite height (a bottom sheet, a dialog, a wrap-content column).
 *
 * The stack named NO screen and NO composable (only androidx.compose frames),
 * and a full static sweep of the App tree found no nest: 31 scroll sites,
 * 4 bottom sheets, 7 dialogs, every lazy layout with a finite parent, no
 * `wrapContentSize(unbounded = true)`, no custom layout. Static analysis is
 * exhausted — so this class finds the offender empirically: it renders every
 * screen's content with RICH state (non-empty lists everywhere, so the lazy
 * branches actually compose) inside the app's own root, and renders every
 * sheet body inside its REAL sheet (a bottom sheet is the one ancestor
 * proven to hand unbounded height — Robo #6 crashed the Search filter sheet
 * exactly this way, fixed then with a `heightIn(max = 1200.dp)` cap).
 *
 * One test per composition, so a failure names the screen: the test that
 * throws IS the offending screen. A test that only asserts the root exists
 * still proves its point — `setContent` throwing is the failure, and a
 * composition that lays out without throwing is the pass.
 *
 * FOUND (first CI run 43758154976): `playerSheetLandscapeWithScrollableContent`
 * threw the exact Robo #10 exception. The nest is PlayerSheet's landscape
 * branch: the panel wraps the sheet in its own verticalScroll column, and
 * four sheets (SubtitleSettings, Tracks, More, PrintLog) scroll themselves
 * — and the player forces landscape on entry (PlayerScreen sets
 * SCREEN_ORIENTATION_LANDSCAPE), so Robo hit it by opening a player sheet
 * after playback started. Fix, same branch: the panel never scrolls; each
 * sheet owns its own scroll (the four plain sheets gained one here). The
 * landscape test is the regression test for that fix — it threw before it
 * and must compose after it.
 *
 * Screens that take a Hilt ViewModel directly (Settings, VIP, Live TV
 * grid branch, My List grid branch, Downloads, Player) cannot
 * be rendered without that ViewModel; their lazy children were verified by
 * reading (no lazy inside any of them) and their extractable sections are
 * rendered here inside a verticalScroll column instead, reproducing the
 * screen's own scroll parent over its real children.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no Linux/aarch64
 * build (robolectric/robolectric#9166) and it fails while the runner boots, so
 * the exclusion for ARM hosts lives in app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class NestedScrollCrashTest {

    @get:Rule
    val compose = createComposeRule()


    private fun samplePost(id: Int, title: String, year: Int = 2024) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = "movie",
        posterUrl = null,
    )

    private fun samplePosts(prefix: String, fromId: Int, titles: List<String>) =
        titles.mapIndexed { index, title ->
            samplePost(fromId + index, if (prefix.isEmpty()) title else "$prefix $title")
        }

    private fun homeState(
        sections: List<HomeSection>,
        continueWatching: List<ResumeTile> = emptyList(),
        pickThreeDone: Boolean = true,
        firstBrowseDone: Boolean = true,
    ) = HomeUiState(
        sections = sections,
        continueWatching = continueWatching,
        pickThreeDone = pickThreeDone,
        firstBrowseDone = firstBrowseDone,
        facets = FacetsResponse(
            genres = listOf(FacetEntry("action", "Action", 42)),
            languages = listOf(FacetEntry("hindi", "Hindi", 120)),
            industries = listOf(FacetEntry("bollywood", "Bollywood", 90)),
            types = listOf(FacetEntry("movie", "Movies", 300)),
        ),
    )

    private val heroRail = HomeSection(
        key = "recent",
        title = "Recently added",
        type = null,
        items = samplePosts("Hero", 1, listOf("Jawan", "Dune", "Inception", "Oppenheimer", "Baahubali", "Pushpa")),
    )
    private val secondRail = HomeSection(
        key = "most-copies",
        title = "Most copies",
        type = null,
        items = samplePosts("Rail", 11, listOf("Salaar", "Animal", "Pathaan", "Guntur", "Devara", "Kalki")),
    )

    // Lazy feed (home fix, Nov 2026): a rail below the fold is not
    // composed until it scrolls into view, and one swipe does not always
    // reach it. Drag until the text is composed (bounded, so a genuinely
    // missing node still fails loudly at the assert that follows). A
    // controlled drag, not a swipeUp fling: a fast fling can overshoot the
    // target and dispose it before the check runs.
    private fun scrollToText(text: String) {
        repeat(12) {
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            compose.onRoot().performTouchInput {
                swipe(
                    start = Offset(center.x, center.y * 1.6f),
                    end = Offset(center.x, center.y * 0.6f),
                    durationMillis = 400,
                )
            }
            compose.waitForIdle()
        }
    }

    // ── Home ─────────────────────────────────────────────────────────────

    @Test
    fun homeRichWithHeroRailsAndContinueWatching() {
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = homeState(
                        sections = listOf(heroRail, secondRail),
                        continueWatching = listOf(
                            ResumeTile(samplePost(21, "Suno Chanda"), 0, 0, 600_000L, 3_600_000L, "work-21"),
                            ResumeTile(samplePost(22, "Parizaad"), 1, 3, 1_200_000L, 3_600_000L, "work-22"),
                        ),
                    ),
                    heroAutoplay = false,
                )
            }
        }
        // The hero rail's own titles are the hero's slides: Audit #18
        // dedupes them out of the rail below, so the "Recently added"
        // header never paints — the second rail's header is the one that
        // proves the rails composed.
        // Home fix (Nov 2026): the feed is a LazyColumn now, so a rail
        // below the fold has no node until the list scrolls to it — swipe
        // it into view first, then scroll to the header.
        scrollToText("Most copies")
        compose.onNodeWithText("Most copies").performScrollTo().assertIsDisplayed()
        compose.onRoot().assertExists()
    }

    @Test
    fun homePickThreeCardVisible() {
        // The gate Robo #10 never saw open: first browse done, nothing
        // watched, nothing saved, candidates waiting on the second rail.
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = homeState(
                        sections = listOf(heroRail, secondRail),
                        pickThreeDone = false,
                    ),
                    heroAutoplay = false,
                )
            }
        }
        // Same Audit #18 dedupe as above: the hero rail paints no header,
        // the second rail does.
        // Home fix (Nov 2026): lazy feed — swipe the rail into view first
        // (see homeRichWithHeroRailsAndContinueWatching above).
        scrollToText("Most copies")
        compose.onNodeWithText("Most copies").performScrollTo().assertIsDisplayed()
        compose.onRoot().assertExists()
    }

    // ── Search ───────────────────────────────────────────────────────────

    @Test
    fun searchResultsWithGroups() {
        // S6: SearchContent reads the PagingData now — the groups arrive as
        // a parameter (derived from the snapshot by the screen) and the
        // list states from the PagingData's loadState.
        val movies = samplePosts("", 31, listOf("Dune", "Dune: Part Two"))
        val series = samplePosts("", 41, listOf("Dune: Prophecy"))
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val pagingDataFlow = MutableStateFlow(
            PagingData.from(
                data = movies + series,
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
        )
        compose.setContent {
            RaddStreamTheme {
                val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                SearchContent(
                    state = SearchUiState(
                        query = "dune",
                        hasSearched = true,
                        serverTotal = 3,
                    ),
                    pagingItems = pagingItems,
                    groups = listOf(
                        TypeGroup("Movies", "movie", movies),
                        TypeGroup("Series", "series", series),
                    ),
                    onRunQuery = {},
                    onRemoveRecent = {},
                    onResetFilters = {},
                    onQueryChanged = {},
                    onShowCloseMatches = {},
                    onShowExactOnly = {},
                    onRunSuggestion = {},
                    onPostClick = {},
                )
            }
        }
        compose.onAllNodesWithText("Dune", useUnmergedTree = true)[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun searchDiscoveryWithAllSections() {
        compose.setContent {
            RaddStreamTheme {
                SearchDiscoveryContent(
                    state = SearchUiState(
                        recents = listOf("Classic Cinema", "Dune"),
                        trending = samplePosts("", 51, listOf("Dune", "Inception")),
                        recommended = samplePosts("", 61, listOf("Oppenheimer")),
                    ),
                    onRunQuery = {},
                    onRemoveRecent = {},
                    onPostClick = {},
                )
            }
        }
        compose.onNodeWithText("Trending now").assertIsDisplayed()
    }

    // ── Detail ───────────────────────────────────────────────────────────

    private val detailPost = PostDetail(
        id = 1,
        title = "Jawan",
        cleanTitle = "Jawan",
        year = 2023,
        runtimeMinutes = 169,
        contentType = "movie",
        posterUrl = null,
        backdropUrls = null,
        imdbRating = "7.0",
        genres = "Action, Thriller",
        synopsis = "A man is driven by a personal vendetta.",
        castNames = "Shah Rukh Khan, Nayanthara",
        industry = "Bollywood",
        language = "Hindi",
    )

    private val detailPrints = ReleaseLayoutBuilder.build(
        contentType = "movie",
        releases = listOf(
            ReleaseDetail(
                id = 1,
                releaseTitle = "Jawan (2023) 2160p UHD BluRay x265 HDR10+ Dolby Vision [Hindi English] AC3 Subtitles",
                size = "15.2GB",
            ),
            ReleaseDetail(
                id = 2,
                releaseTitle = "Jawan (2023) 1080p WEB-DL AAC [English]",
                quality = "1080p",
                size = "4.1GB",
            ),
        ),
    ).workPrints

    private fun detailContent(
        isSeries: Boolean = false,
        seasons: List<SeasonGroup> = emptyList(),
        deadEndReason: String? = null,
    ) {
        compose.setContent {
            RaddStreamTheme {
                DetailContent(
                    post = detailPost,
                    seasons = seasons,
                    workPrints = detailPrints,
                    capabilities = emptyList(),
                    isSeries = isSeries,
                    resume = null,
                    includedPill = null,
                    deadEndReason = deadEndReason,
                    // 2-button detail (Nov 2026): the dead end's reason
                    // reaches the page as the Play button's hint — the
                    // ViewModel puts it there — so the helper models
                    // that instead of passing a rung card.
                    playLabel = "Play",
                    playEnabled = deadEndReason == null,
                    playHint = deadEndReason,
                    downloadLabel = "Download",
                    downloadEnabled = true,
                    onOpenSeason = {},
                    onActorClick = {},
                    similarTitles = samplePosts("", 71, listOf("Pathaan", "Animal", "Salaar")),
                    onPostClick = {},
                    reloadError = null,
                    onRetryReload = {},
                    isFavorite = false,
                    onToggleFavorite = {},
                    onShare = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    @Test
    fun detailMovieWithPrintsAndSimilar() {
        detailContent()
        compose.onNodeWithText("A man is driven by a personal vendetta.").assertIsDisplayed()
    }

    @Test
    fun detailSeriesWithSeasonsAndEpisodes() {
        detailContent(
            isSeries = true,
            seasons = listOf(
                SeasonGroup(
                    number = 1,
                    episodes = listOf(
                        EpisodeGroup(1, 1, 1, "Pilot", detailPrints),
                        EpisodeGroup(1, 2, 2, "The Reckoning", detailPrints),
                    ),
                    packedPrints = detailPrints,
                ),
                SeasonGroup(
                    number = 2,
                    episodes = listOf(EpisodeGroup(2, 1, 3, "Return", detailPrints)),
                    packedPrints = emptyList(),
                ),
            ),
        )
        compose.onRoot().assertExists()
    }

    @Test
    fun detailDeadEndWithReason() {
        detailContent(deadEndReason = "No playable source found for this title.")
        compose.onNodeWithText("No playable source found for this title.").assertIsDisplayed()
    }

    // ── Browse / Live TV / My List ───────────────────────────────────────

    @Test
    fun browseGridWithItemsLoadingMoreAndError() {
        // S6: BrowsePosterGrid reads the PagingData now — the append
        // loadState is one state at a time, so the old "loading AND error
        // at once" combination cannot exist any more. The crash coverage
        // splits in two: items with the next page loading (skeleton
        // footer), and items with the next page failed (retry row).
        val posts = samplePosts("", 81, listOf("Jawan", "Dune", "Inception", "Oppenheimer", "Baahubali", "Pushpa", "Salaar", "Animal"))
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val loadingFlow = MutableStateFlow(
            PagingData.from(
                data = posts,
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = LoadState.Loading),
            ),
        )
        compose.setContent {
            RaddStreamTheme {
                val gridState = rememberLazyGridState()
                val pagingItems = loadingFlow.collectAsLazyPagingItems()
                BrowsePosterGrid(
                    pagingItems = pagingItems,
                    gridState = gridState,
                    onPostClick = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onAllNodesWithText("Jawan", useUnmergedTree = true)[0].assertIsDisplayed()
    }

    @Test
    fun browseGridWithItemsAndAppendError() {
        val posts = samplePosts("", 81, listOf("Jawan", "Dune", "Inception", "Oppenheimer", "Baahubali", "Pushpa", "Salaar", "Animal"))
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val errorFlow = MutableStateFlow(
            PagingData.from(
                data = posts,
                sourceLoadStates = LoadStates(
                    refresh = idle,
                    prepend = idle,
                    append = LoadState.Error(java.io.IOException("Page 2 failed to load.")),
                ),
            ),
        )
        compose.setContent {
            RaddStreamTheme {
                val gridState = rememberLazyGridState()
                val pagingItems = errorFlow.collectAsLazyPagingItems()
                BrowsePosterGrid(
                    pagingItems = pagingItems,
                    gridState = gridState,
                    onPostClick = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onAllNodesWithText("Jawan", useUnmergedTree = true)[0].assertIsDisplayed()
        // S6: the error row is the grid's last item — with 8 posts it sits
        // below the fold, and a Lazy grid only composes visible items, so
        // the test drags it into view first (a controlled drag, not a
        // swipeUp fling, which can overshoot and dispose the target).
        var swipes = 0
        while (compose.onAllNodesWithText("Could not load more.").fetchSemanticsNodes().isEmpty() && swipes < 5) {
            compose.onRoot().performTouchInput {
                swipe(
                    start = Offset(center.x, center.y * 1.6f),
                    end = Offset(center.x, center.y * 0.6f),
                    durationMillis = 400,
                )
            }
            compose.waitForIdle()
            swipes++
        }
        compose.onNodeWithText("Could not load more.").assertIsDisplayed()
    }

    @Test
    fun liveTvLoadingSkeleton() {
        compose.setContent {
            RaddStreamTheme { LiveTvLoadingSkeleton() }
        }
        compose.onRoot().assertExists()
    }

    @Test
    fun liveTvChannelCards() {
        compose.setContent {
            RaddStreamTheme {
                Column(Modifier.fillMaxSize()) {
                    listOf(
                        LiveTvChannel(1, "DD Sports", "Sports", "IN", "Hindi", "", "dd-sports"),
                        LiveTvChannel(2, "DD News", "News", "IN", "Hindi", "", "dd-news"),
                        LiveTvChannel(3, "City 41", "News", "PK", "Urdu", "", "city41"),
                    ).forEach { channel ->
                        LiveTvChannelCard(channel = channel, onClick = {})
                    }
                }
            }
        }
        // A4-F1: the logo plate paints the monogram (mark + "DS"), not the
        // full name — the name under a 48dp mark overflowed the plate, so
        // the Row replaced the label. The card still carries the full name
        // in its one a11y sentence, and prints it below the plate.
        compose.onNodeWithText("DS").assertIsDisplayed()
        compose.onNodeWithContentDescription("DD Sports, live", substring = true).assertExists()
    }

    @Test
    fun myListEmptyWithPopular() {
        compose.setContent {
            RaddStreamTheme {
                MyListEmptyContent(
                    popular = samplePosts("", 91, listOf("Jawan", "Dune", "Inception")),
                    onPostClick = {},
                )
            }
        }
        compose.onNodeWithText("Your list is empty").assertIsDisplayed()
    }

    @Test
    fun myListFilterRow() {
        // L1 (round 2): the sort chips became the filter chips with the
        // sort as a cycling button — the button still names the order in
        // force, so the old "Recently added" assertion still holds.
        compose.setContent {
            RaddStreamTheme {
                MyListFilterRow(
                    filter = MyListFilter.ALL,
                    sort = ListSort.RECENT,
                    onFilter = {},
                    onSort = {},
                )
            }
        }
        compose.onNodeWithText("Recently added").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsDisplayed()
    }

    // ── Account screens ──────────────────────────────────────────────────

    private val accountState = AccountOverviewUiState(
        signedIn = true,
        paywallVisible = true,
    )

    @Test
    fun dashboardSignedInWithHistory() {
        compose.setContent {
            RaddStreamTheme {
                DashboardContent(
                    state = accountState,
                    onBackClick = {},
                    onOpenProfile = {},
                    onOpenVipUpgrade = {},
                )
            }
        }
        compose.onNodeWithText("Dashboard").assertIsDisplayed()
    }

    @Test
    fun profileSignedInWithHistory() {
        compose.setContent {
            RaddStreamTheme {
                ProfileContent(
                    state = accountState,
                    onBackClick = {},
                    onOpenDashboard = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Your profile").assertIsDisplayed()
    }

    // ── Sub-screens ──────────────────────────────────────────────────────

    @Test
    fun helpContentExpanded() {
        compose.setContent {
            RaddStreamTheme {
                HelpContent(
                    faqExpanded = true,
                    onFaqToggle = {},
                    onReportClick = {},
                    onContactClick = {},
                    onAboutClick = {},
                    reportMessage = null,
                    reportMessageIsError = false,
                )
            }
        }
        compose.onRoot().assertExists()
    }

    @Test
    fun languagesContent() {
        compose.setContent {
            RaddStreamTheme {
                LanguagesContent(
                    state = LanguagesUiState(
                        audioLanguage = "Hindi",
                        subtitleLanguage = "English",
                        subtitleSizePercent = 100,
                    ),
                    onAudioSelect = {},
                    onSubtitleSelect = {},
                    onSizeChange = {},
                )
            }
        }
        // The rows speak through content descriptions, not text (the
        // SubScreensMockupTest pins the same nodes this way).
        compose.onNodeWithContentDescription("Audio language: Hindi")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun localFilesGroupedByFolder() {
        val files = listOf(
            LocalVideoFile(uri = "content://media/1", title = "Maula Jatt", durationMs = 7_200_000L, sizeBytes = 1_300_000_000L, folder = "Movies", dateAddedMs = 1_791_000_000_000L, folderPath = "Movies"),
            LocalVideoFile(uri = "content://media/3", title = "Parizaad Ep 23", durationMs = 2_700_000L, sizeBytes = 900_000_000L, folder = "Movies", dateAddedMs = 1_791_000_000_000L, folderPath = "Movies"),
            LocalVideoFile(uri = "content://media/2", title = "Parizaad Ep 24", durationMs = 2_700_000L, sizeBytes = 900_000_000L, folder = "Downloads", dateAddedMs = 1_791_000_000_000L, folderPath = "Download"),
        )
        compose.setContent {
            RaddStreamTheme {
                LocalFolderVideosContent(
                    folder = groupVideosIntoFolders(files).first { it.path == "Movies" },
                    onPlay = {},
                )
            }
        }
        compose.onNodeWithText("Maula Jatt").assertIsDisplayed()
    }

    @Test
    fun watchHistoryWithRows() {
        compose.setContent {
            RaddStreamTheme {
                WatchHistoryContent(
                    rows = listOf(
                        HistoryRow(
                            postId = 7,
                            title = "Suno Chanda",
                            posterUrl = null,
                            scopeLabel = null,
                            progressFraction = 0.5f,
                            progressLabel = "1 min left",
                            finished = false,
                            dateLabel = "15 Nov 2026",
                            watchedLabel = "Watched 2 days ago",
                            workRef = "work-7",
                            seasonNumber = 0,
                            episodeNumber = 0,
                        ),
                    ),
                    onBackClick = {},
                    onClearAll = {},
                    onRemove = {},
                    onPostClick = {},
                )
            }
        }
        compose.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun regionContent() {
        compose.setContent {
            RaddStreamTheme {
                RegionContent(
                    state = RegionUiState(selectedId = UserRegion.DESI_DUBS),
                    onSelect = {},
                    onDone = {},
                    onSkip = {},
                )
            }
        }
        compose.onNodeWithText("Desi").assertIsDisplayed()
    }

    // ── Sheets, inside their real sheet ─────────────────────────────────
    //
    // A bottom sheet is the one ancestor proven to hand unbounded height
    // (Robo #6). Rendering a sheet body bare, in a plain Column, would not
    // reproduce the crash — the body must sit inside the sheet itself.

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun searchFilterSheetInsideBottomSheet() {
        compose.setContent {
            RaddStreamTheme {
                ModalBottomSheet(
                    onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(),
                ) {
                    FilterSheetBody(
                        state = SearchUiState(query = "kpop"),
                        onSelectType = {},
                        onSelectIndustry = {},
                        onSelectGenre = {},
                        onSelectLanguage = {},
                        onSelectQuality = {},
                        onSetIncludedOnly = {},
                        onSelectSort = {},
                        onSetYearRange = { _, _ -> },
                        onResetFilters = {},
                        onDone = {},
                    )
                }
            }
        }
        // The footer sits outside the sheet's scrolling middle and the
        // sheet opens partially expanded, so the button exists below the
        // fold — existence, not display, is what this test pins.
        compose.onNodeWithText("Apply").assertExists()
    }

    @Test
    fun reportBrokenStreamSheetInsideBottomSheet() {
        compose.setContent {
            RaddStreamTheme {
                ReportBrokenStreamDialog(
                    isSubmitting = false,
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Report a problem").assertIsDisplayed()
    }

    @Test
    fun playerSheetPortraitWithScrollableContent() {
        compose.setContent {
            RaddStreamTheme {
                PlayerSheet(onDismiss = {}) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(8) { index -> Text("Subtitle track ${index + 1}") }
                    }
                }
            }
        }
        compose.onNodeWithText("Subtitle track 1").assertIsDisplayed()
    }

    @Test
    fun playerSheetLandscapeWithScrollableContent() {
        // The landscape branch wraps the sheet content in its own
        // verticalScroll column — the one nested-scroll shape in the app
        // that static analysis could not rule out by reading alone, because
        // the nesting only exists when the device is sideways.
        val landscape = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        compose.setContent {
            RaddStreamTheme {
                CompositionLocalProvider(LocalConfiguration provides landscape) {
                    PlayerSheet(onDismiss = {}) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            repeat(8) { index -> Text("Subtitle track ${index + 1}") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Subtitle track 1").assertIsDisplayed()
    }

    // ── Dialog window ────────────────────────────────────────────────────

    @Test
    fun disclaimerDialogWithLongText() {
        compose.setContent {
            RaddStreamTheme {
                DisclaimerDialog(
                    disclaimer = PanelDisclaimer(
                        text = "Radd Stream does not host any content. " +
                            "All links point to publicly available sources. " +
                            "Contact us for removals.",
                        version = 3,
                        email = "radddao1@gmail.com",
                    ),
                    confirmLabel = "I understand",
                    onConfirm = {},
                    onDismissRequest = {},
                    dismissLabel = "Decline",
                    onDecline = {},
                )
            }
        }
        compose.onNodeWithText("I understand").assertIsDisplayed()
    }

    // ── ViewModel screens, sections under their own scroll parent ────────
    //
    // The full screens need their Hilt ViewModel, so the sections render
    // here inside the same verticalScroll column the screen itself uses —
    // the nest, if one existed, would throw here exactly as on the phone.

    @Test
    fun vipSectionsInsideScrollColumn() {
        val state = VipUiState(
            isLoading = false,
            currency = "PKR",
            monthlyPkr = 250,
            yearlyPkr = 1800,
            jazzCashTillId = "7019882",
            jazzCashMobileAccount = "0300-1234567",
            easyPaisaMobile = "0345-7654321",
            raastInstantId = "raddstream@hbl",
            selectedPlan = VipPlan.MONTHLY,
            selectedMethod = VipMethod.JAZZCASH,
        )
        compose.setContent {
            RaddStreamTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PricingSection(state = state) {}
                    PaymentSection(state = state) { _, _ -> }
                    MethodSection(state = state) {}
                }
            }
        }
        compose.onNodeWithText("7019882").assertIsDisplayed()
    }

    @Test
    fun settingsSectionsInsideScrollColumn() {
        compose.setContent {
            RaddStreamTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AccountNavRow(
                        paywallVisible = true,
                        onOpenDashboard = {},
                        onOpenProfile = {},
                    )
                    SwitchRow(checked = true, onCheckedChange = {}, label = "Autoplay previews")
                }
            }
        }
        compose.onNodeWithText("Dashboard").assertIsDisplayed()
    }

    // ── Skeletons ────────────────────────────────────────────────────────

    @Test
    fun posterGridSkeleton() {
        compose.setContent {
            RaddStreamTheme {
                PosterGridSkeleton(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
        compose.onRoot().assertExists()
    }

    @Test
    fun searchResultsSkeleton() {
        compose.setContent {
            RaddStreamTheme { SearchResultsSkeleton() }
        }
        compose.onRoot().assertExists()
    }
}
