package com.raddstream.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.raddstream.app.data.RemoteFeatures
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings round 3 (2027, ST1 mega-cards): the group is one elevated
 * mega-card now (pastel header + its cards on surfaceContainerHigh with a
 * 1dp outlineVariant border), and the debug-only harness card sits below
 * the user-facing groups instead of on top of them.
 *
 * Same render harness as [SettingsRound2Test]: [SettingsContent] renders
 * from plain state with no ViewModel and no Hilt, and inspection mode is
 * provided (Home's pattern) so the staggered entrance renders immediately.
 * The mega-card look itself is proven by the Paparazzi goldens
 * (settingsScreen, settingsScreenLoadError, settingsLargeFont), not by
 * these tests — a background fill has no semantics node to assert on.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRound3Test {

    @get:Rule
    val compose = createComposeRule()

    private val query: MutableState<String> = mutableStateOf("")

    private fun render(
        state: SettingsUiState = SettingsUiState(),
        actions: SettingsActions = SettingsActions(onRetryLoad = { }),
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

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun staggerIsSeventyMsPerGroup() {
        // ST2 (round 2), re-verified in the round-3 re-rate: the entrance
        // stagger is 70ms per group index. The value is a named constant so
        // this pin catches silent drift; the delay itself lives in
        // SettingsGroup.
        assertEquals(70L, SettingsGroupStaggerMs)
    }

    @Test
    fun debugHarnessSitsBelowTheGroups() {
        // Round 3: the debug-only harness card is the last thing on the
        // screen now — it used to be the first impression. DEBUG is true
        // under testDebugUnitTest, so it renders here as in a debug build.
        render()
        val debugTop = topOf("Resolve Test (debug)")
        val helpTop = topOf("Help")
        assertTrue(
            "debug harness ($debugTop) is not below the last group ($helpTop)",
            debugTop > helpTop,
        )
    }
}
