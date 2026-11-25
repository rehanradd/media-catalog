package com.raddstream.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    /**
     * Document order of the node carrying [text] in the semantics tree
     * (depth-first). The LAST match wins, so a header with the same text
     * as its card does not shadow the card.
     *
     * Order is read from the tree, not from bounds: below-fold nodes
     * report 0.0 bounds in this Robolectric setup, so a bounds comparison
     * passes vacuously on zeros — and fails the moment one node lands in
     * the viewport, as the round-3 mega-cards move did.
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
        // Order is read from the semantics tree (document order), not from
        // bounds — see documentOrderIndex.
        render()
        val debugIndex = documentOrderIndex("Resolve Test (debug)")
        val helpIndex = documentOrderIndex("Help")
        assertTrue(
            "debug harness (index $debugIndex) is not below the last group (index $helpIndex)",
            debugIndex > helpIndex,
        )
    }
}
