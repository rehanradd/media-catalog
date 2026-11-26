package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.channelMonogram
import com.raddstream.app.viewmodel.ActorUiState
import com.raddstream.app.viewmodel.ActorViewModel
import com.raddstream.app.viewmodel.PersonState

/**
 * One actor: their photo + bio (TMDb, via `GET /api/v1/person`) and
 * their movies & TV on this server (the existing posts search, by the
 * canonical TMDb name). Site metadata stays primary — the name that
 * opened this screen is the catalog's own scraped cast string, and
 * TMDb only ever supplies the photo and the bio. A name TMDb never
 * heard of shows an initials circle, never a wrong photo, and the
 * works grid still loads by the raw name; Search stays the fallback
 * behind "See all in Search".
 *
 * The name comes from the nav argument, read by the ViewModel — there
 * is no `name` parameter here on purpose, so the screen can never
 * disagree with the ViewModel about who it is showing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActorScreen(
    onBackClick: () -> Unit,
    onPostClick: (Post) -> Unit,
    onOpenSearch: (String) -> Unit,
    viewModel: ActorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    ActorContent(
        state = state,
        onBackClick = onBackClick,
        onPostClick = onPostClick,
        onOpenSearch = onOpenSearch,
        onRetryWorks = { viewModel.retry() },
    )
}

/**
 * The stateless Actor page, so Paparazzi can render it with fake state
 * (the Profile pattern). `name` in the state is the display name: the
 * canonical TMDb name once the lookup lands, else the raw scraped name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActorContent(
    state: ActorUiState,
    onBackClick: () -> Unit,
    onPostClick: (Post) -> Unit,
    onOpenSearch: (String) -> Unit,
    onRetryWorks: () -> Unit,
) {
    val displayName = state.canonicalName?.takeIf { it.isNotBlank() }
        ?: state.rawName
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // Header: the photo (or the initials circle — never a
                // wrong photo), the name, and the birth line. The birth
                // line prints only what TMDb actually sent: a bare date
                // and place joined by " · ", and nothing at all when both
                // are missing — never a "null" and never an invented date.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActorPhoto(
                        name = displayName,
                        photoUrl = state.person?.photoUrl,
                    )
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    val birthLine = listOfNotNull(
                        state.person?.birthday?.takeIf { it.isNotBlank() },
                        state.person?.placeOfBirth?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").takeIf { it.isNotEmpty() }
                    if (birthLine != null) {
                        Text(
                            text = birthLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            item {
                // Bio: the same expandable pattern as Detail's synopsis
                // (4 lines + "Read more", shown only when the text is
                // actually cut). A lookup that found no person, or a
                // person with no bio, says so in one line instead of
                // leaving a gap; an outage says the details are
                // unavailable — the works below still load either way.
                val bio = state.person?.biography
                when {
                    state.personState == PersonState.Found &&
                        !bio.isNullOrBlank() -> {
                        ExpandableBio(text = bio)
                    }
                    state.personState == PersonState.Unavailable -> {
                        Text(
                            text = stringResource(R.string.actor_details_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.actor_no_biography),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val knownFor = state.person?.knownFor.orEmpty()
            if (knownFor.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Known for",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        knownFor.take(5).forEach { credit ->
                            Text(
                                text = if (credit.year != null) {
                                    "${credit.title} (${credit.year})"
                                } else {
                                    credit.title
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.actor_works_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (state.worksLoading && state.works.isEmpty()) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            } else if (state.worksError) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.actor_works_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = onRetryWorks,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            } else if (state.works.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.actor_works_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Two-across poster grid, the same PosterCard the search
                // grid uses. Chunked into pairs because a LazyVerticalGrid
                // cannot live inside this LazyColumn; a pair is one row.
                items(state.works.chunked(2), key = { it.first().id }) { pair ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        pair.forEach { post ->
                            PosterCard(
                                post = post,
                                onClick = { onPostClick(post) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = {
                        onOpenSearch(state.canonicalName ?: state.rawName)
                    },
                    // Detail review (Nov 2026, P1): the 48dp
                    // touch-target floor.
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(stringResource(R.string.actor_see_all))
                }
            }

            item {
                // TMDb attribution — mandatory on any screen showing
                // TMDb data, worded exactly as TMDb requires it.
                Text(
                    text = stringResource(R.string.actor_tmdb_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * The actor's photo: 120dp, circular. [CatalogImage] only ever runs
 * with a real URL — a null photo means no TMDb match (or no profile
 * photo), and that renders the initials circle instead. The initials
 * reuse [channelMonogram]: first letters of the first two words
 * ("Shah Rukh Khan" → "SR"). The "?" branch is defensive only —
 * `parseCastNames` already drops names with no letter in them.
 */
@Composable
private fun ActorPhoto(name: String, photoUrl: String?) {
    if (photoUrl != null) {
        CatalogImage(
            url = photoUrl,
            contentDescription = name,
            fallbackLabel = null,
            modifier = Modifier.size(120.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = channelMonogram(name) ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The bio, expandable exactly like Detail's synopsis: 4 lines, then
 * "Read more" — and the button appears exactly when the text is
 * actually cut (the layout result is the truth; a short bio that fits
 * shows no button at all). A private copy, not a shared component:
 * Detail's `ExpandableSynopsis` is private to DetailScreen, and this
 * screen's copy keeps the two pages independent.
 */
@Composable
private fun ExpandableBio(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var hasOverflow by remember { mutableStateOf(false) }
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> hasOverflow = result.hasVisualOverflow },
        )
        if (expanded || hasOverflow) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (expanded) "Show less" else "Read more")
            }
        }
    }
}
