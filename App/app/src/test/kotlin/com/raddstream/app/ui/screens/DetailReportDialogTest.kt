package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailReportDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reportDialogSubmitsOptionalNoteInOneTap() {
        var submittedNote: String? = null
        compose.setContent {
            RaddStreamTheme {
                ReportBrokenStreamDialog(
                    title = "Sample Film",
                    isSubmitting = false,
                    onSubmit = { submittedNote = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Report an issue").assertIsDisplayed()
        compose.onNodeWithText("Details (optional)").performTextInput("Stream stops at 10m")
        compose.onNodeWithText("Send report").performClick()
        assertEquals("Stream stops at 10m", submittedNote)
    }
}
