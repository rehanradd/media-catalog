package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SearchUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchFilterSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stickyFooterRemainsVisibleOnCompactScreenWithLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                RaddStreamTheme {
                    Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                        FilterSheetBody(
                            state = SearchUiState(),
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
        }
        compose.onNodeWithText("Reset").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsDisplayed()

        val applyBounds = compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot
        val resetBounds = compose.onNodeWithText("Reset").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("Reset below 48dp touch height", resetBounds.height.toDp() >= 48.dp)
            assertTrue("Apply below 48dp touch height", applyBounds.height.toDp() >= 48.dp)
            assertTrue("Apply clipped past 320dp right edge", applyBounds.right.toDp() <= 320.dp)
        }
    }
}
