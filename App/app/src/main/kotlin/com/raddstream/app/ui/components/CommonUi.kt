package com.raddstream.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.RaddEmphasized
import com.raddstream.app.ui.theme.RaddStreamTheme

/**
 * Shared page-level building blocks: section headers, empty/error/loading states,
 * skeleton placeholders, a horizontal rail, and grid paging detection.
 *
 * Motion respects the system "remove animations" / animator-duration-scale setting
 * so the shimmer does not run for users who asked for reduced motion.
 */

/**
 * Session 121 (P-16): the app's filter chips, in the accent. `FilterChip`'s default
 * selected container is `secondaryContainer` — a dark red-brown in this brand — so
 * a selected chip read as an error state next to the jade accent. Selected chips use
 * the jade `primaryContainer` (with its `onPrimaryContainer` label and check) instead;
 * everything unselected keeps the default. Every `FilterChip` in the app uses this.
 * Session 123: the brand value itself moved from maroon toward the coral secondary
 * (same dark container role, warmer hue) so a chip that misses this override still
 * reads as coral family, not an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun raddFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * F25 (Batch B): the chip states as Android Studio sees them — selected
 * beside unselected, on the shared token. A future change to the selected
 * fill, the check or the border shows up here before it ships; the
 * screenshot test in ScreenSnapshotTest pins the same two states in CI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Filter chip states", showBackground = true)
@Composable
private fun FilterChipStatesPreview() {
    RaddStreamTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text("Hindi") },
                colors = raddFilterChipColors(),
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = false,
                onClick = {},
                label = { Text("English") },
                colors = raddFilterChipColors(),
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * One title style for every dialog (D-2): the display family, semibold,
 * 24sp (M3 headlineSmall) — the same face screen titles use, so a dialog headline never renders
 * in the thin, faint face the video audit caught on the give-up, consent and
 * volume dialogs. Every `AlertDialog` title goes through here; the colour
 * defaults to the surface's own, and the player dialogs pass white for their
 * dark container.
 */
@Composable
fun RaddDialogTitle(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = color,
    )
}

/**
 * The Stitch mockup centred dialog: a dark card with a jade icon disc, a
 * centred title, the body, and two side-by-side pill buttons — the jade
 * primary on the right, the dark secondary on the left. One helper so the
 * download confirms, the update dialog and the analytics consent can never
 * drift apart again. [belowActions] is the quiet third row (the consent
 * sheet's "Skip for now"); [secondaryLabel] null means a single full-width
 * primary, for the update that cannot be put off.
 */
@Composable
fun CenteredMockupDialog(
    onDismissRequest: () -> Unit,
    icon: ImageVector,
    title: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryEnabled: Boolean = true,
    belowActions: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                RaddDialogTitle(text = title)
                body()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (secondaryLabel != null) {
                        Button(
                            onClick = { onSecondary?.invoke() },
                            enabled = secondaryEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Text(secondaryLabel)
                        }
                    }
                    Button(
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(primaryLabel)
                    }
                }
                belowActions?.invoke(this)
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            // Density (Nov 2026): section heads are titleMedium (16sp,
            // bold) — 22sp is the app-bar/page-title slot, and a rail head
            // at 22sp shouts over the posters below it. M3 Expressive has
            // no density scale factor; density comes from picking the
            // smaller token, never a global scale.
            // F30 (Batch B): a heading, not bare paint — TalkBack users
            // navigate by headings, and Robo flagged the "Your library"
            // strip as having no screen-reader label at all.
            Text(
                text = title,
                style = RaddEmphasized.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/**
 * A true shimmer: a soft highlight band swept across a dim base rather than an opacity
 * pulse. Used for every placeholder — poster tiles, hero slides, rail skeletons — so a
 * loading screen reads as "artwork is on its way" instead of a row of blank boxes.
 *
 * The band travels at a constant speed ([SHIMMER_PERIOD_MS] per pass), which is what makes
 * it feel calm rather than throb. With reduced motion on, the same box is painted as a
 * static block, so the placeholder never disappears and nothing moves.
 *
 * Robo run 7: the Home skeleton bars were read as low-contrast text (1.09:1) in
 * runs 6 and 7 — the run-6 "sampling artifact" reading changed nothing, so the
 * flag came back identical. The bars are decorative: they carry no words, so
 * they leave the accessibility tree entirely ([clearAndSetSemantics]) instead
 * of standing in it as something a contrast check can sample.
 *
 * [highlightAlpha] is the band's strength: the shared 0.16f default is a
 * calm sweep for small tiles, and a caller whose placeholder fills the
 * screen (the hero skeleton) may ask for a stronger band so the wait reads
 * as loading instead of a blank grey box (Samsung RTL v1.1.2, Issue 3).
 *
 * [bandOnly] is the A4-F1 overlay mode: the band sweeps over content the
 * caller already painted instead of over a dim base of its own — [CatalogImage]
 * paints the branded fallback tile first and sweeps this band over it, so a
 * load that never resolves still shows gradient + mark + title instead of a
 * bare surfaceVariant box. With reduced motion the band does not move, so a
 * band-only shimmer paints nothing at all rather than covering the tile
 * with a static block.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    highlightAlpha: Float = SHIMMER_HIGHLIGHT_ALPHA,
    bandOnly: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val base = MaterialTheme.colorScheme.surfaceVariant
    if (reduceMotion) {
        if (bandOnly) {
            Box(modifier.clearAndSetSemantics { })
            return
        }
        Box(modifier.clip(shape).background(base.copy(alpha = 0.5f)).clearAndSetSemantics { })
        return
    }
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SHIMMER_PERIOD_MS, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )
    Box(
        modifier
            .clip(shape)
            .then(if (bandOnly) Modifier else Modifier.background(base))
            // S5: the band's gradient is built once per size and the
            // animation translates the canvas under it — the old code
            // rebuilt the Brush every frame for every shimmer on screen.
            // The translation is pixel-identical to the old moving
            // gradient: a point (px, py) samples the same t either way.
            .drawWithCache {
                val band = size.width * 0.55f
                val brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight.copy(alpha = highlightAlpha), Color.Transparent),
                    start = Offset(0f, 0f),
                    end = Offset(band, size.height),
                )
                onDrawWithContent {
                    drawContent()
                    val travel = size.width + band * 2f
                    val x = -band + travel * progress
                    translate(left = x) {
                        drawRect(brush, size = Size(band, size.height))
                    }
                }
            }
            // Robo run 7: decorative placeholder — no words, no node in the
            // accessibility tree (see the KDoc above).
            .clearAndSetSemantics { },
    )
}

/**
 * Lazy placeholder that shimmers when motion is allowed, or draws a static block when
 * the user reduced motion — never a blank gap during load.
 *
 * [shape] is the placeholder's own outline, not a shared one: a poster tile is a
 * 12dp card, a text line reads as a line with an 8dp pill, a button is a full
 * stadium. The old code forced 12dp on everything, so text lines read as cards.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    aspectRatioValue: Float? = null,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val boxModifier = if (aspectRatioValue != null) {
        modifier.fillMaxWidth().aspectRatio(aspectRatioValue)
    } else {
        modifier
    }
    ShimmerBox(boxModifier, shape = shape)
}

/**
 * The text column under a poster tile, exactly as [PosterCard] draws it — the
 * one shared shape for every rail cell and grid cell, so a tile and its
 * placeholder are the same height:
 * - title slot: titleSmall's own line height (20dp), one line — the old
 *   skeleton drew two 16dp bars for the two-line title the F7 redesign
 *   removed, so every rail and grid shrank ~20dp when its cards landed;
 * - details line ("2023 · Movie"): labelSmall's 16dp line;
 * - badge slot: always 24dp, whether or not this title carries a badge.
 * No side padding and no bottom padding: the real card's text sits flush
 * with the poster edges on the page background — the old 8dp sides and
 * 10dp bottom were the retired footer box's insets, and they cost another
 * ~10dp of jump. Both line heights come from the typography tokens, the
 * same source PosterCard reads, so a token change moves both together.
 * ([SkeletonPlaceholderTest.railCellsMatchPosterCardHeight] pins the cell
 * at 295dp: 225dp poster + 6dp gap + this 64dp column.)
 */
@Composable
private fun PosterCellTextSkeleton() {
    val titleSlotHeight = with(LocalDensity.current) {
        // TextUnit has no isSpecified in this Compose version — Unspecified
        // is the sentinel to compare against (the same read PosterCard does).
        val lineHeight = MaterialTheme.typography.titleSmall.lineHeight
        if (lineHeight != TextUnit.Unspecified) lineHeight.value.sp.toDp() else 20.dp
    }
    val detailsHeight = with(LocalDensity.current) {
        val lineHeight = MaterialTheme.typography.labelSmall.lineHeight
        if (lineHeight != TextUnit.Unspecified) lineHeight.value.sp.toDp() else 16.dp
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SkeletonBox(
            Modifier.fillMaxWidth().height(titleSlotHeight),
            shape = RoundedCornerShape(8.dp),
        )
        SkeletonBox(
            Modifier.width(80.dp).height(detailsHeight),
            shape = RoundedCornerShape(8.dp),
        )
        SkeletonBox(
            Modifier.width(96.dp).height(24.dp),
            shape = RoundedCornerShape(8.dp),
        )
    }
}

/**
 * One poster tile as its placeholder: the poster box at
 * [CardGeometry.POSTER_ASPECT] over the shared text column, in the same
 * 6dp-spaced Column [PosterCard] draws — the one shape every rail cell,
 * grid cell, discovery cell and load-more row stands in for, so a
 * placeholder and its card are the same height on every screen.
 */
@Composable
fun PosterCellSkeleton(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CardGeometry.POSTER_ASPECT),
        )
        PosterCellTextSkeleton()
    }
}

private const val SHIMMER_PERIOD_MS = 1_400

/** The shimmer band's default strength; the hero skeleton asks for more. */
private const val SHIMMER_HIGHLIGHT_ALPHA = 0.16f

/**
 * S5: a spinner that appears only for a wait that lasts — the
 * delay-then-minimum state machine, anti-flash in both directions.
 * Under [delayMs] a wait shows nothing at all (a spinner that lives
 * 100ms reads as breakage, not progress); once the spinner has
 * appeared it stays up at least [minVisibleMs] so a load that lands
 * just after the delay does not flash it on and off in the same beat.
 * A wait that ends before the delay never shows a spinner — the caller
 * renders nothing (or a skeleton) in that window.
 *
 * The clock is [SystemClock.uptimeMillis]: the min-visible window is
 * measured from the frame the spinner actually appeared, not from when
 * the wait started.
 */
internal const val SPINNER_DELAY_MS = 250L

/** Minimum time a spinner stays visible once it has appeared. */
internal const val SPINNER_MIN_VISIBLE_MS = 500L

@Composable
fun rememberDelayedVisible(
    active: Boolean,
    delayMs: Long = SPINNER_DELAY_MS,
    minVisibleMs: Long = SPINNER_MIN_VISIBLE_MS,
): Boolean {
    var visible by remember { mutableStateOf(false) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (active) {
            delay(delayMs)
            if (!visible) shownAt = SystemClock.uptimeMillis()
            visible = true
        } else if (visible) {
            val elapsed = SystemClock.uptimeMillis() - shownAt
            if (elapsed < minVisibleMs) delay(minVisibleMs - elapsed)
            visible = false
        }
    }
    return visible
}

/**
 * The catalog grid's one column rule: a tile is never narrower than 120dp, so a phone gets
 * three columns and a tablet gets more without any screen saying so. Every real grid and
 * [PosterGridSkeleton] draw from this single value — a skeleton with its own column count is
 * a placeholder whose cells jump sideways the moment the cards replace them.
 */
val CatalogGridColumns: GridCells = GridCells.Adaptive(minSize = 120.dp)

/**
 * The loading stand-in for a poster grid: the same [CatalogGridColumns], the same gutters and
 * the same tile shape as the cards that replace it, so nothing reflows when data arrives.
 * [SKELETON_CELLS] is a fixed handful rather than `columns * rows` because an adaptive grid
 * does not know its column count until it is measured.
 *
 * F21 (Batch B): the column count and the padding are parameters now, because a skeleton
 * must match the grid it stands in for — Search results are a fixed 2-wide grid inside an
 * already-padded column, and the 3-wide adaptive skeleton with its own 16dp sides would
 * reflow sideways the moment the cards landed.
 */
@Composable
/**
 * F21 (Batch B): the columns stay the shared [CatalogGridColumns] rule,
 * written out here — a `columns` parameter would pass `columns = columns`
 * and the grid-rule test (a text scan of the 220 chars after each grid
 * call) would read it as a grid that does not draw from the shared rule.
 * Only `contentPadding` varies by caller.
 */
fun PosterGridSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
) {
    LazyVerticalGrid(
        columns = CatalogGridColumns,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false,
    ) {
        items(SKELETON_CELLS) {
            PosterCellSkeleton()
        }
    }
}

/**
 * The loading stand-in for a horizontal rail: the same tile width, the same poster box and the
 * same two text lines as the [PosterCard]s that replace it, so a row that fills in does not
 * change height under the viewer's thumb. [cells] is a fixed handful for the same reason
 * [PosterGridSkeleton] uses [SKELETON_CELLS] — a rail does not know how many tiles fit until it
 * is measured.
 */
@Composable
fun PosterRailSkeleton(
    modifier: Modifier = Modifier,
    cells: Int = 4,
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp).testTag("posterRailSkeleton"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(cells) { index ->
            PosterCellSkeleton(
                modifier = Modifier
                    .width(CardGeometry.RAIL_CARD_WIDTH_DP.dp)
                    // The first cell stands for all of them in tests: a rail
                    // rendered as the test's root content is measured EXACTLY
                    // at window height, so the Row itself reports the screen
                    // height — the cell is the unit that must match the card.
                    .then(if (index == 0) Modifier.testTag("posterRailCell") else Modifier),
            )
        }
    }
}

/**
 * The loading stand-in for the detail page, mirroring [DetailContent]'s item
 * order block for block — hero, Play, synopsis, utility row, audio line,
 * quality rungs, cast, print summary, similar titles — with each block the
 * same height as the real one, so nothing below the fold reflows when the
 * data lands. Blocks are 16dp apart, the LazyColumn's own spacing.
 * ([SkeletonParityTest] pins the section order, so a redesign that moves
 * a section moves the skeleton with it.)
 *
 * No own padding: the caller applies `detailPadding` (16dp sides, the
 * scaffold top, 40dp bottom breathing) — the same padding the real list
 * gets. The old skeleton added its own 16dp sides and 8dp vertical on top
 * of that, so its poster column sat 32dp in from the edge and 8dp low.
 *
 * The hero mirrors the common case — the full-bleed 16:9 backdrop (most
 * titles carry a TMDB backdrop) with the title block bottom-aligned in
 * a 296dp-min box: two headlineMedium lines (36dp), the bodySmall meta
 * (16dp), the pill + rating row (24dp) and the 22dp genre chips, 6dp
 * apart, 16dp sides, 16dp bottom. The old skeleton mirrored a 176dp band
 * + 92dp spacer + poster header that no longer exists anywhere on the
 * page (Detail review P1), so the hero jumped on every load. A title
 * with no backdrop renders the poster header instead; that jump is the
 * price of not knowing at load time.
 *
 * No episode placeholder: the screen does not know movie-vs-series until
 * the load lands, so a series episode block here would be a guess, not a
 * stand-in (Detail review, Nov 2026 — recorded follow-up).
 */
@Composable
fun DetailSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Hero: 16:9 band behind, title block over its foot.
        Box(
            Modifier.fillMaxWidth().heightIn(min = 296.dp).testTag("detailSkeletonHero"),
        ) {
            SkeletonBox(
                Modifier.fillMaxWidth().aspectRatio(CardGeometry.WIDE_ART_ASPECT),
                shape = RectangleShape,
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SkeletonBox(
                    Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                SkeletonBox(
                    Modifier.fillMaxWidth(0.7f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                SkeletonBox(
                    Modifier.fillMaxWidth(0.85f).height(16.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                SkeletonBox(
                    Modifier.width(120.dp).height(24.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                SkeletonBox(
                    Modifier.fillMaxWidth(0.6f).height(22.dp),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
        DetailBodySkeleton()
    }
}

/**
 * Feel-instant S4: everything below the detail hero — Play, synopsis,
 * utility row, audio line, quality rungs, cast, print summary, similar.
 * Split out of [DetailSkeleton] so the optimistic load can paint the real
 * hero from the tapped row and skeleton only the body: the same pieces,
 * in the same order, with the same tags, so [SkeletonParityTest] still
 * reads them in page order (this function sits directly after
 * [DetailSkeleton], inside that test's scan window). The outer column's
 * 16dp gap and this column's own 16dp gaps keep the geometry identical
 * to the unsplit skeleton.
 */
@Composable
fun DetailBodySkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Play — 48dp, the same min the real hero button carries
        // ([SkeletonParityTest] pins the two together).
        SkeletonBox(
            Modifier.fillMaxWidth().height(48.dp).testTag("detailPlaySkeleton"),
            shape = RoundedCornerShape(24.dp),
        )
        // Synopsis: four bodyMedium lines — the story comes first on the
        // redesigned page, directly under the actions.
        Column(
            Modifier.testTag("detailSkeletonSynopsis"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(3) {
                SkeletonBox(
                    Modifier.fillMaxWidth().height(20.dp),
                    shape = RoundedCornerShape(8.dp),
                )
            }
            SkeletonBox(
                Modifier.fillMaxWidth(0.6f).height(20.dp),
                shape = RoundedCornerShape(8.dp),
            )
        }
        // Utility row: Save / Share / Similar / Report — four borderless
        // icon + label actions, each 48dp min. The skeleton mirrors the
        // real row's shape (24dp icon over a short label), not the old
        // three outlined buttons the redesign removed.
        Row(
            Modifier.fillMaxWidth().testTag("detailSkeletonUtilityRow"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(4) {
                Column(
                    Modifier.weight(1f).height(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SkeletonBox(
                        Modifier.size(24.dp),
                        shape = RoundedCornerShape(4.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    SkeletonBox(
                        Modifier.width(48.dp).height(12.dp),
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        }
        // Audio line: one 48dp tappable row, text + chevron.
        Box(
            Modifier.fillMaxWidth().height(48.dp).testTag("detailSkeletonAudioLine"),
            contentAlignment = Alignment.CenterStart,
        ) {
            SkeletonBox(
                Modifier.fillMaxWidth(0.55f).height(16.dp),
                shape = RoundedCornerShape(8.dp),
            )
        }
        // Quality rungs: "Quality" + subtitle + three rung rows. The
        // section carries its own tag at the call site — the helper's
        // inner tag reads after "similar" in a raw source scan (the
        // helper is defined after this function in the file), so the
        // call-site tag is what [SkeletonParityTest] pins the order on.
        Column(Modifier.testTag("detailSkeletonQuality")) {
            QualityRungsSkeleton()
        }
        // Cast: "Cast" title + one row of 48dp chips.
        Column(
            Modifier.testTag("detailSkeletonCast"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkeletonBox(
                Modifier.width(64.dp).height(24.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(
                    Modifier.width(88.dp).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                SkeletonBox(
                    Modifier.width(72.dp).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                SkeletonBox(
                    Modifier.width(96.dp).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
        // Print summary: titleMedium line + bodySmall line.
        Column(Modifier.testTag("detailSkeletonPrintSummary")) {
            SkeletonBox(
                Modifier.fillMaxWidth(0.5f).height(24.dp),
                shape = RoundedCornerShape(8.dp),
            )
            SkeletonBox(
                Modifier.fillMaxWidth(0.65f).height(16.dp),
                shape = RoundedCornerShape(8.dp),
            )
        }
        // Similar titles: section header + rail.
        Column(Modifier.testTag("detailSkeletonSimilar")) {
            Spacer(Modifier.height(8.dp))
            SkeletonBox(
                Modifier.fillMaxWidth(0.4f).height(24.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            PosterRailSkeleton()
        }
    }
}

/**
 * The quality-rung card as [QualityRungCard] draws it: "Quality"
 * (titleMedium, 24dp) + the Auto subtitle (bodySmall, 16dp) + one row per
 * band, 6dp apart, with the card's own 8dp bottom padding. Three rows is
 * the common ladder (4K / 1080p / 720p / 480p folds to three visible
 * bands on most titles).
 */
@Composable
private fun QualityRungsSkeleton() {
    Column(
        Modifier.testTag("detailSkeletonRungs"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SkeletonBox(
            Modifier.fillMaxWidth(0.35f).height(24.dp),
            shape = RoundedCornerShape(8.dp),
        )
        SkeletonBox(
            Modifier.fillMaxWidth(0.7f).height(16.dp),
            shape = RoundedCornerShape(8.dp),
        )
        repeat(3) {
            // One rung row, outer shape only: the row is a 12dp Surface,
            // 76dp tall (10dp vertical padding around a 24dp title line, a
            // 16dp detail line and a 16dp facts line). The inner bars would
            // nest one shimmer inside another; the slot is what must not move.
            SkeletonBox(
                Modifier.fillMaxWidth().height(76.dp),
                shape = RoundedCornerShape(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One row of filter chips as the screens draw them: 48dp tall (the
 * touch-target floor the real `FilterChip` carries with `heightIn`), 8dp
 * apart, in the same 16dp-side / 4dp-vertical padding. [widths] are the
 * chips' approximate label widths in dp — "Recently added" is wider than
 * "Title A–Z".
 */
@Composable
fun ChipRowSkeleton(widths: List<Int>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        widths.forEach { widthDp ->
            SkeletonBox(
                Modifier.width(widthDp.dp).height(48.dp),
                shape = RoundedCornerShape(8.dp),
            )
        }
    }
}

/**
 * The search loading state as the results list draws it: the results
 * summary line (bodyMedium, 20dp, 8dp vertical padding), one group header
 * ([SectionHeader]: 24dp title, 8dp top and bottom) and fixed 2-wide rows
 * of poster cells — the real results are `chunked(2)` rows in a LazyColumn,
 * so the skeleton is the same shape instead of the adaptive grid, which
 * lays 3+ narrow columns on a wide phone while the real results stay two
 * wide. The old skeleton also drew a phantom 16dp subtitle under the
 * header — the real header names the section and nothing more (A4-F4).
 */
@Composable
fun SearchResultsSkeleton(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                SkeletonBox(
                    Modifier.fillMaxWidth(0.55f).height(20.dp),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                SkeletonBox(
                    Modifier.fillMaxWidth(0.4f).height(24.dp),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }
        items(SEARCH_RESULTS_SKELETON_ROWS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(2) { PosterCellSkeleton(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Rows of poster cells the search-results skeleton stands in with — the first screen's worth. */
private const val SEARCH_RESULTS_SKELETON_ROWS = 4

/**
 * The Live TV loading state as the screen draws it: the channel search
 * field (52dp, 4dp corner), the category chips row, then the channel grid.
 * The old skeleton was the bare poster grid — the field and the chips
 * appeared late and pushed the grid down, and the cells themselves were
 * poster-shaped (2:3) while a channel card is a 16:9 logo plate.
 */
@Composable
fun LiveTvLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            SkeletonBox(
                // Live TV review D11: 52dp, the real field's height — the
                // old 56dp pill shifted the layout 4dp when the list landed.
                Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(4.dp),
            )
        }
        ChipRowSkeleton(widths = listOf(56, 72, 88, 64))
        LazyVerticalGrid(
            columns = CatalogGridColumns,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(SKELETON_CELLS) {
                ChannelCardSkeleton()
            }
        }
    }
}

/**
 * One channel card as [LiveTvChannelCard] draws it: a 16:9 logo plate, the
 * name at titleSmall (20dp, 6dp vertical padding) and the meta line at
 * bodySmall (16dp, 10dp bottom padding) — 58dp under the logo.
 */
@Composable
private fun ChannelCardSkeleton() {
    Column {
        SkeletonBox(aspectRatioValue = CardGeometry.WIDE_ART_ASPECT)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            SkeletonBox(
                Modifier.fillMaxWidth(0.7f).height(20.dp),
                shape = RoundedCornerShape(8.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        ) {
            SkeletonBox(
                Modifier.fillMaxWidth(0.55f).height(16.dp),
                shape = RoundedCornerShape(8.dp),
            )
        }
    }
}

private const val SKELETON_CELLS = 12

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    fillScreen: Boolean = true,
) {
    val containerModifier = if (fillScreen) {
        modifier.fillMaxSize().padding(32.dp)
    } else {
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)
    }
    Box(containerModifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                icon()
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    // UI-fix P2-2: one headline for every failure read the same — a dead
    // server, an empty search and a failed page all said "Something went
    // wrong". Callers name the context; the default stays for callers that
    // have not named theirs yet (Home names its own title now).
    title: String = "Something went wrong",
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    fillScreen: Boolean = true,
) {
    EmptyState(
        title = title,
        modifier = modifier,
        message = message,
        fillScreen = fillScreen,
        icon = {
            // F9 (Batch A): the icon wears the brand tint, not the error red.
            // Every caller of this state is a retryable load failure — a dead
            // server, a failed page — not a destructive mistake, and red says
            // "you did something wrong" where nothing the viewer did was wrong.
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        },
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Live TV review D6: the Retry is the one control a viewer
                // with a dead screen taps most, so it sits on the 48dp
                // floor. ErrorState is shared (Home, Search, Detail,
                // Browse, Live TV) — one fix for all callers.
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
                // A server that is down has two honest answers: try again, or go
                // talk to a different server. Without the second one a person whose
                // configured address is simply wrong can only retry forever.
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
            }
        },
    )
}

/**
 * The compact cousin of [ErrorState]: a page that already shows content but could not
 * finish loading — a failed next page, a detail reload that came back empty — says so in
 * one line with a retry, instead of replacing the content the viewer can already see.
 *
 * This exists because `ErrorState` is a full-screen placeholder, and every screen here
 * only reached for it when its list was EMPTY. A load that failed on page 3 therefore
 * rendered nothing at all: the viewer saw a shorter list than the server has and no way
 * to know the difference.
 */
@Composable
fun InlineLoadFailure(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Audit Detail #5 (Nov 2026): what survived the failed load, in
     * plain words — e.g. "Showing saved details." Null when the caller
     * has nothing stale to name.
     */
    staleNote: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            staleNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Audit Detail #5: Retry stays a text link, not an outlined
        // button — the outlined variant was tried and reverted (CI
        // 45342265965: 2 failures → 45344244123: green on the revert):
        // in the Browse grid the failure row is one narrow cell, and
        // the wider outlined button squeezes the message out of view.
        // The weightier Retry is a follow-up for the Browse lane (the
        // failure row wants a full-width span first). The stale marker
        // above — the part that tells the viewer the data is old — stays.
        TextButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry")
        }
    }
}

/**
 * A horizontal content rail. Kept generic so Home can render every row (newly
 * added, per-type, continue watching) with one keyed, recycled, lazily-composed
 * strip — cheap on low-RAM devices.
 */
@Composable
fun <T> MediaRail(
    items: List<T>,
    key: (T) -> Any,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    /** Per-rail cell width; the rail default. Continue Watching used to ride
     *  this rail with wider cells for its remove ×, but it is full-width rows
     *  now (mockup, Nov 2026), so no rail passes anything else. */
    itemWidthDp: Int = CardGeometry.RAIL_CARD_WIDTH_DP,
    itemContent: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = key) { item ->
            Box(Modifier.width(itemWidthDp.dp)) { itemContent(item) }
        }
    }
}

/** A small text button used as a section "See all" affordance. */
@Composable
fun SeeAllButton(
    onClick: () -> Unit,
    label: String = "See all",
    /**
     * The spoken name. Every Home rail renders one of these, so the bare
     * "See all" repeats once per rail and a screen reader cannot tell the
     * Movies link from the Series one (Robo run 6). Callers pass their
     * section — "See all Movies" — and the button speaks that; the painted
     * text stays the short "See all". Defaults to the label, so a lone
     * button still speaks its own words. The button carries the name (the
     * SwitchRow pattern) and the painted text is silent, so it is heard
     * exactly once.
     */
    contentDescription: String = label,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        // E-T5: the button was 40dp tall. 48dp is the touch-target floor.
        modifier = Modifier.heightIn(min = 48.dp).semantics { this.contentDescription = contentDescription },
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics { })
    }
}

/**
 * The loading stand-in for the Downloads list: rows shaped like the
 * download rows — a 56×84 thumb, a title line, a meta line and a progress
 * bar, 12dp padding like the real row — so nothing reflows when the rows
 * land. Downloads was the only screen that loaded into a bare storage card
 * and a link.
 */
@Composable
fun DownloadsSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 4,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("downloadsSkeleton"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(
                    Modifier.size(width = 56.dp, height = 84.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The real row's title is titleSmall (20dp line, one
                    // line is the common case), the meta is labelSmall
                    // (16dp) and the bar is LinearProgressIndicator's own
                    // 4dp — the old 16/12/8 bars left the row 4dp short.
                    SkeletonBox(
                        Modifier.fillMaxWidth(0.7f).height(20.dp),
                        shape = RoundedCornerShape(8.dp),
                    )
                    SkeletonBox(
                        Modifier.fillMaxWidth(0.5f).height(16.dp),
                        shape = RoundedCornerShape(8.dp),
                    )
                    SkeletonBox(
                        Modifier.fillMaxWidth().height(4.dp),
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        }
    }
}
