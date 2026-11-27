package com.raddstream.app.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.components.ImageFallbackCard
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.components.channelMonogram
import com.raddstream.app.ui.components.posterMetaLine
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.posterSharedElement

/** Test tag on a search-result rich row, so a test can count result rows. */
internal const val SearchResultRowTag = "search-result-row"

/**
 * Design direction REFINED (Nov 2026): the rich row's second line — the
 * "more info" under year · type · ★ rating. Genres first (at most two),
 * then the first language, so a viewer comparing results sees what kind of
 * title it is and what it speaks without opening it. Blank when the row
 * carries neither. Pure, so a test can pin it without a composition.
 */
internal fun searchResultDetailLine(post: Post): String {
    val genres = post.genreList.take(2).joinToString(", ")
    val language = WireText.listOf(post.language).firstOrNull()
    return listOfNotNull(
        genres.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * Design direction REFINED (Nov 2026): a search result is a rich row, not
 * a poster card. Search must look DIFFERENT from Home — YouTube-style: a
 * bigger thumb on the left, the title, the rating and more info on the
 * right (year · type · ★ rating, then genres · language). Search shows
 * MORE than Home, because the viewer is comparing; Home cards stay poster
 * + title + language, and the rating lives here and on Detail.
 *
 * The thumb reuses the shared image loading ([CatalogImage] with its
 * prefetch/cache, retry and branded fallback) — only the row layout is
 * new. The thumb carries the shared element to the Detail hero, the same
 * "poster-<workId>" key the poster card used to carry.
 */
@Composable
internal fun SearchResultRow(
    post: Post,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val title = UserWords.detailTitle(
        displayTitle = post.displayTitleWire,
        cleanTitle = post.cleanTitle,
        title = post.title,
        year = post.year,
        contentType = post.contentType,
        languages = WireText.listOf(post.language),
    )
    val posterUrl = post.posterUrl?.takeIf { it.isNotBlank() }
    // The meta line is the full one — year · ★ rating · type — not the
    // compact card line: the row has room, and the rating is the point.
    val metaLine = posterMetaLine(post, compact = false)
    val detailLine = searchResultDetailLine(post)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SearchResultRowTag)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(CardGeometry.SEARCH_RESULT_ROW_THUMB_WIDTH_DP.dp)
                .aspectRatio(CardGeometry.POSTER_ASPECT)
                .clip(RaddTokens.Shape.Card)
                .posterSharedElement(
                    key = "poster-${post.workId ?: post.id}",
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
        ) {
            if (posterUrl == null) {
                ImageFallbackCard(
                    modifier = Modifier.fillMaxSize(),
                    label = channelMonogram(title),
                )
            } else {
                CatalogImage(
                    url = posterUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // A dead poster URL must not look like "no artwork
                    // exists" — the branded fallback says what failed, and
                    // keeps the title readable. The caption beside the
                    // thumb already carries the title, so the fallback
                    // paints initials only, never the title twice.
                    fallbackLabel = channelMonogram(title),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaLine.isNotBlank()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detailLine.isNotBlank()) {
                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
