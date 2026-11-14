package com.raddstream.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import com.raddstream.app.network.models.FacetEntry
import com.raddstream.app.network.models.FacetsResponse
import com.raddstream.app.network.models.PanelBroadcast
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.screens.HomeContent
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import com.raddstream.app.viewmodel.ResumeTile
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi trial (2026-11-15): renders the REAL composables the app ships —
 * never hand-written stand-ins — to PNGs on the JVM in CI, no emulator, no
 * device. The first run records goldens (`:app:recordPaparazziDebug`); later
 * runs verify against them (`:app:verifyPaparazziDebug`, also inside
 * `testDebugUnitTest`).
 *
 * Scoped to PUBLIC composables on purpose: the Home sections (`HomeRailSection`
 * etc.) are file-private, so the full assembled Home screen cannot be
 * snapshotted until a stateless `HomeContent(state, callbacks)` is extracted
 * (or a `@Preview` builds a fake ViewModel). That extraction is a main-source
 * change and is the follow-up, not this trial.
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
}
