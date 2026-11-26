package com.raddstream.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.raddstream.app.data.RemoteFeatures
import com.raddstream.app.ui.theme.RaddChipBorderSoft
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddCurrent700
import com.raddstream.app.ui.theme.RaddInk850
import com.raddstream.app.ui.theme.RaddInk950
import com.raddstream.app.ui.theme.RaddMistOnInk
import com.raddstream.app.ui.theme.RaddMistOnPaper
import com.raddstream.app.ui.theme.RaddOnCurrentContainer
import com.raddstream.app.ui.theme.RaddOnCurrentContainerLight
import com.raddstream.app.ui.theme.RaddPaperContainer
import com.raddstream.app.ui.theme.RaddPaperDay
import com.raddstream.app.ui.theme.RaddStreamTheme
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

    /**
     * Document order of the node carrying [text] in the semantics tree
     * (depth-first). The LAST match wins: "Appearance" matches the group
     * header too, so the card is the later node.
     *
     * Order is read from the tree, not from bounds: below-fold nodes
     * report 0.0 bounds in this Robolectric setup, so a bounds comparison
     * passes vacuously on zeros — and fails the moment one node lands in
     * the viewport, as the round-3 mega-cards move did (group 0's card
     * top went real at 311.0 while groups 1–4 stayed 0.0).
     */
    private fun documentOrderIndex(text: String): Int {
        val flat = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            flat += node
            node.children.forEach(::walk)
        }
        walk(compose.onRoot().fetchSemanticsNode())
        val index = flat.indexOfLast { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
        assertTrue("no node with text \"$text\"", index >= 0)
        return index
    }

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
        // The button sits at the end of the scrollable column — scroll it
        // into view first (repo pattern: SearchAuditFixesTest/HomeAuditFixesTest).
        // A bare performClick on a below-fold node silently misses.
        compose.onNodeWithText("Clear search").performScrollTo().performClick()
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
        val order = listOf(
            documentOrderIndex("VIP membership"),
            documentOrderIndex("Content preference"),
            documentOrderIndex("Offline"),
            documentOrderIndex("Appearance"),
            documentOrderIndex("Help"),
        )
        assertEquals(
            "groups are not in Account, Playback, Downloads, Appearance, About order: $order",
            order.sorted(),
            order,
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
        // P2-10: the helper style is a @Composable now — its colour is the
        // scheme's onSurfaceVariant — so its scale is pinned inside a
        // composition rather than read off a static val.
        var captured: TextStyle? = null
        compose.setContent {
            RaddStreamTheme(darkTheme = true) { captured = settingsHelperStyle() }
        }
        compose.waitForIdle()
        val helper = requireNotNull(captured) { "settingsHelperStyle() produced no style" }
        assertEquals(12.sp, helper.fontSize)
        assertEquals(FontWeight.Normal, helper.fontWeight)
    }

    @Test
    fun chipSelectedLabelClearsTextBarOnEveryGroupFill() {
        // ST3: the selected chip's label is onPrimaryContainer on the 16%
        // jade tint composite over the group fill — never under 4.5:1.
        // P1-14: the groups are flat now — one surfaceContainer surface
        // and the bare background for the out-of-group rows.
        // P2-10: pinned in both schemes — dark 8.77:1/9.86:1, light
        // 9.59:1/10.43:1.
        val cases = listOf(
            SelectedChipCase("dark surfaceContainer", RaddCurrent500, RaddOnCurrentContainer, RaddInk850),
            SelectedChipCase("dark background", RaddCurrent500, RaddOnCurrentContainer, RaddInk950),
            SelectedChipCase("light surfaceContainer", RaddCurrent700, RaddOnCurrentContainerLight, RaddPaperContainer),
            SelectedChipCase("light background", RaddCurrent700, RaddOnCurrentContainerLight, RaddPaperDay),
        )
        for (case in cases) {
            val composite = over(case.primary, case.fill, 0.16f)
            val ratio = contrastRatio(case.label, composite)
            assertTrue(
                "selected chip label is only $ratio:1 on ${case.name}, under the 4.5:1 text bar",
                ratio >= 4.5f,
            )
        }
    }

    @Test
    fun chipBorderClearsComponentBarOnEveryGroupFill() {
        // ST1: the same border must clear 3:1 on the tonal fill, not just
        // the old flat card. P1-14: the flat fills now. P2-10: pinned in
        // both schemes — dark 4.10:1/4.49:1, light 3.55:1/3.91:1.
        val fills = mapOf(
            "dark surfaceContainer" to RaddInk850,
            "dark background" to RaddInk950,
            "light surfaceContainer" to RaddPaperContainer,
            "light background" to RaddPaperDay,
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
        // P1-14: the flat fills now. P2-10: the helper colour is the
        // scheme's onSurfaceVariant, so the pair is pinned in both
        // schemes — dark 8.62:1/9.43:1, light 5.79:1/6.38:1.
        val pairs = mapOf(
            "dark surfaceContainer" to (RaddMistOnInk to RaddInk850),
            "dark background" to (RaddMistOnInk to RaddInk950),
            "light surfaceContainer" to (RaddMistOnPaper to RaddPaperContainer),
            "light background" to (RaddMistOnPaper to RaddPaperDay),
        )
        for ((name, pair) in pairs) {
            val (text, fill) = pair
            assertTrue(
                "helper text is under 4.5:1 on $name $fill",
                contrastRatio(text, fill) >= 4.5f,
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

    /** One selected-chip colour case: the scheme's primary at 16% over the
     *  card fill, with the scheme's onPrimaryContainer label on top. */
    private data class SelectedChipCase(
        val name: String,
        val primary: Color,
        val label: Color,
        val fill: Color,
    )

    /** Standard src-over alpha compositing: fg at [alpha] over opaque bg. */
    private fun over(fg: Color, bg: Color, alpha: Float): Color = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
        alpha = 1f,
    )
}
