package com.raddstream.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.viewmodel.HistoryRow
import com.raddstream.app.viewmodel.WatchHistoryViewModel

/**
 * Everything this phone watched, newest first — including titles watched to the end.
 *
 * The rows come from the same merged source as the Continue watching rail (local rows
 * folded with a newer synced position), so the two surfaces can never disagree about
 * where a title stopped; the only difference is the filter, and this screen has none.
 * A row taps through to Detail exactly when the rail's rule says it can be routed
 * (`postId > 0`); an unroutable row is shown, not hidden, and simply does not tap.
 *
 * Stitch sub-screens mockup (Nov 2026): the title is "History", a "Clear all"
 * link sits top-right, and each row is a card — poster with a thin jade
 * progress bar under it (only where a real position exists), the title, and
 * "Watched 2 days ago", with an X to forget that one row. The mockup's
 * "Watched 2 days ago" is a relative day; the old absolute date is still the
 * fallback past the first week.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchHistoryScreen(
    onBackClick: () -> Unit,
    onPostClick: (Int) -> Unit,
    viewModel: WatchHistoryViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()

    WatchHistoryContent(
        rows = rows,
        onBackClick = onBackClick,
        onClearAll = { viewModel.clearAll() },
        onRemove = { viewModel.removeRow(it) },
        onPostClick = onPostClick,
    )
}

/**
 * The screen without its ViewModel, so a test can drive it with plain rows:
 * the "History" bar with Clear all, and the card list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchHistoryContent(
    rows: List<HistoryRow>,
    onBackClick: () -> Unit,
    onClearAll: () -> Unit,
    onRemove: (HistoryRow) -> Unit,
    onPostClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(painterResource(R.drawable.ic_action_arrow_left), contentDescription = "Back")
                    }
                },
                actions = {
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = onClearAll) {
                            Text("Clear all")
                        }
                    }
                },
            )
        },
        modifier = modifier,
    ) { contentPadding ->
        if (rows.isEmpty()) {
            EmptyState(
                title = "Nothing watched yet",
                message = "Titles you watch on this phone will show up here, newest first — " +
                    "including ones you finished.",
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_action_history),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(40.dp),
                    )
                },
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Keyed on the scope, not the work: two rows of the same series are two
                // different episodes, and a duplicate key would crash the list.
                items(rows, key = { "${it.postId}|${it.scopeLabel.orEmpty()}" }) { row ->
                    HistoryRowItem(
                        row = row,
                        onClick = { onPostClick(row.postId) },
                        onRemove = { onRemove(row) },
                    )
                }
            }
        }
    }
}

/** One watched scope: poster with its progress bar under it, title, day, and an X. */
@Composable
internal fun HistoryRowItem(
    row: HistoryRow,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = row.tappable, onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CatalogImage(
                    url = row.posterUrl,
                    contentDescription = row.title,
                    modifier = Modifier.size(width = 56.dp, height = 84.dp),
                    fallbackLabel = row.title,
                )
                // The bar belongs to the poster, not the text: it is only
                // ever drawn when a real position exists — a finished title
                // and an unmeasured one both draw nothing rather than
                // inventing a fraction.
                row.progressFraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .width(56.dp)
                            .height(3.dp)
                            .testTag("history_progress"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(row.scopeLabel, row.watchedLabel)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.statusLabel?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag("history_remove"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_close),
                    contentDescription = "Remove ${row.title} from history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
