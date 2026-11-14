package com.raddstream.app.ui.components

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeroIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun heroIndicatorDotsPreserve48dpTargetsAndSelectState() {
        var selectedIndex = -1
        compose.setContent {
            RaddStreamTheme {
                HeroIndicator(
                    count = 4,
                    current = 1,
                    onSelect = { selectedIndex = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Banner 2 of 4").assertIsSelected()
        compose.onNodeWithContentDescription("Banner 1 of 4").assertIsNotSelected()
        compose.onNodeWithContentDescription("Banner 3 of 4").performClick()
        assertEquals(2, selectedIndex)

        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        with(compose.density) {
            assertEquals(4, nodes.size)
            assertTrue(nodes.all { it.boundsInRoot.width.toDp() >= 48.dp && it.boundsInRoot.height.toDp() >= 48.dp })
        }
    }
}
