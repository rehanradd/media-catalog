package com.raddstream.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The cast row: one tappable person per actor, in the order the catalog
 * lists them. A horizontal [LazyRow] of circular photos (56dp, Coil via
 * [CatalogImage]) with the name below (2 lines max, 12sp). Tap opens the
 * Actor screen. A photo that is missing — no TMDb match, or no profile
 * photo — shows an initials circle, never a wrong photo: [photos] maps
 * a name to its TMDb photo URL, and a name absent from the map, or
 * mapped to null, is initials. Horizontal scroll replaces the old
 * "and N more" toggle: a row scrolls, so no expand state is needed.
 *
 * Extracted from DetailScreen (where it was the private `CastSection`
 * of `FilterChip`s) so Paparazzi can render it directly.
 */
@Composable
fun CastRow(
    cast: List<String>,
    photos: Map<String, String?>,
    onActorClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            items(cast, key = { it }) { actor ->
                CastPerson(
                    actor = actor,
                    photoUrl = photos[actor],
                    onClick = { onActorClick(actor) },
                )
            }
        }
    }
}

@Composable
private fun CastPerson(actor: String, photoUrl: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick, role = Role.Button)
            // The whole item is one button named for the actor; the
            // photo and the name underneath are not separate nodes.
            .semantics(mergeDescendants = true) {
                contentDescription = actor
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (photoUrl != null) {
            // A real URL only — a null photo never reaches CatalogImage
            // (it would paint the branded fallback card, which reads as
            // "this person has artwork" rather than "no photo"). The
            // content description is null because the merged parent
            // semantics already names the actor.
            CatalogImage(
                url = photoUrl,
                contentDescription = null,
                fallbackLabel = null,
                modifier = Modifier.size(56.dp).clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // "?" is defensive only — parseCastNames already
                    // drops names with no letter in them.
                    text = channelMonogram(actor) ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = actor,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
