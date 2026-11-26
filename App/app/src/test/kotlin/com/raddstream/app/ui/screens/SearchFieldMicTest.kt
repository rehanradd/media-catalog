package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SE-3 (round 2): the search field's mic — visible only where a speech
 * recognizer exists, TalkBack "Voice search", and a tap hands the query to
 * the recognizer instead of typing it. The clear action keeps working
 * beside it.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class SearchFieldMicTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun micShowsWhenVoiceSearchIsAvailable() {
        compose.setContent {
            RaddStreamTheme {
                SearchField(
                    query = "",
                    onQueryChanged = {},
                    onSubmit = {},
                    onVoiceSearch = {},
                    voiceSearchAvailable = true,
                )
            }
        }
        compose.onNodeWithContentDescription("Voice search").assertIsDisplayed()
    }

    @Test
    fun micHidesWhenNoRecognizerExists() {
        compose.setContent {
            RaddStreamTheme {
                SearchField(
                    query = "",
                    onQueryChanged = {},
                    onSubmit = {},
                    onVoiceSearch = {},
                    voiceSearchAvailable = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Voice search").assertDoesNotExist()
    }

    @Test
    fun tappingMicCallsVoiceSearch() {
        var called = false
        compose.setContent {
            RaddStreamTheme {
                SearchField(
                    query = "",
                    onQueryChanged = {},
                    onSubmit = {},
                    onVoiceSearch = { called = true },
                    voiceSearchAvailable = true,
                )
            }
        }
        compose.onNodeWithContentDescription("Voice search").performClick()
        assertTrue("Mic tap did not call onVoiceSearch", called)
    }

    @Test
    fun clearStillWorksBesideTheMic() {
        var query = "one"
        compose.setContent {
            RaddStreamTheme {
                SearchField(
                    query = query,
                    onQueryChanged = { query = it },
                    onSubmit = {},
                    onVoiceSearch = {},
                    voiceSearchAvailable = true,
                )
            }
        }
        compose.onNodeWithContentDescription("Voice search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", query)
    }

    @Test
    fun typingStillWorksWithTheMicPresent() {
        var query = ""
        compose.setContent {
            RaddStreamTheme {
                SearchField(
                    query = query,
                    onQueryChanged = { query = it },
                    onSubmit = {},
                    onVoiceSearch = {},
                    voiceSearchAvailable = true,
                )
            }
        }
        compose.onNodeWithContentDescription("Search titles or people").performTextInput("a")
        assertEquals("a", query)
    }
}
