package com.raddstream.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raddstream.app.R
import com.raddstream.app.network.models.PanelDisclaimer
import com.raddstream.app.ui.components.RaddDialogTitle
import com.raddstream.app.ui.theme.RaddTokens

/**
 * The operator's disclaimer, in full.
 *
 * The text is the operator's own words — or the shipped default when the
 * panel has published none — served by the panel; this dialog never
 * paraphrases it. The text scrolls: the panel allows up to 4000
 * characters, and a dialog that clips the terms it asks the viewer to
 * accept is not asking.
 *
 * Samsung RTL (v1.1.2, Issue 4): this used to be a Material3 alert dialog whose
 * text slot held an unbounded scrolling Column — the title, the text and
 * the buttons shared one unconstrained column, and on a phone screen the
 * long text ran over the buttons and clipped at the dialog's edge. The
 * layout here is structural instead of hopeful: a `Dialog` with a fixed
 * title, a scrollable text area that owns the middle by weight inside a
 * dialog capped at 85% of the screen height, and the buttons pinned in
 * their own row at the bottom. No text can reach the buttons, and the
 * buttons can never be pushed off the screen, however long the panel's
 * text is or however large the viewer's font scale is.
 *
 * Popup cleanup (26 Nov 2026): the dialog used to open on the whole text —
 * up to 4000 characters at 85% of the screen height — before the viewer
 * could watch anything. It opens on a 2–3 line summary now, with a "Read
 * full disclaimer" link that expands to the full text in the same dialog.
 * The legal text itself is untouched and verbatim behind the link (Session
 * 122: "do not rewrite or shorten it" covers the text, not its
 * presentation); only what is shown first changed. The hard gate is
 * unchanged: first run still needs "I understand" (or Decline, which
 * exits), and the full text is one tap away before either is pressed.
 *
 * Two uses, one dialog: on first run the confirm button is the
 * acknowledgement ("I understand") and the dialog cannot be dismissed
 * any other way — except by declining, which exits the app, because
 * using the app without agreeing is not an option; from Settings the
 * same button just closes it, because re-reading something already
 * accepted is not a new agreement.
 */
@Composable
internal fun DisclaimerDialog(
    disclaimer: PanelDisclaimer,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissLabel: String? = null,
    onDecline: (() -> Unit)? = null,
) {
    // First run (a Decline exists) is not dismissable by back press or by
    // tapping outside: the only ways out are the two buttons. Re-reading
    // from Settings dismisses like any dialog.
    val firstRun = dismissLabel != null && onDecline != null
    // Popup cleanup (26 Nov 2026): collapsed by default — the summary
    // plus the link, not the wall of text. Expanding shows the full
    // verbatim text in place; nothing about the text itself changes.
    var expanded by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = !firstRun,
            dismissOnClickOutside = !firstRun,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Collapsed the dialog sizes to its content (summary +
                    // link + buttons); expanded it takes the old 85% cap so
                    // the full text can scroll without pushing the buttons
                    // off the screen.
                    .let { if (expanded) it.fillMaxHeight(0.85f) else it }
                    .padding(24.dp),
            ) {
                // D-2: one dialog title style everywhere — display family,
                // semibold, 20sp.
                RaddDialogTitle("Disclaimer")
                Spacer(Modifier.height(16.dp))
                if (!expanded) {
                    // The summary, not the terms: what the viewer is
                    // agreeing to, in two lines. The full text is one tap
                    // away, verbatim, before either button is pressed.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Radd Stream doesn't own, host, upload or store any content — " +
                                "it only opens links that are already public, like a web browser.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (disclaimer.email.isNotBlank()) {
                            Text(
                                "If you own content shown here, email ${disclaimer.email} — " +
                                    "it will be hidden within 48 hours while your claim is checked.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { expanded = true },
                        modifier = Modifier.testTag("disclaimer_read_full"),
                    ) {
                        Text("Read full disclaimer")
                        Icon(
                            painterResource(R.drawable.ic_action_keyboard_arrow_down),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    val scrollState = rememberScrollState()
                    val uriHandler = LocalUriHandler.current
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // F13 (Batch B): the text arrives as one block from the
                        // panel, and a wall of it is not read — each line break
                        // is a paragraph, and each paragraph gets its own block
                        // with air between them. A blank line is nothing to show.
                        // Verbatim (Session 122): this renders the operator's
                        // text exactly as served — never paraphrased, never
                        // shortened. Only its presentation changed: it sits
                        // behind the "Read full disclaimer" link now.
                        disclaimer.text.split("\n").map { it.trim() }
                            .filter { it.isNotEmpty() }.forEach { paragraph ->
                                Text(
                                    paragraph,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        // B8: no version in the painted text — acceptance is
                        // already tracked per version, so the number says
                        // nothing a viewer can act on.
                        if (disclaimer.email.isNotBlank()) {
                            // F13 (Batch B) & Small #5: ClickableText ignores LocalContentColor
                            // and rendered "Contact: " in dark ink on a dark surface. Use M3 Text
                            // with explicit high-contrast styling inside a 48dp touch target, while
                            // preserving the single "Contact: <email>" semantics node.
                            val contactText = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    append("Contact: ")
                                }
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ) {
                                    append(disclaimer.email)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Send email to ${disclaimer.email}",
                                    ) {
                                        runCatching { uriHandler.openUri("mailto:${disclaimer.email}") }
                                    },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = contactText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    // F13 (Batch B): the scroll cue — a small line that says the
                    // text goes on, shown only while there is more below. A
                    // dialog that scrolls without saying so hides its own terms.
                    if (scrollState.canScrollForward) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Scroll for more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                painterResource(R.drawable.ic_action_keyboard_arrow_down),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { expanded = false },
                        modifier = Modifier.testTag("disclaimer_show_less"),
                    ) {
                        Text("Show less")
                    }
                }
                Spacer(Modifier.height(16.dp))
                // F13 (Batch B): on first run the Decline is its own line
                // under the Accept button — the choice to walk away is
                // unmissable, not a small word beside the big button. On a
                // re-read there is no Decline at all: the terms are already
                // accepted, and re-reading is not a new agreement.
                if (firstRun) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // D9 (screenshot-fix 2026-11-16): the Robo script taps
                        // this button by resourceId, not by text — the text
                        // tap matched a non-clickable Text node and the
                        // dialog never dismissed (Robo run 2, action 2).
                        // No visual change: a test tag only.
                        Button(
                            shape = RaddTokens.Shape.Pill,
                            onClick = onConfirm,
                            modifier = Modifier.fillMaxWidth().testTag("disclaimer_confirm"),
                        ) {
                            Text(confirmLabel)
                        }
                        TextButton(
                            onClick = onDecline ?: {},
                            modifier = Modifier.testTag("disclaimer_decline"),
                        ) {
                            Text(dismissLabel ?: "")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onConfirm, shape = RaddTokens.Shape.Pill) {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    }
}
