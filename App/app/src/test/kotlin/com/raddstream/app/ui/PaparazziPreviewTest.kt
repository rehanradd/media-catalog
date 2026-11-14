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
import com.raddstream.app.player.KeepUiState
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlaybackInfo
import com.raddstream.app.player.PlaybackRendition
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.PrintInfo
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.screens.DetailContent
import com.raddstream.app.ui.screens.EpisodeRow
import com.raddstream.app.ui.screens.FilterRail
import com.raddstream.app.ui.screens.FilterSheetBody
import com.raddstream.app.ui.screens.BottomControls
import com.raddstream.app.ui.screens.CenterSpinner
import com.raddstream.app.ui.screens.EdgeHud
import com.raddstream.app.ui.screens.HomeContent
import com.raddstream.app.ui.screens.TopBar
import com.raddstream.app.ui.screens.SearchContent
import com.raddstream.app.ui.screens.RungActions
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.EpisodeProgress
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
 * internal with its `RungActions` bundle), `SearchContent` (extracted
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
                    PosterCard(post = mclintock, modifier = Modifier.width(112.dp))
                }
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
                title = "Most versions",
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
        streakCount = 2,
        serverStreak = 2,
        streakLadder = mapOf(1 to 5, 2 to 10, 3 to 15, 7 to 50),
        coinBalance = 120,
        signedIn = true,
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
                            HomeSection(key = "trending", title = "Most versions", type = null, loading = true),
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
                    state = HomeUiState(fatal = "Server did not respond."),
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

    private fun fakeOption(band: String, title: String = band, playsHere: Boolean = true) =
        QualityOption(
            band = band,
            title = title,
            streamId = "h-$band",
            playUrl = "http://example.invalid/$band",
            sizeBytes = 1_400_000_000L,
            backupHandles = emptyList(),
            playsHere = playsHere,
            resumable = true,
            copies = 3,
            isProviderPage = false,
        )

    private val movieRungs = RungActions(
        ladder = LadderForScope(
            options = listOf(
                fakeOption("2160p", "4K Ultra HD", playsHere = false),
                fakeOption("1080p", "1080p Full HD"),
                fakeOption("720p", "720p HD"),
                fakeOption("480p", "480p"),
            ),
            autoBand = "1080p",
            downloadOnlyBands = listOf("2160p"),
        ),
        selectedBand = null,
        onPlay = {},
        onKeep = {},
        tierLabel = { if (it.band == "2160p") "VIP" else "Standard" },
        preferredAudio = setOf("Hindi"),
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
                    officialPrintCount = 3,
                    camPrintCount = 1,
                    isSeries = false,
                    resume = null,
                    onPlayResume = {},
                    episodeProgress = emptyMap(),
                    includedLine = "Included with free · 4 prints",
                    selectedSeason = null,
                    onSelectSeason = {},
                    onPlayPrint = {},
                    onOpenExternal = {},
                    onKeep = {},
                    rungs = movieRungs,
                    ladderScope = null,
                    adFree = false,
                    premiumSourcesLocked = true,
                    onAskRungs = { _, _ -> },
                    hasRungs = { _, _ -> false },
                    onOpenVipUpgrade = {},
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
                    officialPrintCount = 3,
                    camPrintCount = 1,
                    isSeries = false,
                    resume = null,
                    onPlayResume = {},
                    episodeProgress = emptyMap(),
                    includedLine = "Included with free · 4 prints",
                    selectedSeason = null,
                    onSelectSeason = {},
                    onPlayPrint = {},
                    onOpenExternal = {},
                    onKeep = {},
                    rungs = movieRungs,
                    ladderScope = null,
                    adFree = false,
                    premiumSourcesLocked = true,
                    onAskRungs = { _, _ -> },
                    hasRungs = { _, _ -> false },
                    onOpenVipUpgrade = {},
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
                    officialPrintCount = 5,
                    camPrintCount = 0,
                    isSeries = true,
                    resume = ResumePoint(season = 1, episode = 2, positionMs = 1_200_000L, durationMs = 3_000_000L),
                    onPlayResume = {},
                    episodeProgress = mapOf(
                        (1 to 1) to EpisodeProgress(fraction = 1.0f, watched = true, remainingLabel = null),
                        (1 to 2) to EpisodeProgress(fraction = 0.4f, watched = false, remainingLabel = "20 min left"),
                    ),
                    includedLine = "Included with free · 5 prints",
                    selectedSeason = 1,
                    onSelectSeason = {},
                    onPlayPrint = {},
                    onOpenExternal = {},
                    onKeep = {},
                    rungs = movieRungs,
                    ladderScope = null,
                    adFree = true,
                    premiumSourcesLocked = false,
                    onAskRungs = { _, _ -> },
                    hasRungs = { _, _ -> true },
                    onOpenVipUpgrade = {},
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
    fun detailSeriesEpisodes() {
        val season1 = seriesSeasons.first()
        paparazzi.snapshot {
            themed {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EpisodeRow(
                        episode = season1.episodes[0],
                        progress = EpisodeProgress(fraction = 1.0f, watched = true, remainingLabel = null),
                        onPlayPrint = {},
                        onOpenExternal = {},
                        onKeep = {},
                        scopeLabel = "Season 1 · Episode 1",
                        canChooseQuality = true,
                        showQuality = false,
                        onChooseQuality = {},
                        rungs = movieRungs,
                    )
                    EpisodeRow(
                        episode = season1.episodes[1],
                        progress = EpisodeProgress(fraction = 0.4f, watched = false, remainingLabel = "20 min left"),
                        onPlayPrint = {},
                        onOpenExternal = {},
                        onKeep = {},
                        scopeLabel = "Season 1 · Episode 2",
                        canChooseQuality = true,
                        showQuality = false,
                        onChooseQuality = {},
                        rungs = movieRungs,
                    )
                    EpisodeRow(
                        episode = EpisodeGroup(
                            season = 1,
                            numberWithinSeason = 3,
                            absoluteNumber = 3,
                            title = "Part 3",
                            prints = listOf(
                                fakePrint(206, "Money Heist S01E03 1080p WEB-DL", "1080p", season = 1),
                            ),
                        ),
                        progress = null,
                        onPlayPrint = {},
                        onOpenExternal = {},
                        onKeep = {},
                        scopeLabel = "Season 1 · Episode 3",
                        canChooseQuality = false,
                        showQuality = false,
                        onChooseQuality = {},
                        rungs = movieRungs,
                    )
                }
            }
        }
    }
    // Search: SearchContent is the body below the rail — the discovery page,
    // the results grid, and the loading / error / empty states all live here.
    // FilterRail and FilterSheetBody are shot on their own: the rail lives in
    // SearchScreen's column, the sheet in a ModalBottomSheet.
    private fun shotSearch(state: SearchUiState) {
        paparazzi.snapshot {
            themed {
                SearchContent(
                    state = state,
                    onRunQuery = {},
                    onClearRecents = {},
                    onSelectGenre = {},
                    onSubmitCurrentQuery = {},
                    onResetFilters = {},
                    onQueryChanged = {},
                    onShowCloseMatches = {},
                    onShowExactOnly = {},
                    onRunSuggestion = {},
                    onLoadMore = {},
                    onPostClick = {},
                )
            }
        }
    }

    private val searchDiscovery = SearchUiState(
        recents = listOf("Jawan", "Salaar", "Money Heist"),
        trending = homePosts,
    )

    private val searchResults = SearchUiState(
        query = "jwan",
        groups = listOf(
            TypeGroup(label = "Movies", typeKey = "movie", items = listOf(jawan, salaars)),
            TypeGroup(label = "Series", typeKey = "series", items = listOf(mclintock)),
        ),
        resultCount = 3,
        serverTotal = 3,
        hasMore = true,
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
        shotSearch(searchResults)
    }

    @Test
    fun searchLoading() {
        shotSearch(SearchUiState(query = "jawan", isLoading = true, hasSearched = true))
    }

    @Test
    fun searchError() {
        shotSearch(
            SearchUiState(
                query = "jawan",
                error = "Couldn't reach the server. Check your connection and try again.",
                hasSearched = true,
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
    // strip (paused only) and the keep chip; TopBar the title + lock +
    // print sheet; CenterSpinner the buffering spinner only — there is no
    // centre play/pause button anymore, so the playing/paused shots are
    // empty frames, which is the proof.
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

    private fun shotBottom(state: PlayerUiState) {
        paparazzi.snapshot {
            themed {
                BottomControls(
                    state = state,
                    onPlayFromStart = {},
                    onTogglePlayPause = {},
                    onToggleMute = {},
                    onShowSubtitleSheet = {},
                    onShowQualitySheet = {},
                    onToggleFullscreen = {},
                    onShowMoreSheet = {},
                    onSeekTo = {},
                    onSetUserVolume = {},
                    onSetAudioBoostLevel = {},
                    onKeepCurrent = {},
                    onEnterPip = {},
                    onActivity = {},
                    onScrubCommit = {},
                )
            }
        }
    }

    @Test
    fun playerTopBar() {
        paparazzi.snapshot {
            themed {
                TopBar(
                    state = playerBase(),
                    onBack = {},
                    onLock = {},
                    onShowPrintSheet = {},
                )
            }
        }
    }

    @Test
    fun playerCenterPlaying() {
        paparazzi.snapshot {
            themed {
                CenterSpinner(
                    state = playerBase(isPlaying = true),
                )
            }
        }
    }

    @Test
    fun playerCenterPaused() {
        paparazzi.snapshot {
            themed {
                CenterSpinner(
                    state = playerBase(isPlaying = false),
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
    fun playerBottomPlaying() {
        shotBottom(playerBase())
    }

    @Test
    fun playerBottomPaused() {
        shotBottom(
            playerBase(isPlaying = false).copy(
                castNames = listOf("Shah Rukh Khan", "Nayanthara", "Vijay Sethupathi"),
                keep = KeepUiState.Stored(playableOffline = true, note = null),
            ),
        )
    }

    @Test
    fun playerBottomLive() {
        // F29: a live HLS playlist can report a finite DVR-window duration
        // (Discover Pakistan: 2:00:12) — the shot must show the LIVE mark
        // and no scrubber, whatever the duration claims.
        shotBottom(
            playerBase().copy(
                isLive = true,
                durationMs = 7_200_000L,
                positionMs = 3_600_000L,
            ),
        )
    }

    @Test
    fun playerBottomSingleRung() {
        // 25c: one rung only — the Quality primary stays visible, dimmed,
        // instead of vanishing.
        shotBottom(
            playerBase().copy(
                qualityAuto = false,
                qualityRungs = listOf(LadderRung(band = "1080p", streamId = "h-1080p")),
            ),
        )
    }

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
