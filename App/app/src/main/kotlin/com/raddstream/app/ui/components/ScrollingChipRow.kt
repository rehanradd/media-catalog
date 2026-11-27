package com.raddstream.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.pressScale

/**
 * A4-F3: the one scrolling chip row. Before this, every screen hand-rolled
 * its own Box + LazyRow/Row + [RowEndFade] — five sites with the fade, four
 * without it (My List's sort row, Search's suggestion chips, Detail's
 * Season chips, the Player's cast strip), inconsistent padding and gaps,
 * and Home's two chip styles as two unrelated implementations. Every
 * scrolling chip strip in the app rides this now, so the fade, the 8dp
 * gaps and the end padding can never drift apart again.
 *
 * The fade is shown only while the row can still scroll forward
 * (P2, Nov 2026 — it used to be unconditional): at the end of the row
 * the fade sat on top of the last chip even when the chip was fully in
 * view, so the last chip read as clipped ("KPO…", "Thri…", "En…") on
 * Home, Search and Live TV. A fade that says "more to scroll" must
 * vanish when there is nothing more. At rest it still shows — the
 * pinned fade tests (SearchFilterRailTest.rightEdgeFadeMaskExists,
 * ScrollingChipRowTest.fadeNodeExistsInANarrowBoxWithLongLabels) render
 * overflowing rows at rest, where canScrollForward is true.
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
    contentPadding: PaddingValues = PaddingValues(horizontal = RaddTokens.Space.Gutter),
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    horizontalSpacing: Dp = RaddTokens.Space.Sm,
    fadeHeight: Dp = 36.dp,
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
    // P2 (Nov 2026): the end fade is a "more to scroll" cue — it must
    // vanish once the row is scrolled to the end. Unconditional, it sat
    // on top of the last chip even when the chip was fully in view, so
    // the last chip read as clipped at the right edge on Home, Search
    // and Live TV. Before the first layout there is no scroll info yet,
    // so the fade stays on (a row is scrollable far more often than not,
    // and the pinned fade tests render at rest, where it must exist).
    val showEndFade = state.layoutInfo.totalItemsCount == 0 || state.canScrollForward
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
        if (showEndFade) {
            var fadeModifier = Modifier.align(Alignment.CenterEnd)
            if (fadeTestTag != null) fadeModifier = fadeModifier.testTag(fadeTestTag)
            RowEndFade(fadeModifier, height = fadeHeight)
        }
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
internal fun RowEndFade(modifier: Modifier = Modifier, height: Dp = 36.dp) {
    // The fade needs an explicit height: fillMaxHeight() collapses to 0
    // here — the parent Box is wrap-content inside a scrollable column,
    // so there is no definite height to fill. The default 36dp is the
    // height of the filter, Browse and Live TV rows (video-audit #38:
    // chips shrank from the 48dp floor); callers with shorter rows pass
    // their own height. The WIDTH stays 48dp — see Audit #11 below.
    //
    // Audit #11: the strip itself is 48dp wide now, not 24dp — a 24dp
    // fade on a 360dp row dims only the last sliver of the cut chip, so
    // the row still read as broken-off, not scrollable. At 48dp the last
    // chip visibly dims into the edge, which is the scroll cue. The width
    // is the 48dp grid step (RaddTokens.Space.Step48) — pinned by
    // SearchAuditFixesTest.railFadeIs48dpWide, so it must not move.
    //
    // S3 (2027 Home fix, round 2): the linear fade still read as a
    // hard cut — the chips are dark-on-dark (the chip fill to the
    // background is ~10 RGB steps), so a linear ramp leaves the cut chip
    // legible until the last ~10px, and the golden showed the letters
    // fading only in the final sliver. The stops are eased now: the fade
    // reaches 75% background at the halfway point, so the cut chip is
    // substantially dimmed through the whole second half and the row
    // reads as fading, not clipped. Same 48dp width — the width was
    // never the problem, the ramp was.
    //
    // Re-rate 8 (Nov 2026): the eased ramp still left the cut chip
    // legible at rest — the re-rate 8 homeLoaded render shows "KPOP"
    // cut after ANIME, dimmed but readable, so both raters still read
    // the row as clipped. The ramp goes further now, WITHIN the pinned
    // 48dp: 80% background at 30% and full background at 60%, so the
    // outer ~19dp is fully opaque and no glyph survives at the edge.
    // (A 64dp strip was tried first and reverted — the 48dp width is
    // pinned by test, so the fix lives in the ramp, not the width.)
    // The inner gradient still reads as the scroll cue.
    //
    // Re-rate 9 (Nov 2026): the 60%-full ramp overshot — the outer
    // ~19dp is fully opaque, so the next chip (KPOP after ANIME) is
    // completely invisible at rest and the row reads as finished, not
    // scrollable. The cue is capped, not removed: the outer stops sit
    // at 92% background, so a faint ghost of the next chip shows
    // through — enough to say "more", not enough to read as clipped.
    // The 80%-at-30% inner ramp is unchanged, so the cut chip still
    // dims out and never reads as a clipped label.
    Box(
        modifier = modifier
            .width(RaddTokens.Space.Step48)
            .height(height)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.3f to MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                    0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                    1f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                ),
            ),
    )
}

/** The two chip styles the app actually has. */
enum class RaddChipStyle {
    /**
     * A Material3 FilterChip: filters in place, with a selected fill —
     * 8dp radius and a leading check when selected (M3 Expressive,
     * P2-chip2027): the small radius plus the check is what says
     * "filter chip", a plain pill with only a colour change is the
     * 2021 look.
     */
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
 * assertIsSelected() on the sort chips) with the 36dp height floor
 * applied once, here, instead of at every call site (video-audit #38:
 * the 48dp floor made every chip row eat poster space; the user's
 * standing direction is smaller chips). The label rides labelMedium
 * (12sp) for the same reason — a bare Text would inherit FilterChip's
 * labelLarge. Navigation is the Browse Surface pill (24dp radius,
 * transparent, 1dp outlineVariant, 12dp/8dp inner padding).
 *
 * Per-screen selected fills are deliberate and survive through [colors]:
 * every screen uses [raddFilterChipColors] now (H3: Home's
 * primary/onPrimary override is gone — the selected fill is
 * primaryContainer, the unselected container surfaceContainerHigh).
 * The label stays a slot so each site's bold-when-selected and maxLines
 * survive untouched.
 *
 * P2-chip2027: a Filter chip is 8dp ([FilterChipDefaults.shape]) with a
 * leading check when selected — the M3 Expressive signature. A selected
 * chip that only changes colour reads as the old pill; the check is
 * what marks the selection. Call sites that pass their own [leadingIcon]
 * keep it; [showSelectedCheck] opts a chip out (the Live TV query chip
 * is a clear action, not a selection — a check beside its × would fight
 * it). Navigation ignores both: it is a pill with no selection.
 */
/** Test tag on the default selected-check icon, so tests can pin its presence. */
internal const val SelectedCheckTag = "chipSelectedCheck"

/**
 * The M3 Expressive selected-filter mark: an 18dp check in
 * onPrimaryContainer — the same role [raddFilterChipColors] already uses
 * for the selected label, so the check and the label agree. Shared by
 * [RaddChip] and the raw FilterChip sites (Search sheet, quick chips)
 * so a selected filter chip shows the same mark everywhere.
 */
@Composable
internal fun SelectedCheckIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_action_check),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.size(18.dp).testTag(SelectedCheckTag), // token-exempt: icon size, not spacing
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaddChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    style: RaddChipStyle = RaddChipStyle.Filter,
    colors: SelectableChipColors = raddFilterChipColors(),
    // P2-chip2027: filter chips are 8dp — the default shape is
    // FilterChipDefaults.shape, not the token pill (the H3 "chips are
    // pills" comment it replaced was the P2-2 overcorrection). The
    // Navigation style still builds its own pill below.
    shape: Shape = FilterChipDefaults.shape,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    showSelectedCheck: Boolean = true,
) {
    // Round 6 (motion): the chip's own press source — the scale + haptic
    // track the same presses the chip's click owns. Every RaddChip (Home
    // filter/mood, Search quick/filter, Settings chips) gets a press scale
    // (0.97) + haptic on the expressive effects spring.
    val pressSource = remember { MutableInteractionSource() }
    PressHaptics(pressSource)
    // P2-chip2027: the selected check — a caller's own leadingIcon wins,
    // otherwise a selected Filter chip shows the check. Unselected chips
    // and Navigation chips never get one.
    val resolvedLeadingIcon: @Composable (() -> Unit)? = when {
        style != RaddChipStyle.Filter -> leadingIcon
        leadingIcon != null -> leadingIcon
        selected && showSelectedCheck -> ({ SelectedCheckIcon() })
        else -> null
    }
    when (style) {
        RaddChipStyle.Filter -> FilterChip(
            selected = selected,
            onClick = onClick,
            // Video-audit #38: the label is labelMedium (12sp), not the
            // labelLarge a bare Text inherits from FilterChip — applied
            // here, once, so all 16 call sites shrink together.
            label = {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelMedium) {
                    label()
                }
            },
            colors = colors,
            shape = shape,
            leadingIcon = resolvedLeadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            interactionSource = pressSource,
            // Audit #54: a FilterChip is 32 dp tall — the touch-target
            // floor was 48 dp, so the chip itself carried it. Video-audit
            // #38 (Nov 2026): the floor is 36 dp now — the 48dp chips ate
            // the space the posters need, and the user's direction is
            // smaller chips everywhere.
            modifier = modifier.heightIn(min = 36.dp).pressScale(pressSource),
        )

        RaddChipStyle.Navigation -> Surface(
            onClick = onClick,
            modifier = modifier.pressScale(pressSource),
            enabled = enabled,
            interactionSource = pressSource,
            shape = RaddTokens.Shape.Pill,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), // token-exempt: hairline border, not spacing
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            // E-T4: 16dp of vertical padding took the tile to >= 48dp.
            // Video-audit #38: 8dp now — the label is already labelMedium
            // at the one call site, so the pill is ~32dp tall, in line
            // with the Filter chips.
            Box(Modifier.padding(horizontal = RaddTokens.Space.Md, vertical = RaddTokens.Space.Sm)) {
                label()
            }
        }
    }
}
