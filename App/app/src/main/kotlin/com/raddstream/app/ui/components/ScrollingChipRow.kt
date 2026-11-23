package com.raddstream.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A4-F3: the one scrolling chip row. Before this, every screen hand-rolled
 * its own Box + LazyRow/Row + [RowEndFade] — five sites with the fade, four
 * without it (My List's sort row, Search's suggestion chips, Detail's
 * Season chips, the Player's cast strip), inconsistent padding and gaps,
 * and Home's two chip styles as two unrelated implementations. Every
 * scrolling chip strip in the app rides this now, so the fade, the 8dp
 * gaps and the end padding can never drift apart again.
 *
 * The fade stays UNCONDITIONAL, exactly as the hand-rolled rows had it:
 * a conditional canScrollForward fade would risk
 * SearchFilterRailTest.rightEdgeFadeMaskExists, which pins the fade's
 * presence in a 360dp box where Robolectric's ~1px/char metrics may make
 * the row fit.
 *
 * Screen audit (Nov 2026) #1: the row also snaps. A free fling used to
 * rest mid-chip ("Complete serie…" cut at the edge reads as broken, not
 * scrollable, even under the fade); the snap fling behaviour lands every
 * fling with a chip flush at the row's start edge, so the row never rests
 * on a half-visible label. Programmatic scrolls (scrollToItem, the
 * selected-chip-into-view calls) are untouched — snap only governs
 * flings, so no call site changes behaviour except at rest.
 *
 * Not a scrolling row, and deliberately NOT migrated here: Settings'
 * internal `ChipRow` (a FlowRow segmented control — a different component
 * that happens to share the name), AudioBadgeChipRow (poster-card badges),
 * Detail's lone quality-picker chip, and Detail's cast FlowRow.
 */
/**
 * The chip rows snap a chip flush to the row's start edge (audit #1).
 * rememberSnapFlingBehavior defaults to CenterToCenter, which centers a
 * chip in the viewport — wrong for a filter strip — so the row builds its
 * provider with this Start position instead. A Start position returns 0:
 * the item's start aligns with the layout's start. (Compose 1.9 removed
 * SnapPositionInLayout — the 1.6.8/1.7 form — so the position is
 * SnapPosition.Start now.) Shared with the snap test so the row and the
 * test can never drift apart on the position.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val ChipRowSnapPosition = SnapPosition.Start

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollingChipRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    horizontalSpacing: Dp = 8.dp,
    fadeHeight: Dp = 48.dp,
    rowTestTag: String? = null,
    fadeTestTag: String? = null,
    content: LazyListScope.() -> Unit,
) {
    // The snap provider is remembered so the fling behaviour is not
    // recreated on every recomposition — a fresh provider object would
    // rebuild it mid-fling.
    val snapLayoutInfoProvider = remember(state) {
        SnapLayoutInfoProvider(state, snapPosition = ChipRowSnapPosition)
    }
    Box(modifier = modifier.fillMaxWidth()) {
        var rowModifier = Modifier.fillMaxWidth()
        if (rowTestTag != null) rowModifier = rowModifier.testTag(rowTestTag)
        LazyRow(
            state = state,
            modifier = rowModifier,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalAlignment = verticalAlignment,
            flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
        ) {
            content()
        }
        var fadeModifier = Modifier.align(Alignment.CenterEnd)
        if (fadeTestTag != null) fadeModifier = fadeModifier.testTag(fadeTestTag)
        RowEndFade(fadeModifier, height = fadeHeight)
    }
}

/**
 * Home review (Nov 2026): the right-edge fade for the horizontally
 * scrolling chip strips — the last chip fades out instead of hard-clipping
 * mid-word ("Complete s..."). A paint-only overlay: it has no click
 * handling, so taps pass straight through to the row beneath it. Moved
 * here from HomeScreen.kt (A4-F3) so every screen shares the one fade
 * instead of each hand-rolling its own copy.
 */
@Composable
internal fun RowEndFade(modifier: Modifier = Modifier, height: Dp = 48.dp) {
    // The fade needs an explicit height: fillMaxHeight() collapses to 0
    // here — the parent Box is wrap-content inside a scrollable column,
    // so there is no definite height to fill. The default 48dp is the
    // height of the filter, Browse and Live TV rows; callers with
    // shorter rows pass their own height.
    //
    // Audit #11: the strip itself is 48dp wide now, not 24dp — a 24dp
    // fade on a 360dp row dims only the last sliver of the cut chip, so
    // the row still read as broken-off, not scrollable. At 48dp the last
    // chip visibly dims into the edge, which is the scroll cue.
    Box(
        modifier = modifier
            .width(48.dp)
            .height(height)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.background,
                ),
            ),
    )
}

/** The two chip styles the app actually has. */
enum class RaddChipStyle {
    /** A Material3 FilterChip: filters in place, with a selected fill. */
    Filter,

    /**
     * The Browse pill: unfilled, 1dp outline, a navigation link — never a
     * second filter system next to the filled filter row above it (Nov 2026
     * redesign). No selected state; [RaddChip.selected] is ignored.
     */
    Navigation,
}

/**
 * A4-F3: the one chip. Filter delegates to Material3 FilterChip (which is
 * what keeps `selected` semantics — MyListMockupScreenTest pins
 * assertIsSelected() on the sort chips) with the 48dp touch-target floor
 * applied once, here, instead of at every call site. Navigation is the
 * Browse Surface pill (24dp radius, transparent, 1dp outlineVariant,
 * 12dp/16dp inner padding).
 *
 * Per-screen selected fills are deliberate and survive through [colors]:
 * Home and My List use primary/onPrimary, the Search rail uses
 * primaryContainer/primary, Live TV and the rest use [raddFilterChipColors].
 * The label stays a slot so each site's bold-when-selected and maxLines
 * survive untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaddChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    style: RaddChipStyle = RaddChipStyle.Filter,
    colors: SelectableChipColors = raddFilterChipColors(),
    shape: Shape = FilterChipDefaults.shape,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    when (style) {
        RaddChipStyle.Filter -> FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            colors = colors,
            shape = shape,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            // Audit #54: a FilterChip is 32 dp tall — the touch-target
            // floor is 48 dp, so the chip itself carries it.
            modifier = modifier.heightIn(min = 48.dp),
        )

        RaddChipStyle.Navigation -> Surface(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            // E-T4: 16dp of vertical padding takes the tile to >= 48dp.
            Box(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                label()
            }
        }
    }
}
