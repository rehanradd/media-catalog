package com.raddstream.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.viewmodel.DetailNavigationEvent
import com.raddstream.app.viewmodel.DetailViewModel
import com.raddstream.app.viewmodel.EpisodeProgress
import com.raddstream.app.viewmodel.SeasonGroup

/**
 * One tile of the season grid: the episode number, whether it can play
 * here, whether this device already watched it, and whether it is the
 * next one to watch. Pure so SeasonTilesTest pins it without a screen.
 */
data class SeasonTile(
    val number: Int,
    val playable: Boolean,
    val watched: Boolean,
    val isNext: Boolean,
)

/**
 * Detail redesign (Nov 2026): a season's episodes as plain numbered
 * tiles, KissKH-style — 1 2 3 4 5 6, nothing else on the tile. The count
 * is the B2 guide total when the server named one, else the highest
 * episode row on screen — a tile with no playable file shows DIMMED,
 * never hidden, or the numbers would lie (tap 10, get episode 11).
 * `watched` comes from the same progress map the Detail page reads;
 * the first playable, unwatched number is the next one to watch.
 */
internal fun seasonTiles(
    season: SeasonGroup,
    progress: Map<Pair<Int, Int>, EpisodeProgress>,
): List<SeasonTile> {
    // A whole-season file has no numbered episodes — the screen shows
    // its one pack row instead, never fake numbers.
    if (season.isPackOnly) return emptyList()
    val total = season.guideTotal
        ?: season.episodes.maxOfOrNull { it.numberWithinSeason }
        ?: 0
    val byNumber = season.episodes.associateBy { it.numberWithinSeason }
    var nextFound = false
    return (1..total).map { number ->
        val episode = byNumber[number]
        val playable = episode?.prints?.any { it.playableHere || it.opensProviderPage } == true
        val watched = progress[season.number to number]?.watched == true
        val isNext = playable && !watched && !nextFound
        if (isNext) nextFound = true
        SeasonTile(number = number, playable = playable, watched = watched, isNext = isNext)
    }
}

/**
 * Detail redesign (Nov 2026): the season sub-page — "Season 1" and
 * rows of six plain numbered tiles. Tap a number to play that episode; the
 * player picks the picture, so there is no quality choice here. The
 * page shares the Detail page's ViewModel (same back-stack entry), so
 * it reads the same Kids Lock-filtered layout and the same progress —
 * nothing is refetched, and a title Kids Mode refuses never reaches
 * this page at all. A whole-season file gets one row, not fake numbers.
 *
 * The permission + navigation-event plumbing mirrors DetailScreen's:
 * this page is on screen while Detail is not, so the shared ViewModel's
 * Play/Download events need the same routing here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeasonScreen(
    seasonNumber: Int,
    onBackClick: () -> Unit,
    onPlayStream: (streamId: String, title: String) -> Unit,
    /** A link the server calls a page: show the provider's own page rather than fail quietly. */
    onOpenProviderPage: (streamId: String, title: String) -> Unit = { _, _ -> },
    onOpenDownloads: () -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (granted) action?.invoke()
        else Toast.makeText(
            context,
            "Storage access is needed to save videos in Movies/RaddStream.",
            Toast.LENGTH_LONG,
        ).show()
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        // A denied notification still lets the download run; only the progress card is silent.
        action?.invoke()
    }

    fun requestNotificationsThen(action: () -> Unit) {
        val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsGrant) {
            pendingPermissionAction = action
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun downloadWithStoragePermission(action: () -> Unit) {
        val needsLegacyGrant = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyGrant) {
            pendingPermissionAction = { requestNotificationsThen(action) }
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            requestNotificationsThen(action)
        }
    }

    LaunchedEffect(viewModel.navEvents) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is DetailNavigationEvent.PlayStream -> onPlayStream(event.streamId, event.title)
                is DetailNavigationEvent.OpenProviderPage ->
                    onOpenProviderPage(event.streamId, event.title)
                is DetailNavigationEvent.OpenExternal -> openInExternalPlayer(context, event.playUrl)
                is DetailNavigationEvent.DownloadStarted -> onOpenDownloads()
                is DetailNavigationEvent.DownloadNeedsPermission ->
                    downloadWithStoragePermission { viewModel.retryLastDownload() }
            }
        }
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    val season = state.layout?.seasons?.firstOrNull { it.number == seasonNumber }
    val postTitle = state.post?.title

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Season $seasonNumber",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (postTitle != null) {
                            Text(
                                text = postTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading season…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // MX #7 — Kids Lock: the filtered layout never carries a refused
            // season, and a defensive miss reads the same as a refused one —
            // no title, no numbers, only the way back.
            season == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "This season isn't available right now.",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "It may have been removed, or it isn't available in Kids Mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onBackClick,
                    // Detail review (Nov 2026, P1): the 48dp touch-target floor.
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Back to details")
                }
            }
            else -> {
                val tiles = seasonTiles(season, state.episodeProgress)
                val packPrint = season.packedPrints.maxByOrNull { it.rankKey }
                // Detail redesign (Nov 2026): the tiles are rows of six in
                // a LazyColumn, not a LazyVerticalGrid — the catalog grid
                // rule owns every grid's columns (CatalogGridColumns), and
                // a season's numbers are not posters. Six per row, a long
                // scrollable list for a 100+ episode series.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The season's download moved here from the old season
                    // header: one whole-season download, confirmed in the
                    // same dialog the Detail page uses. Downloads are
                    // locked in Kids Mode, so there is no button to tap.
                    if (!state.kidsMode) {
                        item {
                            OutlinedButton(
                                onClick = { viewModel.requestKeepSeason(season) },
                                // Detail review (Nov 2026, P1): the 48dp touch-target floor.
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("Download season")
                            }
                        }
                    }
                    // A whole-season file is one video, not episodes: one
                    // row that says so, with its own Play and Download.
                    // The numbered grid follows it for a mixed season
                    // (episodes AND a pack) — a pack-only season has no
                    // tiles at all, so the rows below are empty there.
                    if (packPrint != null) {
                        item {
                            SeasonPackRow(
                                season = season,
                                onPlay = { viewModel.playPrint(packPrint) },
                                onKeep = {
                                    downloadWithStoragePermission {
                                        viewModel.keepPrint(packPrint)
                                    }
                                },
                            )
                        }
                    }
                    items(tiles.chunked(6), key = { row -> "tile-row-${row.first().number}" }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { tile ->
                                EpisodeTile(
                                    tile = tile,
                                    onClick = {
                                        if (tile.playable) {
                                            viewModel.playEpisode(season.number, tile.number)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // A short last row pads with spacers, so its
                            // tiles keep the full six-across width instead
                            // of stretching to fill the row.
                            repeat(6 - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingSeasonKeep?.let { pending ->
        SeasonKeepConfirmDialog(
            pending = pending,
            wifiOnly = state.wifiOnlyDownloads,
            onConfirm = { viewModel.confirmKeepSeason() },
            onDismiss = { viewModel.dismissKeepSeason() },
        )
    }
}

/**
 * One numbered tile: the number, centred. The next episode to watch is
 * filled jade; one already watched carries a small jade tick; a number
 * with no playable file is dimmed and does nothing when tapped — it
 * stays on screen so the numbers stay true.
 */
@Composable
internal fun EpisodeTile(
    tile: SeasonTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val jade = MaterialTheme.colorScheme.primary
    val description = when {
        !tile.playable -> "Episode ${tile.number}, no playable copy"
        tile.isNext -> "Episode ${tile.number}, next to watch"
        tile.watched -> "Episode ${tile.number}, watched"
        else -> "Episode ${tile.number}"
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(CardGeometry.SEASON_TILE_ASPECT)
            .alpha(if (tile.playable) 1f else 0.35f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (tile.isNext) jade else MaterialTheme.colorScheme.surface,
            )
            .clickable(
                enabled = tile.playable,
                onClick = onClick,
                role = Role.Button,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tile.number.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (tile.isNext) FontWeight.Bold else FontWeight.SemiBold,
            color = if (tile.isNext) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        )
        if (tile.watched && !tile.isNext) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(14.dp),
                tint = jade,
            )
        }
    }
}

/**
 * A whole-season file: one row that says so plainly, with its own Play
 * and Download. The row's label is the season's own count line, so a
 * pack of 12 episodes reads "Season 1 — 12 episodes", never "1 file".
 */
@Composable
internal fun SeasonPackRow(
    season: SeasonGroup,
    onPlay: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Season ${season.number} — ${season.episodeCountLabel}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "This season is offered as one file — playing it starts the whole season.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPlay,
                // Detail review (Nov 2026, P1): the 48dp touch-target floor.
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("Play season")
            }
            OutlinedButton(
                onClick = onKeep,
                // Detail review (Nov 2026, P1): the 48dp touch-target floor.
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("Download")
            }
        }
    }
}
