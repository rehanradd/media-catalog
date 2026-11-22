package com.raddstream.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.FacetEntry
import com.raddstream.app.network.models.FacetsResponse
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.PanelBroadcast
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.network.models.currentCalendarYear
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.screens.DetailContent
import com.raddstream.app.ui.screens.EpisodeTile
import com.raddstream.app.ui.screens.SeasonPackRow
import com.raddstream.app.ui.screens.SeasonTile
import com.raddstream.app.ui.screens.FilterRail
import com.raddstream.app.ui.screens.FilterSheetBody
import com.raddstream.app.ui.screens.CenterPlayPause
import com.raddstream.app.ui.screens.CenterSpinner
import com.raddstream.app.ui.screens.ChipRow
import com.raddstream.app.ui.screens.EdgeFeedbackLine
import com.raddstream.app.ui.screens.EdgeHud
import com.raddstream.app.ui.screens.HomeContent
import com.raddstream.app.ui.screens.Hud
import com.raddstream.app.ui.screens.MyListEmptyContent
import com.raddstream.app.ui.screens.TopBar
import com.raddstream.app.ui.screens.SearchContent
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.GenreFilter
import com.raddstream.app.viewmodel.HomeUiState
import com.raddstream.app.viewmodel.IndustryFilter
import com.raddstream.app.viewmodel.SearchFilters
import com.raddstream.app.viewmodel.SearchSort
import com.raddstream.app.viewmodel.SearchUiState
import com.raddstream.app.viewmodel.TypeGroup
import com.raddstream.app.viewmodel.RankedLink
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.ResumePoint
import com.raddstream.app.viewmodel.ResumeTile
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi trial (2026-11-15): renders the REAL composables the app ships —
 * never hand-written stand-ins — to PNGs on the JVM in CI, no emulator, no
 * device. The first run records goldens (`:app:recordPaparazziDebug`); later
 * runs verify against them (`:app:verifyPaparazziDebug`, also inside
 * `testDebugUnitTest`).
 *
 * Each screen gets its own stateless content composable fed with fake state:
 * `HomeContent` (extracted from HomeScreen), `DetailContent` (made
 * internal; 2-button detail, Nov 2026: two buttons, no rung cards),
 * `SearchContent` (extracted
 * from SearchScreen) and the player chrome (`TopBar`, `CenterSpinner`,
 * `EdgeHud`, `BottomControls`, `KeepChip` — all internal with callbacks).
 * The volume dialog is not shot: it opens only on a long-press, which a
 * snapshot cannot perform.
 *
 * Poster art is a null URL on purpose: Coil cannot load network images under
 * Layoutlib, so the placeholder shows — the layout, type, badges and rank
 * treatment are what these shots judge, not the photo.
 */
class PaparazziPreviewTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun fakePost(
        id: Int,
        title: String,
        year: Int,
        contentType: String,
        rating: String,
        genres: String,
        industry: String,
        language: String,
    ) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = contentType,
        posterUrl = null,
        imdbRating = rating,
        genres = genres,
        industry = industry,
        language = language,
    )

    private val jawan = fakePost(
        id = 1,
        title = "Jawan",
        year = 2023,
        contentType = "movie",
        rating = "7.0",
        genres = "Action, Thriller",
        industry = "Bollywood",
        language = "Hindi",
    )
    private val salaars = fakePost(
        id = 2,
        title = "Salaar: Part 1 – Ceasefire",
        year = 2023,
        contentType = "movie",
        rating = "6.5",
        genres = "Action",
        industry = "Tollywood",
        language = "Telugu",
    )
    private val mclintock = fakePost(
        id = 3,
        title = "McLintock!",
        year = 1963,
        contentType = "movie",
        rating = "7.0",
        genres = "Comedy, Western",
        industry = "Hollywood",
        language = "English",
    )

    // Rail-title cleanup (Nov 2026): the third rail card carries the
    // suffix stack the catalog actually sends, so the golden proves the
    // tile paints the stripped title ("No Hard Feelings"), not the raw
    // "(2023) English Movie" form the meta line already states.
    private val noHardFeelings = fakePost(
        id = 4,
        title = "No Hard Feelings (2023) English Movie",
        year = 2023,
        contentType = "movie",
        rating = "6.5",
        genres = "Comedy",
        industry = "Hollywood",
        language = "English",
    )

    /** Dark-first, like the shipped app: RaddStreamTheme defaults to the brand scheme. */
    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    @Test
    fun posterCardSingleRankedWithProgress() {
        paparazzi.snapshot {
            themed {
                PosterCard(
                    post = jawan,
                    modifier = Modifier.width(160.dp).padding(16.dp),
                    rank = 1,
                    progressFraction = 0.45f,
                    progressLabel = "23 min left",
                )
            }
        }
    }

    // Gemini home review (Nov 2026): max one badge per card — a ranked
    // new-release tile shows the rank only, never a NEW pill beside it.
    // The year reads off the clock so the fixture stays a new release
    // whenever this runs.
    @Test
    fun posterCardRankedNewReleaseShowsRankOnly() {
        paparazzi.snapshot {
            themed {
                PosterCard(
                    post = fakePost(
                        id = 5,
                        title = "New Hit",
                        year = currentCalendarYear(),
                        contentType = "movie",
                        rating = "8.0",
                        genres = "Action",
                        industry = "Hollywood",
                        language = "English",
                    ),
                    modifier = Modifier.width(160.dp).padding(16.dp),
                    rank = 1,
                )
            }
        }
    }

    @Test
    fun posterCardRailRow() {
        paparazzi.snapshot {
            themed {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosterCard(post = jawan, modifier = Modifier.width(112.dp), rank = 1)
                    PosterCard(post = salaars, modifier = Modifier.width(112.dp), rank = 2)
                    PosterCard(post = noHardFeelings, modifier = Modifier.width(112.dp))
                }
            }
        }
    }

    // My List review (Nov 2026): the empty state — the jade heart in its
    // tinted container, the retitled starter rail, the compact
    // "★ 7.0 · 2023" meta, and the rail pulled up under the message
    // instead of floating in a void. The first card is saved, so the shot
    // also pins the filled jade heart against the two outline hearts.
    @Test
    fun myListEmptyState() {
        paparazzi.snapshot {
            themed {
                MyListEmptyContent(
                    popular = listOf(jawan, salaars, mclintock),
                    onPostClick = {},
                    isFavorite = { it.id == 1 },
                    onToggleFavorite = {},
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun filterChipStates() {
        paparazzi.snapshot {
            themed {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("Hindi") },
                        colors = raddFilterChipColors(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text("English") },
                        colors = raddFilterChipColors(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }

    /**
     * Settings review (Gemini 5.5/10, 2026-11-16): the real ChipRow from
     * SettingsScreen — the 44dp visible pill (12dp label padding) and the
     * strengthened selected state (1.5dp jade border + 16% jade tint fill,
     * unselected softened to #333333 / #B3B3B3). The golden is the only
     * place the pill height is observable: semantics merge the label into
     * the chip node, so a unit test sees the 48dp touch target, not the
     * 44dp pill.
     */
    @Test
    fun settingsChipRow() {
        paparazzi.snapshot {
            themed {
                ChipRow(
                    options = listOf("Auto", "High", "Medium", "Battery saver"),
                    selectedIndex = 1,
                    onSelect = {},
                )
            }
        }
    }

    // ── HomeContent: the assembled Home page from fake state ─────────────────
    // lastHomeSyncMs stays null on purpose: the sync line dates rows from the
    // wall clock, and a relative "x min ago" would make the golden flaky.
    // heroAutoplay is off: the carousel's timer never settles in a snapshot.

    private val homePosts = listOf(jawan, salaars, mclintock) + fakePost(
        id = 4,
        title = "Pushpa 2: The Rule",
        year = 2024,
        contentType = "movie",
        rating = "7.1",
        genres = "Action, Crime",
        industry = "Tollywood",
        language = "Telugu",
    )

    private val loadedHome = HomeUiState(
        sections = listOf(
            HomeSection(
                key = "trending",
                title = "Most available",
                type = null,
                items = homePosts,
                sort = "trending",
            ),
            HomeSection(
                key = "bollywood",
                title = "Bollywood",
                type = "movie",
                items = listOf(jawan, salaars),
                industry = "bollywood",
            ),
        ),
        continueWatching = listOf(
            ResumeTile(
                post = jawan,
                season = 1,
                episode = 3,
                positionMs = 1_500_000L,
                durationMs = 2_700_000L,
                workRef = "work:jawan",
            ),
        ),
        broadcasts = listOf(
            PanelBroadcast(
                id = 1,
                title = "Live TV is here",
                body = "Watch 23 channels free.",
                severity = "info",
            ),
        ),
        trialDaysRemaining = 5,
        favoriteIds = setOf(1),
        facets = FacetsResponse(
            genres = listOf(FacetEntry("action", "Action", 120), FacetEntry("drama", "Drama", 98)),
            languages = listOf(FacetEntry("hindi", "Hindi", 210)),
            industries = listOf(
                FacetEntry("bollywood", "Bollywood", 4275),
                FacetEntry("tollywood", "Tollywood", 856),
            ),
        ),
    )

    @Test
    fun homeLoaded() {
        paparazzi.snapshot {
            themed {
                HomeContent(state = loadedHome, heroAutoplay = false)
            }
        }
    }

    @Test
    fun homeLoading() {
        paparazzi.snapshot {
            themed {
                HomeContent(
                    state = HomeUiState(
                        sections = listOf(
                            HomeSection(key = "trending", title = "Most available", type = null, loading = true),
                            HomeSection(key = "bollywood", title = "Bollywood", type = "movie", loading = true),
                        ),
                    ),
                    heroAutoplay = false,
                )
            }
        }
    }

    @Test
    fun homeError() {
        paparazzi.snapshot {
            themed {
                HomeContent(
                    state = HomeUiState(fatal = "Couldn't reach the catalog."),
                    heroAutoplay = false,
                )
            }
        }
    }

    @Test
    fun homeEmpty() {
        paparazzi.snapshot {
            themed {
                HomeContent(state = HomeUiState(), heroAutoplay = false)
            }
        }
    }

    // ── DetailContent: the assembled detail page from fake state ─────────────
    // Same fake-print pattern as VipQualityGateTest: describe() parses the
    // release title into a real PrintDescriptor, so the rows render exactly
    // what the production builder would produce for that title.

    private fun descriptor(releaseTitle: String, quality: String?) =
        ReleaseLabelParsing.describe(releaseTitle = releaseTitle, quality = quality)

    private fun fakePrint(releaseId: Int, releaseTitle: String, quality: String?, season: Int? = null) =
        RankedPrint(
            releaseId = releaseId,
            rawTitle = "$quality copy",
            descriptor = descriptor(releaseTitle, quality),
            languages = listOf("Hindi"),
            sizeBytes = 1_400_000_000L,
            coverage = CoverageType.WHOLE_WORK,
            season = season,
            episodeFrom = null,
            episodeTo = null,
            // One healthy, directly playable link: a print with no links renders
            // as "Not available to stream yet", which is the download-only
            // state — the shot must judge the Play state real viewers see.
            links = listOf(
                RankedLink(
                    streamId = "h-$releaseId",
                    linkId = releaseId,
                    health = LinkState.ACTIVE,
                    ephemeral = false,
                    resumable = true,
                    playUrl = "http://example.invalid/play/$releaseId",
                    label = "Fast direct",
                    kind = "direct",
                    playability = Playability.STREAM,
                    score = 100,
                ),
            ),
        )

    private val jawanDetail = PostDetail(
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
        synopsis = "A man is driven by a personal vendetta to rectify the wrongs in society, while also protecting a special group of people.",
        castNames = "Shah Rukh Khan, Nayanthara, Vijay Sethupathi",
        industry = "Bollywood",
        language = "Hindi",
    )

    private val moviePrints = listOf(
        fakePrint(101, "Jawan (2023) Hindi 2160p WEB-DL", "2160p"),
        fakePrint(102, "Jawan (2023) Hindi 1080p WEB-DL", "1080p"),
        fakePrint(103, "Jawan (2023) Hindi 720p WEB-DL", "720p"),
        fakePrint(104, "Jawan (2023) Hindi CAM 480p", "480p"),
    )

    @Test
    fun detailMovie() {
        paparazzi.snapshot {
            themed {
                DetailContent(
                    post = jawanDetail,
                    seasons = emptyList(),
                    workPrints = moviePrints,
                    capabilities = listOf("Hindi audio", "English subtitles"),
                    isSeries = false,
                    resume = null,
                    includedPill = "VIP",
                    // 2-button detail (Nov 2026): the page shows two
                    // buttons, not rung cards — the golden records them.
                    playLabel = "Play · 1080p",
                    playEnabled = true,
                    downloadLabel = "Download",
                    downloadEnabled = true,
                    onOpenSeason = {},
                    similarTitles = listOf(salaars, mclintock),
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
    fun detailMovieError() {
        paparazzi.snapshot {
            themed {
                DetailContent(
                    post = jawanDetail,
                    seasons = emptyList(),
                    workPrints = moviePrints,
                    capabilities = listOf("Hindi audio", "English subtitles"),
                    isSeries = false,
                    resume = null,
                    includedPill = "VIP",
                    onOpenSeason = {},
                    playLabel = "Play · 1080p",
                    playEnabled = true,
                    downloadLabel = "Download",
                    downloadEnabled = true,
                    similarTitles = listOf(salaars, mclintock),
                    reloadError = "Could not refresh this title.",
                    onRetryReload = {},
                    isFavorite = true,
                    onToggleFavorite = {},
                    onShare = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    private val seriesDetail = PostDetail(
        id = 2,
        title = "Money Heist",
        cleanTitle = "Money Heist",
        year = 2017,
        contentType = "series",
        posterUrl = null,
        backdropUrls = null,
        imdbRating = "8.2",
        genres = "Crime, Drama",
        synopsis = "A criminal mastermind known as The Professor plans the biggest heist in recorded history.",
        castNames = "Úrsula Corberó, Álvaro Morte, Pedro Alonso",
        industry = "Hollywood",
        language = "English",
    )

    private val seriesSeasons = listOf(
        SeasonGroup(
            number = 1,
            episodes = listOf(
                EpisodeGroup(
                    season = 1,
                    numberWithinSeason = 1,
                    absoluteNumber = 1,
                    title = "Part 1",
                    prints = listOf(
                        fakePrint(201, "Money Heist S01E01 1080p WEB-DL", "1080p", season = 1),
                        fakePrint(202, "Money Heist S01E01 720p WEB-DL", "720p", season = 1),
                    ),
                ),
                EpisodeGroup(
                    season = 1,
                    numberWithinSeason = 2,
                    absoluteNumber = 2,
                    title = "Part 2",
                    prints = listOf(
                        fakePrint(203, "Money Heist S01E02 1080p WEB-DL", "1080p", season = 1),
                    ),
                ),
            ),
            packedPrints = listOf(
                fakePrint(204, "Money Heist S01 Complete 1080p WEB-DL", "1080p", season = 1),
            ),
        ),
        SeasonGroup(
            number = 2,
            episodes = listOf(
                EpisodeGroup(
                    season = 2,
                    numberWithinSeason = 1,
                    absoluteNumber = 3,
                    title = "Part 3",
                    prints = listOf(
                        fakePrint(205, "Money Heist S02E01 1080p WEB-DL", "1080p", season = 2),
                    ),
                ),
            ),
            packedPrints = emptyList(),
        ),
    )

    @Test
    fun detailSeries() {
        paparazzi.snapshot {
            themed {
                DetailContent(
                    post = seriesDetail,
                    seasons = seriesSeasons,
                    workPrints = emptyList(),
                    capabilities = listOf("English audio", "Hindi subtitles"),
                    isSeries = true,
                    resume = ResumePoint(season = 1, episode = 2, positionMs = 1_200_000L, durationMs = 3_000_000L),
                    includedPill = "Free · up to 720p",
                    onOpenSeason = {},
                    // 2-button detail (Nov 2026): resume names the
                    // episode and the quality; the golden records it.
                    playLabel = "Resume S1:E2 · 720p",
                    playEnabled = true,
                    downloadLabel = "Download",
                    downloadEnabled = true,
                    similarTitles = listOf(jawan, mclintock),
                    reloadError = null,
                    onRetryReload = {},
                    isFavorite = true,
                    onToggleFavorite = {},
                    onShare = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    // The episode rows live below the fold of the full detail page (the
    // LazyColumn only composes the viewport), so they get their own shot:
    // a watched episode, one in progress, and one never started.
    @Test
    fun detailSeasonTiles() {
        // Detail redesign (Nov 2026): the season sub-page is plain
        // numbered tiles — the next episode filled jade, a watched one
        // with a small jade tick, a number with no playable file dimmed
        // and never hidden — and a whole-season file gets one row, not
        // fake numbers. The page lays the tiles out as rows of six in a
        // LazyColumn; the shot renders one row of tiles plus the pack row
        // so the golden pins the tile visuals without the list's
        // unbounded-height measurement.
        paparazzi.snapshot {
            themed {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EpisodeTile(
                            tile = SeasonTile(number = 1, playable = true, watched = true, isNext = false),
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        EpisodeTile(
                            tile = SeasonTile(number = 2, playable = true, watched = false, isNext = true),
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        EpisodeTile(
                            tile = SeasonTile(number = 3, playable = false, watched = false, isNext = false),
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        EpisodeTile(
                            tile = SeasonTile(number = 4, playable = true, watched = false, isNext = false),
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SeasonPackRow(
                        season = seriesSeasons.first(),
                        onPlay = {},
                        onKeep = {},
                    )
                }
            }
        }
    }

    // Search: SearchContent is the body below the rail — the discovery page,
    // the results grid, and the loading / error / empty states all live here.
    // FilterRail and FilterSheetBody are shot on their own: the rail lives in
    // SearchScreen's column, the sheet in a ModalBottomSheet.
    // Feel-instant S6: SearchContent reads the PagingData now — the shots
    // feed it a static PagingData through a StateFlow, the way the Paging
    // test guide drives load states in isolation, and the groups arrive as
    // a parameter (derived from the snapshot by the screen).
    private fun shotSearch(
        state: SearchUiState,
        groups: List<TypeGroup> = emptyList(),
        pagingData: PagingData<Post> = PagingData.empty(),
    ) {
        val pagingDataFlow = MutableStateFlow(pagingData)
        paparazzi.snapshot {
            themed {
                val pagingItems = pagingDataFlow.collectAsLazyPagingItems()
                SearchContent(
                    state = state,
                    pagingItems = pagingItems,
                    groups = groups,
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
    }

    private val searchDiscovery = SearchUiState(
        recents = listOf("Jawan", "Salaar", "Money Heist"),
        trending = homePosts,
        // Mockup (Nov 2026): the discovery page adds "Recommended for you"
        // under the recents — 4 posts exercises the grid's incomplete last
        // row (3 + 1) in the golden.
        recommended = homePosts,
    )

    private val searchResultGroups = listOf(
        TypeGroup(label = "Movies", typeKey = "movie", items = listOf(jawan, salaars)),
        TypeGroup(label = "Series", typeKey = "series", items = listOf(mclintock)),
    )

    private val searchResults = SearchUiState(
        query = "jwan",
        serverTotal = 3,
        hasSearched = true,
        // The query is one edit away from a real title, so both the
        // did-you-mean line and the fuzzy banner render in this shot.
        didYouMean = "jawan",
        isFuzzy = true,
    )

    @Test
    fun searchDiscovery() {
        shotSearch(searchDiscovery)
    }

    @Test
    fun searchResults() {
        // The append is not at its end, so the count line still reads the
        // capped "Showing 3 of 3 results" the old golden carried — the
        // footer below it is gone (S6 retires Load more / Show all), so
        // this golden is refreshed by the record run.
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        shotSearch(
            searchResults,
            groups = searchResultGroups,
            pagingData = PagingData.from(
                data = listOf(jawan, salaars, mclintock),
                sourceLoadStates = LoadStates(refresh = idle, prepend = idle, append = idle),
            ),
        )
    }

    @Test
    fun searchLoading() {
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        shotSearch(
            SearchUiState(query = "jawan", hasSearched = true),
            pagingData = PagingData.empty(
                sourceLoadStates = LoadStates(
                    refresh = LoadState.Loading,
                    prepend = idle,
                    append = idle,
                ),
            ),
        )
    }

    @Test
    fun searchError() {
        // The message is the friendly one for an unresolvable host now —
        // the old golden carried a hardcoded string the app never showed —
        // so this golden is refreshed by the record run.
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        shotSearch(
            SearchUiState(query = "jawan", hasSearched = true),
            pagingData = PagingData.empty(
                sourceLoadStates = LoadStates(
                    refresh = LoadState.Error(IOException("Unable to resolve host")),
                    prepend = idle,
                    append = idle,
                ),
            ),
        )
    }

    @Test
    fun searchEmpty() {
        shotSearch(SearchUiState(query = "zzzzqqq", hasSearched = true))
    }

    @Test
    fun searchEmptyFiltered() {
        shotSearch(
            SearchUiState(
                query = "jawan",
                hasSearched = true,
                filters = SearchFilters(
                    genre = GenreFilter.ACTION,
                    industry = IndustryFilter.BOLLYWOOD,
                ),
            ),
        )
    }

    @Test
    fun searchFilterRail() {
        paparazzi.snapshot {
            themed {
                FilterRail(
                    state = SearchUiState(
                        filters = SearchFilters(
                            type = "movie",
                            genre = GenreFilter.ACTION,
                            industry = IndustryFilter.BOLLYWOOD,
                        ),
                        sort = SearchSort.HIGHEST_IMDB,
                    ),
                    onSelectType = {},
                    onOpenFilters = {},
                )
            }
        }
    }

    @Test
    fun searchFilterSheet() {
        paparazzi.snapshot {
            themed {
                FilterSheetBody(
                    state = SearchUiState(
                        filters = SearchFilters(
                            type = "movie",
                            genre = GenreFilter.ACTION,
                            industry = IndustryFilter.BOLLYWOOD,
                            yearFrom = 2020,
                            yearTo = 2024,
                        ),
                        sort = SearchSort.HIGHEST_IMDB,
                    ),
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

    // Player chrome: the standard three zones plus Ghost mode's EdgeHud.
    // BottomControls carries the six primaries, the scrubber, the cast
    // strip (paused only) and the keep chip; TopBar the title + live badge +
    // lock + print sheet; CenterSpinner the buffering spinner only;
    // CenterPlayPause the frosted centre transport, so the playing/paused
    // shots show the button and the buffering shot shows the ring.
    // EdgeFeedbackLine is the slim edge track that replaced the centre HUD card.
    private val playerRendition = PlaybackRendition(
        print = PrintInfo(resolution = "1080p"),
        playback = PlaybackInfo(label = "Fast direct"),
    )

    private fun playerBase(
        isPlaying: Boolean = true,
        positionMs: Long = 2_730_000L,
        durationMs: Long = 9_540_000L,
    ) = PlayerUiState(
        // startPhase is derived, never stored: connected + a known duration
        // (and not playing) is what makes it NONE, so the centre controls
        // show the transport button instead of the start spinner.
        connected = true,
        isPlaying = isPlaying,
        positionMs = positionMs,
        bufferedMs = 3_000_000L,
        durationMs = durationMs,
        userVolume = 0.8f,
        pendingTitle = "Jawan",
        titleYear = 2023,
        activeRendition = playerRendition,
        qualityBand = "1080p",
        qualityAuto = true,
        autoQualityBand = "1080p",
        qualityRungs = listOf(
            LadderRung(band = "1080p", streamId = "h-1080p"),
            LadderRung(band = "720p", streamId = "h-720p"),
        ),
    )

    @Test
    fun playerTopBar() {
        paparazzi.snapshot {
            themed {
                TopBar(
                    state = playerBase(),
                    onBack = {},
                    onLock = {},
                    onShowSpeedSheet = {},
                    onToggleDecoderMode = {},
                )
            }
        }
    }

    @Test
    fun playerTopBarLive() {
        paparazzi.snapshot {
            themed {
                TopBar(
                    state = playerBase().copy(
                        isLive = true,
                        pendingTitle = "Madani Channel Urdu",
                        titleYear = null,
                        activeRendition = PlaybackRendition(
                            print = PrintInfo(resolution = "1080p"),
                            playback = PlaybackInfo(label = "Live", playUrl = "https://example.com/live.m3u8"),
                        ),
                    ),
                    onBack = {},
                    onLock = {},
                    onShowSpeedSheet = {},
                    onToggleDecoderMode = {},
                )
            }
        }
    }

    // Review fix (16 Nov): the centre button exists only while paused, so
    // there is no playing-centre shot to record — the subject is gone.
    // playerCenterPaused below is the centre shot now.

    @Test
    fun playerCenterPaused() {
        paparazzi.snapshot {
            themed {
                CenterPlayPause(
                    state = playerBase(isPlaying = false),
                    onTogglePlayPause = {},
                    onPlayFromStart = {},
                    onSeekBy = {},
                )
            }
        }
    }

    @Test
    fun playerCenterBuffering() {
        paparazzi.snapshot {
            themed {
                CenterSpinner(
                    state = playerBase().copy(isBuffering = true),
                )
            }
        }
    }

    @Test
    fun playerEdgeFeedbackVolume() {
        paparazzi.snapshot {
            themed {
                EdgeFeedbackLine(Hud.Volume(0.6f))
            }
        }
    }

    @Test
    fun playerEdgeFeedbackBrightness() {
        paparazzi.snapshot {
            themed {
                EdgeFeedbackLine(Hud.Brightness(0.4f))
            }
        }
    }

    // Review fix (16 Nov): the bottom-row shots moved to
    // PlayerLandscapePaparazziTest — the row needs a landscape frame, and a
    // portrait golden would enshrine a clipped More button.

    @Test
    fun playerGhost() {
        paparazzi.snapshot {
            themed {
                EdgeHud(
                    state = playerBase(),
                    onBack = {},
                    onPlayFromStart = {},
                    onTogglePlayPause = {},
                    onToggleViewMode = {},
                    onLock = {},
                )
            }
        }
    }

}
