package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ListSort
import com.raddstream.app.viewmodel.MyListFilter
import com.raddstream.app.viewmodel.MyListUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mockup (Nov 2026): the My List filter chips are 48dp tall, all four
 * fully visible in one row in mockup order, the active chip jade-filled
 * — and the "Trending to Start Your List" rail sits below the grid as a full-span
 * footer whenever the trending load produced titles. L1 (round 2): the
 * sort chips became the filter chips (All / Movies / Series / Downloads)
 * with the sort as a cycling button at the row's right end. Runs on the JVM
 * under Robolectric because this project has no emulator image.
 *
 * Requires an x86_64 runner. Robolectric's native runtime has no
 * Linux/aarch64 build (robolectric/robolectric#9166) and it fails while
 * the runner boots, so the exclusion for ARM hosts lives in
 * app/build.gradle.kts rather than here.
 */
@RunWith(RobolectricTestRunner::class)
class MyListMockupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(id: Int, title: String, contentType: String = "movie") = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2024,
        contentType = contentType,
        // No poster URL: the card renders the branded fallback, so no image
        // load runs in the test.
        posterUrl = null,
    )

    // fetchSemanticsNodes() is a member of SemanticsNodeInteractionCollection,
    // not a top-level function — it needs no import (BottomNavTouchTargetTest
    // uses it the same way). .single() because each tag is unique.
    private fun chipNode(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().single()

    private fun chipLeft(tag: String): Float = chipNode(tag).boundsInRoot.left

    private fun chipHeightDp(tag: String) =
        with(compose.density) { chipNode(tag).boundsInRoot.height.toDp() }

    // L1 (round 2): the filter row is wider than a phone — four chips
    // plus the sort button — so these row tests run in a 600dp box, where
    // every chip is on screen at once. On a 390dp phone the chip strip
    // scrolls (ScrollingChipRow + the S3 fade); the loaded-content tests
    // below prove the row works at phone width.
    private fun filterRowContent(
        filter: MyListFilter,
        sort: ListSort,
        onFilter: (MyListFilter) -> Unit = {},
        onSort: (ListSort) -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(600.dp)) {
                    MyListFilterRow(filter = filter, sort = sort, onFilter = onFilter, onSort = onSort)
                }
            }
        }
    }

    @Test
    fun filterChipsAreInMockupOrderWithAllSelected() {
        filterRowContent(filter = MyListFilter.ALL, sort = ListSort.RECENT)
        // All four are on screen, in mockup order, left to right.
        compose.onNodeWithTag("myListFilterChip_ALL").assertIsDisplayed()
        compose.onNodeWithTag("myListFilterChip_MOVIES").assertIsDisplayed()
        compose.onNodeWithTag("myListFilterChip_SERIES").assertIsDisplayed()
        compose.onNodeWithTag("myListFilterChip_DOWNLOADS").assertIsDisplayed()
        val lefts = listOf(
            chipLeft("myListFilterChip_ALL"),
            chipLeft("myListFilterChip_MOVIES"),
            chipLeft("myListFilterChip_SERIES"),
            chipLeft("myListFilterChip_DOWNLOADS"),
        )
        assertTrue("filter chips read left-to-right as $lefts", lefts == lefts.sorted())
        // Only the active chip is selected.
        compose.onNodeWithTag("myListFilterChip_ALL").assertIsSelected()
        compose.onNodeWithTag("myListFilterChip_MOVIES").assertIsNotSelected()
        compose.onNodeWithTag("myListFilterChip_SERIES").assertIsNotSelected()
        compose.onNodeWithTag("myListFilterChip_DOWNLOADS").assertIsNotSelected()
    }

    @Test
    fun filterChipsAre48dpTall() {
        filterRowContent(filter = MyListFilter.ALL, sort = ListSort.RECENT)
        // FilterChip's own visual height is 32dp — the mockup's chips are
        // 48dp, the touch-target floor, so the chip itself carries it.
        listOf(
            "myListFilterChip_ALL",
            "myListFilterChip_MOVIES",
            "myListFilterChip_SERIES",
            "myListFilterChip_DOWNLOADS",
        ).forEach { tag ->
            assertEquals("$tag is not 48dp tall", 48.dp, chipHeightDp(tag))
        }
    }

    @Test
    fun tappingAFilterChipSelectsIt() {
        // The setContent lambda must read the `selected` state itself —
        // the first version passed it through filterRowContent's plain
        // `filter` parameter, so the tap assigned a var nothing read and
        // the chip never became selected (Android 45539693673).
        var selected by mutableStateOf(MyListFilter.ALL)
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(600.dp)) {
                    MyListFilterRow(
                        filter = selected,
                        sort = ListSort.RECENT,
                        onFilter = { selected = it },
                        onSort = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("myListFilterChip_SERIES").performClick()
        compose.onNodeWithTag("myListFilterChip_SERIES").assertIsSelected()
        compose.onNodeWithTag("myListFilterChip_ALL").assertIsNotSelected()
        assertEquals(MyListFilter.SERIES, selected)
    }

    @Test
    fun sortButtonCyclesTheOrder() {
        // L1 (round 2): the sort is a visible secondary control at the
        // row's right end — one tap cycles Recently added → Title A–Z →
        // Unwatched first, no sheet. Same state-in-lambda rule as the
        // filter tap above: the lambda reads `sort` itself.
        var sort by mutableStateOf(ListSort.RECENT)
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(600.dp)) {
                    MyListFilterRow(
                        filter = MyListFilter.ALL,
                        sort = sort,
                        onFilter = {},
                        onSort = { sort = it },
                    )
                }
            }
        }
        compose.onNodeWithTag(MyListSortButtonTag).assertIsDisplayed()
        compose.onNodeWithText("Recently added").assertIsDisplayed()
        compose.onNodeWithTag(MyListSortButtonTag).performClick()
        assertEquals(ListSort.TITLE, sort)
        compose.onNodeWithText("Title A–Z").assertIsDisplayed()
        compose.onNodeWithTag(MyListSortButtonTag).performClick()
        assertEquals(ListSort.UNWATCHED, sort)
        compose.onNodeWithText("Unwatched first").assertIsDisplayed()
    }

    @Test
    fun sortButtonIs48dpTall() {
        filterRowContent(filter = MyListFilter.ALL, sort = ListSort.RECENT)
        val height = with(compose.density) {
            compose.onAllNodesWithTag(MyListSortButtonTag)
                .fetchSemanticsNodes().single().boundsInRoot.height.toDp()
        }
        // The floor is a minimum: the button's own M3 content padding
        // makes it taller than 48dp, and that is fine — what the review
        // forbids is a button UNDER the floor.
        assertTrue("Sort button is $height tall, expected at least 48dp", height >= 48.dp)
    }

    @Test
    fun popularRowRendersTheTrendingRail() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp)) {
                    PopularRow(
                        popular = listOf(
                            post(1, "Inception"),
                            post(2, "Dune"),
                            post(3, "McLintock!"),
                        ),
                        onPostClick = {},
                    )
                }
            }
        }
        // My List review (Nov 2026): the rail's old "Most copies" title
        // read like warehouse inventory; the shelf now says what it is.
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        // Dev-cleanup (Nov 2026): the subtitle said the same thing as the
        // title ("Trending titles to start your list") — the rail carries
        // the title alone now.
        compose.onNodeWithText("Trending titles to start your list")
            .assertDoesNotExist()
        compose.onNodeWithText("Inception", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Dune", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("McLintock!", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun popularRowRendersTheRecentlyAddedRailWithoutASubtitle() {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp)) {
                    PopularRow(
                        popular = listOf(
                            post(1, "Inception"),
                            post(2, "Dune"),
                            post(3, "McLintock!"),
                        ),
                        onPostClick = {},
                        titleRes = R.string.mylist_recent_title,
                    )
                }
            }
        }
        compose.onNodeWithText("Recently Added").assertIsDisplayed()
        // Screen audit #5 (Nov 2026): the subtitle restated the title
        // ("New titles in the catalog") — the rail carries the title
        // alone now, like the Trending rail above it.
        compose.onNodeWithText("New titles in the catalog")
            .assertDoesNotExist()
        compose.onNodeWithText("Inception", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Dune", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("McLintock!", useUnmergedTree = true).assertIsDisplayed()
    }

    // UI/UX audit Library #2 (Nov 2026): the 3-up rail's caption is one
    // line, so "Salaar: Part 1 – Ceasefire" truncated mid-word to
    // "Salaar: Part 1 – …". The rail passes titleMaxLines = 2 now, so its
    // cards stand one title line taller than the same tiles at one line.
    // The painted texts are cleared from semantics (one spoken sentence
    // per tile), so this pins geometry, not words — the PosterCard search
    // grid test pins its two-line slot the same way. The comparison row
    // reproduces the rail's geometry exactly (same width, padding and
    // spacing, compact meta on both sides), so the only height difference
    // is the title slot.
    @Test
    fun popularRowTitlesGetTwoLines() {
        val railTitles = listOf("Alpha One", "Alpha Two", "Alpha Three")
        val gridTitles = listOf("Beta One", "Beta Two", "Beta Three")
        compose.setContent {
            RaddStreamTheme {
                Column(Modifier.width(390.dp)) {
                    PopularRow(
                        popular = railTitles.mapIndexed { i, title -> post(i + 1, title) },
                        onPostClick = {},
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridTitles.mapIndexed { i, title -> post(i + 11, title) }.forEach { tile ->
                            PosterCard(
                                post = tile,
                                onClick = {},
                                compactMeta = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        fun heightFor(title: String): Float =
            compose.onNode(hasContentDescription(title, substring = true))
                .fetchSemanticsNode().boundsInRoot.height
        val rail = heightFor("Alpha One")
        val grid = heightFor("Beta One")
        assertTrue("rail card ($rail px) is not taller than the one-line card ($grid px)", rail > grid)
    }

    // ── My List review fixes (Nov 2026) ──────────────────────────────

    private fun loadedState(
        favorites: List<Post>,
        hiddenCount: Int = 0,
        popular: List<Post> = emptyList(),
        progressByPostId: Map<Int, Float> = emptyMap(),
        progressLabelByPostId: Map<Int, String> = emptyMap(),
        filter: MyListFilter = MyListFilter.ALL,
        downloadedPostIds: Set<Int> = emptySet(),
        railsLoadFailed: Boolean = false,
    ) = MyListUiState(
        favorites = favorites,
        sort = ListSort.RECENT,
        filter = filter,
        hiddenCount = hiddenCount,
        popular = popular,
        progressByPostId = progressByPostId,
        progressLabelByPostId = progressLabelByPostId,
        downloadedPostIds = downloadedPostIds,
        railsLoadFailed = railsLoadFailed,
    )

    private fun loadedContent(
        state: MyListUiState,
        onPostClick: (Post) -> Unit = {},
        onPlayClick: (Post) -> Unit = {},
        onRemove: (Int) -> Unit = {},
        onFilter: (MyListFilter) -> Unit = {},
        onRetryRails: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp).fillMaxSize()) {
                    MyListLoadedContent(
                        state = state,
                        onPostClick = onPostClick,
                        onPlayClick = onPlayClick,
                        onRemove = onRemove,
                        onSort = {},
                        onFilter = onFilter,
                        onRetryRails = onRetryRails,
                        isFavorite = { false },
                        onToggleFavorite = {},
                    )
                }
            }
        }
    }

    // L1 (round 2): the filter tests drive the grid through the row's
    // onFilter the way the screen does — the state lives in a MutableState
    // the setContent lambda reads, so assigning it re-composes the grid.
    // A plain value closed over at setContent time never changes: the
    // first version of these tests tapped the chip and the grid never
    // moved (Android 45539693673 — four assertDoesNotExist failures).
    private fun loadedContentFiltered(
        favorites: List<Post>,
        downloadedPostIds: Set<Int> = emptySet(),
        popular: List<Post> = emptyList(),
    ) {
        var current by mutableStateOf(
            loadedState(favorites, downloadedPostIds = downloadedPostIds, popular = popular),
        )
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp).fillMaxSize()) {
                    MyListLoadedContent(
                        state = current,
                        onPostClick = {},
                        onPlayClick = {},
                        onRemove = {},
                        onSort = {},
                        onFilter = {
                            current = loadedState(
                                favorites,
                                downloadedPostIds = downloadedPostIds,
                                popular = popular,
                                filter = it,
                            )
                        },
                        isFavorite = { false },
                        onToggleFavorite = {},
                    )
                }
            }
        }
    }

    // The clock advances past the cross-fade and the staggered entrance
    // (400ms delay + 400ms duration at most) before any absence is
    // asserted.
    // The test's onFilter re-sets the content with the new filter (the
    // way the ViewModel emits a new state); the click already fired it,
    // so this only settles the cross-fade + entrance afterwards.
    private fun tapFilterAndSettle(filter: MyListFilter) {
        compose.onNodeWithTag("myListFilterChip_${filter.name}").performClick()
        compose.mainClock.advanceTimeBy(1000)
    }

    @Test
    fun savedCountNamesEverySavedTitle() {
        loadedContent(loadedState(listOf(post(1, "Inception"), post(2, "Dune"))))
        compose.onNodeWithText("2 titles").assertIsDisplayed()
    }

    @Test
    fun savedCountIsSingularForOneTitle() {
        loadedContent(loadedState(listOf(post(1, "Inception"))))
        compose.onNodeWithText("1 title").assertIsDisplayed()
    }

    @Test
    fun savedCountIncludesHiddenTitles() {
        // A row this build may not draw is still saved: one visible
        // title plus one hidden one is two titles, not one.
        loadedContent(loadedState(listOf(post(1, "Inception")), hiddenCount = 1))
        compose.onNodeWithText("2 titles").assertIsDisplayed()
    }

    @Test
    fun halfWatchedTileSpeaksItsProgress() {
        loadedContent(
            loadedState(
                favorites = listOf(post(1, "Inception")),
                progressByPostId = mapOf(1 to 0.5f),
                progressLabelByPostId = mapOf(1 to "S2:E5 · 23 min left"),
            ),
        )
        // The tile's one spoken sentence carries the words now — the
        // review's mute grey bar said nothing about which episode or
        // how much was left.
        compose.onNodeWithContentDescription("Inception, 2024 · Movie, S2:E5 · 23 min left")
            .assertIsDisplayed()
    }

    @Test
    fun starterShelfShowsWhileTheListIsSmall() {
        loadedContent(
            loadedState(
                favorites = listOf(post(1, "Inception")),
                popular = listOf(post(2, "Dune"), post(3, "McLintock!")),
            ),
        )
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
    }

    @Test
    fun starterShelfShowsWithTwoSaves() {
        // UI/UX audit Library #5 (Nov 2026): the shelf used to vanish
        // the moment a second title was saved (favorites.size <= 1) —
        // a 2-save list lost all suggestions. The shelf stays while the
        // list is small (at most five saves).
        loadedContent(
            loadedState(
                favorites = listOf(post(1, "Inception"), post(2, "Dune")),
                popular = listOf(post(3, "McLintock!"), post(4, "Gen V")),
            ),
        )
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
    }

    @Test
    fun starterShelfHidesOnceTheListGrows() {
        // My List review (Nov 2026): the shelf is a *starter* shelf —
        // a small list shows it the same way in every session, whether
        // the ViewModel was born onto one save or two. UI/UX audit
        // Library #5 (Nov 2026): "small" is five saves, not one — the
        // shelf leaves once the list is established.
        loadedContent(
            loadedState(
                favorites = listOf(
                    post(1, "Inception"),
                    post(2, "Dune"),
                    post(3, "McLintock!"),
                    post(4, "Gen V"),
                    post(5, "Oppenheimer"),
                    post(6, "Jawan"),
                ),
                popular = listOf(post(7, "Salaar"), post(8, "Dune 2")),
            ),
        )
        compose.onNodeWithText("Trending to Start Your List").assertDoesNotExist()
    }

    // ── APP-06 one-tap play (Phase 4) ─────────────────────────────────

    @Test
    fun savedTileGrowsAPlayButton() {
        // APP-06: the saved tile is the strongest resume case — it grows
        // the same play button Home's rails have, one per saved title.
        // Two saves still show the starter shelf (the bound is five), but
        // the shelf tiles carry no play button — PopularRow never passes
        // onPlayClick — so every Play node here is a saved tile's.
        loadedContent(
            loadedState(listOf(post(1, "Inception"), post(2, "Dune"))),
        )
        compose.onNodeWithContentDescription("Play Inception").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play Dune").assertIsDisplayed()
    }

    @Test
    fun playButtonTapPlaysThatTitleOnly() {
        // APP-06: the play button starts its own title and never also
        // opens it — the tap is play, not a doorway tap.
        var played by mutableStateOf<Post?>(null)
        val opened = mutableListOf<Post>()
        loadedContent(
            loadedState(listOf(post(1, "Inception"), post(2, "Dune"))),
            onPostClick = { opened += it },
            onPlayClick = { played = it },
        )
        compose.onNodeWithContentDescription("Play Dune").performClick()
        assertEquals("Dune", played?.title)
        assertEquals(2, played?.id)
        assertTrue("play tap also opened a title: $opened", opened.isEmpty())
    }

    @Test
    fun tileTapStillOpensTheTitle() {
        // APP-06: the tile itself stays a doorway — only the play button
        // on it starts playback.
        var played by mutableStateOf<Post?>(null)
        val opened = mutableListOf<Post>()
        loadedContent(
            loadedState(listOf(post(1, "Inception"), post(2, "Dune"))),
            onPostClick = { opened += it },
            onPlayClick = { played = it },
        )
        compose.onNodeWithContentDescription("Inception, 2024 · Movie").performClick()
        assertEquals(listOf("Inception"), opened.map { it.title })
        assertTrue("tile tap also played: $played", played == null)
    }

    @Test
    fun removeButtonStillRemovesTheTitle() {
        // APP-06: the management action is untouched — the × beside the
        // title still removes its own row and starts no playback.
        var played by mutableStateOf<Post?>(null)
        val opened = mutableListOf<Post>()
        val removed = mutableListOf<Int>()
        loadedContent(
            loadedState(listOf(post(1, "Inception"), post(2, "Dune"))),
            onPostClick = { opened += it },
            onPlayClick = { played = it },
            onRemove = { removed += it },
        )
        compose.onNodeWithContentDescription("Remove Inception from My List").performClick()
        assertEquals(listOf(1), removed)
        assertTrue("remove tap also opened a title: $opened", opened.isEmpty())
        assertTrue("remove tap also played: $played", played == null)
    }

    // ── L1/L2/L4 (round 2) ────────────────────────────────────────────

    @Test
    fun filterMoviesHidesSeries() {
        // L1: the Movies chip scopes the grid to movies — the series tile
        // leaves, the movie tile stays, the count still names the whole list.
        loadedContentFiltered(
            listOf(
                post(1, "Inception", "movie"),
                post(2, "Gen V", "series"),
            ),
        )
        tapFilterAndSettle(MyListFilter.MOVIES)
        compose.onNodeWithContentDescription("Inception, 2024 · Movie").assertIsDisplayed()
        compose.onNodeWithContentDescription("Gen V, 2024 · Series").assertDoesNotExist()
        compose.onNodeWithText("2 titles").assertIsDisplayed()
    }

    @Test
    fun filterDownloadsShowsOnlyDownloaded() {
        // L1 + L2: the Downloads chip shows only titles with a verified
        // copy on the device.
        loadedContentFiltered(
            listOf(post(1, "Inception"), post(2, "Dune")),
            downloadedPostIds = setOf(2),
        )
        tapFilterAndSettle(MyListFilter.DOWNLOADS)
        compose.onNodeWithContentDescription("Dune, 2024 · Movie, downloaded").assertIsDisplayed()
        compose.onNodeWithContentDescription("Inception, 2024 · Movie").assertDoesNotExist()
    }

    @Test
    fun filterWithNoMatchNamesTheFilter() {
        // L1: a filter that matches nothing says so in the grid instead of
        // leaving a blank screen — the line names the filter, not the list.
        loadedContentFiltered(listOf(post(1, "Inception")))
        tapFilterAndSettle(MyListFilter.SERIES)
        compose.onNodeWithText("No series saved yet").assertIsDisplayed()
        compose.onNodeWithText("1 title").assertIsDisplayed()
    }

    @Test
    fun downloadedTileWearsTheBadge() {
        // L2: a verified copy on the device badges the tile — and the
        // tile's one spoken sentence says "downloaded".
        loadedContent(
            loadedState(
                listOf(post(1, "Inception")),
                downloadedPostIds = setOf(1),
            ),
        )
        compose.onNodeWithContentDescription("Inception, 2024 · Movie, downloaded")
            .assertIsDisplayed()
    }

    @Test
    fun starterShelfHidesUnderAFilter() {
        // L1: the shelf is a discovery footer for the whole list — under
        // a filter it would show titles the filter just hid.
        loadedContentFiltered(
            listOf(post(1, "Inception")),
            popular = listOf(post(2, "Dune"), post(3, "McLintock!")),
        )
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        tapFilterAndSettle(MyListFilter.MOVIES)
        compose.onNodeWithText("Trending to Start Your List").assertDoesNotExist()
    }

    @Test
    fun railsErrorCardOffersRetry() {
        // L4: a failed rail load leaves the inline retry where the shelf
        // would have been — the card says what failed and offers the retry.
        var retried = false
        loadedContent(
            loadedState(listOf(post(1, "Inception")), railsLoadFailed = true),
            onRetryRails = { retried = true },
        )
        compose.onNodeWithText("Couldn't load suggestions").assertIsDisplayed()
        // The card is the grid's footer, below the saved tile — on the
        // short Robolectric window its button sits below the fold (the
        // shelf title, at the footer's top, is visible without scrolling;
        // this button, at the card's bottom, is not). Scroll it in first.
        compose.onNodeWithTag(MyListRailsRetryTag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MyListRailsRetryTag).performClick()
        assertTrue("retry tap did not fire", retried)
    }

    // ── Hidden list ───────────────────────────────────────────────────

    private fun hiddenContent(
        popular: List<Post> = emptyList(),
        popularLoading: Boolean = false,
        recent: List<Post> = emptyList(),
        railsLoadFailed: Boolean = false,
        onRetryRails: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                // Window-height host, scroll-into-view for the second rail:
                // the hidden state's message, Browse button and two rails
                // are taller than the Robolectric window. A 1000dp Box does
                // NOT help — it makes the Column's viewport taller than its
                // content, so there is nothing to scroll and the rail stays
                // outside the window. fillMaxHeight gives the Column a real
                // scroll range; the second-rail test scrolls the rail in.
                Box(Modifier.width(390.dp).fillMaxHeight()) {
                    MyListHiddenContent(
                        hiddenNotice = "2 saved titles are no longer available.",
                        popular = popular,
                        recent = recent,
                        popularLoading = popularLoading,
                        railsLoadFailed = railsLoadFailed,
                        onRetryRails = onRetryRails,
                        onPostClick = {},
                        onBrowseClick = {},
                    )
                }
            }
        }
    }

    // L4 (round 2): the empty state gets the same inline retry — its
    // helper mirrors hiddenContent (same scroll host, same rails params).
    private fun emptyContent(
        railsLoadFailed: Boolean = false,
        onRetryRails: () -> Unit = {},
    ) {
        compose.setContent {
            RaddStreamTheme {
                Box(Modifier.width(390.dp).fillMaxHeight()) {
                    MyListEmptyContent(
                        popular = emptyList(),
                        onPostClick = {},
                        railsLoadFailed = railsLoadFailed,
                        onRetryRails = onRetryRails,
                    )
                }
            }
        }
    }

    @Test
    fun hiddenBranchShowsTheNoticeAndTheWayOut() {
        hiddenContent()
        compose.onNodeWithText("Nothing to show from your list").assertIsDisplayed()
        compose.onNodeWithText("2 saved titles are no longer available.")
            .assertIsDisplayed()
        compose.onNodeWithText("Browse the catalog").assertIsDisplayed()
    }

    @Test
    fun browseButtonIs48dpTall() {
        hiddenContent()
        val height = with(compose.density) {
            compose.onAllNodesWithTag(MyListBrowseButtonTag)
                .fetchSemanticsNodes().single().boundsInRoot.height.toDp()
        }
        // The floor is a minimum: the button's own M3 content padding
        // makes it taller than 48dp, and that is fine — what the review
        // forbids is a button UNDER the floor, like the old 40dp one.
        assertTrue("Browse button is $height tall, expected at least 48dp", height >= 48.dp)
    }

    @Test
    fun hiddenBranchShowsTheTrendingRailWhenLoaded() {
        hiddenContent(popular = listOf(post(1, "Inception"), post(2, "Dune")))
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        // Audit #9 (Nov 2026): the title is findable only in the tile's
        // paint-only caption now — and the caption sits below the poster,
        // below the Robolectric fold the fallback label used to clear, so
        // the rail is scrolled into view first (as the second-rail test
        // already does for its own rail).
        compose.onNodeWithText("Inception", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun hiddenBranchShowsSecondRailWhenRecentLoaded() {
        // A4-F5: the hidden list gets the same second rail as the
        // truly-empty state, so it never ends in a void either.
        hiddenContent(
            popular = listOf(post(1, "Inception"), post(2, "Dune")),
            recent = listOf(post(3, "Oppenheimer")),
        )
        compose.onNodeWithText("Trending to Start Your List").assertIsDisplayed()
        compose.onNodeWithText("Recently Added").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Oppenheimer", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun hiddenBranchShowsARailSkeletonWhileLoading() {
        hiddenContent(popularLoading = true)
        compose.onNodeWithTag("posterRailSkeleton").assertIsDisplayed()
        compose.onNodeWithText("Trending to Start Your List").assertDoesNotExist()
    }

    @Test
    fun hiddenBranchShowsRailsErrorCardWhenRailsFail() {
        // L4: the rails failed and there is nothing to show — the retry
        // card stands where the rail would have been.
        var retried = false
        hiddenContent(railsLoadFailed = true, onRetryRails = { retried = true })
        compose.onNodeWithText("Couldn't load suggestions").assertIsDisplayed()
        compose.onNodeWithTag(MyListRailsRetryTag).performClick()
        assertTrue("retry tap did not fire", retried)
    }

    @Test
    fun emptyBranchShowsRailsErrorCardWhenRailsFail() {
        // L4: the truly-empty state gets the same inline retry.
        var retried = false
        emptyContent(railsLoadFailed = true, onRetryRails = { retried = true })
        compose.onNodeWithText("Couldn't load suggestions").assertIsDisplayed()
        compose.onNodeWithTag(MyListRailsRetryTag).performClick()
        assertTrue("retry tap did not fire", retried)
    }
}
