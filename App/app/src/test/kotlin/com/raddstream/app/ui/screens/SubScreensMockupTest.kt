package com.raddstream.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.raddstream.app.data.UserRegion
import com.raddstream.app.data.local.LocalVideoFile
import com.raddstream.app.data.local.groupVideosIntoFolders
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.HistoryRow
import com.raddstream.app.viewmodel.LanguagesUiState
import com.raddstream.app.viewmodel.RegionUiState
import com.raddstream.app.viewmodel.WatchHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stitch sub-screens mockups (Nov 2026): Languages, Content preference,
 * Local files, History and Help, all in the dark #121212 + jade #2BC79A
 * language — headed option cards with a jade check on the choice in force,
 * folder rows with a jade play button, "Watched 2 days ago" rows with a
 * Clear all link and an X, and four Help rows that all go somewhere.
 *
 * The mockups illustrate with three options and invented names; the tests
 * pin the real lists instead — nine audio languages, seven subtitle
 * languages, the real region choices (Desi / East Asian / International),
 * the real folders on the phone, and the real history rows.
 */
@RunWith(RobolectricTestRunner::class)
class SubScreensMockupTest {

    @get:Rule
    val compose = createComposeRule()

    // ── Languages ────────────────────────────────────────────────────────

    private fun languagesState(
        audio: String = "Hindi",
        subtitle: String = "English",
        sizePercent: Int = 100,
    ) = LanguagesUiState(
        audioLanguage = audio,
        subtitleLanguage = subtitle,
        subtitleSizePercent = sizePercent,
    )

    @Test
    fun languagesShowsEveryAudioOptionWithTheRealPickChecked() {
        compose.setContent {
            RaddStreamTheme {
                LanguagesContent(
                    state = languagesState(),
                    onAudioSelect = {},
                    onSubtitleSelect = {},
                    onSizeChange = {},
                )
            }
        }
        // All nine real options are rows, not the mockup's three. The rows
        // past the first screenful live below the fold in the scrollable
        // column, so each one is scrolled into view before it is asserted.
        listOf("Player default", "English", "Hindi", "Urdu", "Tamil", "Telugu", "Malayalam", "Kannada", "Japanese")
            .forEach { label ->
                compose.onNodeWithContentDescription("Audio language: $label")
                    .performScrollTo().assertIsDisplayed()
            }
        compose.onNodeWithContentDescription("Audio language: Hindi").assertIsSelected()
        compose.onNodeWithContentDescription("Audio language: Urdu").assertIsNotSelected()
    }

    @Test
    fun languagesShowsEverySubtitleOptionWithTheRealPickChecked() {
        compose.setContent {
            RaddStreamTheme {
                LanguagesContent(
                    state = languagesState(),
                    onAudioSelect = {},
                    onSubtitleSelect = {},
                    onSizeChange = {},
                )
            }
        }
        listOf("Off", "English", "Hindi", "Urdu", "Tamil", "Telugu", "Kannada")
            .forEach { label ->
                compose.onNodeWithContentDescription("Subtitle language: $label")
                    .performScrollTo().assertIsDisplayed()
            }
        compose.onNodeWithContentDescription("Subtitle language: English").assertIsSelected()
        compose.onNodeWithContentDescription("Subtitle language: Off").assertIsNotSelected()
    }

    @Test
    fun languagesTappingARowReportsThatLanguage() {
        var picked: String? = null
        compose.setContent {
            RaddStreamTheme {
                LanguagesContent(
                    state = languagesState(),
                    onAudioSelect = { picked = it },
                    onSubtitleSelect = {},
                    onSizeChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Audio language: Urdu").performClick()
        assertEquals("Urdu", picked)
    }

    @Test
    fun languagesSizeRowShowsTheRealPercent() {
        compose.setContent {
            RaddStreamTheme {
                LanguagesContent(
                    state = languagesState(sizePercent = 125),
                    onAudioSelect = {},
                    onSubtitleSelect = {},
                    onSizeChange = {},
                )
            }
        }
        // The size card sits at the bottom of the scrollable column.
        compose.onNodeWithText("Subtitle size").performScrollTo().assertIsDisplayed()
        // The stored value is a percent — the mockup's "Medium" is invented.
        compose.onNodeWithText("125%").assertIsDisplayed()
    }

    // ── Content preference (Region) ──────────────────────────────────────

    @Test
    fun regionShowsTheRealChoicesWithTheStoredOneChecked() {
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
        // The mockup's Pakistan / India / International are Stitch's
        // invention; the real choices are Desi, East Asian, International.
        compose.onNodeWithText("Desi").assertIsDisplayed()
        compose.onNodeWithText("East Asian").assertIsDisplayed()
        compose.onNodeWithText("International").assertIsDisplayed()
        compose.onNodeWithText("Bollywood & Hindi dubs").assertIsDisplayed()
        // The jade check badge is a plain Icon inside the selectable card,
        // so the card's merged semantics swallow its test tag — the stored
        // pick is pinned through the card's own selected state instead.
        compose.onNodeWithTag("regionChoice_${UserRegion.DESI_DUBS}").assertIsSelected()
        compose.onNodeWithTag("regionChoice_${UserRegion.EAST_ASIAN}").assertIsNotSelected()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
    }

    @Test
    fun regionTappingACardReportsThatRegionAndDoneReportsBack() {
        var picked: String? = null
        var done = false
        compose.setContent {
            RaddStreamTheme {
                RegionContent(
                    state = RegionUiState(selectedId = UserRegion.DESI_DUBS),
                    onSelect = { picked = it },
                    onDone = { done = true },
                    onSkip = {},
                )
            }
        }
        compose.onNodeWithTag("regionChoice_${UserRegion.EAST_ASIAN}").performClick()
        assertEquals(UserRegion.EAST_ASIAN, picked)
        compose.onNodeWithText("Done").performClick()
        assertTrue(done)
    }

    // ── Local files ──────────────────────────────────────────────────────

    private fun localFile(
        uri: String = "content://media/1",
        title: String = "Maula Jatt",
        folder: String = "Movies",
    ) = LocalVideoFile(
        uri = uri,
        title = title,
        durationMs = 7_200_000L,
        sizeBytes = 1_300_000_000L,
        folder = folder,
        dateAddedMs = 1_791_000_000_000L,
        folderPath = folder,
    )

    @Test
    fun localLibraryFolderListShowsFoldersWithCounts() {
        compose.setContent {
            RaddStreamTheme {
                LocalFolderListContent(
                    folders = groupVideosIntoFolders(
                        listOf(
                            localFile(),
                            localFile(uri = "content://media/3", title = "Parizaad Ep 23", folder = "Movies"),
                            localFile(uri = "content://media/2", title = "Parizaad Ep 24", folder = "Downloads"),
                        ),
                    ),
                    thumbnails = emptyMap(),
                    onOpenFolder = {},
                )
            }
        }
        // One folder row per real folder, each with its count — the two
        // folders hold different counts so neither "N videos" line is
        // ambiguous to a single-node lookup. The files themselves stay
        // hidden until their folder opens: that is the folder-first split.
        compose.onNodeWithText("Movies").assertIsDisplayed()
        compose.onNodeWithText("2 videos").assertIsDisplayed()
        compose.onNodeWithText("Downloads").assertIsDisplayed()
        compose.onNodeWithText("1 video").assertIsDisplayed()
        compose.onNodeWithText("Maula Jatt").assertDoesNotExist()
    }

    @Test
    fun localLibraryFolderRowOpensItsFolder() {
        var opened: String? = null
        compose.setContent {
            RaddStreamTheme {
                LocalFolderListContent(
                    folders = groupVideosIntoFolders(
                        listOf(
                            localFile(),
                            localFile(uri = "content://media/2", title = "Parizaad Ep 24", folder = "Downloads"),
                        ),
                    ),
                    thumbnails = emptyMap(),
                    onOpenFolder = { opened = it.path },
                )
            }
        }
        compose.onNodeWithTag("localFolder_Movies").performClick()
        assertEquals("Movies", opened)
    }

    @Test
    fun localLibraryFolderVideosShowPlayButtonPerRow() {
        compose.setContent {
            RaddStreamTheme {
                LocalFolderVideosContent(
                    folder = groupVideosIntoFolders(
                        listOf(
                            localFile(),
                            localFile(uri = "content://media/3", title = "Parizaad Ep 23", folder = "Movies"),
                        ),
                    ).first { it.path == "Movies" },
                    onPlay = {},
                )
            }
        }
        compose.onNodeWithText("Maula Jatt").assertIsDisplayed()
        compose.onNodeWithText("Parizaad Ep 23").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTag("localFile_play").fetchSemanticsNodes().size)
    }

    @Test
    fun localLibraryRowsShowTheEpisodeThatTellsSameNamedFilesApart() {
        // Video-audit #40: two files called "River of No Return (2026)
        // Episode 15" and "… Episode 16" both read "River of No Return
        // (2…" at one line. Each row's details line leads with its
        // episode, so the pair differs even where the title still cuts.
        // Each episode string matches two nodes per file — its title node
        // and its details node — so the count pins the details line
        // carrying it, not just the title.
        compose.setContent {
            RaddStreamTheme {
                LocalFolderVideosContent(
                    folder = groupVideosIntoFolders(
                        listOf(
                            localFile(uri = "content://media/1", title = "River of No Return (2026) Episode 15", folder = "Movies"),
                            localFile(uri = "content://media/2", title = "River of No Return (2026) Episode 16", folder = "Movies"),
                        ),
                    ).first { it.path == "Movies" },
                    onPlay = {},
                )
            }
        }
        compose.onAllNodesWithText("Episode 15", substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("Episode 16", substring = true).assertCountEquals(2)
    }

    @Test
    fun localLibraryFolderVideoPlayButtonPlaysThatFile() {
        var played: LocalVideoFile? = null
        compose.setContent {
            RaddStreamTheme {
                LocalFolderVideosContent(
                    folder = groupVideosIntoFolders(listOf(localFile())).first(),
                    onPlay = { played = it },
                )
            }
        }
        compose.onNodeWithTag("localFile_play").performClick()
        assertEquals("Maula Jatt", played?.title)
    }

    // ── History ──────────────────────────────────────────────────────────

    private fun historyRow(
        postId: Int = 7,
        title: String = "Suno Chanda",
        fraction: Float? = 0.5f,
        watchedLabel: String = "Watched 2 days ago",
    ) = HistoryRow(
        postId = postId,
        title = title,
        posterUrl = null,
        scopeLabel = null,
        progressFraction = fraction,
        progressLabel = "1 min left",
        finished = false,
        dateLabel = "15 Nov 2026",
        watchedLabel = watchedLabel,
        workRef = "work-$postId",
        seasonNumber = 0,
        episodeNumber = 0,
    )

    @Test
    fun historyShowsClearAllAndWatchedLabels() {
        compose.setContent {
            RaddStreamTheme {
                WatchHistoryContent(
                    rows = listOf(historyRow()),
                    onBackClick = {},
                    onClearAll = {},
                    onRemove = {},
                    onPostClick = {},
                )
            }
        }
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("Clear all").assertIsDisplayed()
        compose.onNodeWithText("Suno Chanda").assertIsDisplayed()
        compose.onNodeWithText("Watched 2 days ago").assertIsDisplayed()
        compose.onNodeWithTag("history_progress").assertIsDisplayed()
        compose.onNodeWithTag("history_remove").assertIsDisplayed()
    }

    @Test
    fun historyClearAllAndRemoveReportBack() {
        var cleared = false
        var removed: HistoryRow? = null
        compose.setContent {
            RaddStreamTheme {
                WatchHistoryContent(
                    rows = listOf(historyRow()),
                    onBackClick = {},
                    onClearAll = { cleared = true },
                    onRemove = { removed = it },
                    onPostClick = {},
                )
            }
        }
        compose.onNodeWithText("Clear all").performClick()
        assertTrue(cleared)
        compose.onNodeWithTag("history_remove").performClick()
        assertEquals("Suno Chanda", removed?.title)
    }

    @Test
    fun historyRowWithoutAProgressDrawsNoBar() {
        compose.setContent {
            RaddStreamTheme {
                WatchHistoryContent(
                    rows = listOf(historyRow(fraction = null)),
                    onBackClick = {},
                    onClearAll = {},
                    onRemove = {},
                    onPostClick = {},
                )
            }
        }
        compose.onNodeWithTag("history_progress").assertDoesNotExist()
    }

    @Test
    fun watchedLabelIsRelativeForTheLastWeekAndAbsoluteAfter() {
        val now = 1_791_000_000_000L
        val day = 86_400_000L
        assertEquals("Today", WatchHistoryRow.relativeDateLabel(now, now))
        assertEquals("Yesterday", WatchHistoryRow.relativeDateLabel(now - day, now))
        assertEquals("3 days ago", WatchHistoryRow.relativeDateLabel(now - 3 * day, now))
        assertEquals("6 days ago", WatchHistoryRow.relativeDateLabel(now - 6 * day, now))
        assertEquals(
            WatchHistoryRow.dateLabel(now - 10 * day),
            WatchHistoryRow.relativeDateLabel(now - 10 * day, now),
        )
    }

    // ── Help ─────────────────────────────────────────────────────────────

    @Test
    fun helpShowsAllFourRowsAndEachOneReportsBack() {
        var reported = false
        var contacted = false
        var about = false
        compose.setContent {
            RaddStreamTheme {
                HelpContent(
                    faqExpanded = false,
                    onFaqToggle = {},
                    onReportClick = { reported = true },
                    onContactClick = { contacted = true },
                    onAboutClick = { about = true },
                    reportMessage = null,
                    reportMessageIsError = false,
                )
            }
        }
        compose.onNodeWithTag("help_row_faq").assertIsDisplayed()
        compose.onNodeWithTag("help_row_report").assertIsDisplayed()
        compose.onNodeWithTag("help_row_contact").assertIsDisplayed()
        compose.onNodeWithTag("help_row_about").assertIsDisplayed()
        // The answers stay hidden until the FAQ row is opened.
        compose.onNodeWithText("Watch").assertDoesNotExist()
        compose.onNodeWithTag("help_row_report").performClick()
        assertTrue(reported)
        compose.onNodeWithTag("help_row_contact").performClick()
        assertTrue(contacted)
        compose.onNodeWithTag("help_row_about").performClick()
        assertTrue(about)
    }

    @Test
    fun helpFaqExpandsToTheExistingAnswers() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            RaddStreamTheme {
                HelpContent(
                    faqExpanded = expanded,
                    onFaqToggle = { expanded = !expanded },
                    onReportClick = {},
                    onContactClick = {},
                    onAboutClick = {},
                    reportMessage = null,
                    reportMessageIsError = false,
                )
            }
        }
        compose.onNodeWithTag("help_row_faq").performClick()
        assertTrue(expanded)
        compose.onNodeWithText("Watch").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("VIP").assertIsDisplayed()
        compose.onNodeWithText("Need more help?").assertIsDisplayed()
    }
}
