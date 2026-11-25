package com.raddstream.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.raddstream.app.data.RemoteFeatures
import com.raddstream.app.ui.theme.RaddChipBorderSoft
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddCurrentContainerDark
import com.raddstream.app.ui.theme.RaddInk850
import com.raddstream.app.ui.theme.RaddInk900
import com.raddstream.app.ui.theme.RaddOnCurrentContainer
import com.raddstream.app.ui.theme.RaddSettingsHelper
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.ui.theme.RaddTideContainerDark
import com.raddstream.app.ui.theme.contrastRatio
import com.raddstream.app.viewmodel.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings round 2 (2027, ST1–ST6 + S8): the screen split into
 * [SettingsContent] is what makes these tests possible — the content renders
 * from plain state with no ViewModel and no Hilt, so Robolectric can drive
 * the search branches and the error branch directly.
 *
 * The Kids Lock card arrives as a slot: the real card needs a
 * [com.raddstream.app.viewmodel.KidsLockViewModel], which needs a repository,
 * so the tests pass a marker Text instead. The marker proves the *gate*
 * (the query shows/hides the slot); the card's own content is covered by
 * the Kids Lock tests. The Paparazzi golden passes an empty slot for the
 * same reason.
 *
 * Inspection mode is provided (Home's pattern): the group entrance is a
 * real-time stagger and Robolectric never advances it, so without inspection
 * mode every group would stay invisible in the tree.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRound2Test {

    @get:Rule
    val compose = createComposeRule()

    private val query: MutableState<String> = mutableStateOf("")
    private var retryCalled = false

    private fun render(
        state: SettingsUiState = SettingsUiState(),
        actions: SettingsActions = SettingsActions(onRetryLoad = { retryCalled = true }),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                RaddStreamTheme(darkTheme = true) {
                    SettingsContent(
                        state = state,
                        features = RemoteFeatures(),
                        actions = actions,
                        searchQuery = query.value,
                        onSearchQueryChange = { query.value = it },
                        kidsLockCard = { Text("Kids Lock") },
                        profileLinkDialog = {},
                        onOpenDownloads = {},
                        onOpenVipUpgrade = {},
                        onOpenRegionChoice = {},
                        onOpenDashboard = {},
                        onOpenProfile = {},
                        onOpenHelp = {},
                        onOpenLanguages = {},
                        onOpenWatchHistory = {},
                        onOpenResolveTest = {},
                        onOpenPressTest = {},
                        onOpenDiagnostics = {},
                        showDiagnostics = false,
                        scrollToContact = false,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun typeQuery(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun searchFiltersToMatchingCardOnly() {
        render()
        typeQuery("kids lock")
        compose.onNodeWithText("Kids Lock").assertExists()
        compose.onNodeWithText("Playback").assertExists()
        compose.onNodeWithText("Content preference").assertDoesNotExist()
        compose.onNodeWithText("Offline").assertDoesNotExist()
        compose.onNodeWithText("Downloads").assertDoesNotExist()
    }

    @Test
    fun searchNoResultsShowsClearSearch() {
        render()
        typeQuery("zzznope")
        compose.onNodeWithText("No results").assertIsDisplayed()
        compose.onNodeWithText("Clear search").performClick()
        compose.waitForIdle()
        assertEquals("", query.value)
        compose.onNodeWithText("Content preference").assertExists()
    }

    @Test
    fun searchClearButtonClearsQuery() {
        render()
        typeQuery("offline")
        compose.onNodeWithText("Offline").assertExists()
        compose.onNodeWithText("Content preference").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitForIdle()
        assertEquals("", query.value)
        compose.onNodeWithText("Content preference").assertExists()
    }

    @Test
    fun groupOrderIsAccountPlaybackDownloadsAppearanceAbout() {
        render()
        // Card titles, one per group, in §0.8 order — each is unique text.
        // "Appearance" matches the header too, so the card is the later node.
        val appearanceTop = compose.onAllNodesWithText("Appearance")
            .fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        val tops = listOf(
            topOf("VIP membership"),
            topOf("Content preference"),
            topOf("Offline"),
            appearanceTop,
            topOf("Help"),
        )
        assertEquals(
            "groups are not in Account, Playback, Downloads, Appearance, About order: $tops",
            tops.sorted(),
            tops,
        )
    }

    @Test
    fun typographyScaleSplitsGroupRowAndHelper() {
        // ST4: group titles 16sp SemiBold, row/card titles 14sp Medium,
        // helpers stay 12sp Regular.
        assertEquals(16.sp, settingsGroupTitleStyle.fontSize)
        assertEquals(FontWeight.SemiBold, settingsGroupTitleStyle.fontWeight)
        assertEquals(14.sp, settingsRowTitleStyle.fontSize)
        assertEquals(FontWeight.Medium, settingsRowTitleStyle.fontWeight)
        assertEquals(12.sp, settingsHelperStyle.fontSize)
        assertEquals(FontWeight.Normal, settingsHelperStyle.fontWeight)
    }

    @Test
    fun chipSelectedLabelClearsTextBarOnEveryGroupFill() {
        // ST3: the selected chip's label is onPrimaryContainer on the 16%
        // jade tint composite over each group fill — never under 4.5:1.
        val fills = mapOf(
            "primaryContainer" to RaddCurrentContainerDark,
            "tertiaryContainer" to RaddTideContainerDark,
            "surfaceContainerLow" to RaddInk900,
            "surfaceContainer" to RaddInk850,
        )
        for ((name, fill) in fills) {
            val composite = over(RaddCurrent500, fill, 0.16f)
            val ratio = contrastRatio(RaddOnCurrentContainer, composite)
            assertTrue(
                "selected chip label is only $ratio:1 on $name, under the 4.5:1 text bar",
                ratio >= 4.5f,
            )
        }
    }

    @Test
    fun chipBorderClearsComponentBarOnEveryGroupFill() {
        // ST1: the same border must clear 3:1 on every tonal fill, not just
        // the old flat card — surfaceContainerHigh is excluded by design
        // (2.96:1) and no group uses it.
        val fills = mapOf(
            "primaryContainer" to RaddCurrentContainerDark,
            "tertiaryContainer" to RaddTideContainerDark,
            "surfaceContainerLow" to RaddInk900,
            "surfaceContainer" to RaddInk850,
        )
        for ((name, fill) in fills) {
            val ratio = contrastRatio(RaddChipBorderSoft, fill)
            assertTrue(
                "chip border is only $ratio:1 on $name, under the M3 3:1 component bar",
                ratio >= 3f,
            )
        }
    }

    @Test
    fun helperTextClearsTextBarOnEveryGroupFill() {
        val fills = listOf(
            RaddCurrentContainerDark,
            RaddTideContainerDark,
            RaddInk900,
            RaddInk850,
        )
        for (fill in fills) {
            assertTrue(
                "helper text is under 4.5:1 on $fill",
                contrastRatio(RaddSettingsHelper, fill) >= 4.5f,
            )
        }
    }

    @Test
    fun errorBranchShowsRetryAndCallsRetry() {
        retryCalled = false
        render(state = SettingsUiState(loadError = "Couldn't reach your preferences."))
        compose.onNodeWithText("Couldn't load your settings").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach your preferences.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertTrue("Retry did not call the retry action", retryCalled)
    }

    @Test
    fun settingsQueryMatchesBlankMatchesEverything() {
        assertTrue(settingsQueryMatches("", "Content preference", "region"))
        assertTrue(settingsQueryMatches("   ", "Content preference", "region"))
    }

    @Test
    fun settingsQueryMatchesTitleAndKeywordsCaseInsensitive() {
        assertTrue(settingsQueryMatches("KIDS LOCK", "kids lock", "pin"))
        assertTrue(settingsQueryMatches("wifi", "offline", "wi-fi", "wifi"))
        assertTrue(settingsQueryMatches("dynamic colour", "playback display", "dynamic colour"))
    }

    @Test
    fun settingsQueryMatchesMultiWordRequiresAllWords() {
        assertFalse(settingsQueryMatches("kids downloads", "kids lock", "pin"))
        assertTrue(settingsQueryMatches("watch history", "watch history", "watched"))
    }

    @Test
    fun settingsQueryMatchesNoMatchReturnsFalse() {
        assertFalse(settingsQueryMatches("zzznope", "Content preference", "region"))
    }

    /** Standard src-over alpha compositing: fg at [alpha] over opaque bg. */
    private fun over(fg: Color, bg: Color, alpha: Float): Color = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
        alpha = 1f,
    )
}
