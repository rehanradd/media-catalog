package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raddstream.app.brand.Brand
import com.raddstream.app.ui.components.CenteredMockupDialog

/**
 * The first-run question Tracking A asks before it counts anything.
 *
 * Session 123 (D-1): it used to arrive as a 10-line paragraph in a dialog
 * over a black void — the first impression of the product was a terms page.
 * It is now the mockup's centred card: one promise line, two answers, and
 * the skip below them. What it promises is the same as ever: totals only
 * (how many plays, how often playback fails, how often the app crashes),
 * never a name, a number, a location, a title, or a search, and no
 * per-person record anywhere. Both answers are real choices: "Allow"
 * turns the counters on, "Don't allow" leaves the app exactly as it was —
 * nothing is counted, nothing is sent, and nothing about the app changes.
 * The choice can be changed later in Settings. B5: there is also a visible
 * "Skip for now" — first open must reach the catalog in one tap, not wait
 * on a question. Skipping answers nothing and counts nothing; the sheet
 * simply asks again on the next launch, the same way it already did for a
 * first-run viewer who had not seen Home yet. Dismissing the dialog is
 * the same skip.
 *
 * The operator has not written analytics wording yet, the way he has not
 * written the disclaimer text either — this is this app's own plain-English
 * draft.
 */
@Composable
internal fun AnalyticsConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onSkip: () -> Unit = {},
) {
    CenteredMockupDialog(
        onDismissRequest = onSkip,
        icon = Icons.Default.BarChart,
        // The name comes from Brand, never a literal — the drift test
        // scans raw file text, comments included, so even a quoted
        // brand name in a comment trips it. The short name is the
        // sentence-case one.
        title = Brand.copy(
            "analytics_consent_title",
            "Help improve {name}",
            "name" to Brand.shortName,
        ),
        primaryLabel = "Allow",
        onPrimary = onAccept,
        secondaryLabel = "Don't allow",
        onSecondary = onDecline,
        belowActions = {
            // B5: the visible Skip — one tap reaches the catalog, the
            // question waits for the next launch. A skip is not an answer:
            // nothing is recorded, nothing is counted.
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Skip for now")
            }
        },
    ) {
        Text(
            "Share anonymous usage data. Nothing personal is ever sent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
