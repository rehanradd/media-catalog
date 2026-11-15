package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun reportSheetSubmitsTheTappedReasonInOneTap() {
        var submittedReason: String? = null
        compose.setContent {
            RaddStreamTheme {
                ReportBrokenStreamSheetBody(
                    isSubmitting = false,
                    onSubmit = { submittedReason = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Report a problem").assertIsDisplayed()
        for (reason in listOf("Video won't play", "Wrong title", "Bad quality", "Something else")) {
            compose.onNodeWithText(reason).assertIsDisplayed()
        }
        // The mockup is categories only — no free-text field any more.
        compose.onNodeWithText("Details (optional)").assertDoesNotExist()
        compose.onNodeWithText("Video won't play").performClick()
        assertEquals("Video won't play", submittedReason)
    }

    @Test
    fun reportSheetCancelCallsDismiss() {
        var dismissed = false
        compose.setContent {
            RaddStreamTheme {
                ReportBrokenStreamSheetBody(
                    isSubmitting = false,
                    onSubmit = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(true, dismissed)
    }
}
