package com.raddstream.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.PanelDisclaimer
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Samsung RTL (v1.1.2, Issue 4): the first-run disclaimer dialog's text
 * overlapped and clipped — the long text ran over the buttons in an
 * AlertDialog whose text slot held an unbounded scrolling column. The
 * dialog is now a bounded layout (fixed title, weighted scrollable text,
 * buttons pinned in their own row), so however long the panel's text is,
 * the buttons stay on screen and reachable. This pins that with a
 * near-maximum-length text (the panel allows 4000 characters) the confirm
 * and decline buttons are both displayed, not pushed off the dialog.
 *
 * Runs on the JVM under Robolectric because this project has no emulator image.
 * Requires an x86_64 runner (robolectric/robolectric#9166); see
 * CatalogImageLoadingTest for the exclusion note.
 */
@RunWith(RobolectricTestRunner::class)
class DisclaimerDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val longText = ("Radd Stream is a media browser and video player. " +
        "It does not own, host, upload, or store any movie, show, video, or song. ")
        .repeat(30).take(4000)

    @Test
    fun firstRunDialogKeepsBothButtonsVisibleWithLongText() {
        compose.setContent {
            RaddStreamTheme {
                DisclaimerDialog(
                    disclaimer = PanelDisclaimer(
                        text = longText,
                        version = 1,
                        email = "radddao1@gmail.com",
                    ),
                    confirmLabel = "I understand",
                    onConfirm = {},
                    onDismissRequest = {},
                    dismissLabel = "Decline",
                    onDecline = {},
                )
            }
        }
        compose.onNodeWithText("I understand").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        // B8: no version in the painted text — acceptance is tracked per
        // version, so the number says nothing a viewer can act on.
        compose.onNodeWithText("Version 1").assertDoesNotExist()
        // Popup cleanup (26 Nov 2026): the full text sits behind the
        // "Read full disclaimer" link now — expand first, then the
        // contact line and its 48dp target are reachable as before.
        compose.onNodeWithText("Read full disclaimer").performClick()
        compose.onNodeWithText("Contact: radddao1@gmail.com").assertExists()
        val contactNode = compose.onNode(
            androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasText("Contact: radddao1@gmail.com"),
        )
        contactNode.performScrollTo()
        val contactBounds = contactNode.fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            org.junit.Assert.assertTrue(
                "Contact link touch height below 48dp",
                contactBounds.height.toDp() >= 48.dp,
            )
        }
    }

    @Test
    fun settingsDialogKeepsItsCloseButtonVisibleWithLongText() {
        compose.setContent {
            RaddStreamTheme {
                DisclaimerDialog(
                    disclaimer = PanelDisclaimer(
                        text = longText,
                        version = 1,
                        email = "radddao1@gmail.com",
                    ),
                    confirmLabel = "Close",
                    onConfirm = {},
                    onDismissRequest = {},
                )
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    // Popup cleanup (26 Nov 2026): the dialog opens on a 2–3 line summary
    // with a "Read full disclaimer" link; the full verbatim text is one
    // tap away, and the hard gate (I understand / Decline) is unchanged.

    private val shortText = "Full legal paragraph one.\n\nFull legal paragraph two."

    private fun setShortDialog() {
        compose.setContent {
            RaddStreamTheme {
                DisclaimerDialog(
                    disclaimer = PanelDisclaimer(
                        text = shortText,
                        version = 1,
                        email = "radddao1@gmail.com",
                    ),
                    confirmLabel = "I understand",
                    onConfirm = {},
                    onDismissRequest = {},
                    dismissLabel = "Decline",
                    onDecline = {},
                )
            }
        }
    }

    @Test
    fun collapsedDialogShowsSummaryAndLinkNotFullText() {
        setShortDialog()
        compose.onNodeWithText("Read full disclaimer").assertIsDisplayed()
        compose.onNodeWithText("Full legal paragraph one.").assertDoesNotExist()
        compose.onNodeWithText("Full legal paragraph two.").assertDoesNotExist()
        // The hard gate is still there, on the summary alone.
        compose.onNodeWithText("I understand").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
    }

    @Test
    fun expandingRevealsFullTextVerbatimAndCollapsesAgain() {
        setShortDialog()
        compose.onNodeWithText("Read full disclaimer").performClick()
        compose.onNodeWithText("Full legal paragraph one.").assertIsDisplayed()
        compose.onNodeWithText("Full legal paragraph two.").assertIsDisplayed()
        compose.onNodeWithText("Contact: radddao1@gmail.com").assertExists()
        compose.onNodeWithText("Show less").performClick()
        compose.onNodeWithText("Full legal paragraph one.").assertDoesNotExist()
        compose.onNodeWithText("Read full disclaimer").assertIsDisplayed()
    }
}
