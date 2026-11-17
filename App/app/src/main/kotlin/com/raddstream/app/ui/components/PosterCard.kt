package com.raddstream.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.network.models.currentCalendarYear
import com.raddstream.app.network.models.isNewReleaseYear
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.RaddArtScrim
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.ui.theme.pressScale

/**
 * The canonical catalog tile. Used by Home rails, the search grid, My List and
 * Downloads. Kept intentionally light (one [CatalogImage] + a couple of Texts) and
 * always rendered inside a keyed lazy container so Compose can recycle slots cheaply
 * on low-RAM phones (Compose performance guidance). Review redesign (Nov 2026):
 * frameless — the M11 artwork-accent rim and the dark footer box are gone (both
 * reviewers read the rim as a selected/promoted outline), so the poster paints
 * edge-to-edge at 10dp corners and the title + meta sit below it on the page
 * background; the NEW pill is a small dark chip, top-left, never covering faces
 * or logos. No quality badge: the list wire carries no quality field (it
 * lives on Detail's RankedPrint), and inventing one would break the honesty
 * rule; that badge waits on a server `best_quality` field.
 *
 * Accessibility: the tile leads with one merged sentence — title, year and type, the rating out
 * of ten, and the progress line when the app has one — so the facts arrive in an order that
 * reads, instead of as bare labels off the artwork. The painted star is decorative to a screen
 * reader for exactly that reason: the sentence already says it. Every tap target fills the tile
 * width, and the image is one [CardGeometry.POSTER_ASPECT] box tall, so it clears 48dp on any
 * phone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    post: Post,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /**
     * How far a title the viewer already started has run, 0f..1f, or null when the app never
     * learned a duration. Only the Continue watching rail has an honest answer for this: a bar
     * on any other tile would claim a position the viewer has no row for. It is drawn as a
     * width, not only as a colour, so it survives a colour-blind viewer and a screen reader.
     */
    progressFraction: Float? = null,
    /** The same fact in words — "S2:E5 · 23 min left". */
    progressLabel: String? = null,
    /**
     * One-tap remove, shown as a small × on the poster's corner. Only My List
     * and Continue Watching pass it: everywhere else the tile is a doorway,
     * not a list to edit, and a remove badge there would be a trap for a
     * slipping thumb.
     */
    onRemove: (() -> Unit)? = null,
    /**
     * Pick 3 (home review, Nov 2026): the favourite heart, drawn on the
     * poster's free TopStart corner. Only Pick 3 passes it — the heart IS
     * that card's action, so it rides the art instead of the text row. In
     * the ranked rail the rank badge owns TopStart and the NEW pill stays
     * off, so a tile carries either a rank or a heart, never both. Null
     * everywhere else: everywhere else the tile is a doorway, not a list
     * to edit, and a heart there would be a trap for a slipping thumb.
     */
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    /**
     * 1-based server ranking position (1..10) when rendered inside the ranked rail.
     * Null everywhere else — no synthetic rank numbers are ever invented.
     */
    rank: Int? = null,
    /**
     * My List review (Nov 2026): the compact meta line for the 3-up
     * starter rail — rating and year only, "★ 8.6 · 2024", no type word.
     * Three segments truncate to "S..." in a 3-up cell; Home and Search
     * rails have room and keep the full "★ 6.7 · 2026 · Series" line, so
     * this stays false everywhere except [PopularRow]. The type stays in
     * the tile's spoken sentence and on Detail — only the painted rail
     * line loses it.
     */
    compactMeta: Boolean = false,
) {
    // Rail-title cleanup (Nov 2026): the tile paints the same clean title
    // Detail and the hero paint ([UserWords.detailTitle] — the server's
    // display title first, then the clean title, then the raw title, each
    // stripped of the year/language/type/season suffixes the meta line
    // already states), so a rail card can never show "(2023) English
    // Movie" under a meta line that already reads "2023 · Movie". The
    // title data itself is untouched — only what is painted.
    val title = UserWords.detailTitle(
        displayTitle = post.displayTitleWire,
        cleanTitle = post.cleanTitle,
        title = post.title,
        year = post.year,
        contentType = post.contentType,
        languages = WireText.listOf(post.language),
    )
    val typeLabel = post.typeLabel
    // M11 (home review, Nov 2026): the tile is a new release when its year
    // is plausible and within the last two calendar years. The bound is
    // stricter than [Post.yearLabel]'s — display allows next year's films,
    // NEW means released — so a mis-parsed 2027 can never earn the badge.
    val isNewRelease = remember(post.year) {
        isNewReleaseYear(post.year, currentCalendarYear())
    }
    // M3 (home review, Nov 2026): the year comes from [Post.yearLabel], the
    // guarded choke point — the old inline `takeIf { it > 0 }` let an
    // impossible year (2027 for a 2024 work, 20215) straight onto the tile.
    // Mockup (Nov 2026): the meta line carries the rating too — one line,
    // "★ 6.7 · 2026 · Series" — so the rating badge that used to ride the
    // poster is gone: it would say the same number twice on one tile.
    val details = posterMetaLine(post, compactMeta)
    // The spoken sentence keeps the old shape — "2024 · Movie" — and the
    // rating arrives once, as "rating 8.6 out of 10" below, so a screen
    // reader never hears the number twice.
    val spokenDetails = listOfNotNull(
        post.yearLabel,
        typeLabel,
    ).joinToString(" · ")
    // Robo run 2: the badge pills announced their a11y labels as separate
    // nodes, so every tile with the same badge repeated the same words ("Hindi
    // dub of a work originally in another language" on dozens of tiles). The
    // tile speaks one sentence — badges included — and the painted row below
    // is paint-only, so a screen reader hears each tile once.
    val tileBadges = TileAudioBadges.forPost(post)
    val a11y = buildString {
        if (rank != null && rank in 1..10) append("Rank ").append(rank).append(", ")
        append(title)
        if (spokenDetails.isNotBlank()) append(", ").append(spokenDetails)
        if (isNewRelease) append(", new release")
        post.imdbRating?.let { UserWords.displayRating(it) }?.let { rating ->
            append(", rating ").append(rating).append(" out of 10")
        }
        if (tileBadges.isNotEmpty()) append(", ").append(tileBadges.joinToString(", ") { it.a11y })
        if (progressLabel != null) append(", ").append(progressLabel)
    }

    // Review redesign (Nov 2026): the title slot is exactly one line tall,
    // taken from the style's own line height, so every card in a rail has
    // the same height — the round-2 "inconsistent card heights" complaint,
    // kept from the two-line slot (F7, Batch B). The height is
    // density-scaled, so a 200% font scale grows the slot instead of
    // clipping the line.
    val titleLineHeight = MaterialTheme.typography.titleSmall.lineHeight
    val titleSlotHeight = with(LocalDensity.current) {
        // TextUnit has no isSpecified in this Compose version — Unspecified
        // is the sentinel to compare against.
        if (titleLineHeight != TextUnit.Unspecified) titleLineHeight.value.sp.toDp() else 20.dp
    }

    val clickable = Modifier.semantics { contentDescription = a11y }

    // UI-fix P1-X1: the tile's own press source — the scale + haptic track
    // the same presses the tile's combinedClickable already owns.
    val pressSource = remember { MutableInteractionSource() }
    PressHaptics(pressSource)

    // Review redesign (Nov 2026): frameless — no Card, no rim, no glow.
    // The poster paints edge-to-edge and the text sits below it on the
    // page background, so the tile reads as artwork, not as a boxed card.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .pressScale(pressSource)
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(
                            interactionSource = pressSource,
                            // 1.7.0 has no interactionSource-only overload:
                            // the indication is required, and null is right
                            // here — pressScale is the tile's feedback now,
                            // not the Material ripple.
                            indication = null,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    onClick != null ->
                        Modifier.combinedClickable(
                            interactionSource = pressSource,
                            indication = null,
                            onClick = onClick,
                        )
                    else -> Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardGeometry.POSTER_ASPECT)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomStart,
        ) {
            val posterUrl = post.posterUrl?.takeIf { it.isNotBlank() }
            if (posterUrl == null) {
                ImageFallbackCard(
                    modifier = Modifier.fillMaxSize(),
                    // F6 (Batch A): the full title, not initials — the tile
                    // must say which title has no poster.
                    label = title,
                )
            } else {
                CatalogImage(
                    url = posterUrl,
                    contentDescription = null, // tile-level a11y already announced the title
                    modifier = Modifier.fillMaxSize(),
                    // A dead poster URL must not look like "no artwork exists" — the
                    // branded fallback says what failed, and keeps the title readable.
                    fallbackLabel = title,
                )
            }

            // F12 (Batch B): the remove × no longer sits on the poster
            // art — it rode the image corner and covered the artwork on
            // every saved tile. It lives in the text row now, beside the
            // title, on the page background; the image corner is art-only.

            // Bottom scrim: a poster's lower edge is whatever the artwork happens to be, and
            // the progress bar below has to stay visible against it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                            )
                        )
                    )
                    .height(36.dp),
            )

            progressFraction?.let { fraction ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                ) {
                    // My List review (Nov 2026): the bar is jade, not grey —
                    // the same primary Home's Continue Watching rail paints,
                    // so a half-watched saved title reads as progress, not
                    // as a loading strip. Only My List passes a fraction.
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            // Mockup (Nov 2026): no rating badge on the poster any more —
            // the rating lives in the meta line under the poster ("★ 6.7 ·
            // 2026 · Series"), and a badge here would say the same number
            // twice. The scrim stays: the 3dp progress bar below still
            // needs contrast against bright artwork.

            if (rank != null && rank in 1..10) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RaddArtScrim)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                        .clearAndSetSemantics { },
                ) {
                    Text(
                        text = "#$rank",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }

            // Review redesign (Nov 2026): the NEW pill is a small dark
            // chip — scrim plate, jade text — never a neon sticker over
            // the art. It rides TopStart, where the eye lands first;
            // the Pick 3 heart owns TopStart in its rail, so there it
            // falls back to TopEnd instead. It never rides with a rank
            // badge — a ranked tile shows the rank only (Gemini home
            // review, Nov 2026: max one badge per card — #1 + NEW on a
            // narrow poster crowded the art). Paint-only, the tile's
            // one spoken sentence already says "new release".
            if (isNewRelease && rank == null) {
                val newAlignment =
                    if (onToggleFavorite != null) Alignment.TopEnd else Alignment.TopStart
                Box(
                    modifier = Modifier
                        .align(newAlignment)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RaddArtScrim)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                        .clearAndSetSemantics { },
                ) {
                    Text(
                        text = "NEW",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }

            // Pick 3: the heart on the poster — the card's whole point
            // is tapping it. A 48dp touch target in a scrimmed circle,
            // so it reads as a control and never as part of the art; the
            // button eats its own tap, so it never also opens the title
            // (same as the remove × below). TopStart: the NEW pill falls
            // back to TopEnd when the heart is here, and a rank badge
            // would own TopStart — the two never meet on one tile (see
            // the parameter docs).
            if (onToggleFavorite != null) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) {
                            "Remove $title from My List"
                        } else {
                            "Save $title to My List"
                        },
                        tint = if (isFavorite) {
                            // My List review (Nov 2026): a saved heart is
                            // jade, the brand primary. The coral secondary
                            // read as an error state next to the jade My
                            // List tab — and the empty-state heart is jade
                            // too, so a saved tile and the empty state
                            // finally agree on what "saved" looks like.
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // The text row: the title, details, badges and progress are paint
        // here, not four more nodes saying the same words the tile already
        // said — the column below is cleared for exactly that reason, so
        // the remove button sits BESIDE it in this row, not inside it:
        // a cleared subtree would swallow the button's own description
        // and tap. One spoken sentence per tile (see the a11y string
        // above).
        Row(
            // Review redesign (Nov 2026): the text sits on the page
            // background now, aligned to the poster edges — no inset,
            // no footer box.
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f).clearAndSetSemantics { },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // M2 (home review, Nov 2026): Compose's Ellipsis cuts at
                // the last fitting character, not the last word
                // ("Strange New W…"). When the one line is ellipsized
                // mid-word, back the text off to the previous word
                // boundary once — the trimmed string is strictly shorter
                // and the guard below keeps the state from ever looping.
                var displayTitle by remember(title) {
                    mutableStateOf(UserWords.breakableTitle(title))
                }
                Text(
                    text = displayTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        if (result.isLineEllipsized(0)) {
                            val trimmed = UserWords.trimToWordBoundary(
                                displayTitle,
                                result.getLineEnd(0, visibleEnd = true),
                            )
                            if (trimmed != displayTitle) displayTitle = trimmed
                        }
                    },
                    modifier = Modifier.height(titleSlotHeight),
                )
                if (details.isNotBlank()) {
                    Text(
                        text = details,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        // F7 (Batch B): one line, not two — year + type
                        // never needs a second line, and the second line
                        // was the other half of the uneven card heights.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Session 121 (P-28): the badge row only exists when a title has
                // badges, so cards in one rail had different heights and a badge
                // could straddle the rail's clip edge ("Dual Audio" cut mid-glyph).
                // The slot is always 24 dp — the pill's own height — so card height
                // is invariant to badge presence and rails stay uniform.
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                    // Dub status decides whether this title is watchable at all for a
                    // Hindi/Urdu-first viewer, so it is said on the tile, not after a tap.
                    AudioBadgeChipRow(badges = tileBadges)
                }

                progressLabel?.let { label ->
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // F12 (Batch B): one tap, not a held tile and not a confirm —
            // the × removes the row at once, and the screen offers Undo in
            // the snackbar. The button eats its own tap, so it never also
            // opens the title. It sits beside the title now, never on the
            // poster art above.
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove $title from My List",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
/**
 * My List review (Nov 2026): the rail meta line, as one pure function so
 * the compact form is assertable without a layout. Full form is
 * "★ 6.7 · 2026 · Series"; the compact form (the 3-up starter rail, where
 * three segments truncate to "S...") drops the type word — "★ 8.6 · 2024".
 * Both join with a middle dot (Gemini home review, Nov 2026: one separator
 * everywhere — the hero, the spoken sentence and the cards all use · now).
 * The type stays in the tile's spoken sentence
 * and on Detail; only the painted rail line loses it. An absent rating or
 * year simply leaves its slot out — never an invented value.
 */
internal fun posterMetaLine(post: Post, compact: Boolean): String =
    if (compact) {
        listOfNotNull(
            UserWords.displayRating(post.imdbRating)?.let { "★ $it" },
            post.yearLabel,
        ).joinToString(" · ")
    } else {
        listOfNotNull(
            UserWords.displayRating(post.imdbRating)?.let { "★ $it" },
            post.yearLabel,
            post.typeLabel,
        ).joinToString(" · ")
    }

/**
 * F25 (Batch B): the placeholder tile as Android Studio sees it — a title
 * with no poster art, so the F6 gradient + title treatment is visible
 * without running the app. The screenshot test in ScreenSnapshotTest pins
 * the same tile in CI.
 */
@Preview(name = "Poster card placeholder", showBackground = true)
@Composable
private fun PosterCardPlaceholderPreview() {
    RaddStreamTheme {
        PosterCard(
            post = Post(
                id = 1,
                title = "Jawan",
                cleanTitle = "Jawan",
                year = 2023,
                contentType = "movie",
                posterUrl = null,
            ),
            modifier = Modifier.width(180.dp),
        )
    }
}
