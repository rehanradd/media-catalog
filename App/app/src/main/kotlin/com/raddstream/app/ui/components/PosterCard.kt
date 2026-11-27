package com.raddstream.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raddstream.app.R
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.ui.theme.pressScale
import com.raddstream.app.ui.theme.rememberArtworkAccent

/**
 * Round 6 (motion): the shared-element key for a work's artwork — the
 * "poster-<workId>" key the Detail hero uses on the other side of the
 * transition. The key identifies the WORK, not literally a poster: the
 * hero's backdrop-first art and a rail tile's poster are the same work.
 * Infra should consolidate this into Motion.kt; the mega-merge dedupes.
 */
internal fun sharedPosterKey(post: Post): String = "poster-${post.workId ?: post.id}"

/**
 * Round 6 (motion): the shared-element modifier for a work's artwork.
 * Returns [Modifier] unchanged when either scope is null (dormant until
 * the infra branch passes the scopes from the NavHost's
 * SharedTransitionLayout). Only the artwork morphs — the scrim, progress
 * bar and rank stay put.
 */
@Composable
internal fun sharedPosterModifier(
    post: Post,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return Modifier
    return with(sharedTransitionScope) {
        Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(key = sharedPosterKey(post)),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }
}

/**
 * The canonical catalog tile. Used by Home rails, the search grid, My List and
 * Downloads. Kept intentionally light (one [CatalogImage] + a couple of Texts) and
 * always rendered inside a keyed lazy container so Compose can recycle slots cheaply
 * on low-RAM phones (Compose performance guidance). Review redesign (Nov 2026):
 * frameless — the M11 artwork-accent rim and the dark footer box are gone (both
 * reviewers read the rim as a selected/promoted outline), so the poster paints
 * edge-to-edge at 16dp corners (SE-4, round 2: the brief's 16–20dp card
 * radius, via the `RaddTokens.Radius.Card` token) and the title + meta sit
 * below it on the page background; the NEW pill is gone (video audit
 * issue 30, Nov 2026: the year-only rule put it on everything, including
 * a 1997 title, so the badge meant nothing — it is removed, not repaired,
 * because the list wire carries no added date to build a true recency
 * badge from). No quality badge: the list wire carries no quality field
 * (it lives on Detail's RankedPrint), and inventing one would break the
 * honesty rule; that badge waits on a server `best_quality` field.
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
     * the ranked rail the rank badge owns TopStart, so a tile carries
     * either a rank or a heart, never both. Null
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
     * Home fix (Nov 2026): direct play — one tap starts the title, no Detail
     * stop in between. Only Home rails pass it (APP-06, Phase 4), so only
     * they grow the play button; everywhere else the tile stays a doorway
     * to Detail. P2-7 (B-36): My List's saved tiles used to pass it too —
     * the button is gone there now (tile tap opens the title, Play lives
     * on Detail). Null means no play button. The tap opens Detail with
     * autoplay, and Detail decides what "play" means for that title
     * (resume, HLS, quality sheet, or the "no working link" line) — the
     * tile never guesses playability itself.
     */
    onPlayClick: (() -> Unit)? = null,
    /**
     * L2 (round 2): the download badge — a verified copy of this title
     * sits on the device (see MyListViewModel.downloadedPostIdsFor). Only
     * My List passes it: the keep store is the only download state
     * joinable to a tile by post id. False everywhere else — a tile must
     * never claim a copy it cannot prove.
     */
    isDownloaded: Boolean = false,
    /**
     * P1-3 (B-10): reserve the 24 dp badge slot even when this tile has
     * no badges, so a surface where a badge is EXPECTED stays uniform.
     * Only My List passes true — it is the one surface whose tiles join
     * the download store, so a downloaded badge may appear on any tile
     * there and the grid must not raggedly grow a row when one lands.
     * Everywhere else the slot collapses when empty (see the badge row
     * below), so an unbadged tile is 24 dp tighter. False by default:
     * uniformity bought with permanent empty space is the bug B-10
     * names, and no other caller expects a badge on every tile.
     */
    reserveBadgeSlot: Boolean = false,
    /**
     * REA #2 (Netflix, Nov 2026): the "97% Match" for this tile, from
     * the "For you" row's ranker score. Null everywhere except that row
     * — a % with no ranker score behind it would be invented.
     */
    matchPercent: Int? = null,
    /**
     * Screen audit #7 (Nov 2026): the search results grid passes 2 —
     * a long title gets a second line instead of truncating under a
     * "Series" header that already says what it is. Everywhere else
     * keeps the one-line slot (F7): rails stay uniform.
     */
    titleMaxLines: Int = 1,
    /**
     * Round 6 (motion): the shared-element scopes, passed down from the
     * NavHost's SharedTransitionLayout by the infra branch. Both null by
     * default — the tile compiles and renders exactly as before until the
     * scopes arrive, so this is dormant until infra wires them. When
     * wired, the poster image carries sharedElement(key =
     * "poster-<workId>"), one key per work, shared with the Detail hero.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    // Rail-title cleanup (Nov 2026): the tile paints the same clean title
    // Detail and the hero paint ([UserWords.detailTitle] — the server's
    // display title first, then the clean title, then the raw title, each
    // stripped of the year/language/type/season suffixes), so a rail card
    // can never show "(2023) English Movie" under a meta line that
    // already reads "English". The title data itself is untouched —
    // only what is painted.
    val title = UserWords.detailTitle(
        displayTitle = post.displayTitleWire,
        cleanTitle = post.cleanTitle,
        title = post.title,
        year = post.year,
        contentType = post.contentType,
        languages = WireText.listOf(post.language),
    )
    val typeLabel = post.typeLabel
    // Video audit (Nov 2026), refined direction 21:51 PKT 27 Nov: the
    // meta line is the LANGUAGE only — "Hindi" — so the card shows
    // poster + title + language and nothing else. Year and rating are
    // OFF home cards (they live on Detail, and rating in Search too);
    // the type word never paints either. It stays in the spoken
    // sentence and on Detail.
    val details = posterMetaLine(post, matchPercent)
    val showDetails = details.isNotBlank()
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
        post.imdbRating?.let { UserWords.displayRating(it) }?.let { rating ->
            append(", rating ").append(rating).append(" out of 10")
        }
        if (tileBadges.isNotEmpty()) append(", ").append(tileBadges.joinToString(", ") { it.a11y })
        if (matchPercent != null) append(", $matchPercent percent match")
        if (isDownloaded) append(", downloaded")
        if (progressLabel != null) append(", ").append(progressLabel)
    }

    // Review redesign (Nov 2026): the title slot is exactly one line tall,
    // taken from the style's own line height, so every card in a rail has
    // the same height — the round-2 "inconsistent card heights" complaint,
    // kept from the two-line slot (F7, Batch B). The height is
    // density-scaled, so a 200% font scale grows the slot instead of
    // clipping the line. Screen audit #7: the slot scales with
    // titleMaxLines — the search grid's two-line titles get a two-line
    // slot, so its cards stay uniform with each other. Round 5: the slot
    // is a floor, not a cap (see the title Text below) — an exact
    // height() is a hair short of the laid-out lines at 1.3x, so the
    // layout collapsed to one line.
    // Video audit (Nov 2026): the title rides labelMedium (12sp), not
    // titleSmall (14sp) — smaller text so more content fits, per the
    // owner's direction; the slot height follows the same style.
    val titleLineHeight = MaterialTheme.typography.labelMedium.lineHeight
    val titleSlotHeight = with(LocalDensity.current) {
        // TextUnit has no isSpecified in this Compose version — Unspecified
        // is the sentinel to compare against.
        if (titleLineHeight != TextUnit.Unspecified) titleLineHeight.value.sp.toDp() else 20.dp // token-exempt: typography fallback (a line height), not spacing
    }

    val clickable = Modifier.semantics { contentDescription = a11y }

    // P1-4 (B-11): the tile's own press source — the scale + ripple
    // track the same presses the tile's combinedClickable already owns.
    // The haptic does NOT ride this source any more: a tick on every
    // tile tap is noisy, so haptics are gated to the primary actions —
    // the play button and the Pick 3 heart — each on its own source
    // below. The tile tap itself gets ripple + scale only.
    val pressSource = remember { MutableInteractionSource() }
    val playPressSource = remember { MutableInteractionSource() }
    PressHaptics(playPressSource)
    val favoritePressSource = remember { MutableInteractionSource() }
    PressHaptics(favoritePressSource)

    // Home fix (Nov 2026): D-pad / remote focus. The tile is focusable by
    // default (combinedClickable), but a focused tile painted nothing — a
    // remote user could not see which tile was selected. A focused tile
    // wears a primary-colour rim on its poster, the same accent the NEW
    // pill and the hero use, so focus reads as selection everywhere.
    var isFocused by remember { mutableStateOf(false) }

    // Review redesign (Nov 2026): frameless — no Card, no rim, no glow.
    // The poster paints edge-to-edge and the text sits below it on the
    // page background, so the tile reads as artwork, not as a boxed card.
    // L2 (round 2): the poster radius is the Card token (16dp) —
    // verify-first measured the true value at 8dp (Shape.Poster), not the
    // 10dp the Library rating read or the 12dp the Search rating read;
    // both measurements were wrong. The brief's 16–20dp range is the bar,
    // and the Card token is its 16dp end.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .pressScale(pressSource, pressedScale = TILE_PRESS_SCALE)
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(
                            interactionSource = pressSource,
                            // P1-4 (B-11): no indication override — the
                            // default LocalIndication is the M3 ripple, so
                            // the tile gets its state layer back. The old
                            // `indication = null` (pressScale as the only
                            // feedback) is gone; scale is the secondary cue
                            // now, at 0.96, and the ripple is the primary one.
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    onClick != null ->
                        Modifier.combinedClickable(
                            interactionSource = pressSource,
                            onClick = onClick,
                        )
                    else -> Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Xxs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CardGeometry.POSTER_ASPECT)
                // SE-4 (round 2): the card radius is the brief's 16dp
                // (`RaddTokens.Radius.Card`), not the 8dp poster token —
                // the tile is the card, and the 16–20dp radius is what
                // lifts Cards off the 10/12dp cap the re-rate measured.
                .clip(RaddTokens.Shape.Card)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (isFocused) {
                        Modifier.border(
                            width = 2.dp, // token-exempt: focus-border width, not spacing
                            color = MaterialTheme.colorScheme.primary,
                            shape = RaddTokens.Shape.Card,
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            val posterUrl = post.posterUrl?.takeIf { it.isNotBlank() }
            // S5: the poster's own accent, sampled once its bitmap lands —
            // the loading slot paints in this colour at low alpha instead
            // of grey, so a rail fills in as its own palette. Before the
            // first load there is no bitmap, so the accent is the brand
            // fallback (the floor, not a guess); a tile whose poster has
            // loaded once repaints its own colour on every later load.
            // Keyed on the post, so a recycled slot never wears the
            // previous title's colour.
            var posterBitmap by remember(post.id) { mutableStateOf<Bitmap?>(null) }
            val posterAccent = rememberArtworkAccent(posterBitmap)
            // Audit #7: the big rank numeral owns the poster's bottom-left
            // — a two-line fallback title centred in the box runs into it
            // (the "2" striking through "– Ceasefire"). A ranked tile
            // clears the numeral's zone: the fallback content shifts up by
            // half the clearance, so the title never reaches the numeral.
            // The numeral is ~54dp tall (displayMedium + bottom padding);
            // 56dp clears it with room for a three-line title.
            val rankClearance = if (rank != null && rank in 1..10) 56.dp else 0.dp // token-exempt: rank-numeral clearance, a layout constant derived from the ~54dp numeral
            if (posterUrl == null) {
                ImageFallbackCard(
                    modifier = Modifier.fillMaxSize()
                        .then(sharedPosterModifier(post, sharedTransitionScope, animatedVisibilityScope)),
                    // Video audit issue 32 (Nov 2026): no monogram here —
                    // a title with no poster gets the branded placeholder
                    // (mark only), never a two-letter box. The caption
                    // below the poster already carries the title, and a
                    // monogram ("IO", "IC") reads as content that is not
                    // there. (Audit #9's initials-only rule is superseded
                    // by this: the mark alone is the placeholder.)
                    label = null,
                    bottomClearance = rankClearance,
                )
            } else {
                CatalogImage(
                    url = posterUrl,
                    contentDescription = null, // tile-level a11y already announced the title
                    modifier = Modifier.fillMaxSize()
                        .then(sharedPosterModifier(post, sharedTransitionScope, animatedVisibilityScope)),
                    // A dead poster URL must not look like "no artwork exists" — the
                    // branded fallback says what failed, and keeps the title readable.
                    // Video audit issue 32: no monogram on the error slot
                    // either — the branded placeholder, never a monogram.
                    // Video audit issue 27: a backdrop filed as a poster
                    // renders fitted, never centre-cropped into a fragment.
                    fallbackLabel = null,
                    fallbackBottomClearance = rankClearance,
                    slotAspect = CardGeometry.POSTER_ASPECT,
                    fitWideArt = true,
                    onBitmap = { posterBitmap = it },
                    accentTint = posterAccent,
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
                    .height(36.dp), // token-exempt: bottom-scrim height, fixed component geometry
            )

            progressFraction?.let { fraction ->
                // Round 6 (motion): the bar glides to its value on a spring
                // instead of jumping — animateFloatAsState starts at the
                // target on first composition, so a Paparazzi golden still
                // shows the settled fraction.
                val animatedFraction by animateFloatAsState(
                    targetValue = fraction.coerceIn(0f, 1f),
                    animationSpec = if (rememberReduceMotion()) snap() else RaddMotion.defaultSpatial(),
                    label = "posterProgress",
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp) // token-exempt: progress-bar thickness, not spacing
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                ) {
                    // My List review (Nov 2026): the bar is jade, not grey —
                    // the same primary Home's Continue Watching rail paints,
                    // so a half-watched saved title reads as progress, not
                    // as a loading strip. Only My List passes a fraction.
                    Box(
                        Modifier
                            .fillMaxWidth(animatedFraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            // Mockup (Nov 2026): no rating badge on the poster any more —
            // the rating lives on Detail (and in Search), not on the
            // card at all, and a badge here would say the same number
            // twice. The scrim stays: the 3dp progress bar below still
            // needs contrast against bright artwork.

            // Home fix (Nov 2026): big Top 10 numerals. The small "#N" chip
            // read as a badge, not a chart position — a viewer could not tell
            // a Top 10 rail from any other rail at a glance. The numeral is
            // display-size at the poster's bottom-left, over the bottom
            // scrim, with a hard shadow so it reads over any artwork — the
            // Netflix shape, kept inside this tile (no layout change to the
            // rail). Paint-only: the tile's one spoken sentence already says
            // "Rank N".
            if (rank != null && rank in 1..10) {
                Text(
                    text = "$rank",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        shadow = Shadow(
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                            offset = Offset(2f, 2f),
                            blurRadius = 6f,
                        ),
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = RaddTokens.Space.Sm, bottom = RaddTokens.Space.Micro)
                        .clearAndSetSemantics { },
                )
            }

            // Video audit issue 30 (Nov 2026): the NEW pill is gone — the
            // year-only rule put it on everything, including a 1997
            // title, so it meant nothing. Do not re-add a recency badge
            // from the year: a true one needs a server `date_added` on
            // the list projection. The rank numeral below is the only
            // overlay a ranked tile carries.

            // Pick 3: the heart on the poster — the card's whole point
            // is tapping it. The touch target stays 48dp (the a11y pin
            // in PickThreeCardTest) while the scrimmed disc reads 40dp,
            // per the owner's smaller-buttons direction: the disc is
            // TopStart-aligned inside the 48dp button, so it sits
            // exactly where the 40dp button sat. The button eats its own
            // tap, so it never also opens the title (same as the remove
            // × below). TopStart: a rank numeral would own BottomStart
            // — the two never meet on one tile (see the parameter docs).
            if (onToggleFavorite != null) {
                IconButton(
                    onClick = onToggleFavorite,
                    interactionSource = favoritePressSource,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(RaddTokens.Space.Xs)
                        .size(48.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp) // token-exempt: on-poster button size, fixed component geometry
                                .background(
                                    MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(if (isFavorite) R.drawable.ic_action_favorite_filled else R.drawable.ic_tab_my_list),
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
                                modifier = Modifier.size(24.dp), // token-exempt: icon size, not spacing
                            )
                        }
                    }
                }
            }

            // Home fix (Nov 2026): direct play — the play affordance on
            // the tile. A 40dp play button in a scrimmed circle at the
            // poster's bottom-right, opposite the rank numeral; it eats
            // its own tap, so it never also opens the title (same as the
            // heart above). Only Home rails pass onPlayClick, so only
            // they grow the button (P2-7 removed it from My List tiles).
            if (onPlayClick != null) {
                IconButton(
                    onClick = onPlayClick,
                    interactionSource = playPressSource,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(RaddTokens.Space.Xs)
                        .size(40.dp) // token-exempt: on-poster button size, fixed component geometry
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_play_filled),
                        contentDescription = "Play $title",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp), // token-exempt: icon size, not spacing
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
                verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Micro),
            ) {
                // M2 (home review, Nov 2026): Compose's Ellipsis cuts at
                // the last fitting character, not the last word
                // ("Strange New W…"). When the one line is ellipsized
                // mid-word, back the text off to the previous word
                // boundary once — the trimmed string is strictly shorter
                // and the guard below keeps the state from ever looping.
                // Screen audit #7: with two lines the cut lands on the
                // LAST line, not line 0 — the check reads the last
                // laid-out line, so a two-line title trims the same way.
                var displayTitle by remember(title) {
                    mutableStateOf(UserWords.breakableTitle(title))
                }
                Text(
                    text = displayTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        val lastLine = result.lineCount - 1
                        if (lastLine >= 0 && result.isLineEllipsized(lastLine)) {
                            val trimmed = UserWords.trimToWordBoundary(
                                displayTitle,
                                result.getLineEnd(lastLine, visibleEnd = true),
                            )
                            if (trimmed != displayTitle) displayTitle = trimmed
                        }
                    },
                    // Round 5: heightIn(min = …), not height(…) — the slot
                    // is the uniform floor every tile reserves, never a
                    // cap. An exact height() of two line heights is a hair
                    // short of two laid-out lines at 1.3x (font padding),
                    // so the layout collapsed to one ellipsized line and
                    // the trim below cut it to "Salaar:…" — maxLines = 2
                    // had no visible effect (round 3, record 45580590803;
                    // the Search grid's two-line titles collapse the same
                    // way at 1.3x). The min keeps rails uniform; the lines
                    // measure their natural height above it.
                    modifier = Modifier.heightIn(min = titleSlotHeight * titleMaxLines),
                )
                if (showDetails) {
                    // Video audit, refined direction (Nov 2026): the meta
                    // line is one short word — the language, or "97%
                    // Match · Hindi" — so there is nothing width-aware to
                    // drop any more and no BoxWithConstraints: the line
                    // paints exactly as [posterMetaLine] computed it.
                    var displayDetails by remember(details) { mutableStateOf(details) }
                    Text(
                        text = displayDetails,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        // F7 (Batch B): one line, not two — a second line
                        // was the other half of the uneven card heights.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (result.lineCount > 0 && result.isLineEllipsized(0)) {
                                val trimmed = trimMetaLineToWordBoundary(
                                    displayDetails,
                                    result.getLineEnd(0, visibleEnd = true),
                                )
                                if (trimmed != displayDetails) displayDetails = trimmed
                            }
                        },
                    )
                }
                // P1-3 (B-10): the badge slot collapses when the tile has
                // no badges — the old always-24 dp slot bought uniform
                // card heights with permanent empty space on every
                // unbadged tile. An AnimatedVisibility holds the slot
                // open only while a badge is on it (or while the caller
                // reserved it — see reserveBadgeSlot), so a badge that
                // lands late (a download finishing) grows the tile
                // instead of popping in over the rail's clip edge, and
                // an unbadged tile is 24 dp tighter. Under reduced
                // motion the change is instant, never animated.
                // L2 (round 2): the badge slot — the download badge rides
                // here, ahead of the audio badges, so it never competes
                // with the heart or the play button on the poster (max one
                // badge per card on the art). Overlaying these badges on
                // the art would break that rule, so the slot collapses
                // instead of moving.
                AnimatedVisibility(
                    visible = isDownloaded || tileBadges.isNotEmpty() || reserveBadgeSlot,
                    enter = fadeIn(
                        animationSpec = if (rememberReduceMotion()) snap() else RaddMotion.defaultEffects(),
                    ) + if (rememberReduceMotion()) {
                        EnterTransition.None
                    } else {
                        expandVertically(animationSpec = RaddMotion.defaultSpatial())
                    },
                    exit = fadeOut(
                        animationSpec = if (rememberReduceMotion()) snap() else RaddMotion.defaultEffects(),
                    ) + if (rememberReduceMotion()) {
                        ExitTransition.None
                    } else {
                        shrinkVertically(animationSpec = RaddMotion.defaultSpatial())
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(24.dp), // token-exempt: badge-slot height, fixed component geometry
                        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Micro),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isDownloaded) {
                            DownloadedBadge()
                        }
                        // Dub status decides whether this title is watchable at all for a
                        // Hindi/Urdu-first viewer, so it is said on the tile, not after a tap.
                        AudioBadgeChipRow(badges = tileBadges)
                    }
                }

                progressLabel?.let { label ->
                    // Round 3 (Library): the painted line is the compact
                    // form at large font scale (see
                    // progressLabelForFontScale); the spoken sentence above
                    // keeps the full label.
                    val displayLabel = progressLabelForFontScale(
                        label,
                        LocalDensity.current.fontScale,
                    )
                    Text(
                        text = displayLabel,
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
                        .size(40.dp) // token-exempt: on-poster button size, fixed component geometry
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_close),
                        contentDescription = "Remove $title from My List",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp), // token-exempt: icon size, not spacing
                    )
                }
            }
        }
    }
}
/**
 * P1-4 (B-11): the tile's press scale — 0.96, one step deeper than the
 * shared 0.97, so the scale still reads as a secondary cue once the M3
 * ripple is the primary feedback. Other controls keep the shared token.
 */
private const val TILE_PRESS_SCALE = 0.96f

/**
 * S2 (2027 Home fix): word-safe trim for the meta line. When the line
 * still ellipsizes, back off to the previous word boundary and strip a
 * dangling separator ("97% Match · …" → "97% Match…").
 * Mirrors the title's [UserWords.trimToWordBoundary] for the meta line —
 * except the title stub guard: a meta line's segments are not title
 * words, so "97%" is a complete segment that still trims ("97%…"),
 * never a bare stub ("The…") that keeps the layout's own cut.
 */
internal fun trimMetaLineToWordBoundary(text: String, cutIndex: Int): String {
    val trimmed = UserWords.trimToWordBoundary(text, cutIndex, stubGuard = false)
    // trimToWordBoundary appends "…" after the boundary — if the boundary
    // fell just after a separator ("97% Match · …"), drop the separator
    // first so the line never ends on a dangling "·".
    return trimmed.removeSuffix("·…").let { if (it != trimmed) "${it.trimEnd()}…" else trimmed }
}

/**
 * Round 3 (Library): the painted progress line at large font scale.
 * "S2:E5 · 23 min left" fits the tile's text column at 1.0x but
 * overflows it at 1.3x ("S2:E5 · 23 mi…"). The painted line drops the
 * lowest-priority word — "left" — so the episode scope and the minutes
 * still read in full; the spoken sentence (built from the full label)
 * still says "23 min left", so TalkBack loses nothing. A label that
 * does not end in " left" paints unchanged — the ellipsis is the
 * fallback, as before. Pure string work, like the meta helpers.
 */
internal fun progressLabelForFontScale(label: String, fontScale: Float): String =
    if (fontScale > 1.0f) label.removeSuffix(" left") else label

/**
 * The rail meta line, as one pure function so the form is assertable
 * without a layout. Video audit, refined direction (21:51 PKT 27 Nov
 * 2026): the card shows poster + title + LANGUAGE only — the line is
 * the first language the wire carries ("Hindi"), never the year, the
 * rating or the type word. Those live in the tile's spoken sentence
 * and on Detail (rating also in Search); only the painted rail line
 * loses them. A row with no language paints no meta line at all — never
 * an invented value. The wire's language column holds a list as text
 * ("Hindi + English + Japanese"), so the first item is the one glance
 * the card gets; Detail carries the full list.
 *
 * REA #2 (Netflix, Nov 2026): [matchPercent] paints first — "97% Match ·
 * Hindi" — and alone when nothing else paints ("97% Match",
 * never "97% Match · " with a dangling separator). Null means no match
 * to paint: the line is exactly as before.
 */
internal fun posterMetaLine(post: Post, matchPercent: Int? = null): String {
    val match = matchPercent?.let { "$it% Match" }
    return listOfNotNull(
        match,
        WireText.listOf(post.language).firstOrNull(),
    ).joinToString(" · ")
}

/**
 * L2 (round 2): the download badge — a jade filled check in a pill, not
 * a grey icon, so a downloaded title reads as "yours, ready" at a
 * glance (the award lift). Paint-only: the tile's one spoken sentence
 * already says "downloaded".
 */
@Composable
private fun DownloadedBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RaddTokens.Shape.Pill)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = RaddTokens.Space.Sm, vertical = RaddTokens.Space.Micro)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_action_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(12.dp), // token-exempt: icon size, not spacing
        )
        Text(
            text = stringResource(R.string.poster_downloaded),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
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
            modifier = Modifier.width(180.dp), // token-exempt: preview-only width
        )
    }
}
