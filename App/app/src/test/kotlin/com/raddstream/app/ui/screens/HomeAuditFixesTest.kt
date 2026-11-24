package com.raddstream.app.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PanelBroadcast
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.HomeUiState
import com.raddstream.app.viewmodel.ResumeTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI/UX audit fixes (Nov 2026), Home screen — the behaviours the audit
 * ranked #1–#13, pinned where a test can say something real. Pixels are
 * the Paparazzi goldens' job, not this file's: these tests pin the
 * decisions (which titles the hero shows, which actions the empty and
 * error states offer, that the loading skeletons render) and the touch
 * targets.
 */
@RunWith(RobolectricTestRunner::class)
class HomeAuditFixesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun post(id: Int, title: String, year: Int, workRef: String? = null) = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = year,
        contentType = "movie",
        posterUrl = null,
        imdbRating = "7.0",
        genres = "Action",
        industry = "bollywood",
        language = "Hindi",
        workRef = workRef,
    )

    private fun resumeTile(titlePost: Post) = ResumeTile(
        post = titlePost,
        season = 1,
        episode = 3,
        positionMs = 1_500_000L,
        durationMs = 2_700_000L,
        workRef = "work:${titlePost.title.lowercase()}",
    )

    // ── Audit #1: the hero never repeats a Continue Watching title ──────

    @Test
    fun heroExcludesContinueWatchingTitles() {
        val jawan = post(1, "Jawan", 2023, workRef = "work:jawan")
        val salaars = post(2, "Salaar", 2023, workRef = "work:salaar")
        val section = HomeSection(
            key = "trending",
            title = "Most available",
            type = null,
            items = listOf(jawan, salaars),
        )
        val result = heroItemsExcludingContinueWatching(section, listOf(resumeTile(jawan)), heroMax = 6)
        assertEquals(listOf(salaars), result)
    }

    @Test
    fun heroBackfillsPastHeroMaxAfterExclusion() {
        val jawan = post(1, "Jawan", 2023, workRef = "work:jawan")
        val salaars = post(2, "Salaar", 2023, workRef = "work:salaar")
        val rest = (3..8).map { post(it, "Title $it", 2024, workRef = "work:title$it") }
        val section = HomeSection(
            key = "trending",
            title = "Most available",
            type = null,
            items = listOf(jawan, salaars) + rest,
        )
        // Six slides, two of them Continue Watching: the hero still has
        // six slides, backfilled from the rail's own later rows.
        val result = heroItemsExcludingContinueWatching(
            section,
            listOf(resumeTile(jawan), resumeTile(salaars)),
            heroMax = 6,
        )
        assertEquals(6, result.size)
        assertTrue(result.none { it.id == 1 || it.id == 2 })
    }

    @Test
    fun heroIsEmptyWhenEverySlideIsContinueWatching() {
        val jawan = post(1, "Jawan", 2023, workRef = "work:jawan")
        val section = HomeSection(
            key = "trending",
            title = "Most available",
            type = null,
            items = listOf(jawan),
        )
        assertEquals(
            emptyList<Post>(),
            heroItemsExcludingContinueWatching(section, listOf(resumeTile(jawan)), heroMax = 6),
        )
    }

    @Test
    fun heroExclusionIgnoresNullSectionAndZeroMax() {
        val jawan = post(1, "Jawan", 2023, workRef = "work:jawan")
        assertEquals(
            emptyList<Post>(),
            heroItemsExcludingContinueWatching(null, listOf(resumeTile(jawan)), heroMax = 6),
        )
        val section = HomeSection(
            key = "trending",
            title = "Most available",
            type = null,
            items = listOf(jawan),
        )
        assertEquals(
            emptyList<Post>(),
            heroItemsExcludingContinueWatching(section, emptyList(), heroMax = 0),
        )
    }

    @Test
    fun heroExclusionFallsBackToTitleAndYearWithoutWorkRefs() {
        // A Continue Watching row built from a local row carries no work
        // ref on its post — the match is title + year then, the same
        // identity the rail dedupe already uses.
        val jawan = post(1, "Jawan", 2023)
        val jawan2024 = post(2, "Jawan", 2024)
        val section = HomeSection(
            key = "trending",
            title = "Most available",
            type = null,
            items = listOf(jawan, jawan2024),
        )
        assertEquals(
            listOf(jawan2024),
            heroItemsExcludingContinueWatching(section, listOf(resumeTile(jawan)), heroMax = 6),
        )
    }

    // ── Audit #4 + #5: the empty state seeks content, in plain words ───

    @Test
    fun emptyStateOffersBrowseTitlesAndRenamedAddress() {
        var browsed: String? = "unset"
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(),
                    heroAutoplay = false,
                    onBrowseType = { browsed = it },
                )
            }
        }
        compose.onNodeWithText("Browse titles").assertExists()
        compose.onNodeWithText("Change server address").assertExists()
        compose.onNodeWithText("Try a different address").assertDoesNotExist()
        // DIAGNOSTIC (2027 Home fix, 24 Nov): the click below stopped
        // reaching onBrowseType once the empty-state art changed. Dump the
        // tree and prove the node carries a click action before clicking.
        compose.onRoot().printToLog("HOME-EMPTY-TEST")
        compose.onNodeWithText("Browse titles").assertHasClickAction()
        compose.onNodeWithText("Browse titles").performClick()
        assertTrue(browsed == null)
    }

    // ── Audit #5 + #6: the error state retries filled, address renamed ──

    @Test
    fun errorStateOffersRetryAndRenamedAddress() {
        var settingsOpened = false
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(fatal = "Couldn't reach the catalog."),
                    heroAutoplay = false,
                    onOpenSettingsClick = { settingsOpened = true },
                )
            }
        }
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Change server address").assertExists()
        compose.onNodeWithText("Try a different address").assertDoesNotExist()
        compose.onNodeWithText("Titles load from this address — if it changed, update it here.")
            .assertExists()
        // S4(c) (2027 Home fix): the third door — the Settings screen,
        // where the server address and everything else lives.
        compose.onNodeWithText("Open Settings").assertExists()
        compose.onNodeWithText("Open Settings").performClick()
        assertTrue(settingsOpened)
    }

    // ── Audit #3: rail skeletons render in a snapshot's frozen clock ────

    @Test
    fun loadingRailSkeletonRendersImmediatelyInInspectionMode() {
        // The clock is frozen: the rails' stagger delay never elapses, so
        // without the inspection-mode entrance the skeleton would never
        // appear — the exact gap the homeLoading golden showed.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                RaddStreamTheme {
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
        compose.onNodeWithText("Most available").assertExists()
        compose.onNodeWithTag("posterRailSkeleton").assertExists()
    }

    // ── Audit #12: the three dismissals are 48dp, in the end corner ─────

    @Test
    fun dismissButtonsAre48dp() {
        val jawan = post(1, "Jawan", 2023, workRef = "work:jawan")
        compose.setContent {
            RaddStreamTheme {
                HomeContent(
                    state = HomeUiState(
                        // No sections: the trial note is the 5th lazy item
                        // (chips, Continue Watching, broadcast, trial — the
                        // broadcasts ride below CW now, H1), so it is
                        // composed in the first Robolectric viewport.
                        // With a hero + rail above it, the lazy column
                        // never composes it and the node does not exist.
                        sections = emptyList(),
                        continueWatching = listOf(resumeTile(jawan)),
                        broadcasts = listOf(
                            PanelBroadcast(
                                id = 1,
                                title = "Live TV is here",
                                body = "Watch 23 channels free.",
                                severity = "info",
                            ),
                        ),
                        trialDaysRemaining = 5,
                    ),
                    heroAutoplay = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Dismiss announcement")
            .assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Remove Jawan from Continue Watching")
            .assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Dismiss trial note")
            .assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(48.dp)
    }
}
